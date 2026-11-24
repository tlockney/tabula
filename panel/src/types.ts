/** The part of Home Assistant's frontend contract the panel depends on. */
export interface HomeAssistant {
  callWS<T>(msg: { type: string; [key: string]: unknown }): Promise<T>;
  /**
   * `hass.callService`; awaited for the entity's outcome, so a refused or failed
   * command rejects with HA's own message (P5.5).
   */
  callService?(
    domain: string,
    service: string,
    serviceData?: Record<string, unknown>,
  ): Promise<unknown>;
  /** The frontend's event bus; absent in a test's fake unless the test needs it. */
  connection?: HassConnection;
  /**
   * The frontend's live states (`hass.states`): the settings editor's entity
   * picker reads it, and the Activity tab follows its own entities here (P5.5).
   * HA replaces the `hass` object on every change, so the tab re-renders
   * without polling.
   */
  states?: HassStates;
}

/** HA's event subscription: resolves to the unsubscribe function. */
export interface HassConnection {
  subscribeEvents(callback: (event: unknown) => void, eventType: string): Promise<UnsubscribeFunc>;
}

export type UnsubscribeFunc = () => Promise<void>;

/** `tabula/panel_info` (integration/custom_components/tabula/panel_api.py). */
export interface PanelInfo {
  /** The integration's version, from its manifest. */
  version: string;
  /** TLP major the integration speaks (docs/PROTOCOL.md 8). */
  protocol_version: number;
  /** Settings schema version the integration stores (schema/settings.schema.json). */
  schema_version: number;
  /** Paired devices under the hub. */
  devices: number;
}

/** One entity of a device, as `tabula/panel_devices` groups it. */
export interface PanelDeviceEntity {
  entity_id: string;
  name: string | null;
  state: string | null;
}

/** Settings revisions, and whether HA's copy has not been applied yet. */
export interface PanelDeviceSettings {
  ha_revision: number;
  /** What the device reported at registration or in its last ACK; null offline. */
  device_revision: number | null;
  pending: boolean;
}

/** The reported screen state (PROTOCOL.md 4.4); null keys were never reported. */
export interface PanelDeviceScreen {
  screen_on: boolean | null;
  brightness: number | null;
  screensaver_active: boolean | null;
  screensaver_mode: string | null;
}

/** One entry of `tabula/panel_devices` (P5.2). */
export interface PanelDevice {
  device_id: string;
  ha_device_id: string;
  name: string | null;
  model: string | null;
  area_id: string | null;
  area_name: string | null;
  connected: boolean;
  /** RFC 3339; when a registered session last ended. */
  last_seen: string | null;
  app_version: string | null;
  os_version: string | null;
  protocol_version: number | null;
  settings_schema_version: number | null;
  capabilities: string[];
  settings: PanelDeviceSettings;
  current_page: string | null;
  screen: PanelDeviceScreen;
  /** Entities grouped by platform (the entity id's domain). */
  entities: Record<string, PanelDeviceEntity[]>;
}

/** One reported entity's live state, as the frontend's `hass.states` holds it. */
export interface HassState {
  entity_id: string;
  state: string;
  attributes: Record<string, unknown>;
}

/** The part of `hass.states` the editor's entity picker reads. */
export type HassStates = Record<string, HassState>;

/** `tabula/panel_settings` (integration/custom_components/tabula/panel_settings.py). */
export interface SettingsSnapshot {
  device_id: string;
  ha_device_id: string;
  name: string | null;
  connected: boolean;
  /** False when the device has no stored document: `settings` is the defaults. */
  stored: boolean;
  /** The stored document, or the schema defaults when there is none. */
  settings: Record<string, unknown>;
  /** The schema's defaults, for showing an unset key as what it would be. */
  defaults: Record<string, unknown>;
  /** HA's revision; a write bumps it by one. */
  ha_revision: number;
  /** What the device reported at registration or in its last ACK; null offline. */
  device_revision: number | null;
  /** A stored document the device has not applied yet. */
  pending: boolean;
  /** The settings schema version the integration stores. */
  schema_version: number;
  /** The device's reported settings schema version; null before registration. */
  device_schema_version: number | null;
  capabilities: string[];
  /** The schema version that introduced each key (`SettingsSchema.since`). */
  field_since: Record<string, number>;
}

/** `tabula/panel_settings_set`'s response. */
export interface SettingsSaveResult {
  /** The revision saved, or null when every submitted key was rejected. */
  revision: number | null;
  /** The keys that applied, as flat schema keys. */
  applied: string[];
  /** The keys that did not, each with the schema's reason. */
  rejected: { key: string; reason: string }[];
}
