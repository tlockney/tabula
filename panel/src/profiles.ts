/**
 * The Profiles section's vocabulary as pure functions (P5.6).
 *
 * Only `shared`-role leaves can travel in a profile (F-MGT-03, ADR 0035 item
 * 8); the picker is built from the generated schema, so a schema bump adds its
 * keys without new UI code. Everything here is testable without a DOM: the
 * view ([profiles-view.ts]) renders what these return and nothing else.
 */
import { SETTINGS_FIELDS, type SettingsField } from "./generated/settings";
import {
  clientProblem,
  fieldDefault,
  SETTINGS_SECTIONS,
  titleFor,
  type SettingsSection,
} from "./settings";
import { STRINGS } from "./strings";
import type { DriftMember, ProfileDiffRow } from "./types";

/** A flat key -> value document, as a profile holds it. */
export type ProfileValues = Record<string, unknown>;

/** The sections and groups a profile may cover: `shared` leaves only. */
export function shareableSections(): SettingsSection[] {
  const sections: SettingsSection[] = [];
  for (const section of SETTINGS_SECTIONS) {
    const fields = section.fields.filter((field) => field.role === "shared");
    const groups = section.groups
      .map((group) => ({
        ...group,
        fields: group.fields.filter((field) => field.role === "shared"),
      }))
      .filter((group) => group.fields.length > 0);
    if (fields.length || groups.length) {
      sections.push({ ...section, fields, groups });
    }
  }
  return sections;
}

/** Every key a profile could cover, in schema order. */
export function shareableFields(): SettingsField[] {
  return SETTINGS_FIELDS.filter((field) => field.role === "shared");
}

/** The label for one key, from the same table the settings editor uses. */
export function fieldLabel(field: SettingsField): string {
  return titleFor(field.path);
}

/** A group's three states: every key covered, some, or none. */
export type GroupState = "all" | "some" | "none";

/** Where a group's keys stand against the draft's included set. */
export function groupState(
  fields: readonly SettingsField[],
  included: ReadonlySet<string>,
): GroupState {
  const count = fields.filter((field) => included.has(field.key)).length;
  if (count === 0) {
    return "none";
  }
  return count === fields.length ? "all" : "some";
}

/** The included set after toggling a group on or off: one key per leaf. */
export function toggleGroup(
  fields: readonly SettingsField[],
  included: ReadonlySet<string>,
  on: boolean,
): Set<string> {
  const next = new Set(included);
  for (const field of fields) {
    if (on) {
      next.add(field.key);
    } else {
      next.delete(field.key);
    }
  }
  return next;
}

/** The included set after toggling one key. */
export function toggleKey(included: ReadonlySet<string>, key: string, on: boolean): Set<string> {
  const next = new Set(included);
  if (on) {
    next.add(key);
  } else {
    next.delete(key);
  }
  return next;
}

/** A new profile's starting point: no keys at all (F-MGT-04, ADR 0035 item 9). */
export function emptySelection(): Set<string> {
  return new Set();
}

/** The values a save sends: the included keys, in schema order, as typed values. */
export function selectedValues(
  included: ReadonlySet<string>,
  draft: ReadonlyMap<string, unknown>,
): ProfileValues {
  const values: ProfileValues = {};
  for (const field of SETTINGS_FIELDS) {
    if (!included.has(field.key)) {
      continue;
    }
    // An included key with no typed value shares its schema default, which is
    // what the editor showed it as.
    const value = draft.has(field.key) ? draft.get(field.key) : fieldDefault(field);
    if (value !== undefined) {
      values[field.key] = value;
    }
  }
  return values;
}

/** `screensaver_clock_face` -> the key's group id (`screensaver.clock`). */
export function groupIdOf(field: SettingsField): string {
  const parts = field.path.split(".");
  return parts.length > 2 ? parts.slice(0, 2).join(".") : parts[0];
}

