"""Device speaker (plan 5.5): tts.speak and media_player.play_media target it."""

from __future__ import annotations

from typing import Any

from homeassistant.components.media_player import (
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
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
        await self.async_command("play_media", {"url": media_id, "media_type": str(media_type)})

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
