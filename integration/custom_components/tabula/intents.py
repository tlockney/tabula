"""Voice-started intercom calls: the Assist intents and their sentences (P9.6).

ADR 0025 decision 8 makes a call startable by voice from the first intercom
release; ADR 0036 fixes how. A satellite tablet that hears "call the kitchen",
"call Thomas" or "call everyone" runs the same code path `tabula.intercom_call`
takes, so every guard in `intercom.py` applies unchanged: the capability gate,
the one-tablet rule for a two-way call, the same-area refusal, the busy checks
and the timers. Nothing is rung that a service call could not ring.

The caller is the tablet that heard the request, never a named argument:
`Intent.device_id` is the assist satellite's HA device (P7.3), so an intent can
only ever start a call from the tablet the person is standing at.

Three things are deliberate:

* **Sentences live in the config directory.** A custom component cannot ship
  sentences into HA's built-in intent set, and the conversation agent's
  in-memory intent override belongs to the `conversation` integration and is
  reset when it sets up. So the sentences go where HA documents for a local
  sentence set, `<config>/custom_sentences/<language>/`, as one file this
  integration writes and removes; the file's absence is what "no intents"
  means on disk. `en` is the only language for now (`LANGUAGES`), and the
  structure is per language.
* **The intents and the sentences exist only while a tablet can use them.** At
  least one paired tablet must declare both `voice` (it can hear a request) and
  `intercom_talk` (it can carry a call). A household with no such tablet is
  left with no new sentences and no new intents, so saying "call the kitchen"
  matches nothing rather than reaching a handler that cannot work.
* **`broadcast <message>` is not ours.** HA's own `HassBroadcast` intent
  already announces a spoken message through every satellite (PROTOCOL.md's
  satellite ANNOUNCE feature, P7.3), and an intercom broadcast is live one-way
  audio with no text on the wire (ADR 0025 decision 2). The two would want the
  same words, so this module registers the live forms only -- "broadcast" with
  nothing after it, "intercom broadcast", "call everyone" -- and leaves the
  announcement to HA. ADR 0036 records the reasoning.

Refusals are spoken, never silence: an unknown room or person, a tablet that
cannot call, a room holding two tablets and the manager's own refusals all
answer with a short sentence in the caller's language.
"""

from __future__ import annotations

import logging
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any

import voluptuous as vol
import yaml
from homeassistant.components.conversation.agent_manager import (
    async_get_agent as async_get_conversation_agent,
)
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import area_registry as ar
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import intent
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from .const import (
    INTERCOM_PERSONS,
    INTERCOM_VOICE_CAPABILITIES,
    SIGNAL_CAPABILITIES,
)
from .intercom import MODE_BROADCAST, MODE_CALL

if TYPE_CHECKING:
    from . import DeviceRecord, TabulaHub

_LOGGER = logging.getLogger(__name__)

# The intents a person speaks. HA's intent types are global names, so they carry
# the project name (ADR 0036).
INTENT_CALL = "TabulaIntercomCall"
INTENT_BROADCAST = "TabulaIntercomBroadcast"
INTENTS = (INTENT_CALL, INTENT_BROADCAST)

# Languages with sentences. One entry today; the tables below are keyed by
# language so another is a data change, not a restructure (ADR 0036).
LANGUAGES = ("en",)
LANGUAGE = LANGUAGES[0]

# Where HA reads a local sentence set from: <config>/custom_sentences/<lang>/.
# The file name is ours alone, and it is written whole or removed; nothing else
# in that directory is touched.
SENTENCES_DIR = "custom_sentences"
SENTENCES_FILE = "tabula_intercom.yaml"

# Sentences, in hassil syntax. hassil matches intents in the order they are
# loaded and stops at the first match, and both of ours are appended in this
# order, so the broadcast comes first: "call everyone" matches a broadcast
# sentence before the call intent's wildcard can read "everyone" as a room.
# The wildcard list captures the rest of the sentence, which is why "call the
# kitchen" and "call Thomas" share one shape.
SENTENCES: dict[str, dict[str, list[str]]] = {
    "en": {
        INTENT_BROADCAST: [
            "broadcast",
            "intercom broadcast",
            "start [an] intercom broadcast",
            "call everyone",
            "call everybody",
            "call all [the] (rooms|tablets)",
        ],
        INTENT_CALL: [
            "call {target}",
        ],
    }
}

