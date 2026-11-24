import { afterEach, describe, expect, it } from "vitest";
import {
  DEVICE_ROUTE_PREFIX,
  connectionLabel,
  deviceIdFromRoute,
  deviceRoute,
  filterDevices,
  formatWhen,
  settingsBadge,
  sortDevices,
} from "./devices";
import type { TabulaPanel } from "./shell";
import {
  FakeConnection,
  INFO,
  device,
  fakeHass,
  mount,
  navigate,
  settle,
  type CallHandler,
} from "./test-helpers";
import type { PanelDevice } from "./types";

const ONLINE = device({
  device_id: "11111111-1111-1111-1111-111111111111",
  ha_device_id: "aaaa0000000000000000000000000001",
  name: "Kitchen",
  area_id: "office",
  area_name: "Office",
  connected: true,
  app_version: "0.1.0",
});

const OFFLINE = device({
  device_id: "22222222-2222-2222-2222-222222222222",
  ha_device_id: "bbbb0000000000000000000000000002",
  name: "Hall",
  area_name: "Hallway",
  connected: false,
  last_seen: "2026-11-23T18:04:00Z",
  app_version: "0.0.9",
  settings: { ha_revision: 2, device_revision: null, pending: false },
});

/** A hass whose device list is fixed and whose panel_info is the standard one. */
function responder(devices: PanelDevice[]): CallHandler {
  return async (msg) => (msg.type === "tabula/panel_info" ? INFO : devices);
}

function rows(element: TabulaPanel): string[] {
  return [...(element.shadowRoot?.querySelectorAll("tbody tr") ?? [])].map(
    (row) => row.getAttribute("data-device") ?? "",
  );
}

function text(element: TabulaPanel): string {
  return element.shadowRoot?.textContent ?? "";
}

afterEach(() => {
  document.body.replaceChildren();
  window.location.hash = "";
});

describe("device list", () => {
  it("lists every paired tablet with its badges, area and app version", async () => {
    const element = await mount(fakeHass(responder([ONLINE, OFFLINE])));
    const body = text(element);

    expect(body).toContain("Kitchen");
    expect(body).toContain("Hall");
    expect(body).toContain("Office");
    expect(body).toContain("Hallway");
    expect(body).toContain("Online");
    expect(body).toContain("Offline");
    expect(body).toContain("0.0.9");
    // The offline badge carries when the device was last connected.
    expect(body).toContain("last seen Nov 23, 2026");
    expect(body).toContain("UTC");
    // Settings badges: both agree with HA.
    expect(body).toContain("In sync");
  });

  it("sorts by name with offline devices last", async () => {
    const element = await mount(fakeHass(responder([OFFLINE, ONLINE])));
    expect(rows(element)).toEqual([ONLINE.ha_device_id, OFFLINE.ha_device_id]);
  });

  it("filters by name or area", async () => {
    const element = await mount(fakeHass(responder([ONLINE, OFFLINE])));
    const input = element.shadowRoot?.querySelector(
      "input[type='search']",
    ) as HTMLInputElement | null;
    expect(input).not.toBeNull();

    if (input) {
      input.value = "hall";
      input.dispatchEvent(new Event("input"));
    }
    await settle(element);

    expect(rows(element)).toEqual([OFFLINE.ha_device_id]);
    expect(text(element)).not.toContain("Kitchen");

    if (input) {
      input.value = "nowhere";
      input.dispatchEvent(new Event("input"));
    }
    await settle(element);
    expect(text(element)).toContain("No device matches that filter.");
  });

  it("points at the pairing flow when nothing is paired", async () => {
    const element = await mount(fakeHass(responder([])));
    const body = text(element);
    expect(body).toContain("No tablets are paired yet");
    const link = element.shadowRoot?.querySelector(
      "a[href='/config/integrations/integration/tabula']",
    );
    expect(link?.textContent ?? "").toContain("All devices");
  });

  it("shows the time the shown snapshot was taken", async () => {
    const element = await mount(fakeHass(responder([ONLINE])));
    expect(text(element)).toContain("Snapshot taken");
  });

  it("shows a settings badge for a pending push and a device behind", async () => {
    const pending = device({
      ha_device_id: "cccc0000000000000000000000000003",
      name: "Pending",
      settings: { ha_revision: 4, device_revision: 3, pending: true },
    });
    const behind = device({
      ha_device_id: "dddd0000000000000000000000000004",
      name: "Behind",
      settings_schema_version: 1,
      settings: { ha_revision: 4, device_revision: 4, pending: false },
    });
    const element = await mount(fakeHass(responder([pending, behind])));
    const body = text(element);
    expect(body).toContain("Update pending");
    expect(body).toContain("Device behind");
  });
});

describe("failures", () => {
  it("shows the error text and retries the device list", async () => {
    let calls = 0;
    const handler: CallHandler = async (msg) => {
      if (msg.type === "tabula/panel_info") {
        return INFO;
      }
      calls += 1;
      if (calls === 1) {
        throw new Error("connection lost");
      }
      return [ONLINE];
    };
    const element = await mount(fakeHass(handler));

    const alert = element.shadowRoot?.querySelector("[role='alert']");
    expect(alert?.textContent ?? "").toContain("The device list could not be loaded");
    expect(alert?.textContent ?? "").toContain("connection lost");

    const button = element.shadowRoot?.querySelector("button");
    expect(button?.textContent ?? "").toContain("Try again");
    button?.click();
    await settle(element);

    expect(calls).toBe(2);
    expect(rows(element)).toEqual([ONLINE.ha_device_id]);
    expect(element.shadowRoot?.querySelector("[role='alert']")).toBeNull();
  });

  it("shows a device-not-found state for an unknown hash with a way back", async () => {
    const element = await mount(fakeHass(responder([ONLINE])), "#/device/not-a-device");
    const body = text(element);
    expect(body).toContain("Device not found");
    const back = element.shadowRoot?.querySelector(".empty a");
    expect(back?.getAttribute("href")).toBe("#/devices");
  });
});

