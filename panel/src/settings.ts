/**
 * The settings editor's vocabulary as pure functions (P5.3).
 *
 * The editor is generated from `SETTINGS_FIELDS` (the schema, via
 * tools/codegen_ts.py): sections and groups come from the dotted paths, labels
 * from the panel's string table with a humanized fallback, and each control's
 * bounds, enum and pattern from the leaf's own metadata. Client checks mirror
 * that metadata for immediacy only; the server's response is what the form
 * reports (P5.3 step 5).
 */
import {
  NO_DEFAULT,
  SETTINGS_FIELDS,
  type SettingsArrayItems,
  type SettingsField,
  type SettingsItemProperty,
} from "./generated/settings";
import { STRINGS } from "./strings";
import type { HassStates } from "./types";

/** The form's state: every leaf's value, keyed by flat schema key. */
export type SettingsValues = Record<string, unknown>;

/** A nested group inside a section (`screensaver.clock`, `widgets.weather`, …). */
export interface SettingsGroup {
  /** The dotted path prefix, e.g. "screensaver.clock". */
  id: string;
  title: string;
  fields: SettingsField[];
}

/** One section of the form: a top-level schema group, in schema order. */
export interface SettingsSection {
  /** The top-level group name, e.g. "screensaver". */
  id: string;
  title: string;
  /** Leaves that sit directly in the section, before its nested groups. */
  fields: SettingsField[];
  groups: SettingsGroup[];
}

/** The leaf names the schema gives a label of its own; the rest are humanized. */
const LABELS: Record<string, string> = STRINGS.settings.labels;