# What the intents say back. Keys are either a speech key of this module or a
# translation key `intercom.py` raises, so a refusal is explained in the words
# the household already sees in HA's UI (strings.json's exceptions), shortened
# for speech. English only, like the sentences (ADR 0036).
SPEECH: dict[str, dict[str, str]] = {
    "en": {
        "calling": "Calling {target}.",
        "broadcasting": "Broadcasting.",
        "not_a_tablet": "This device cannot start an intercom call.",
        "unknown_target": "I could not find {target}.",
        "intercom_caller_cannot_call": "This tablet cannot start an intercom call.",
        "intercom_caller_offline": "This tablet is not connected.",
        "intercom_caller_busy": "This tablet is already in a call.",
        "intercom_no_target_devices": "There is nothing to call.",
        "intercom_no_capable_targets": "No tablet can take that call.",
        "intercom_one_callee": "That is more than one tablet: {devices}.",
        "intercom_same_area": "{device} is in the same room, so a call would echo.",
        "intercom_busy": "{devices} is already in a call.",
        "intercom_call_id_in_use": "That call is already running.",
        "intercom_bad_mode": "That is not a kind of call I know.",
        "refused": "The call could not be started.",
    }
}

# A person's name maps to one area or one tablet (ADR 0036). Both are named the
# way a person says them: an area's name or id, a tablet's name or either id.
PERSON_NAME_MAX = 64
PERSON_TARGET_MAX = 128
PERSON_KEYS = ("area", "device")


class PersonMapError(ValueError):
    """The person map an options form submitted is not usable."""

    def __init__(self, problems: list[str]) -> None:
        super().__init__("; ".join(problems))
        self.problems = problems


def parse_persons(value: Any) -> list[dict[str, str]]:
    """The stored person map from an options form value (YAML text, or a list).

    Every entry names one person and exactly one of `area` or `device`; names
    are unique so "call Thomas" has one answer. Anything else is refused with
    the problems listed, rather than stored and ignored later.
    """
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return []
        try:
            parsed = yaml.safe_load(text)
        except yaml.YAMLError as err:
            raise PersonMapError([f"not valid YAML: {err}"]) from err
    else:
        parsed = value
    if parsed is None:
        return []
    if not isinstance(parsed, list) or not all(isinstance(item, dict) for item in parsed):
        raise PersonMapError(["must be a list of names, one per entry"])
    persons: list[dict[str, str]] = []
    seen: set[str] = set()
    for index, item in enumerate(parsed, start=1):
        name = str(item.get("name") or "").strip()
        if not name or len(name) > PERSON_NAME_MAX:
            raise PersonMapError([f"entry {index}: name is required"])
        if name.casefold() in seen:
            raise PersonMapError([f"entry {index}: {name} is named twice"])
        seen.add(name.casefold())
        given = [key for key in PERSON_KEYS if str(item.get(key) or "").strip()]
        if len(given) != 1:
            raise PersonMapError(
                [f"entry {index}: give one of area or device, not both or neither"]
            )
        target = str(item[given[0]]).strip()
        if len(target) > PERSON_TARGET_MAX:
            raise PersonMapError([f"entry {index}: {given[0]} is too long"])
        persons.append({"name": name, given[0]: target})
    return persons


def persons_to_text(persons: Iterable[dict[str, str]]) -> str:
    """The person map as the editor shows it: YAML, one entry per person."""
    listed = list(persons)
    return yaml.safe_dump(listed, sort_keys=False) if listed else ""


def sentences_for(language: str) -> dict[str, Any]:
    """One language's sentence document, in the shape HA reads from disk."""
    return {
        "language": language,
        "intents": {
            name: {"data": [{"sentences": list(sentences)}]}
            for name, sentences in SENTENCES[language].items()
        },
        "lists": {"target": {"wildcard": True}},
    }


@dataclass(frozen=True)
class Target:
    """What a spoken name resolved to: the tablets, and how to say it back."""

    records: list[DeviceRecord]
    label: str


def _label(record: DeviceRecord) -> str:
    """A tablet's name for speech, or the short id when it has none."""
    return record.name or record.device_id[:8]


def _normalize(text: str) -> str:
    """A spoken name with the noise removed: case, articles, spacing."""
    name = " ".join(str(text or "").split()).casefold()
    for article in ("the ", "a ", "an "):
        if name.startswith(article):
            name = name[len(article) :]
            break
    return name


