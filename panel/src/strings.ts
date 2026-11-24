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

  // P5.2: the device list and the device page shell.
  devicesLoading: "Loading the paired devices…",
  devicesErrorTitle: "The device list could not be loaded",
  filterPlaceholder: "Filter by name or area",
  emptyTitle: "No tablets are paired yet",
  emptyBody:
    "Pair a tablet from Home Assistant's Tabula integration page; it appears here once it registers.",
  noMatch: "No device matches that filter.",
  columnDevice: "Device",
  columnArea: "Area",
  columnConnection: "Connection",
  columnApp: "App",
  columnSettings: "Settings",
  online: "Online",
  offline: "Offline",
  neverSeen: "Offline · never connected",
  lastSeen: "last seen",
  noArea: "No area",
  snapshotTaken: "Snapshot taken",
  settingsInSync: "In sync",
  settingsPending: "Update pending",
  settingsBehind: "Device behind",
  settingsUnknown: "Unknown",
  notFoundTitle: "Device not found",
  notFoundBody: "No paired tablet has that id. It may have been removed from Home Assistant.",
  backToList: "All devices",
  tabOverview: "Overview",
  tabSettings: "Settings",
  tabLogs: "Logs",
  tabActivity: "Activity",
  tabPending: "This tab arrives in a later release.",
  overview: {
    identity: "Identity",
    link: "Link",
    versions: "Versions",
    state: "Reported state",
    settings: "Settings",
    entities: "Entities",
    deviceId: "Device id",
    haDeviceId: "HA device id",
    model: "Model",
    area: "Area",
    appVersion: "App version",
    osVersion: "OS version",
    protocolVersion: "Protocol version",
    settingsSchema: "Settings schema",
    connected: "Connection",
    lastSeen: "Last seen",
    currentPage: "Current page",
    screenOn: "Screen on",
    brightness: "Brightness",
    screensaver: "Screensaver",
    haRevision: "HA revision",
    deviceRevision: "Device revision",
    pending: "Push pending",
    none: "None reported",
    yes: "Yes",
    no: "No",
  },

  // P5.5: the Activity tab - the latest capture and the command buttons.
  activity: {
    title: "Latest screenshot",
    capture: "Capture screenshot",
    capturing: "Capturing…",
    stillRunning: "Still running…",
    captured: "Capture finished: the image and its timestamp are updated.",
    running: "Running…",
    done: "Done.",
    alt: "Latest screenshot of the tablet's screen",
    lastCapture: "Last capture",
    never: "Never captured",
    caption: "Reported state",
    noState: "The tablet has not reported its screen state.",
    screenOn: "Screen on",
    screenOff: "Screen off",
    noScreensaver: "No screensaver",
    screensaver: "Screensaver",
    page: "Page",
    placeholderNote:
      "No image is stored, so the tablet's placeholder is shown: the screen was off, or nothing has been captured yet.",
    noneNote: "Nothing has been captured yet, so the tablet's placeholder is shown.",
    unavailableNote: "The image entity is unavailable in Home Assistant.",
    fromLastConnection: "From the last connection.",
    unsupported:
      "This tablet does not declare the screenshot capability, so there is no image to show.",
    commandsTitle: "Commands",
    configuration: "Configuration",
    configurationNote: "These change how the dashboard runs on the tablet, unlike the ones above.",
    clearCacheNote: "Clear cache removes the dashboard's stored data on the tablet.",
    noCommands: "This tablet exposes no command buttons.",
    offlineReason: "The tablet is offline, so Home Assistant would refuse the command.",
    unavailableReason: "The entity is unavailable in Home Assistant.",
  },
} as const;

/** The hub status line: integration, protocol and schema versions, device count. */
export function statusLine(info: PanelInfo): string {
  const devices = info.devices === 1 ? "1 device" : `${info.devices} devices`;
  return (
    `Tabula ${info.version} · protocol ${info.protocol_version} · ` +
    `settings schema ${info.schema_version} · ${devices}`
  );
}

/** The reason a failed call is shown, kept to one line. */
export function errorLine(reason: unknown): string {
  if (reason instanceof Error && reason.message) {
    return reason.message;
  }
  return String(reason ?? "Unknown error");
}

/**
 * The still-running note under a press that has not finished (P5.5): the panel
 * stops implying progress and says who will report the outcome.
 */
export function stillRunningLine(seconds: number): string {
  return (
    `Still running after ${seconds} seconds: the tablet has not answered yet. ` +
    "Home Assistant reports the outcome when it does."
  );
}
