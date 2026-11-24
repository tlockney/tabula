import { afterEach, describe, expect, it, vi } from "vitest";
import { SETTINGS_FIELDS } from "./generated/settings";
import { SETTINGS_SECTIONS } from "./settings";
import {
  changedValues,
  clientProblem,
  entityChoices,
  flattenSettings,
  labelFor,
  isBehind,
  isColorField,
  itemOrderKey,
  newItem,
  patternMatches,
  saveValues,
  sortItems,
  swatchColor,
  titleFor,
  withAlpha,
} from "./settings";
import type { TabulaSettingsEditor } from "./settings-editor";
import {
  INFO,
  STATES,
  device,
  fakeHass,
  mount,
  mountEditor,
  settle,
  settingsDocument,
  settingsSnapshot,
  type CallHandler,
} from "./test-helpers";
import type { SettingsSaveResult, SettingsSnapshot } from "./types";

afterEach(() => {
  document.body.innerHTML = "";
});

/** A field by its flat key, as the generated table holds it. */
function field(key: string) {
  const found = SETTINGS_FIELDS.find((candidate) => candidate.key === key);
  if (found === undefined) {
    throw new Error(`no field ${key}`);
  }
  return found;
}

/** A handler that records the writes and answers both panel commands. */
function responder(
  snapshot: SettingsSnapshot,
  save?: SettingsSaveResult,
): { handler: CallHandler; writes: Record<string, unknown>[]; reads: () => number } {
  const writes: Record<string, unknown>[] = [];
  let reads = 0;
  const handler: CallHandler = async (msg) => {
    if (msg.type === "tabula/panel_settings_set") {
      writes.push(msg.values as Record<string, unknown>);
      return (
        save ?? {
          revision: 2,
          applied: Object.keys(msg.values as Record<string, unknown>),
          rejected: [],
        }
      );
    }
    reads += 1;
    return snapshot;
  };
  return { handler, writes, reads: () => reads };
}

function root(element: TabulaSettingsEditor): ShadowRoot {
  return element.renderRoot as ShadowRoot;
}

function query(element: TabulaSettingsEditor, selector: string): HTMLElement | null {
  return root(element).querySelector(selector);
}

function button(element: TabulaSettingsEditor, selector: string): HTMLButtonElement {
  const found = query(element, selector);
  if (found === null) {
    throw new Error(`no button ${selector}`);
  }
  return found as HTMLButtonElement;
}

/** Type into a control the way a user's keystroke does. */
async function type(
  element: TabulaSettingsEditor,
  selector: string,
  value: string,
  event = "input",
): Promise<void> {
  const input = query(element, selector) as HTMLInputElement | HTMLSelectElement;
  input.value = value;
  input.dispatchEvent(new Event(event, { bubbles: true }));
  await settle(element);
}

/** The Save button's click, with the round trip settled. */
async function save(element: TabulaSettingsEditor): Promise<void> {
  button(element, ".actions button.primary").click();
  await settle(element);
}

// --- the generated vocabulary ------------------------------------------------

