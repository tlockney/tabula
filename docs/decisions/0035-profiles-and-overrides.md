# 0035: Profiles, effective values and per-device overrides

Status: accepted
Date: 2026-11-24
Packet: P5.6

## Context

F-MGT-02 makes a profile a named partial settings document many devices can
share: effective value per key is the profile's if it covers the key, otherwise
the device's own. It also leaves one question open: what happens when a key the
profile covers is edited for a single device. The three candidates are a
refusal, an explicit per-device override with a visible marker (the FEATURES
recommendation), and letting the edit stand until the next profile save
overwrites it.

The answer shapes everything after it: how the store is laid out, what a profile
push may touch, what the panel shows on a device whose value disagrees with its
profile, and what "drift" can even mean. Drift is the other question. TLP sends
settings only one way (HA to the device) and the device never uploads its
document (PROTOCOL.md 4.5), so the integration cannot compare values. All it
knows is the revision the device reported at registration or in its last
`set_settings` ACK (4.2, 4.6).

## Decision

1. **A per-device override, created by editing a covered key for one device.**
   An override is one key's value for one device, shadowing what the device's
   assigned profile says for that key. It is created by any per-device write of
   a covered key whose value differs from the profile's, and it records the
   same value HA stores, so HA remains authoritative and no second document
   exists to fall out of step. A write whose value equals the profile's clears
   the override instead: the two states are the same state seen from two sides,
   so one rule keeps them consistent whatever made the write (options flow,
   settings entities, panel editor, services). Profile application itself is
   the one caller that writes covered keys without recording overrides
   (`record_overrides=False`): it writes the effective values, not a user's
   per-device exception.

2. **Effective values, and only covered keys travel.** For a device assigned to
   a profile, per key: the override if one is set, else the profile's value when
   the profile covers the key, else the device's own value. Applying a profile
   is a normal settings write through `async_set_settings`: the effective values
   of the covered keys that differ from what the device's document holds, as one
   revision with one `set_settings` push. Nothing else is touched, so an
   uncovered key keeps exactly what HA stored. A profile with no covered keys
   (the built-in empty profile) writes nothing, which is why assigning a device
   to it never changes its settings.

3. **A profile push leaves overridden keys alone.** Saving a profile, syncing it
   and assigning a device to it all compute per key, so an overridden key keeps
   its override: the profile's new value for that key is stored in the profile
   and applies to every other member, and the overriding device keeps its own
   value until the override is cleared. A push never silently discards a
   user's explicit choice; that is the whole point of choosing an override over
   "overwritten on next sync".

4. **Clearing an override is one action in two places.** The panel shows an
   overridden key marked as such, both in the profile editor's member list and
   on the device page, with a control that clears the override for that key; the
   settings editor clears it implicitly when the user submits the profile's own
   value. `tabula/panel_profile_override` does both explicitly (`value: null`
   clears). Clearing applies the profile's value through the same write path, so
   the device converges in one revision.

5. **Overrides live only where they can shadow something.** An override exists
   for a device only while its assigned profile covers the key. Assigning a
   device to another profile, deleting the profile a device was assigned to and
   removing a key from a profile all drop the overrides that no longer shadow
   anything (ADR-consistent: the value stays in the device's document, because
   that document already holds the override's value, so nothing changes for the
   device at the moment it stops being an exception).

6. **Assignment and overrides are HA-side state, never sent to the device.** The
   `ProfileStore` is one HA `Store` (`.storage/tabula.profiles`) holding named
   profiles, the device-to-profile assignment and the overrides. The device only
   ever receives effective values in a normal `set_settings` document; nothing in
   TLP or the settings schema mentions profiles, an assignment is not a setting
   and no payload names a profile. A device therefore cannot tell whether a value
   came from its own document or a profile, which is the point: the protocol did
   not change for this feature.

7. **Drift is revision-based, and the ADR says so plainly.** A member drifts when
   the revision it reported differs from the revision HA stores; the report gives
   the signed difference, the connection state and when it was last seen. Two
   more member conditions are reported beside it because they are visible from
   HA's side and need attention: a member with an override shadowing a profile
   key (informational: this member intentionally differs), and a member whose
   settings schema version predates a key the profile covers (the push translates
   the document down and drops that key, so the member agrees on the revision but
   not on the value). Nobody should expect a value comparison: the protocol has
   no upload direction, so a value that differs on the device for another reason
   is invisible here.

8. **The store enforces the role rule, not the panel.** A profile may hold only
   `shared`-role leaves (F-MGT-03). Saving validates each key against the schema's
   own leaf subschema and refuses anything else with a per-key reason: an unknown
   key, a group key, a key of another role (`identity`, `connection`, `hardware`)
   and a value the leaf rejects. A payload that smuggles a forbidden key inside a
   larger request loses that key and keeps the rest, exactly like the settings
   editor (P5.3). The panel never offers a non-shared key; the server does not
   rely on that.

9. **The built-in empty profile is synthesized, not stored.** Its id is `empty`,
   its name is reserved, it holds no values and cannot be renamed, deleted or
   saved over. Removing a profile reassigns its members to it and writes nothing
   to their settings (F-MGT-02). New profiles start with no keys at all, which
   satisfies F-MGT-04 without a second list of "room" keys: the user adds the
   groups and keys a household wants to share.

## Consequences

- The device document is the one stored copy of every settings value; a profile
  is a template applied into it. No read path needs to resolve profiles, so
  entities, the options flow and the settings editor keep working unchanged.
- Offline members need no queue: a profile save writes the next revision into
  their document, and the existing registration reconcile (plan 5.6, PROTOCOL
  4.2) pushes it when they come back.
- Deleting a profile is not a rollback: members keep the last applied values as
  their own. The panel says so next to the delete control.
- A device that leaves a profile keeps its overrides only for keys the new
  profile covers; everything else stays as its own value.
- A profile save writes to every member in one HA task; a large fleet pushes
  sequentially (one command round trip each) through the existing push path.

## Alternatives considered

- **Refuse the per-device edit.** Safest for consistency, but F-MGT-01 makes HA
  the place settings are edited, and a household that wants the kitchen at 30 %
  brightness while the profile says 40 % has no way to say so without leaving
  the profile.
- **Let the edit stand until the next profile save overwrites it.** No new
  storage, but it is a silent data loss: an explicit choice disappears the next
  time anyone edits the profile, and nothing in the UI would have warned.
- **A second settings document per device (profile values kept separate and
  merged on read).** Cleaner conceptually, but it would move profile resolution
  into every read path (entities, options flow, pushes) and give two places for
  a value to live.
- **Value-based drift.** Requires the device to upload its settings document,
  which TLP deliberately does not have; a new frame for it would be a protocol
  addition serving only a report.
