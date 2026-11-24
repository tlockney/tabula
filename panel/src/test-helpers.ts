/**
 * Test-only helpers: a fake `hass` and a mount that waits for the panel's
 * first round trip. Not part of the bundle (only `panel.ts` is the entry), so
 * nothing here ships; the Vitest include pattern runs only `*.test.ts`.
 */
import "./panel";
import type { TabulaPanel } from "./shell";
import type {
  HassConnection,
  HomeAssistant,
  PanelDevice,
  PanelInfo,
  UnsubscribeFunc,
} from "./types";

export const INFO: PanelInfo = {
  version: "0.1.0",
  protocol_version: 1,
  schema_version: 3,
  devices: 2,
};

export type CallHandler = (msg: { type: string; [key: string]: unknown }) => Promise<unknown>;

export function fakeHass(handler: CallHandler, connection?: HassConnection): HomeAssistant {
  return {
    callWS: <T>(msg: { type: string; [key: string]: unknown }) => handler(msg) as Promise<T>,
    connection,
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
export async function settle(element: TabulaPanel): Promise<void> {
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
