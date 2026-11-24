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
  /** HA's websocket subscription (`subscribe_events` and friends, P5.4). */
  subscribeMessage?<T>(
    callback: (message: T) => void,
    msg: { type: string; [key: string]: unknown },
  ): Promise<UnsubscribeFunc>;
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

/** One line of `tabula/panel_logs` (PROTOCOL.md 4.8's log_line). */
export interface PanelLogLine {
  /** RFC 3339, the device's clock when the line was written. */
  ts: string;
  level: string;
  tag: string;
  message: string;
}

/** The log stream's state, as the snapshot and the stream report it. */
export interface PanelLogSubscription {
  active: boolean;
  viewers: number;
  level: string | null;
  /** RFC 3339; when the device's own subscription expires. */
  expires_at: string | null;
}

/** `tabula/panel_logs` (integration/custom_components/tabula/logs.py, P5.4). */
export interface PanelLogs {
  device_id: string;
  connected: boolean;
  last_seen: string | null;
  /** The ring's bound, LOG_BUFFER_LINES (ADR 0002). */
  capacity: number;
  /** Oldest first, as the device sent them. */
  lines: PanelLogLine[];
  subscription: PanelLogSubscription;
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

/** One profile, as `tabula/panel_profiles` lists it (P5.6). */
export interface PanelProfile {
  profile_id: string;
  name: string;
  /** The built-in empty profile: no keys, permanent, read-only (ADR 0035 item 9). */
  builtin: boolean;
  /** Flat shared key -> value; only keys the profile covers. */
  values: Record<string, unknown>;
  /** The device ids assigned to it. */
  members: string[];
  member_count: number;
}

/** One device the Profiles section can assign, with its exceptions. */
export interface PanelProfileDevice {
  device_id: string;
  ha_device_id: string;
  name: string | null;
  connected: boolean;
  last_seen: string | null;
  settings_schema_version: number | null;
  profile_id: string;
  /** Flat key -> value for keys this device overrides (ADR 0035 item 1). */
  overrides: Record<string, unknown>;
}

/** `tabula/panel_profiles`. */
export interface PanelProfiles {
  schema_version: number;
  profiles: PanelProfile[];
  assignments: Record<string, string>;
  devices: PanelProfileDevice[];
}

/** What applying a profile did to one member. */
export interface ProfileApplyResult {
  device_id: string;
  connected: boolean;
  /** The keys written, as flat schema keys; empty when it was current. */
  applied: string[];
  /** The revision saved, or null when nothing changed. */
  revision: number | null;
}

/** `tabula/panel_profile_save`. */
export interface ProfileSaveResult {
  profile: PanelProfile;
  applied: ProfileApplyResult[];
  rejected: { key: string; reason: string }[];
}

/** One covered key against one member's current value. */
export interface ProfileDiffRow {
  device_id: string;
  key: string;
  profile_value: unknown;
  /** What would be written: the override when one shadows the key. */
  effective_value: unknown;
  device_value: unknown;
  device_unset: boolean;
  overridden: boolean;
  changes: boolean;
}

/** `tabula/panel_profile_diff`. */
export interface ProfileDiff {
  rows: ProfileDiffRow[];
  changing: number;
}

/** One drifting member (ADR 0035 item 7). */
export interface DriftMember {
  device_id: string;
  ha_device_id: string;
  name: string | null;
  profile_id: string;
  profile_name: string;
  connected: boolean;
  last_seen: string | null;
  ha_revision: number;
  device_revision: number | null;
  /** HA's revision minus the reported one; null when the device never reported. */
  revisions_behind: number | null;
  drifted: boolean;
  overrides: { key: string; value: unknown }[];
  /** Covered keys the device's settings schema predates. */
  behind_keys: string[];
}

/** `tabula/panel_drift`. */
export interface DriftReport {
  members: DriftMember[];
}

/** `tabula/panel_profile_override`. */
export interface ProfileOverrideResult {
  device_id: string;
  overrides: Record<string, unknown>;
  applied: ProfileApplyResult;
}
