/**
 * Test-only helpers: a fake `hass` and a mount that waits for the panel's
 * first round trip. Not part of the bundle (only `panel.ts` is the entry), so
 * nothing here ships; the Vitest include pattern runs only `*.test.ts`.
 */
import "./panel";
import type { TabulaActivity } from "./activity";
import "./settings-editor";
import { SETTINGS_DEFAULTS } from "./generated/settings";
import type { TabulaSettingsEditor } from "./settings-editor";
import type { TabulaPanel } from "./shell";
import type {
  HassConnection,
  HassStates,
  HomeAssistant,
  PanelDevice,
  PanelInfo,
  SettingsSnapshot,
  UnsubscribeFunc,
} from "./types";

export const INFO: PanelInfo = {
  version: "0.1.0",
  protocol_version: 1,
  schema_version: 3,
  devices: 2,
};

export type CallHandler = (msg: { type: string; [key: string]: unknown }) => Promise<unknown>;

export function fakeHass(
  handler: CallHandler,
  connection?: HassConnection,
  states?: HassStates,
): HomeAssistant {
  return {
    callWS: <T>(msg: { type: string; [key: string]: unknown }) => handler(msg) as Promise<T>,
    connection,
    states,
  };
}

/** The HA event bus, with the events it was asked for and a way to fire one. */
export class FakeConnection implements HassConnection {
  readonly types: string[] = [];
  unsubscribed = 0;
  private readonly callbacks: ((event: unknown) => void)[] = [];

  async subscribeEvents(
    callback: (event: unknown) => void,
    eventType: string,
  ): Promise<UnsubscribeFunc> {
    this.types.push(eventType);
    this.callbacks.push(callback);
    return async () => {
      this.unsubscribed += 1;
    };
  }

  fire(): void {
    for (const callback of this.callbacks) {
      callback({ event_type: this.types[0], data: {} });
    }
  }
}

/** Let the element's pending requests and re-renders settle. */
export async function settle(element: { updateComplete: Promise<unknown> }): Promise<void> {
  for (let i = 0; i < 8; i += 1) {
    await Promise.resolve();
    await element.updateComplete;
  }
}

/** Mount the element with a fake hass at a hash route, after its first load. */
export async function mount(hass: HomeAssistant, hash = ""): Promise<TabulaPanel> {
  window.location.hash = hash;
  const element = document.createElement("tabula-panel") as TabulaPanel;
  element.hass = hass;
  document.body.append(element);
  await customElements.whenDefined("tabula-panel");
  await settle(element);
  return element;
}

/** Navigate the panel to another hash route, as a link click would. */
export async function navigate(element: TabulaPanel, hash: string): Promise<void> {
  window.location.hash = hash;
  window.dispatchEvent(new HashChangeEvent("hashchange"));
  await settle(element);
}

/** One `tabula/panel_devices` entry, with everything a test does not name. */
export function device(overrides: Partial<PanelDevice> = {}): PanelDevice {
  return {
    device_id: "11111111-2222-3333-4444-555555555555",
    ha_device_id: "abcdef0123456789abcdef0123456789",
    name: "Office Kiosk",
    model: "KFTRPWI",
    area_id: null,
    area_name: null,
    connected: true,
    last_seen: null,
    app_version: "0.1.0",
    os_version: "Fire OS 7.3.2.9 (Android 9)",
    protocol_version: 1,
    settings_schema_version: 3,
    capabilities: ["screen", "screensaver"],
    settings: { ha_revision: 2, device_revision: 2, pending: false },
    current_page: "/lovelace/0",
    screen: {
      screen_on: true,
      brightness: 60,
      screensaver_active: false,
      screensaver_mode: "clock",
    },
    entities: {
      sensor: [{ entity_id: "sensor.office_kiosk_battery", name: "Battery", state: "87" }],
    },
    ...overrides,
  };
}

/** `hass.states` from `{entity_id: state}` (null is HA's "unknown"). */
export function statesFrom(
  entries: Record<string, string | null>,
  pictures: Record<string, string> = {},
): HassStates {
  return Object.fromEntries(
    Object.entries(entries).map(([entityId, state]) => {
      const attributes: Record<string, unknown> = {};
      if (pictures[entityId] !== undefined) {
        attributes.entity_picture = pictures[entityId];
      }
      return [entityId, { entity_id: entityId, state: state ?? "unknown", attributes }];
    }),
  );
}