describe("settings helpers", () => {
  it("sections and groups follow the schema order", () => {
    const sections = SETTINGS_SECTIONS.map((section) => section.id);
    expect(sections.slice(0, 3)).toEqual(["identity", "connection", "screen"]);
    const screensaver = SETTINGS_SECTIONS.find((section) => section.id === "screensaver");
    expect(screensaver?.title).toBe("Screensaver");
    const clock = screensaver?.groups.find((group) => group.id === "screensaver.clock");
    expect(clock?.title).toBe("Clock");
    expect(clock?.fields.map((item) => item.key)).toContain("screensaver_clock_text_color");
  });

  it("labels come from the string table, with a humanized fallback", () => {
    expect(titleFor("idle_timeout")).toBe("Idle timeout");
    expect(labelFor(field("screen_brightness"))).toBe("Brightness");
    expect(labelFor(field("screensaver_clock_face"))).toBe("Face");
    expect(titleFor("some_new_key")).toBe("Some new key");
  });

  it("a document flattens to the form's values, defaults included", () => {
    const values = flattenSettings({ screen: { brightness: 12 } });
    expect(values.screen_brightness).toBe(12);
    expect(values.screensaver_clock_text_color).toBe("#FFFFFFFF");
    // A leaf with no default stays absent rather than invented.
    expect("identity_name" in values).toBe(false);
  });

  it("only changed leaves are submitted", () => {
    const original = flattenSettings(settingsDocument());
    const draft = { ...original, screen_brightness: 33 };
    expect(Object.keys(changedValues(original, draft))).toEqual(["screen_brightness"]);
  });

  it("colours are recognised by their pattern, not their names", () => {
    expect(isColorField(field("screensaver_clock_text_color"))).toBe(true);
    expect(isColorField(field("screensaver_clock_font_family"))).toBe(false);
    expect(swatchColor("#FF112233")).toBe("#112233");
    expect(withAlpha("#FF112233", "#445566")).toBe("#FF445566");
    expect(withAlpha("#112233", "#445566")).toBe("#445566");
  });

  it("item pattern and order come from the schema's items", () => {
    expect(
      patternMatches(field("at_a_glance_entities").items?.pattern ?? null, "sensor.door"),
    ).toBe(true);
    expect(
      patternMatches(field("at_a_glance_entities").items?.pattern ?? null, "not an entity"),
    ).toBe(false);
    const items = field("schedule_slots").items;
    expect(items).not.toBeNull();
    expect(itemOrderKey(items!)).toBe("start");
    const sorted = sortItems(items!, [{ start: "22:30" }, { start: "06:30" }, { start: "" }]) as {
      start: string;
    }[];
    expect(sorted.map((row) => row.start)).toEqual(["06:30", "22:30", ""]);
    expect(newItem(items!)).toMatchObject({ start: "", mode: "clock", brightness: null });
  });

  it("the client mirror repeats the schema's bounds", () => {
    expect(clientProblem(field("screen_brightness"), 200)).toContain("0 to 100");
    expect(clientProblem(field("screen_brightness"), 40)).toBeNull();
    expect(clientProblem(field("screensaver_mode"), "night")).toContain("listed values");
    expect(clientProblem(field("at_a_glance_entities"), ["not an entity"])).not.toBeNull();
    expect(clientProblem(field("schedule_slots"), [{ mode: "clock" }])).toContain(
      "Start is required",
    );
    expect(clientProblem(field("schedule_slots"), [{ start: "06:30", mode: "clock" }])).toBeNull();
  });

  it("a key newer than the device's schema version is flagged", () => {
    const since = { voice_wake_word_enabled: 3 };
    expect(isBehind(field("voice_wake_word_enabled"), since, 1)).toBe(true);
    expect(isBehind(field("voice_wake_word_enabled"), since, 3)).toBe(false);
    expect(isBehind(field("voice_wake_word_enabled"), since, null)).toBe(false);
  });

  it("the entity picker offers only ids the schema accepts", () => {
    const ids = entityChoices(STATES, field("at_a_glance_entities").items);
    expect(ids).toEqual(["light.kitchen", "sensor.office_temperature"]);
  });

  it("a save sorts object arrays into schema order", () => {
    const original = flattenSettings(settingsDocument());
    const draft = {
      ...original,
      schedule_slots: [{ start: "22:30" }, { start: "06:30" }],
    };
    const values = saveValues(original, draft) as {
      schedule_slots: { start: string }[];
    };
    expect(values.schedule_slots.map((row) => row.start)).toEqual(["06:30", "22:30"]);
  });
});

// --- controls ----------------------------------------------------------------

