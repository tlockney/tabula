# 0036: Voice-started intercom calls

Status: accepted
Date: 2026-11-24
Packet: P9.6

## Context

ADR 0025 decision 8 makes a call startable by voice from the first intercom release, and ADR 0024/P7.3 gave the hub a native `assist_satellite` entity, so a tablet already hears a wake word and hands a transcript to HA's conversation pipeline. What was missing is a way for a spoken sentence to reach `tabula.intercom_call`.

Read from HA core 2025.1.0 (our floor), 2026.2.3 (our pin) and 2026.10.4, Apache-2.0:

- `homeassistant.helpers.intent` has `async_register(hass, handler)`, `async_remove(hass, intent_type)` and `async_get(hass)` returning the registered handlers. `IntentHandler.intent_type` is a global name and `slot_schema` is a `vol.Schema`-style dict; an unparsed slot makes HA answer with its own error, so both of ours are `str`.
- The default conversation agent loads its sentence set once per language into `LanguageIntents` and keeps it (`async_get_or_load_intents`); `agent.async_reload(language)` drops it. On 2025.1 `async_get_agent` raises `KeyError` when the `conversation` component is not set up; 2026.x returns `None`.
- The agent also merges every `custom_sentences/<language>/*.yaml` file into that set, which is the documented way for a local integration to add sentences without patching HA. Its file format is `language`, `intents: {name: {data: [{sentences: [...]}]}}` and `lists`, i.e. the same document hassil parses.
- The agent caches one recognition result per (text, language, device, satellite), including fuzzy matches. A new sentence is matched strictly again; a repeated one may be answered from that cache before the strict pass.
- `assist_satellite` passes `device_id` (the satellite's HA device) and `satellite_id` into `async_converse`, so `Intent.device_id` is the tablet that heard the request.
- hassil matches intents in load order and stops at the first match; `lists: {name: {wildcard: true}}` lets one sentence capture the rest of a phrase.
- `home_assistant_intents` (the packaged English set, 39 intents) has no sentence that starts with "call", and `HassBroadcast` owns "broadcast <message>" through the satellite ANNOUNCE feature.

## Decision

1. **Our sentences are a local sentence set on disk, written by the integration.** `<config>/custom_sentences/en/tabula_intercom.yaml` is written when the gate opens and removed when it closes or the entry unloads; nothing else in that directory is touched, and the file's absence is what "no intents" means on disk. A component cannot add to HA's built-in intent set, and the agent's in-memory override belongs to the `conversation` integration and is discarded when it sets up — a file survives both. The document is built per language (`LANGUAGES`, `en` for now), so another language is a data change.

2. **The agent's cache is cleared after the file changes.** `agent.async_reload("en")` runs only when the file changed on disk, and a missing `conversation` component (2025.1 `KeyError`, 2026.x `None`) is not an error: there is then nothing to clear.

3. **The intents and the sentences exist only while a tablet can use them.** The gate is the capability pair `voice` + `intercom_talk` (`INTERCOM_VOICE_CAPABILITIES`): a tablet must be able to hear the request and carry the call. Both handlers and the file appear and disappear together, re-checked on every `SIGNAL_CAPABILITIES` register frame. A listen-only tablet can be called but cannot call, and a household with no capable tablet matches nothing at all.

4. **The caller is the tablet that heard it, never a named argument.** `Intent.device_id` resolves through the hub's device records; a request from a device that is not a paired tablet is refused out loud. There is no `caller` slot, so no sentence can start a call from somewhere else.

5. **The intents run the service's code path.** Both handlers call `IntercomManager.async_start_call`, so the capability gate, the one-tablet rule, the same-area refusal, the busy checks and the timers are P9.2's, unchanged; nothing is rung that `tabula.intercom_call` could not ring. Refusals reuse the manager's own translation keys, shortened for speech, so a household hears the same reasons it would read in HA's UI. An unmapped HA error still speaks: "The call could not be started."

6. **A spoken name resolves person, then area, then tablet.** The person map is the hub entry option `intercom_persons`: a list of `{name, area|device}`, edited as YAML in the options flow's new "Intercom names" step and validated on save (`PersonMapError` lists every problem). Names are unique and casefolded; a target is an area's name or id, or a tablet's name, Tabula id or HA device id. A person wins over an area of the same name, because a person is something the household wrote down on purpose. An entry whose area or tablet no longer exists resolves to nothing and is refused out loud. No reload is needed: `async_update_entry` rewrites the entry object the hub already holds, and the map is read at request time.

7. **The options flow became a menu.** Per-tablet settings and the intercom names are two steps (`settings`, `persons`) behind `async_step_init`; the tablet editor is unchanged, so the existing settings tests still describe it. A hub that is not loaded aborts (`hub_not_loaded`) instead of showing a form that cannot save.

8. **The live forms only; "broadcast <message>" stays HA's.** This module registers `broadcast`, `intercom broadcast`, `start [an] intercom broadcast`, `call everyone`, `call everybody`, `call all [the] (rooms|tablets)` and `call {target}`. A spoken *message* is `HassBroadcast`, which already announces through every satellite on the P7.3 announce path; an intercom broadcast is live one-way audio with no text on the wire (ADR 0025 decision 2). Registering our own `broadcast {message}` would either shadow HA's intent or be shadowed by it, for the same words, so the packet's "broadcast <message>" is delivered as "broadcast" plus HA's announcement, and this deviation is recorded in the packet's Result. The broadcast sentences come first in the file, because hassil stops at the first match and the wildcard `call {target}` would otherwise read "everyone" as a room.

9. **Intent names are namespaced.** `TabulaIntercomCall` and `TabulaIntercomBroadcast`: HA's intent types share one global namespace with every integration and every built-in set.

## Consequences

- A voice-started call is complete up to the ring: the callee gets `intercom_ring` with the caller's HA and Tabula ids and the mode, and every guard answers out loud. The caller's own engine still has no call id, which is P9.2's open question 1 and a protocol addition, not this packet's; when that frame lands, this path inherits it with no change here.
- The sentence file is per install, not per entry. That matches the single hub entry the config flow enforces (one unique id), and a second entry would have to namespace the file first.
- Text-to-speech announcements and live broadcasts stay two different things with two different owners, which is what keeps "broadcast" from becoming ambiguous.
- A tablet that gains `voice` while HA is running gets its sentences without a restart, at the cost of one agent reload per capability change.
- The refusal table is English like the sentences; another language adds a column, not a code path.
- `docs/provenance/P9.6.md` records the behaviour as independent design, and the packet's tests run against the mock device and HA's conversation helpers only: no audio is played or captured by this packet.

## Alternatives considered

- **Patch the default agent's intents in memory.** Would need private attributes of the `conversation` integration and would be dropped by its own setup and by every reload; the documented custom-sentence directory is the supported path.
- **A conversation trigger or a `conversation` YAML block instead of a component.** Both need the household to edit YAML and neither can be gated on a live capability; the gate has to follow register frames.
- **Ship sentences in the integration's own package and point HA at them.** HA reads `custom_sentences` under the config directory only; there is no per-integration sentence path in the supported releases.
- **Register the intents always and refuse inside the handler.** Cheaper, but then "call the kitchen" would match in a household where no tablet can call, and answer with a refusal instead of HA's ordinary "I don't understand"; the packet asks for the intents to be absent.
- **A `caller` slot so any satellite could start a call for another room.** Nothing in the packet needs it, and it would let a sentence start a call from a tablet nobody is standing at, against decision 4.
- **Our own `broadcast {message}` intent.** See decision 8: the words would collide with HA's announcement intent, and the announcement path already reaches every tablet.
- **Store the person map in the settings store like per-device settings.** The map is about the hub, not a device, and it needs a free-form list; an entry option is what the options flow already writes.
- **Reload the entry when the person map changes.** A reload tears down every tablet's socket for a text edit; the map is read per request, so the reload buys nothing.
