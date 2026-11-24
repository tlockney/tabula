/** The part of Home Assistant's frontend contract the shell depends on. */
export interface HomeAssistant {
  callWS<T>(msg: { type: string; [key: string]: unknown }): Promise<T>;
}

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
