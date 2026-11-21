# 0014: Screenshot screen-off placeholder

Status: accepted
Date: 2026-11-21
Packet: P3.13

## Context

F-HA-12 asks for a screenshot of the tablet's dashboard, and F-MGT-07 says a
screen-off tablet reports "nothing to capture" rather than failing the
command. The image entity (F-HA-02) shows the latest capture; the tablet
uploads a JPEG only while the command is pending (PROTOCOL.md 5), so with the
screen off nothing ever arrives at the integration.

The image entity still has to render something: HA's image platform shows a
generic broken-image tile when the entity has no image to serve. The naive
choice — serve a black frame as the "screen off" state — would misrepresent
the device: a black JPEG is indistinguishable from a real capture of a
black screensaver (P2.4) or a panel fault.

## Decision

The image entity serves a designed static placeholder when it has no stored
capture for the device: a near-black slate with a muted power-off glyph,
bundled in the integration as a fixed JPEG (`screenshot_placeholder.jpg`,
800x480, quality 80). It is never confused with a real capture: it is a
drawn image, not a frame of the display. The placeholder serves for every
"no capture yet" state — never paired with an upload, screen off, capture
failed, HA restarted and the stored file is gone — because from the
integration's side these are indistinguishable: there is no stored image
(the store lives in `config/tabula/screenshots`, outside HA's media dir,
so captures stay out of the media browser).

The device side decides the difference: with the screen off it captures
nothing and still ACKs the screenshot command (F-MGT-07), so HA never
replaces a stored capture with a "blank" frame.

## Consequences

- The image entity always renders, including before the first capture and
  after a restart, with no special-casing of the entity state.
- The placeholder is a designed asset: changing its look means changing the
  bundled JPEG, not code.
- The "no capture yet" states cannot be told apart by looking at the entity;
  the `last_capture` timestamp sensor (F-HA-12) is the signal for when the
  most recent capture succeeded.
- The mock device applies no screen: it embeds a fixed JPEG and validates
  the quality hint (10-95) without using it, recorded as a packet deviation.

## Alternatives considered

- Serve a black frame — rejected: indistinguishable from a real capture of
  a black screensaver, and hides faults.
- Set the entity unavailable while no capture exists — rejected: the entity
  then disappears from dashboards that show it, which is worse than a clear
  placeholder, and F-HA-02 wants an image entity that shows the latest
  capture.
- Have the device upload a "screen off" marker image — rejected: HA would
  store it as if it were a capture, overwriting the last real image.