describe("settings editor controls", () => {
  it("renders a switch for a boolean and saves the flat key", async () => {
    const { handler, writes } = responder(settingsSnapshot());
    const element = await mountEditor(handler);

    const toggle = query(
      element,
      '[data-key="screensaver_enabled"] input[role="switch"]',
    ) as HTMLInputElement | null;
    expect(toggle).not.toBeNull();
    expect(toggle?.checked).toBe(true);
    toggle?.click();
    await settle(element);
    expect(element.isDirty).toBe(true);

    await save(element);
    expect(writes).toEqual([{ screensaver_enabled: false }]);
    expect(element.isDirty).toBe(false);
  });

  it("renders a number input and a slider at the schema's bounds", async () => {
    const { handler, writes } = responder(settingsSnapshot());
    const element = await mountEditor(handler);

    const number = query(element, '[data-key="screen_brightness"] input[type="number"]');
    const slider = query(element, '[data-key="screen_brightness"] input[type="range"]');
    expect(number?.getAttribute("min")).toBe("0");
    expect(number?.getAttribute("max")).toBe("100");
    expect(slider?.getAttribute("max")).toBe("100");

    await type(element, '[data-key="screen_brightness"] input[type="number"]', "33");
    await save(element);
    expect(writes).toEqual([{ screen_brightness: 33 }]);
  });

  it("renders a select with the schema's enum", async () => {
    const { handler } = responder(settingsSnapshot());
    const element = await mountEditor(handler);

    const select = query(element, '[data-key="screensaver_mode"] select') as HTMLSelectElement;
    expect([...select.options].map((option) => option.value)).toEqual([
      "clock",
      "weather",
      "photos",
      "website",
      "dashboard",
      "calendar",
      "dim",
      "black",
    ]);
  });

  it("renders a text input and a colour swatch for a colour leaf", async () => {
    const { handler } = responder(settingsSnapshot());
    const element = await mountEditor(handler);

    expect(
      query(element, '[data-key="screensaver_clock_text_color"] input[type="text"]'),
    ).not.toBeNull();
    const swatch = query(
      element,
      '[data-key="screensaver_clock_text_color"] input[type="color"]',
    ) as HTMLInputElement;
    // #AARRGGBB in the schema, #rrggbb in the picker: the alpha byte is kept.
    expect(swatch.value).toBe("#ffffff");
  });

  it("carries the string bounds the schema sets", async () => {
    const { handler } = responder(settingsSnapshot());
    const element = await mountEditor(handler);
    const input = query(element, '[data-key="identity_name"] input[type="text"]');
    expect(input?.getAttribute("maxlength")).toBe("64");
  });

  it("renders the nested groups as collapsible sections", async () => {
    const { handler } = responder(settingsSnapshot());
    const element = await mountEditor(handler);

    const section = query(element, '[data-section="screensaver"]');
    const group = section?.querySelector('details[data-group="screensaver.clock"]');
    expect(group).not.toBeNull();
    expect(group?.querySelector("summary")?.textContent?.trim()).toBe("Clock");
    expect(group?.querySelector('[data-key="screensaver_clock_face"]')).not.toBeNull();
  });

  it("edits the At a Glance row: add, pick and remove an entity", async () => {
    const { handler, writes } = responder(settingsSnapshot());
    const element = await mountEditor(handler);

    button(element, '[data-key="at_a_glance_entities"] button.add').click();
    await settle(element);
    const rowInput = query(element, '[data-key="at_a_glance_entities"] .row input');
    expect(rowInput).not.toBeNull();
    expect(rowInput?.getAttribute("list")).toBe("tabula-choices-at_a_glance_entities");
    const options = [
      ...root(element).querySelectorAll(
        'datalist[id="tabula-choices-at_a_glance_entities"] option',
      ),
    ].map((option) => option.getAttribute("value"));
    expect(options).toEqual(["light.kitchen", "sensor.office_temperature"]);

    await type(element, '[data-key="at_a_glance_entities"] .row input', "sensor.door");
    await save(element);
    expect(writes).toEqual([{ at_a_glance_entities: ["sensor.door"] }]);
  });

  it("edits schedule slots, including the explicit inherit state", async () => {
    const { handler, writes } = responder(settingsSnapshot());
    const element = await mountEditor(handler);

    for (let index = 0; index < 2; index += 1) {
      button(element, '[data-key="schedule_slots"] button.add').click();
      await settle(element);
    }

    const rows = root(element).querySelectorAll('[data-key="schedule_slots"] .row.slot');
    expect(rows.length).toBe(2);
    // A new slot starts on the mode default and inherits the nullable overrides.
    const mode = rows[0].querySelector("select") as HTMLSelectElement;
    expect(mode.value).toBe("clock");

    const setStart = async (row: number, value: string): Promise<void> => {
      const inputs = root(element).querySelectorAll<HTMLInputElement>(
        '[data-key="schedule_slots"] .row.slot input[type="text"]',
      );
      inputs[row].value = value;
      inputs[row].dispatchEvent(new Event("input", { bubbles: true }));
      await settle(element);
    };
    await setStart(0, "22:30");
    await setStart(1, "06:30");

    // brightness is nullable: the "inherit" checkbox is on until it is cleared.
    const inherit = root(element).querySelectorAll<HTMLInputElement>(
      '[data-key="schedule_slots"] .row.slot .inherit-row input[type="checkbox"]',
    )[0];
    expect(inherit.checked).toBe(true);
    inherit.click();
    await settle(element);
    const brightness = root(element).querySelector<HTMLInputElement>(
      '[data-key="schedule_slots"] .row.slot .inherit-row input[type="number"]',
    );
    expect(brightness).not.toBeNull();
    if (brightness !== null) {
      brightness.value = "5";
      brightness.dispatchEvent(new Event("input", { bubbles: true }));
    }
    await settle(element);

    await save(element);
    expect(writes).toHaveLength(1);
    // The schema wants start-time order, whatever order the rows were edited in.
    expect(writes[0].schedule_slots).toEqual([
      {
        start: "06:30",
        mode: "clock",
        brightness: null,
        power_off_delay: null,
        presence_dismiss: null,
        widgets: null,
        at_a_glance: null,
      },
      {
        start: "22:30",
        mode: "clock",
        brightness: 5,
        power_off_delay: null,
        presence_dismiss: null,
        widgets: null,
        at_a_glance: null,
      },
    ]);
  });

  it("reports the form's dirty state for the leave guard", async () => {
    const { handler } = responder(settingsSnapshot());
    const element = await mountEditor(handler);
    const seen: boolean[] = [];
    element.addEventListener("dirty-changed", (event) => {
      seen.push((event as CustomEvent<boolean>).detail);
    });

    await type(element, '[data-key="screen_brightness"] input[type="number"]', "44");
    expect(seen.at(-1)).toBe(true);
    // The mark is per section, not just one form-wide flag.
    expect(query(element, '[data-section="screen"] .section-dirty')?.textContent).toContain(
      "Unsaved changes",
    );
    expect(query(element, '[data-section="dashboard"] .section-dirty')).toBeNull();

    button(element, ".actions button:not(.primary)").click();
    await settle(element);
    expect(seen.at(-1)).toBe(false);
    expect(element.isDirty).toBe(false);
  });

  it("shows a rejected key's reason from the server, keeping the rest applied", async () => {
    const snapshot = settingsSnapshot();
    const { handler } = responder(snapshot, {
      revision: 2,
      applied: ["screensaver_idle_timeout"],
      rejected: [{ key: "screen_brightness", reason: "200 is greater than the maximum of 100" }],
    });
    const element = await mountEditor(handler);

    await type(element, '[data-key="screen_brightness"] input[type="number"]', "200");
    await save(element);

    const fieldBox = query(element, '[data-key="screen_brightness"]');
    expect(fieldBox?.classList.contains("invalid")).toBe(true);
    expect(fieldBox?.querySelector(".rejected")?.textContent).toContain(
      "greater than the maximum of 100",
    );
    const summary = query(element, ".save-summary");
    expect(summary?.textContent).toContain("1 setting applied");
    expect(summary?.textContent).toContain("1 setting was rejected");
    // The rejected key stays dirty, so a corrected save sends it again.
    expect(element.isDirty).toBe(true);
  });

  it("says nothing was saved when every key is rejected", async () => {
    const { handler } = responder(settingsSnapshot(), {
      revision: null,
      applied: [],
      rejected: [{ key: "screen_brightness", reason: "not a number" }],
    });
    const element = await mountEditor(handler);
    await type(element, '[data-key="screen_brightness"] input[type="number"]', "1");
    await save(element);

    expect(query(element, ".save-summary")?.textContent).toContain("Nothing was saved");
  });

  it("claims 'applied on the tablet' only once the device's revision caught up", async () => {
    const snapshot = settingsSnapshot({ device_revision: 1, pending: true });
    const appliedSnapshot = settingsSnapshot({
      ha_revision: 2,
      device_revision: 2,
      pending: false,
    });
    const writes: Record<string, unknown>[] = [];
    let reads = 0;
    const handler: CallHandler = async (msg) => {
      if (msg.type === "tabula/panel_settings_set") {
        writes.push(msg.values as Record<string, unknown>);
        return { revision: 2, applied: Object.keys(msg.values as object), rejected: [] };
      }
      reads += 1;
      return reads === 1 ? snapshot : appliedSnapshot;
    };
    const element = await mountEditor(handler, snapshot);
    expect(query(element, ".settings-status .meta")?.textContent).toContain("tablet at 1");

    await type(element, '[data-key="screen_brightness"] input[type="number"]', "33");
    await save(element);
    expect(query(element, ".save-summary")?.textContent).toContain("Applied on the tablet.");
  });

  it("tells an offline tablet's user the write arrives on reconnect", async () => {
    const snapshot = settingsSnapshot({ connected: false, device_revision: null, pending: true });
    const { handler } = responder(snapshot);
    const element = await mountEditor(handler);

    await type(element, '[data-key="screen_brightness"] input[type="number"]', "33");
    await save(element);
    expect(query(element, ".save-summary")?.textContent).toContain(
      "receives this when it reconnects",
    );
  });

  it("notes a key the tablet's schema version cannot take yet", async () => {
    const snapshot = settingsSnapshot({ device_schema_version: 1 });
    const { handler } = responder(snapshot);
    const element = await mountEditor(handler);

    const wake = query(element, '[data-key="voice_wake_word_enabled"]');
    expect(wake?.querySelector(".behind")?.textContent).toContain("after it updates");
    const brightness = query(element, '[data-key="screen_brightness"]');
    expect(brightness?.querySelector(".behind")).toBeNull();
  });

  it("a load failure shows its reason with a retry", async () => {
    let calls = 0;
    const handler: CallHandler = async (msg) => {
      calls += 1;
      if (calls === 1) {
        throw new Error("boom");
      }
      expect(msg.type).toBe("tabula/panel_settings");
      return settingsSnapshot();
    };
    const element = await mountEditor(handler);
    expect(query(element, ".error")?.textContent).toContain("boom");

    button(element, ".error button").click();
    await settle(element);
    expect(query(element, ".settings-section")).not.toBeNull();
  });
});