class IntercomVoiceIntents:
    """The intercom intents of one hub: their gate, their sentences, their words."""

    def __init__(self, hub: TabulaHub) -> None:
        self.hub = hub
        self.hass: HomeAssistant = hub.hass
        self._unsub: Callable[[], None] | None = None
        # Whether the handlers are registered right now, so a capability change
        # registers them once and removes them once.
        self._registered = False

    # --- lifecycle ---------------------------------------------------------

    async def async_setup(self) -> None:
        """Follow capability changes, then apply the gate as things stand."""
        self._unsub = async_dispatcher_connect(
            self.hass, SIGNAL_CAPABILITIES, self._capabilities_changed
        )
        await self._async_apply()

    async def async_shutdown(self) -> None:
        """Unload: no intents, no sentences left behind on disk."""
        self.shutdown()
        await self._async_write_sentences(present=False)

    @callback
    def shutdown(self) -> None:
        """Drop the listener and the handlers (a hub teardown)."""
        if self._unsub is not None:
            self._unsub()
            self._unsub = None
        self._unregister()

    @callback
    def _capabilities_changed(self, _record: DeviceRecord) -> None:
        """A tablet registered: the gate may have opened or closed."""
        self.hass.async_create_task(self._async_apply())

    async def _async_apply(self) -> None:
        """Register or remove the intents, and the sentences with them."""
        capable = self.capable_devices()
        if capable:
            self._register()
        else:
            self._unregister()
        await self._async_write_sentences(present=bool(capable))

    # --- the gate ----------------------------------------------------------

    def capable_devices(self) -> list[DeviceRecord]:
        """Every tablet that can hear a request and carry a call."""
        return [
            record
            for record in self.hub.devices.values()
            if set(record.capabilities) >= INTERCOM_VOICE_CAPABILITIES
        ]

    # --- registration ------------------------------------------------------

    def _register(self) -> None:
        """Register both handlers once; a second registration is a no-op."""
        if self._registered:
            return
        intent.async_register(self.hass, IntercomCallHandler(self))
        intent.async_register(self.hass, IntercomBroadcastHandler(self))
        self._registered = True
        _LOGGER.debug("Intercom voice intents registered: a tablet can call")

    def _unregister(self) -> None:
        """Remove both handlers; HA ignores a name that is not there."""
        if not self._registered:
            return
        for name in INTENTS:
            intent.async_remove(self.hass, name)
        self._registered = False
        _LOGGER.debug("Intercom voice intents removed: no tablet can call")

    async def _async_write_sentences(self, *, present: bool) -> None:
        """Write or remove the sentence file, then let the agent re-read it."""
        changed = await self.hass.async_add_executor_job(self._write_sentences, present)
        if changed:
            await self._async_reload_agent()

    def _write_sentences(self, present: bool) -> bool:
        """The file work, in an executor; True when something changed."""
        path = Path(self.hass.config.path(SENTENCES_DIR, LANGUAGE, SENTENCES_FILE))
        if not present:
            if not path.exists():
                return False
            path.unlink()
            return True
        document = yaml.safe_dump(sentences_for(LANGUAGE), sort_keys=False)
        if path.exists() and path.read_text(encoding="utf-8") == document:
            return False
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(document, encoding="utf-8")
        return True

    async def _async_reload_agent(self) -> None:
        """Drop the conversation agent's cached intents so it reads the file.

        The agent caches its parsed sentences per language, so a gate that
        opened after it first loaded `en` would otherwise not see them until
        HA restarts. `async_get_agent` is the one call both supported HA
        shapes share: 2025.1 raises `KeyError` when the conversation component
        is not set up at all, later releases return None.
        """
        try:
            agent = async_get_conversation_agent(self.hass)
        except KeyError:  # the conversation component is not set up: nothing to clear
            return
        reload_agent = getattr(agent, "async_reload", None)
        if reload_agent is None:
            return
        await reload_agent(LANGUAGE)

    # --- the spoken names --------------------------------------------------

    def _persons(self) -> list[dict[str, str]]:
        """The person map of this hub entry; anything unusable is skipped."""
        stored = self.hub.entry.options.get(INTERCOM_PERSONS) or []
        if not isinstance(stored, list):
            return []
        return [item for item in stored if isinstance(item, dict)]

    def lookup(self, spoken: str) -> Target | None:
        """Resolve a spoken name: a person first, then an area, then a tablet.

        A person is what the household wrote in the options flow, so it wins
        over a room of the same name: "call Thomas" is Thomas's tablet even
        when a tablet is called Thomas. No match at all is None, which the
        caller answers with a spoken refusal.
        """
        name = _normalize(spoken)
        if not name:
            return None
        if (person := self._person(name)) is not None:
            return self._from_person(person)
        if (target := self._from_area(name)) is not None:
            return target
        return self._from_tablet(name)

    def _person(self, name: str) -> dict[str, str] | None:
        for person in self._persons():
            if _normalize(person.get("name", "")) == name:
                return person
        return None

    def _from_person(self, person: dict[str, str]) -> Target | None:
        """A person's area or tablet; None when it names nothing that exists."""
        name = str(person.get("name") or "")
        if area_name := str(person.get("area") or "").strip():
            area = self._area_entry(area_name)
            if area is None:
                return None
            return Target(self._tablets_in(area), name or area.name)
        device = str(person.get("device") or "").strip()
        if not device:
            return None
        target = self._from_tablet(device)
        if target is None:
            return None
        return Target(target.records, name or target.label)

    def _area_entry(self, name: str) -> Any | None:
        """An HA area by spoken name or by id."""
        registry = ar.async_get(self.hass)
        return registry.async_get_area_by_name(name) or registry.async_get_area(name)

    def _from_area(self, name: str) -> Target | None:
        area = self._area_entry(name)
        return Target(self._tablets_in(area), area.name) if area is not None else None

    def _tablets_in(self, area: Any) -> list[DeviceRecord]:
        """Every tablet in an area, through the device registry (ADR 0010 item 3)."""
        devices = dr.async_get(self.hass)
        ha_ids = {entry.id for entry in dr.async_entries_for_area(devices, area.id)}
        return [r for r in self.hub.devices.values() if r.ha_device_id in ha_ids]

    def _from_tablet(self, name: str) -> Target | None:
        """A tablet by its name, its Tabula id or its HA device id."""
        wanted = _normalize(name)
        for record in self.hub.devices.values():
            if wanted in (_normalize(record.name or ""), record.device_id, record.ha_device_id):
                return Target([record], _label(record))
        return None

    # --- speaking ----------------------------------------------------------

    def speak(self, language: str | None, key: str, **values: str) -> str:
        """One sentence in the caller's language, falling back to English."""
        code = (language or LANGUAGE).split("-")[0].lower()
        table = SPEECH.get(code) or SPEECH[LANGUAGE]
        template = table.get(key) or table["refused"]
        try:
            return template.format(**values)
        except (KeyError, IndexError):  # a placeholder the refusal did not carry
            return table["refused"]

    def _response(
        self, intent_obj: intent.Intent, key: str, **values: str
    ) -> intent.IntentResponse:
        """A spoken refusal: an error response whose speech is the message."""
        response = intent_obj.create_response()
        response.async_set_error(
            intent.IntentResponseErrorCode.FAILED_TO_HANDLE,
            self.speak(intent_obj.language, key, **values),
        )
        return response

    # --- handling ----------------------------------------------------------

    async def async_ask(self, intent_obj: intent.Intent, mode: str) -> intent.IntentResponse:
        """Start a call from the tablet that heard the request, or say why not."""
        caller = self.hub.record_for_ha_device(intent_obj.device_id)
        if caller is None:
            return self._response(intent_obj, "not_a_tablet")
        callees: list[DeviceRecord] | None = None
        target_name = ""
        if mode == MODE_CALL:
            spoken = str((intent_obj.slots.get("target") or {}).get("value") or "")
            target = self.lookup(spoken)
            if target is None:
                return self._response(intent_obj, "unknown_target", target=spoken.strip())
            callees, target_name = target.records, target.label
        try:
            await self.hub.intercom.async_start_call(caller, callees, mode)
        except HomeAssistantError as err:
            # Every guard the service has: the capability gate, one callee, the
            # same-area refusal, busy. The key is what the refusal is about.
            key = getattr(err, "translation_key", None) or "refused"
            return self._response(intent_obj, key, **dict(err.translation_placeholders or {}))
        response = intent_obj.create_response()
        if mode == MODE_BROADCAST:
            response.async_set_speech(self.speak(intent_obj.language, "broadcasting"))
        else:
            response.async_set_speech(
                self.speak(intent_obj.language, "calling", target=target_name)
            )
        return response