/** `idle_timeout` -> "Idle timeout": a label for a key the string table omits. */
export function humanize(id: string): string {
  const leaf = id.split(".").pop() ?? id;
  const words = leaf.replace(/[_.]/g, " ").trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** The panel label for a section, group or leaf path. */
export function titleFor(id: string): string {
  return LABELS[id] ?? humanize(id);
}

/** The panel label for one leaf. */
export function labelFor(field: SettingsField): string {
  return titleFor(field.path);
}

function clone(value: unknown): unknown {
  return Array.isArray(value) ? value.map((item) => clone(item)) : value;
}

/** The leaf's schema default, or undefined when the schema sets none. */
export function fieldDefault(field: SettingsField): unknown {
  return field.default === NO_DEFAULT ? undefined : clone(field.default);
}

/** The value at a dotted path, or undefined when any part of it is absent. */
export function valueAt(document: unknown, path: string): unknown {
  let node: unknown = document;
  for (const part of path.split(".")) {
    if (typeof node !== "object" || node === null || !(part in node)) {
      return undefined;
    }
    node = (node as Record<string, unknown>)[part];
  }
  return node;
}

/** A document as the form's flat values, filling in what the document omits. */
export function flattenSettings(document: unknown): SettingsValues {
  const values: SettingsValues = {};
  for (const field of SETTINGS_FIELDS) {
    const stored = valueAt(document, field.path);
    if (stored !== undefined) {
      values[field.key] = clone(stored);
    } else if (field.default !== NO_DEFAULT) {
      values[field.key] = clone(field.default);
    }
  }
  return values;
}

/** One leaf's current value: the draft's when it has one, else its default. */
export function settingValue(values: SettingsValues, field: SettingsField): unknown {
  return field.key in values ? values[field.key] : fieldDefault(field);
}

/** Structural equality for the JSON-shaped values settings hold. */
export function sameValue(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

/**
 * The leaves whose draft value differs from what was loaded.
 *
 * One map for the whole form, because a save is one revision (ADR 0011); the
 * per-section dirty marks are derived from the same map.
 */
export function changedValues(original: SettingsValues, draft: SettingsValues): SettingsValues {
  const changed: SettingsValues = {};
  for (const field of SETTINGS_FIELDS) {
    const now = settingValue(draft, field);
    if (now === undefined) {
      continue;
    }
    if (!sameValue(now, settingValue(original, field))) {
      changed[field.key] = now;
    }
  }
  return changed;
}

/** The sections and groups of the form, in schema order (P5.3 step 5). */
export function buildSections(): SettingsSection[] {
  const sections: SettingsSection[] = [];
  const byId = new Map<string, SettingsSection>();
  for (const field of SETTINGS_FIELDS) {
    const parts = field.path.split(".");
    const sectionId = parts[0];
    let section = byId.get(sectionId);
    if (section === undefined) {
      section = { id: sectionId, title: titleFor(sectionId), fields: [], groups: [] };
      byId.set(sectionId, section);
      sections.push(section);
    }
    if (parts.length <= 2) {
      section.fields.push(field);
      continue;
    }
    const groupId = parts.slice(0, 2).join(".");
    let group = section.groups.find((item) => item.id === groupId);
    if (group === undefined) {
      group = { id: groupId, title: titleFor(groupId), fields: [] };
      section.groups.push(group);
    }
    group.fields.push(field);
  }
  return sections;
}

/** The form's sections, built once: the schema cannot change while it runs. */
export const SETTINGS_SECTIONS: readonly SettingsSection[] = buildSections();

/** The section a changed key belongs to (`screensaver_clock_face` -> "screensaver"). */
export function sectionOf(field: SettingsField): string {
  return field.path.split(".")[0];
}

/** The sections whose leaves changed: the per-section dirty marks (step 6). */
export function dirtySections(changed: SettingsValues): string[] {
  const fields = new Map(SETTINGS_FIELDS.map((field) => [field.key, field]));
  const sections = new Set<string>();
  for (const key of Object.keys(changed)) {
    const field = fields.get(key);
    if (field !== undefined) {
      sections.add(sectionOf(field));
    }
  }
  return [...sections];
}

/** True when the pattern accepts the value; an unusable pattern accepts it. */
export function patternMatches(pattern: string | null, value: string): boolean {
  if (!pattern) {
    return true;
  }
  try {
    return new RegExp(pattern, "u").test(value);
  } catch {
    return true;
  }
}

/**
 * True for the schema's colour leaves (#RRGGBB / #AARRGGBB).
 *
 * Read from the pattern's behaviour rather than its spelling: a colour pattern
 * accepts both hex forms and refuses a word, which no other string leaf's
 * pattern does.
 */
export function isColorField(field: SettingsField): boolean {
  return (
    field.type === "string" &&
    patternMatches(field.pattern, "#FFFFFFFF") &&
    patternMatches(field.pattern, "#FFFFFF") &&
    !patternMatches(field.pattern, "blue")
  );
}

/** `#AARRGGBB` -> the `#rrggbb` a colour input shows (alpha is kept separately). */
export function swatchColor(value: unknown): string {
  if (typeof value !== "string") {
    return "#000000";
  }
  const hex = value.startsWith("#") ? value.slice(1) : value;
  if (hex.length === 8) {
    return `#${hex.slice(2)}`.toLowerCase();
  }
  return `#${hex.slice(0, 6)}`.toLowerCase();
}

/** A picked `#rrggbb` as a settings colour, keeping an existing alpha byte. */
export function withAlpha(previous: unknown, picked: string): string {
  const rgb = picked.replace("#", "").toUpperCase();
  if (typeof previous === "string" && /^#[0-9A-Fa-f]{8}$/.test(previous)) {
    return `#${previous.slice(1, 3).toUpperCase()}${rgb}`;
  }
  return `#${rgb}`;
}

/** The first required item property: the schedule's `start`, the row's order. */
export function itemOrderKey(items: SettingsArrayItems): string | null {
  const required = items.required.find((name) =>
    items.properties.some((property) => property.name === name),
  );
  return required ?? null;
}

/** Array items in the schema's order: by the required key, unset rows last. */
export function sortItems(items: SettingsArrayItems, value: unknown): unknown[] {
  const rows = Array.isArray(value) ? [...value] : [];
  const key = itemOrderKey(items);
  if (key === null) {
    return rows;
  }
  return rows.sort((left, right) => {
    const a = String((left as Record<string, unknown>)?.[key] ?? "");
    const b = String((right as Record<string, unknown>)?.[key] ?? "");
    if (a === b) {
      return 0;
    }
    if (a === "") {
      return 1;
    }
    if (b === "") {
      return -1;
    }
    return a < b ? -1 : 1;
  });
}

/** A new row for an array of objects: the schema's defaults, unset stays null. */
export function newItem(items: SettingsArrayItems): Record<string, unknown> {
  const row: Record<string, unknown> = {};
  for (const property of items.properties) {
    if (property.default !== NO_DEFAULT) {
      row[property.name] = property.default;
    } else if (property.nullable) {
      row[property.name] = null;
    } else if (property.type === "integer") {
      row[property.name] = property.minimum ?? 0;
    } else if (property.type === "boolean") {
      row[property.name] = false;
    } else {
      row[property.name] = "";
    }
  }
  return row;
}

/** True when the device's schema version predates the key (P5.3 step 4). */
export function isBehind(
  field: SettingsField,
  fieldSince: Record<string, number>,
  deviceSchemaVersion: number | null,
): boolean {
  if (deviceSchemaVersion === null) {
    return false;
  }
  return (fieldSince[field.key] ?? 0) > deviceSchemaVersion;
}

/** One item property's value as the reason it is unusable, or null. */
export function propertyProblem(property: SettingsItemProperty, value: unknown): string | null {
  if (value === null) {
    return property.nullable ? null : STRINGS.settings.problems.required;
  }
  if (property.type === "boolean") {
    return typeof value === "boolean" ? null : STRINGS.settings.problems.boolean;
  }
  if (property.type === "integer") {
    return integerProblem(value, property.minimum, property.maximum);
  }
  if (typeof value !== "string") {
    return STRINGS.settings.problems.text;
  }
  if (property.enumValues !== null && !property.enumValues.includes(value)) {
    return STRINGS.settings.problems.choice;
  }
  if (property.maxLength !== null && value.length > property.maxLength) {
    return STRINGS.settings.problems.long(property.maxLength);
  }
  if (!patternMatches(property.pattern, value)) {
    return STRINGS.settings.problems.format;
  }
  return null;
}

function integerProblem(value: unknown, min: number | null, max: number | null): string | null {
  if (typeof value !== "number" || !Number.isInteger(value)) {
    return STRINGS.settings.problems.integer;
  }
  if ((min !== null && value < min) || (max !== null && value > max)) {
    return STRINGS.settings.problems.range(min, max);
  }
  return null;
}

function arrayProblem(field: SettingsField, value: unknown): string | null {
  const items = field.items;
  if (!Array.isArray(value)) {
    return STRINGS.settings.problems.list;
  }
  if (field.maximum !== null && value.length > field.maximum) {
    return STRINGS.settings.problems.items(field.maximum);
  }
  if (items === null) {
    return null;
  }
  if (items.type !== "object") {
    return value.some(
      (item) => typeof item !== "string" || propertyProblem(scalarProperty(items), item) !== null,
    )
      ? STRINGS.settings.problems.listItems
      : null;
  }
  const names = items.properties.map((property) => property.name);
  for (const row of value) {
    if (typeof row !== "object" || row === null) {
      return STRINGS.settings.problems.listItems;
    }
    const record = row as Record<string, unknown>;
    for (const required of items.required) {
      if (record[required] === undefined || record[required] === null) {
        return STRINGS.settings.problems.requiredProperty(titleFor(required));
      }
    }
    for (const [name, item] of Object.entries(record)) {
      const property = items.properties.find((candidate) => candidate.name === name);
      if (property === undefined) {
        return STRINGS.settings.problems.unknownProperty(name);
      }
      const problem = propertyProblem(property, item);
      if (problem !== null) {
        return `${titleFor(name)}: ${problem}`;
      }
    }
    if (names.length === 0) {
      return STRINGS.settings.problems.listItems;
    }
  }
  return null;
}

/** A scalar array item as a property, so one rule judges items and leaves alike. */
function scalarProperty(items: SettingsArrayItems): SettingsItemProperty {
  return {
    name: "item",
    type: items.type === "object" ? "string" : items.type,
    enumValues: items.enumValues,
    minimum: null,
    maximum: null,
    maxLength: null,
    pattern: items.pattern,
    nullable: false,
    default: NO_DEFAULT,
    help: "",
  };
}

/**
 * The client-side mirror of a leaf's check: the first reason it is unusable.
 *
 * Immediacy only (P5.3 step 5): the server validates the same metadata and its
 * response is what the form reports, so a disagreement costs a round trip, not
 * a wrong save.
 */
export function clientProblem(field: SettingsField, value: unknown): string | null {
  if (field.type === "array") {
    return arrayProblem(field, value);
  }
  if (field.type === "boolean") {
    return typeof value === "boolean" ? null : STRINGS.settings.problems.boolean;
  }
  if (field.type === "integer") {
    return integerProblem(value, field.minimum, field.maximum);
  }
  if (typeof value !== "string") {
    return STRINGS.settings.problems.text;
  }
  if (field.enumValues !== null) {
    return field.enumValues.includes(value) ? null : STRINGS.settings.problems.choice;
  }
  if (field.minLength !== null && value.length < field.minLength) {
    return STRINGS.settings.problems.short(field.minLength);
  }
  if (field.maxLength !== null && value.length > field.maxLength) {
    return STRINGS.settings.problems.long(field.maxLength);
  }
  if (!patternMatches(field.pattern, value)) {
    return STRINGS.settings.problems.format;
  }
  return null;
}

/** Entity ids for the picker, filtered by the array's item pattern. */
export function entityChoices(
  states: HassStates | undefined,
  items: SettingsArrayItems | null,
): string[] {
  const ids = Object.keys(states ?? {}).sort();
  return items === null ? ids : ids.filter((id) => patternMatches(items.pattern, id));
}

/** The settings a save would send, with object arrays in their schema order. */
export function saveValues(original: SettingsValues, draft: SettingsValues): SettingsValues {
  const changed = changedValues(original, draft);
  for (const field of SETTINGS_FIELDS) {
    if (field.items !== null && field.items.type === "object" && field.key in changed) {
      changed[field.key] = sortItems(field.items, changed[field.key]);
    }
  }
  return changed;
}