describe("the device page's Settings tab", () => {
  it("mounts the editor for the selected device", async () => {
    vi.stubGlobal("confirm", () => true);
    const { handler } = responder(settingsSnapshot());
    const tablet = device({ device_id: settingsSnapshot().device_id });
    const shellHandler: CallHandler = async (msg) => {
      if (msg.type === "tabula/panel_info") {
        return INFO;
      }
      if (msg.type === "tabula/panel_devices") {
        return [tablet];
      }
      return handler(msg);
    };
    const element = await mount(fakeHass(shellHandler), `#/device/${tablet.ha_device_id}`);
    const tab = (name: string): HTMLButtonElement => {
      const found = [
        ...(element.renderRoot as ShadowRoot).querySelectorAll<HTMLButtonElement>("button.tab"),
      ].find((candidate) => candidate.textContent?.trim() === name);
      if (found === undefined) {
        throw new Error(`no ${name} tab`);
      }
      return found;
    };
    tab("Settings").click();
    await settle(element);

    const editor = (element.renderRoot as ShadowRoot).querySelector("tabula-settings-editor");
    expect(editor).not.toBeNull();
    vi.unstubAllGlobals();
  });
});

describe("the leave guard", () => {
  it("keeps the user on a dirty Settings tab unless they confirm leaving", async () => {
    const confirm = vi.fn(() => false);
    vi.stubGlobal("confirm", confirm);
    const { handler } = responder(settingsSnapshot());
    const tablet = device({ device_id: settingsSnapshot().device_id });
    const shellHandler: CallHandler = async (msg) => {
      if (msg.type === "tabula/panel_info") {
        return INFO;
      }
      if (msg.type === "tabula/panel_devices") {
        return [tablet];
      }
      return handler(msg);
    };
    const element = await mount(fakeHass(shellHandler), `#/device/${tablet.ha_device_id}`);
    const tab = (name: string): HTMLButtonElement =>
      [
        ...(element.renderRoot as ShadowRoot).querySelectorAll<HTMLButtonElement>("button.tab"),
      ].find((candidate) => candidate.textContent?.trim() === name)!;
    tab("Settings").click();
    await settle(element);

    const editor = (element.renderRoot as ShadowRoot).querySelector(
      "tabula-settings-editor",
    ) as TabulaSettingsEditor;
    const number = query(
      editor,
      '[data-key="screen_brightness"] input[type="number"]',
    ) as HTMLInputElement | null;
    if (number === null) {
      throw new Error("no brightness control");
    }
    number.value = "44";
    number.dispatchEvent(new Event("input", { bubbles: true }));
    await settle(editor);

    tab("Overview").click();
    await settle(element);
    expect(confirm).toHaveBeenCalled();
    // Refused: the Settings tab (and the draft) stay on screen.
    expect(
      (element.renderRoot as ShadowRoot).querySelector("tabula-settings-editor"),
    ).not.toBeNull();

    confirm.mockReturnValue(true);
    tab("Overview").click();
    await settle(element);
    expect((element.renderRoot as ShadowRoot).querySelector("tabula-settings-editor")).toBeNull();
    vi.unstubAllGlobals();
  });
});