class IntercomCallHandler(intent.IntentHandler):
    """``call <area or person>``: a two-way call from the tablet that heard it."""

    intent_type = INTENT_CALL
    description = "Call a room, a tablet or a person on the intercom"

    def __init__(self, voices: IntercomVoiceIntents) -> None:
        self.voices = voices

    @property
    def slot_schema(self) -> dict | None:
        return {vol.Required("target"): str}

    async def async_handle(self, intent_obj: intent.Intent) -> intent.IntentResponse:
        return await self.voices.async_ask(intent_obj, MODE_CALL)


class IntercomBroadcastHandler(intent.IntentHandler):
    """``broadcast``: live one-way audio to every tablet that can hear."""

    intent_type = INTENT_BROADCAST
    description = "Speak through every intercom tablet at once"

    def __init__(self, voices: IntercomVoiceIntents) -> None:
        self.voices = voices

    async def async_handle(self, intent_obj: intent.Intent) -> intent.IntentResponse:
        return await self.voices.async_ask(intent_obj, MODE_BROADCAST)


# The names the rest of the integration (and the tests) reach for.
__all__ = [
    "INTENTS",
    "INTENT_BROADCAST",
    "INTENT_CALL",
    "IntercomVoiceIntents",
    "PersonMapError",
    "parse_persons",
    "persons_to_text",
    "sentences_for",
]
