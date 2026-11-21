"""Device speaker (plan 5.5): tts.speak and media_player.play_media target it."""

from __future__ import annotations

from typing import Any

from homeassistant.components import media_source
from homeassistant.components.media_player import (
    ATTR_MEDIA_ANNOUNCE,
    ATTR_MEDIA_EXTRA,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
    async_process_play_media_url,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import TabulaHub
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform

SPEAKER = TabulaEntityDescription(
    key="speaker",
    translation_key="speaker",
    capabilities=frozenset({"audio"}),
    state_keys=frozenset({"volume", "muted", "media_state"}),
)

STATES = {
    "idle": MediaPlayerState.IDLE,
    "playing": MediaPlayerState.PLAYING,
    "paused": MediaPlayerState.PAUSED,
}


class TabulaSpeaker(TabulaEntity, MediaPlayerEntity):
    _attr_supported_features = (
        MediaPlayerEntityFeature.PLAY_MEDIA
        | MediaPlayerEntityFeature.MEDIA_ANNOUNCE
        | MediaPlayerEntityFeature.PLAY
        | MediaPlayerEntityFeature.PAUSE
        | MediaPlayerEntityFeature.STOP
        | MediaPlayerEntityFeature.VOLUME_SET
        | MediaPlayerEntityFeature.VOLUME_MUTE
    )

    @property
    def state(self) -> MediaPlayerState | None:
        return STATES.get(self.device_state.get("media_state", ""))

    @property
    def volume_level(self) -> float | None:
        volume = self.device_state.get("volume")
        return None if volume is None else volume / 100

    @property
    def is_volume_muted(self) -> bool | None:
        muted = self.device_state.get("muted")
        return None if muted is None else bool(muted)

    async def async_play_media(
        self, media_type: MediaType | str, media_id: str, **kwargs: Any
    ) -> None:
        """Play a URL or media-source item, tts.speak included (ADR 0001 item 5).

        A media-source ID (tts.speak sends ``media-source://tts/...``) resolves
        to an HA path; HA paths that need auth are signed. HA-hosted media goes
        out as a ``/`` path the tablet resolves against the base URL it already
        reaches HA on (PROTOCOL.md 4.5, ADR 0010), so no internal or external
        URL needs configuring. The tablet never sends its token for it.
        """
        if media_source.is_media_source_id(media_id):
            item = await media_source.async_resolve_media(self.hass, media_id, self.entity_id)
            media_id = item.url
        url = async_process_play_media_url(self.hass, media_id, allow_relative_url=True)
        args: dict[str, Any] = {
            "url": url,
            "media_type": "music" if media_type == MediaType.MUSIC else "audio",
            "announce": bool(kwargs.get(ATTR_MEDIA_ANNOUNCE)),
        }
        volume = (kwargs.get(ATTR_MEDIA_EXTRA) or {}).get("volume")
        if isinstance(volume, int) and 0 <= volume <= 100:
            args["volume"] = volume
        await self.async_command("play_media", args)

    async def async_media_play(self) -> None:
        await self.async_command("media_control", {"action": "resume"})

    async def async_media_pause(self) -> None:
        await self.async_command("media_control", {"action": "pause"})

    async def async_media_stop(self) -> None:
        await self.async_command("media_control", {"action": "stop"})

    async def async_set_volume_level(self, volume: float) -> None:
        await self.async_command(
            "media_control", {"action": "set_volume", "volume": round(volume * 100)}
        )

    async def async_mute_volume(self, mute: bool) -> None:
        await self.async_command("media_control", {"action": "mute" if mute else "unmute"})


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(setup_platform(hass, hub, async_add_entities, [SPEAKER], TabulaSpeaker))