describe("device page", () => {
  it("opens the device a direct link names, offline or not", async () => {
    const element = await mount(
      fakeHass(responder([ONLINE, OFFLINE])),
      deviceRoute(OFFLINE.ha_device_id),
    );
    const body = text(element);
    expect(body).toContain("Hall");
    expect(body).toContain(OFFLINE.device_id);
    expect(body).toContain("last seen Nov 23, 2026");
    // The shell's tabs are all there; only the overview has content yet.
    for (const tab of ["Overview", "Settings", "Logs", "Activity"]) {
      expect(body).toContain(tab);
    }
  });

  it("moves from the list to the device page and back", async () => {
    const element = await mount(fakeHass(responder([ONLINE, OFFLINE])));
    const link = element.shadowRoot?.querySelector(`a[href='${deviceRoute(ONLINE.ha_device_id)}']`);
    expect(link).not.toBeNull();

    await navigate(element, deviceRoute(ONLINE.ha_device_id));
    expect(text(element)).toContain(ONLINE.device_id);
    expect(text(element)).toContain("Kitchen");

    await navigate(element, "#/devices");
    expect(rows(element)).toEqual([ONLINE.ha_device_id, OFFLINE.ha_device_id]);
  });

  it("switches tabs and keeps the later ones as placeholders", async () => {
    const element = await mount(fakeHass(responder([ONLINE])), deviceRoute(ONLINE.ha_device_id));
    const tabs = [...(element.shadowRoot?.querySelectorAll("[role='tab']") ?? [])];
    expect(tabs).toHaveLength(4);

    (tabs[2] as HTMLElement).click();
    await settle(element);
    expect(text(element)).toContain("This tab arrives in a later release.");
    expect(text(element)).not.toContain("Current page");

    (tabs[0] as HTMLElement).click();
    await settle(element);
    expect(text(element)).toContain("Current page");
  });
});

describe("freshness", () => {
  it("reloads the snapshot when the panel becomes visible again", async () => {
    let devicesCalls = 0;
    const handler: CallHandler = async (msg) => {
      if (msg.type === "tabula/panel_info") {
        return INFO;
      }
      devicesCalls += 1;
      return [ONLINE];
    };
    const element = await mount(fakeHass(handler));
    expect(devicesCalls).toBe(1);

    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      get: () => "visible",
    });
    document.dispatchEvent(new Event("visibilitychange"));
    await settle(element);

    expect(devicesCalls).toBe(2);
    // Nothing polls: there is no timer to fire a third call.
    expect(devicesCalls).toBe(2);
  });

  it("reloads the snapshot when a tabula event arrives", async () => {
    const connection = new FakeConnection();
    let devicesCalls = 0;
    const handler: CallHandler = async (msg) => {
      if (msg.type === "tabula/panel_info") {
        return INFO;
      }
      devicesCalls += 1;
      return [ONLINE];
    };
    const element = await mount(fakeHass(handler, connection));

    // Every tabula_* event type HA can fire (events.py), not a wildcard.
    expect(connection.types).toContain("tabula_screensaver");
    expect(connection.types).toContain("tabula_notification");
    expect(connection.types).toContain("tabula_page");
    expect(devicesCalls).toBe(1);

    connection.fire();
    await settle(element);

    expect(devicesCalls).toBe(2);
  });
});

describe("helpers", () => {
  it("builds and parses the device route", () => {
    expect(deviceRoute("abc")).toBe(`${DEVICE_ROUTE_PREFIX}abc`);
    expect(deviceIdFromRoute(deviceRoute("abc"))).toBe("abc");
    expect(deviceIdFromRoute("#/devices")).toBeNull();
    expect(deviceIdFromRoute("#/device/")).toBeNull();
    expect(deviceIdFromRoute("#/device/a/b")).toBeNull();
  });

  it("filters by name or area and sorts offline last, then by name", () => {
    const devices = [OFFLINE, ONLINE];
    expect(filterDevices(devices, "office").map((d) => d.name)).toEqual(["Kitchen"]);
    expect(filterDevices(devices, "").length).toBe(2);
    expect(sortDevices(devices).map((d) => d.name)).toEqual(["Kitchen", "Hall"]);
  });

  it("names the settings state", () => {
    expect(settingsBadge(ONLINE, 3)).toBe("in sync");
    expect(settingsBadge(OFFLINE, 3)).toBe("unknown");
    expect(
      settingsBadge(device({ settings: { ha_revision: 3, device_revision: 2, pending: true } }), 3),
    ).toBe("update pending");
    expect(settingsBadge(device({ settings_schema_version: 2 }), 3)).toBe("device behind");
  });

  it("labels the connection with a UTC last-seen time", () => {
    expect(connectionLabel(ONLINE)).toBe("Online");
    expect(connectionLabel(OFFLINE)).toBe("Offline · last seen Nov 23, 2026, 6:04 PM UTC");
    expect(connectionLabel(device({ connected: false, last_seen: null }))).toBe(
      "Offline · never connected",
    );
    expect(formatWhen("not a date")).toBe("not a date");
  });
});
