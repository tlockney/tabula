# 0009: Phase 1 entity model choices

Status: accepted
Date: 2026-11-21
Packet: P1.3

## Context

Plan section 5.5 lists the Phase 1 platforms, and FEATURES F-HA-02, F-HA-04, F-HA-09 and F-HA-11 describe how entities follow capabilities and the connection. A few points are open in FEATURES or not covered by the plan. P1.3 needs an answer to each before it can ship entities.

## Decision

1. **Capability to entity mapping.** The protocol leaves some state keys "covered by none" (PROTOCOL.md section 4.0). Entities reading those keys always exist; the others exist only while their capability is declared. The table lives in `integration/custom_components/tabula/entity.py`. A capability dropped at registration removes its entities (F-HA-02: no permanent placeholders); a disconnect removes nothing (F-HA-04).
2. **Connectivity sensor reads off, not unavailable** (the open question in F-HA-04 and F-HA-09). HA computes it from the connection itself, so it always has a true value. This matches the meaning of HA's connectivity device class, and lets automations trigger on the tablet dropping off without also reacting to "unavailable".
3. **The screensaver-active switch has no entity category.** F-HA-11 groups the switches under the configuration category, but turning this one on starts the screensaver now: it is a control, so it shows with the device's controls. Keep screen on, adaptive brightness, the screensaver brightness number and the screensaver mode select are configuration.
4. **Network type and Wi-Fi signal sensors** are added, both diagnostic, the signal one disabled by default. They read state keys the protocol already carries (`network`, `wifi_rssi`) and cost nothing on the device. This is beyond the plan's Phase 1 sensor list.
5. **Entities survive restarts.** Declared capabilities are stored with each paired device, so after an HA restart the entities exist, unavailable, before the tablet reconnects.

## Consequences

- P1.4 wires the entity actions to commands; actions raise `HomeAssistantError` until then.
- The dashboard view select (plan 5.5) waits for a design of its option list (F-HA-10) and for `navigate` dispatch in P1.4.