export interface ServiceCall {
  domain: string;
  service: string;
  data: Record<string, unknown>;
}

/** A recording `hass.callService`, optionally failing like HA does. */
export function fakeCallService(
  handler: (call: ServiceCall) => Promise<unknown> = async () => undefined,
): { calls: ServiceCall[]; callService: NonNullable<HomeAssistant["callService"]> } {
  const calls: ServiceCall[] = [];
  return {
    calls,
    callService: async (domain, service, data = {}) => {
      const call = { domain, service, data };
      calls.push(call);
      return handler(call);
    },
  };
}

/** A device with the Activity tab's entities, as P5.2's payload carries them. */
export function activityDevice(overrides: Partial<PanelDevice> = {}): PanelDevice {
  return device({
    capabilities: ["screen", "navigation", "screenshot"],
    entities: {
      button: [
        {
          entity_id: "button.office_kiosk_capture_screenshot",
          name: "Capture screenshot",
          state: "unknown",
        },
        {
          entity_id: "button.office_kiosk_clear_cache",
          name: "Clear cache",
          state: "unknown",
        },
        {
          entity_id: "button.office_kiosk_go_to_start_page",
          name: "Go to start page",
          state: "unknown",
        },
        {
          entity_id: "button.office_kiosk_reload_dashboard",
          name: "Reload dashboard",
          state: "unknown",
        },
        {
          entity_id: "button.office_kiosk_restart_app",
          name: "Restart app",
          state: "unknown",
        },
      ],
      image: [
        {
          entity_id: "image.office_kiosk_screenshot",
          name: "Screenshot",
          state: "2026-11-24T10:00:00+00:00",
        },
      ],
      sensor: [
        {
          entity_id: "sensor.office_kiosk_last_capture",
          name: "Last capture",
          state: "2026-11-24T10:00:00+00:00",
        },
      ],
    },
    ...overrides,
  });
}

/** Mount the Activity tab with a fake hass, after its first render. */
export async function mountActivity(
  hass: HomeAssistant,
  panelDevice: PanelDevice,
): Promise<TabulaActivity> {
  const element = document.createElement("tabula-activity") as TabulaActivity;
  element.device = panelDevice;
  element.hass = hass;
  document.body.append(element);
  await customElements.whenDefined("tabula-activity");
  await settle(element);
  return element;
}

/** Hand the tab a new `hass`, as HA does on every state change. */
export async function updateHass(element: TabulaActivity, hass: HomeAssistant): Promise<void> {
  element.hass = hass;
  await settle(element);
}

/** A document with every schema default filled in, as a fresh copy. */
export function settingsDocument(): Record<string, unknown> {
  return structuredClone(SETTINGS_DEFAULTS) as unknown as Record<string, unknown>;
}

/** One `tabula/panel_settings` payload, with everything a test does not name. */
export function settingsSnapshot(overrides: Partial<SettingsSnapshot> = {}): SettingsSnapshot {
  return {
    device_id: "11111111-2222-3333-4444-555555555555",
    ha_device_id: "abcdef0123456789abcdef0123456789",
    name: "Office Kiosk",
    connected: true,
    stored: true,
    settings: settingsDocument(),
    defaults: settingsDocument(),
    ha_revision: 1,
    device_revision: 1,
    pending: false,
    schema_version: 3,
    device_schema_version: 3,
    capabilities: ["screen", "screensaver"],
    field_since: { screen_brightness: 1, schedule_slots: 2, voice_wake_word_enabled: 3 },
    ...overrides,
  };
}

/** A couple of live entity states, for the editor's entity picker. */
export const STATES: HassStates = {
  "sensor.office_temperature": {
    entity_id: "sensor.office_temperature",
    state: "21",
    attributes: {},
  },
  "light.kitchen": { entity_id: "light.kitchen", state: "on", attributes: {} },
};

/**
 * Mount the settings editor with a fake hass and the read it answers with.
 *
 * The element is the device page's Settings tab body; a test drives it as the
 * user would (typing, selecting, pressing Save) and reads the calls it made.
 */
export async function mountEditor(
  handler: CallHandler,
  snapshot: SettingsSnapshot = settingsSnapshot(),
): Promise<TabulaSettingsEditor> {
  const element = document.createElement("tabula-settings-editor") as TabulaSettingsEditor;
  element.hass = fakeHass(handler, undefined, STATES);
  element.deviceId = snapshot.device_id;
  document.body.append(element);
  await settle(element);
  return element;
}
