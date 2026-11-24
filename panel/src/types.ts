/** The part of Home Assistant's frontend contract the panel depends on. */
export interface HomeAssistant {
  callWS<T>(msg: { type: string; [key: string]: unknown }): Promise<T>;
  /** The frontend's event bus; absent in a test's fake unless the test needs it. */
  connection?: HassConnection;
}

/** HA's event subscription: resolves to the unsubscribe function. */
export interface HassConnection {
  subscribeEvents(callback: (event: unknown) => void, eventType: string): Promise<UnsubscribeFunc>;
  /** HA's websocket subscription (`subscribe_events` and friends, P5.4). */
  subscribeMessage?<T>(callback: (message: T) => void, msg: { type: string; [key: string]: unknown }): Promise<UnsubscribeFunc>;
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