/**
 * A profile name's problem, or null.
 *
 * Mirrors the server's rule (ADR 0035 item 9, ADR 0002 `PROFILE_NAME_MAX`): a
 * name is required, at most 64 characters, unique ignoring case, and the
 * built-in profile's name is reserved. The server is what decides; this only
 * tells the user before the round trip.
 */
export function profileNameProblem(
  name: string,
  existing: readonly { profile_id: string; name: string }[],
  self?: string,
): string | null {
  const trimmed = name.trim();
  if (!trimmed) {
    return STRINGS.profiles.nameRequired;
  }
  if (trimmed.length > 64) {
    return STRINGS.profiles.nameTooLong;
  }
  if (trimmed.toLowerCase() === "empty") {
    return STRINGS.profiles.nameReserved;
  }
  const clash = existing.find(
    (profile) =>
      profile.profile_id !== self && profile.name.toLowerCase() === trimmed.toLowerCase(),
  );
  return clash === undefined ? null : STRINGS.profiles.nameTaken(clash.name);
}

/** A leaf's value as text for an input: arrays and objects as JSON. */
export function valueText(value: unknown): string {
  if (value === undefined || value === null) {
    return "";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

/**
 * A raw input as the leaf's own type, or the reason it is not.
 *
 * The same checks the settings editor makes (clientProblem), so an invalid
 * value is refused before the save and the server refuses it again anyway.
 */
export function parseProfileValue(
  field: SettingsField,
  raw: string,
): { value?: unknown; problem?: string } {
  let value: unknown = raw;
  if (field.type === "boolean") {
    value = raw === "true";
  } else if (field.type === "integer") {
    if (raw.trim() === "" || !/^-?\d+$/.test(raw.trim())) {
      return { problem: STRINGS.settings.problems.integer };
    }
    value = Number.parseInt(raw, 10);
  } else if (field.type === "array") {
    try {
      value = JSON.parse(raw);
    } catch {
      return { problem: STRINGS.profiles.notJson };
    }
  }
  const problem = clientProblem(field, value);
  return problem === null ? { value } : { problem };
}

/** A value as the diff table shows it. */
export function displayValue(value: unknown, unset = false): string {
  if (unset || value === undefined) {
    return STRINGS.profiles.unset;
  }
  if (Array.isArray(value) && value.length === 0) {
    return STRINGS.profiles.emptyList;
  }
  if (typeof value === "object" && value !== null) {
    return JSON.stringify(value);
  }
  return String(value);
}

/** One member's diff rows, for the preview and the sync report. */
export function rowsByDevice(rows: readonly ProfileDiffRow[]): Map<string, ProfileDiffRow[]> {
  const grouped = new Map<string, ProfileDiffRow[]>();
  for (const row of rows) {
    const list = grouped.get(row.device_id) ?? [];
    list.push(row);
    grouped.set(row.device_id, list);
  }
  return grouped;
}

/** How many rows would change, and how many are overridden (the preview's line). */
export function diffSummary(rows: readonly ProfileDiffRow[]): {
  changing: number;
  overridden: number;
} {
  return {
    changing: rows.filter((row) => row.changes).length,
    overridden: rows.filter((row) => row.overridden).length,
  };
}

/** The tags a drifting member carries: revision, override, old schema. */
export function driftKinds(member: DriftMember): string[] {
  const kinds: string[] = [];
  if (member.drifted) {
    kinds.push(
      member.revisions_behind === null
        ? STRINGS.profiles.driftRevision
        : STRINGS.profiles.driftBehind(member.revisions_behind),
    );
  }
  if (member.overrides.length) {
    kinds.push(STRINGS.profiles.driftOverride(member.overrides.length));
  }
  if (member.behind_keys.length) {
    kinds.push(STRINGS.profiles.driftSchema(member.behind_keys.length));
  }
  return kinds;
}

/** A drift row's class, for the tag colours. */
export function driftClass(member: DriftMember): string {
  return `drift-row${member.connected ? "" : " offline"}`;
}

/** The keys a member's profile covers but its schema cannot take. */
export function behindKeys(member: DriftMember, values: ProfileValues): string[] {
  return member.behind_keys.filter((key) => key in values);
}
