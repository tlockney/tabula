"""Exceptions the integration raises with a device's own error code.

``TabulaHub.async_command`` raises one of these for every way a command can
fail, and the device's NACK code rides along in ``code`` so a caller can tell
one refusal from another (the intercom relay reads ``busy``). The error still
carries a translation key, so what a person sees is unchanged.
"""

from __future__ import annotations

from typing import Any

from homeassistant.exceptions import HomeAssistantError


class DeviceCommandError(HomeAssistantError):
    """A command that was refused, unanswered or undeliverable.

    ``code`` is the device's `result.error.code` when it answered at all, and
    None when the command never reached a device (offline, unsupported
    capability, no answer in time, socket closed).
    """

    def __init__(self, *, code: str | None = None, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.code = code
