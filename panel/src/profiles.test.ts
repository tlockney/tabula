import { beforeEach, describe, expect, it } from "vitest";
import "./profiles-view";
import type { TabulaProfiles } from "./profiles-view";
import {
  diffSummary,
  displayValue,
  driftKinds,
  groupState,
  parseProfileValue,
  profileNameProblem,
  rowsByDevice,
  selectedValues,
  shareableFields,
  shareableSections,
  toggleGroup,
  toggleKey,
} from "./profiles";
import type { SettingsField } from "./generated/settings";
import { fieldDefault } from "./settings";
import { fakeHass, settle } from "./test-helpers";
import type { DriftMember, PanelProfiles, ProfileDiffRow } from "./types";

const BRIGHTNESS = shareableFields().find((field) => field.key === "screen_brightness");
const IDLE = shareableFields().find((field) => field.key === "screensaver_idle_timeout");
if (BRIGHTNESS === undefined || IDLE === undefined) {
  throw new Error("the schema no longer has the keys these tests use");
}

const PROFILES: PanelProfiles = {
  schema_version: 3,
  profiles: [
    {
      profile_id: "empty",
      name: "Empty",
      builtin: true,
      values: {},
      members: [],
      member_count: 0,
    },
    {
      profile_id: "01JPROFILE",
      name: "Kitchen",
      builtin: false,
      values: { screen_brightness: 40 },
      members: ["dev-1", "dev-2"],
      member_count: 2,
    },
  ],
  assignments: { "dev-1": "01JPROFILE", "dev-2": "01JPROFILE" },
  devices: [
    {
      device_id: "dev-1",
      ha_device_id: "ha-1",
      name: "Kitchen Tablet",
      connected: true,
      last_seen: null,
      settings_schema_version: 3,
      profile_id: "01JPROFILE",
      overrides: {},
    },
    {
      device_id: "dev-2",
      ha_device_id: "ha-2",
      name: "Hallway Tablet",
      connected: false,
      last_seen: "2026-11-24T04:00:00Z",
      settings_schema_version: 3,
      profile_id: "01JPROFILE",
      overrides: { screen_brightness: 20 },
    },
  ],
};

const DRIFT: DriftMember[] = [
  {
    device_id: "dev-2",
    ha_device_id: "ha-2",
    name: "Hallway Tablet",
    profile_id: "01JPROFILE",
    profile_name: "Kitchen",
    connected: false,
    last_seen: "2026-11-24T04:00:00Z",
    ha_revision: 5,
    device_revision: 4,
    revisions_behind: 1,
    drifted: true,
    overrides: [{ key: "screen_brightness", value: 20 }],
    behind_keys: [],
  },
];

function row(overrides: Partial<ProfileDiffRow> = {}): ProfileDiffRow {
  return {
    device_id: "dev-1",
    key: "screen_brightness",
    profile_value: 40,
    effective_value: 40,
    device_value: 30,
    device_unset: false,
    overridden: false,
    changes: true,
    ...overrides,
  };
}

describe("the shareable picker", () => {
  it("offers only shared-role keys", () => {
    for (const field of shareableFields()) {
      expect(field.role).toBe("shared");
    }
    const ids = shareableSections().map((section) => section.id);
    expect(ids).not.toContain("identity");
    expect(ids).not.toContain("connection");
    expect(ids).toContain("screen");
  });

  it("starts with no keys at all, which is F-MGT-04's default", () => {
    const included = new Set<string>();
    expect(groupState(shareableFields(), included)).toBe("none");
  });

  it("toggles a group on and off without touching other keys", () => {
    const fields: SettingsField[] = [BRIGHTNESS, IDLE];
    const on = toggleGroup(fields, new Set(["screensaver_enabled"]), true);
    expect(on.has("screen_brightness")).toBe(true);
    expect(on.has("screensaver_idle_timeout")).toBe(true);
    expect(on.has("screensaver_enabled")).toBe(true);
    expect(groupState(fields, on)).toBe("all");

    const partial = toggleKey(on, "screen_brightness", false);
    expect(groupState(fields, partial)).toBe("some");
    expect(toggleGroup(fields, partial, false).size).toBe(1);
  });
});

describe("profile names", () => {
  const existing = [
    { profile_id: "empty", name: "Empty" },
    { profile_id: "a", name: "Kitchen" },
  ];

  it("requires a name, capped and unique ignoring case", () => {
    expect(profileNameProblem("", existing)).toBeTruthy();
    expect(profileNameProblem("x".repeat(65), existing)).toBeTruthy();
    expect(profileNameProblem(" kitchen ", existing)).toBeTruthy();
    expect(profileNameProblem("empty", existing)).toBeTruthy();
  });

  it("lets a profile keep its own name", () => {
    expect(profileNameProblem("Kitchen", existing, "a")).toBeNull();
    expect(profileNameProblem("Hallway", existing)).toBeNull();
  });
});

