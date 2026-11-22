# Task #127: the pairing screen with no network

| Behavior | Source | Packet |
| --- | --- | --- |
| While the pair request cannot reach Home Assistant, the pairing screen stays on the address form, names the address it cannot reach, and keeps retrying in the background; the code screen appears only once Home Assistant has issued a code, so an empty code is never shown | independent design (PROTOCOL.md section 2) | #127 |
