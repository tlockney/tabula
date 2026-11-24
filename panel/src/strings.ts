import type { PanelInfo } from "./types";

/**
 * Every string the shell shows, in one table the later Phase 5 packets extend
 * (the panel ships English like the rest of the integration's UI).
 */
export const STRINGS = {
  panelTitle: "Tabularium",
  panelSubtitle: "Tabula device management",
  loading: "Loading the hub's status…",
  errorTitle: "The Tabularium could not load its status",
  retry: "Try again",
  devicesRoute: "Devices",
  devicesPlaceholder: "The device list arrives in a later release.",
} as const;

/** The hub status line: integration, protocol and schema versions, device count. */
export function statusLine(info: PanelInfo): string {
  const devices = info.devices === 1 ? "1 device" : `${info.devices} devices`;
  return (
    `Tabula ${info.version} · protocol ${info.protocol_version} · ` +
    `settings schema ${info.schema_version} · ${devices}`
  );
}

/** The reason a failed `tabula/panel_info` call is shown, kept to one line. */
export function errorLine(reason: unknown): string {
  if (reason instanceof Error && reason.message) {
    return reason.message;
  }
  return String(reason ?? "Unknown error");
}