describe("typed values", () => {
  it("parses each leaf by its own type", () => {
    expect(parseProfileValue(BRIGHTNESS, "55").value).toBe(55);
    expect(parseProfileValue(BRIGHTNESS, "abc").problem).toBeTruthy();
    const array = shareableFields().find((field) => field.type === "array");
    if (array !== undefined) {
      expect(parseProfileValue(array, "not json").problem).toBeTruthy();
      expect(parseProfileValue(array, "[]").value).toEqual([]);
    }
    const flag = shareableFields().find((field) => field.type === "boolean");
    if (flag !== undefined) {
      expect(parseProfileValue(flag, "true").value).toBe(true);
    }
  });

  it("sends only the included keys, in schema order", () => {
    const values = selectedValues(
      new Set(["screensaver_idle_timeout", "screen_brightness"]),
      new Map<string, unknown>([
        ["screensaver_idle_timeout", 300],
        ["screen_brightness", 40],
        ["screensaver_enabled", true],
      ]),
    );
    expect(Object.keys(values)).toEqual(["screen_brightness", "screensaver_idle_timeout"]);
  });

  it("shows unset and empty values plainly", () => {
    expect(displayValue(undefined, true)).toBe("Not set");
    expect(displayValue([])).toBe("Empty list");
    expect(displayValue(55)).toBe("55");
  });
});

describe("diff and drift rows", () => {
  it("groups rows by device and counts what changes", () => {
    const rows = [row(), row({ key: "screensaver_idle_timeout", changes: false })];
    expect([...rowsByDevice(rows).keys()]).toEqual(["dev-1"]);
    expect(diffSummary(rows)).toEqual({ changing: 1, overridden: 0 });
  });

  it("names each drift reason", () => {
    expect(driftKinds(DRIFT[0])).toHaveLength(2);
    expect(
      driftKinds({ ...DRIFT[0], overrides: [], behind_keys: ["screensaver_idle_timeout"] }),
    ).toEqual(["1 revision behind", "1 key too new for the app"]);
    expect(driftKinds({ ...DRIFT[0], drifted: false, overrides: [], behind_keys: [] })).toEqual([]);
  });
});

describe("the Profiles view", () => {
  const calls: { type: string; [key: string]: unknown }[] = [];

  beforeEach(() => {
    calls.length = 0;
    document.body.innerHTML = "";
  });

  function hass(options: { save?: unknown; diff?: unknown } = {}) {
    return fakeHass(async (msg) => {
      calls.push(msg);
      if (msg.type === "tabula/panel_profiles") {
        return PROFILES;
      }
      if (msg.type === "tabula/panel_drift") {
        return { members: DRIFT };
      }
      if (msg.type === "tabula/panel_profile_save") {
        return options.save ?? { profile: PROFILES.profiles[1], applied: [], rejected: [] };
      }
      if (msg.type === "tabula/panel_profile_diff") {
        return options.diff ?? { rows: [row()], changing: 1 };
      }
      return {};
    });
  }

  async function mount(fake = hass()): Promise<TabulaProfiles> {
    const element = document.createElement("tabula-profiles") as TabulaProfiles;
    element.hass = fake;
    document.body.append(element);
    await settle(element);
    return element;
  }

  it("lists profiles with their members and reports drift", async () => {
    const element = await mount();
    const text = element.shadowRoot?.textContent ?? "";
    expect(text).toContain("Kitchen");
    expect(text).toContain("Kitchen Tablet, Hallway Tablet");
    expect(text).toContain("1 revision behind");
    expect(text).toContain("Hallway Tablet");
  });

  it("opens a new profile with no keys covered", async () => {
    const element = await mount();
    const button = [...(element.shadowRoot?.querySelectorAll("button") ?? [])].find((item) =>
      item.textContent?.includes("New profile"),
    );
    button?.click();
    await settle(element);

    const editor = element.shadowRoot?.querySelector("[data-profile-editor]");
    expect(editor).not.toBeNull();
    expect(
      element.shadowRoot?.querySelectorAll(".profile-key input[type=checkbox]:checked"),
    ).toHaveLength(0);
  });

  it("sends the picked keys and values to the save command", async () => {
    const element = await mount();
    const button = [...(element.shadowRoot?.querySelectorAll("button") ?? [])].find((item) =>
      item.textContent?.includes("New profile"),
    );
    button?.click();
    await settle(element);

    const name = element.shadowRoot?.querySelector(".profile-name input") as HTMLInputElement;
    name.value = "Hallway";
    name.dispatchEvent(new Event("input"));
    const key = element.shadowRoot?.querySelector(
      '[data-key="screen_brightness"] input[type=checkbox]',
    ) as HTMLInputElement;
    key.checked = true;
    key.dispatchEvent(new Event("change"));
    await settle(element);

    const save = [...(element.shadowRoot?.querySelectorAll("button") ?? [])].find((item) =>
      item.textContent?.includes("Save profile"),
    );
    save?.click();
    await settle(element);

    const sent = calls.find((msg) => msg.type === "tabula/panel_profile_save");
    expect(sent?.name).toBe("Hallway");
    expect(sent?.values).toEqual({ screen_brightness: fieldDefault(BRIGHTNESS) });
  });

  it("previews an unsaved document against the members", async () => {
    const element = await mount();
    const edit = [...(element.shadowRoot?.querySelectorAll("button") ?? [])].find((item) =>
      item.textContent?.includes("Edit"),
    );
    edit?.click();
    await settle(element);
    const preview = [...(element.shadowRoot?.querySelectorAll("button") ?? [])].find((item) =>
      item.textContent?.includes("Preview changes"),
    );
    preview?.click();
    await settle(element);

    const sent = calls.find((msg) => msg.type === "tabula/panel_profile_diff");
    expect(sent?.profile_id).toBe("01JPROFILE");
    expect(sent?.values).toEqual({ screen_brightness: 40 });
    expect(element.shadowRoot?.querySelector("[data-preview]")?.textContent).toContain("Changes");
  });
});
