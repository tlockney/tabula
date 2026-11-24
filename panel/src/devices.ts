import { STRINGS } from "./strings";
import type { PanelDevice } from "./types";

/**
 * The panel's event types (integration/custom_components/tabula/events.py).
 * HA's bus has no wildcard subscription, so the shell names them all: any of
 * them can change what the device list shows.
 */
export const TABULA_EVENT_TYPES = [
  "tabula_notification",
  "tabula_screensaver",
  "tabula_media",
  "tabula_page",
  "tabula_wake_word",
  "tabula_voice",
  "tabula_intercom",
] as const;

/** The device page route prefix: `#/device/<ha_device_id>` (P5.2). */
export const DEVICE_ROUTE_PREFIX = "#/device/";

/** The device's display name, falling back to the short device id. */
export function deviceName(device: PanelDevice): string {
  return device.name ?? device.device_id.slice(0, 8);
}

/** The hash route for one device's page. */
export function deviceRoute(haDeviceId: string): string {
  return `${DEVICE_ROUTE_PREFIX}${encodeURIComponent(haDeviceId)}`;
}

/** The device an `#/device/<id>` route names, or null for any other route. */
export function deviceIdFromRoute(route: string): string | null {
  if (!route.startsWith(DEVICE_ROUTE_PREFIX)) {
    return null;
  }
  const rest = route.slice(DEVICE_ROUTE_PREFIX.length);
  if (!rest || rest.includes("/")) {
    return null;
  }
  return decodeURIComponent(rest);
}

/** Devices whose name or area contains the query, case-insensitively. */
export function filterDevices(devices: readonly PanelDevice[], query: string): PanelDevice[] {
  const needle = query.trim().toLowerCase();
  if (!needle) {
    return [...devices];
  }
  return devices.filter((device) =>
    [deviceName(device), device.area_name ?? ""].some((value) =>
      value.toLowerCase().includes(needle),
    ),
  );
}

/**
 * Name order, with offline devices last: the ones that need attention are
 * offline, and the rest of the list stays stable, so the panel sorts by
 * connection first and then by name (P5.2).
 */
export function sortDevices(devices: readonly PanelDevice[]): PanelDevice[] {
  return [...devices].sort((left, right) => {
    if (left.connected !== right.connected) {
      return left.connected ? -1 : 1;
    }
    const byName = deviceName(left).localeCompare(deviceName(right), undefined, {
      sensitivity: "base",
    });
    return byName !== 0 ? byName : left.ha_device_id.localeCompare(right.ha_device_id);
  });
}

export type SettingsBadge = "in sync" | "update pending" | "device behind" | "unknown";

/**
 * The settings badge for a device.
 *
 * `currentSchema` is the schema version the integration stores (from
 * `tabula/panel_info`): a device behind it needs a migration push before edits
 * land. Otherwise a pending revision means HA has not applied its copy yet. An
 * offline device that has not reported a revision is unknown rather than
 * wrong.
 */
export function settingsBadge(device: PanelDevice, currentSchema?: number): SettingsBadge {
  if (
    currentSchema !== undefined &&
    device.settings_schema_version !== null &&
    device.settings_schema_version < currentSchema
  ) {
    return "device behind";
  }
  if (device.settings.pending) {
    return "update pending";
  }
  if (device.settings.device_revision === null) {
    return "unknown";
  }
  return "in sync";
}

/** The settings badge's label. */
export function settingsLabel(badge: SettingsBadge): string {
  switch (badge) {
    case "in sync":
      return STRINGS.settingsInSync;
    case "update pending":
      return STRINGS.settingsPending;
    case "device behind":
      return STRINGS.settingsBehind;
    default:
      return STRINGS.settingsUnknown;
  }
}

/** The connection badge: online, or offline with when the device was last seen. */
export function connectionLabel(device: PanelDevice): string {
  if (device.connected) {
    return STRINGS.online;
  }
  if (device.last_seen) {
    return `${STRINGS.offline} · ${STRINGS.lastSeen} ${formatWhen(device.last_seen)}`;
  }
  return STRINGS.neverSeen;
}

/**
 * An RFC 3339 timestamp as text, in UTC.
 *
 * The fleet is spread over whatever timezone each room is in, so one labelled
 * zone beats a per-browser guess: two admins looking at the same panel read the
 * same instant, and the tests do not depend on the machine's zone.
 */
export function formatWhen(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const formatted = new Intl.DateTimeFormat("en-US", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  }).format(date);
  return `${formatted} UTC`;
}

/** The tabs of the device page shell; P5.3-P5.5 fill the later ones. */
export const DEVICE_TABS = ["overview", "settings", "logs", "activity"] as const;
export type DeviceTab = (typeof DEVICE_TABS)[number];

/** The tab's label. */
export function tabLabel(tab: DeviceTab): string {
  switch (tab) {
    case "overview":
      return STRINGS.tabOverview;
    case "settings":
      return STRINGS.tabSettings;
    case "logs":
      return STRINGS.tabLogs;
    default:
      return STRINGS.tabActivity;
  }
}
