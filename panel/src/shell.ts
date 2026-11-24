import {
  LitElement,
  html,
  nothing,
  unsafeCSS,
  type PropertyValueMap,
  type TemplateResult,
} from "lit";
import styles from "./styles.css?inline";
// Registers <tabula-settings-editor>; the Settings tab mounts it (P5.3).
import "./settings-editor";
import {
  DEVICE_TABS,
  TABULA_EVENT_TYPES,
  connectionLabel,
  deviceIdFromRoute,
  deviceName,
  deviceRoute,
  filterDevices,
  formatWhen,
  settingsBadge,
  settingsLabel,
  sortDevices,
  tabLabel,
  type DeviceTab,
} from "./devices";
import "./activity";
import { STRINGS, errorLine, statusLine } from "./strings";
import type { HomeAssistant, PanelDevice, PanelInfo, UnsubscribeFunc } from "./types";

/** Hash route the panel lands on: the device list. */
export const LANDING_ROUTE = "#/devices";

/** A hash route, falling back to the landing route for anything else. */
export function routeOf(hash: string): string {
  return hash.startsWith("#/") ? hash : LANDING_ROUTE;
}

/** A definition row: `<dt>label</dt><dd>value</dd>`. */
function row(label: string, value: string | number): TemplateResult {
  return html`<dt>${label}</dt>
    <dd>${value}</dd>`;
}

/** A badge's class token, from its label (`in sync` -> `in-sync`). */
function badgeClass(kind: string): string {
  return `badge ${kind.replace(/\s+/g, "-")}`;
}

/**
 * The panel: the hub status line and the device list, with one page per device
 * (P5.2). The snapshot it renders comes whole from the integration; the panel
 * joins nothing itself.
 */
export class TabulaPanel extends LitElement {
  static override styles = unsafeCSS(styles);

  static override properties = {
    hass: { attribute: false },
    info: { state: true },
    error: { state: true },
    route: { state: true },
    devices: { state: true },
    devicesError: { state: true },
    takenAt: { state: true },
    filter: { state: true },
    tab: { state: true },
    settingsDirty: { state: true },
  };

  /** Home Assistant's frontend interface, set by the panel host. */
  declare hass?: HomeAssistant;
  declare info?: PanelInfo;
  declare error?: string;
  declare route: string;
  declare devices?: PanelDevice[];
  declare devicesError?: string;
  /** When the shown snapshot was taken (ISO); the list is not polled. */
  declare takenAt?: string;
  declare filter: string;
  declare tab: DeviceTab;
  /** The Settings tab holds unsaved changes: leaving the page asks first. */
  declare settingsDirty: boolean;

  private requested = false;
  private subscribing = false;
  private refreshing = false;
  private unsubs: UnsubscribeFunc[] = [];

  constructor() {
    super();
    this.route = routeOf(window.location?.hash ?? "");
    this.filter = "";
    this.tab = "overview";
    this.settingsDirty = false;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    window.addEventListener("hashchange", this.onHashChange);
    window.addEventListener("beforeunload", this.onBeforeUnload);
    document.addEventListener("visibilitychange", this.onVisibilityChange);
  }

  override disconnectedCallback(): void {
    window.removeEventListener("hashchange", this.onHashChange);
    window.removeEventListener("beforeunload", this.onBeforeUnload);
    document.removeEventListener("visibilitychange", this.onVisibilityChange);
    this.unsubscribeFromEvents();
    super.disconnectedCallback();
  }

  private readonly onHashChange = (): void => {
    const next = routeOf(window.location?.hash ?? "");
    if (next !== this.route && !this.confirmLeave()) {
      // Stay: put the address back where the panel is (a dirty settings form).
      window.history.replaceState(null, "", this.route);
      return;
    }
    this.settingsDirty = false;
    this.route = next;
  };

  /** A dirty settings form asks before the browser unloads the panel. */
  private readonly onBeforeUnload = (event: BeforeUnloadEvent): void => {
    if (!this.settingsDirty) {
      return;
    }
    event.preventDefault();
    event.returnValue = "";
  };

  /** True when it is fine to leave the current page (clean, or confirmed). */
  private confirmLeave(): boolean {
    if (!this.settingsDirty) {
      return true;
    }
    return window.confirm(STRINGS.settings.discard);
  }

  private readonly onSettingsDirty = (event: CustomEvent<boolean>): void => {
    this.settingsDirty = event.detail;
  };

  /** Coming back to the tab is the refresh trigger; there is no timer (P5.2). */
  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === "visible") {
      void this.load();
    }
  };

  protected override updated(changed: PropertyValueMap<this>): void {
    if (changed.has("route")) {
      // A new device route starts on the overview tab.
      this.tab = "overview";
    }
    // `hass` can arrive before or after the first render, so the request starts
    // the first time it is present rather than from connectedCallback.
    if (changed.has("hass") && this.hass) {
      if (!this.requested) {
        this.requested = true;
        void this.load();
      }
      void this.subscribeToEvents();
    }
  }

  /** Both panel calls, each with its own error state: one failing page should
   * still leave the other usable. */
  private async load(): Promise<void> {
    const hass = this.hass;
    if (!hass) {
      return;
    }
    await Promise.all([this.loadStatus(hass), this.loadDevices(hass)]);
  }

  private async loadStatus(hass: HomeAssistant): Promise<void> {
    this.error = undefined;
    try {
      this.info = await hass.callWS<PanelInfo>({ type: "tabula/panel_info" });
    } catch (reason) {
      this.info = undefined;
      this.error = errorLine(reason);
    }
  }

  private async loadDevices(hass: HomeAssistant): Promise<void> {
    this.devicesError = undefined;
    try {
      this.devices = await hass.callWS<PanelDevice[]>({ type: "tabula/panel_devices" });
      this.takenAt = new Date().toISOString();
    } catch (reason) {
      // The previous snapshot stays on screen, labelled with its own time.
      this.devicesError = errorLine(reason);
    }
  }

  /** A device state or event change is a refresh trigger (P5.2). */
  private async subscribeToEvents(): Promise<void> {
    const connection = this.hass?.connection;
    if (!connection || this.subscribing) {
      return;
    }
    this.subscribing = true;
    try {
      for (const eventType of TABULA_EVENT_TYPES) {
        this.unsubs.push(await connection.subscribeEvents(this.refresh, eventType));
      }
    } catch {
      // A subscription failure is not fatal: the visibility refresh remains.
      this.subscribing = false;
    }
  }

  /** One load per burst of events: seven subscriptions can fire for one frame. */
  private readonly refresh = (): void => {
    if (this.refreshing) {
      return;
    }
    this.refreshing = true;
    void Promise.resolve().then(async () => {
      this.refreshing = false;
      await this.load();
    });
  };

  private unsubscribeFromEvents(): void {
    for (const unsubscribe of this.unsubs) {
      void unsubscribe();
    }
    this.unsubs = [];
    this.subscribing = false;
  }

  /** The error actions: ask again. `updated()` will not fire without a new hass. */
  private readonly retry = (): void => {
    this.requested = true;
    void this.load();
  };

  private readonly onFilter = (event: Event): void => {
    this.filter = (event.target as HTMLInputElement).value;
  };

  private readonly selectTab = (tab: DeviceTab): void => {
    if (tab !== this.tab && !this.confirmLeave()) {
      return;
    }
    this.settingsDirty = false;
    this.tab = tab;
  };

  protected override render(): TemplateResult {
    return html`
      <main>
        <header>
          <h1>${STRINGS.panelTitle}</h1>
          <p class="subtitle">${STRINGS.panelSubtitle}</p>
          ${
            this.info
              ? html`<p class="status">${statusLine(this.info)}</p>`
              : this.error
                ? nothing
                : html`<p class="status">${STRINGS.loading}</p>`
          }
        </header>
        ${this.error ? this.renderError(STRINGS.errorTitle, this.error) : nothing}
        ${this.renderRoute()}
      </main>
    `;
  }

  private renderError(title: string, message: string): TemplateResult {
    return html`
      <div class="error" role="alert">
        <strong>${title}</strong>
        <span>${message}</span>
        <button type="button" @click=${this.retry}>${STRINGS.retry}</button>
      </div>
    `;
  }

  private renderRoute(): TemplateResult | typeof nothing {
    const deviceId = deviceIdFromRoute(this.route);
    return deviceId === null ? this.renderDevices() : this.renderDeviceRoute(deviceId);
  }

  private renderDevices(): TemplateResult {
    const error: TemplateResult | typeof nothing = this.devicesError
      ? this.renderError(STRINGS.devicesErrorTitle, this.devicesError)
      : nothing;
    const devices = this.devices;
    if (devices === undefined) {
      return html`${error}
        <section class="route">
          <p>${STRINGS.devicesLoading}</p>
        </section>`;
    }
    const visible = sortDevices(filterDevices(devices, this.filter));
    return html`${error}
      <section class="route">
        <div class="toolbar">
          <h2>${STRINGS.devicesRoute}</h2>
          <input
            type="search"
            aria-label=${STRINGS.filterPlaceholder}
            placeholder=${STRINGS.filterPlaceholder}
            .value=${this.filter}
            @input=${this.onFilter}
          />
        </div>
        ${
          devices.length === 0
            ? this.renderEmpty()
            : visible.length === 0
              ? html`<p class="empty-note">${STRINGS.noMatch}</p>`
              : this.renderTable(visible)
        }
        ${
          this.takenAt
            ? html`<p class="snapshot">${STRINGS.snapshotTaken} ${formatWhen(this.takenAt)}</p>`
            : nothing
        }
      </section>`;
  }

  private renderEmpty(): TemplateResult {
    return html`
      <div class="empty">
        <h3>${STRINGS.emptyTitle}</h3>
        <p>${STRINGS.emptyBody}</p>
        <a href="/config/integrations/integration/tabula">${STRINGS.backToList}</a>
      </div>
    `;
  }

  private renderTable(devices: PanelDevice[]): TemplateResult {
    const schema = this.info?.schema_version;
    return html`
      <table>
        <thead>
          <tr>
            <th>${STRINGS.columnDevice}</th>
            <th>${STRINGS.columnArea}</th>
            <th>${STRINGS.columnConnection}</th>
            <th>${STRINGS.columnApp}</th>
            <th>${STRINGS.columnSettings}</th>
          </tr>
        </thead>
        <tbody>
          ${devices.map((device) => {
            const badge = settingsBadge(device, schema);
            return html`
              <tr
                data-device=${device.ha_device_id}
                class=${device.connected ? "online" : "offline"}
              >
                <td>
                  <a href=${deviceRoute(device.ha_device_id)}>${deviceName(device)}</a>
                  ${device.model ? html`<span class="model">${device.model}</span>` : nothing}
                </td>
                <td>${device.area_name ?? STRINGS.noArea}</td>
                <td>
                  <span class=${badgeClass(device.connected ? "online" : "offline")}>
                    ${device.connected ? STRINGS.online : STRINGS.offline}
                  </span>
                  ${
                    !device.connected && device.last_seen
                      ? html`<span class="last-seen"
                          >${STRINGS.lastSeen} ${formatWhen(device.last_seen)}</span
                        >`
                      : nothing
                  }
                </td>
                <td>${device.app_version ?? STRINGS.overview.none}</td>
                <td>
                  <span class=${badgeClass(badge)}>${settingsLabel(badge)}</span>
                </td>
              </tr>
            `;
          })}
        </tbody>
      </table>
    `;
  }

  private renderDeviceRoute(deviceId: string): TemplateResult | typeof nothing {
    const error = this.devicesError
      ? this.renderError(STRINGS.devicesErrorTitle, this.devicesError)
      : nothing;
    const devices = this.devices;
    if (devices === undefined) {
      return this.devicesError
        ? error
        : html`<section class="route">
            <p>${STRINGS.devicesLoading}</p>
          </section>`;
    }
    const device = devices.find((item) => item.ha_device_id === deviceId);
    if (device === undefined) {
      return html`${error}
        <section class="route empty" role="status">
          <h2>${STRINGS.notFoundTitle}</h2>
          <p>${STRINGS.notFoundBody}</p>
          <a href=${LANDING_ROUTE}>${STRINGS.backToList}</a>
        </section>`;
    }
    return html`${error}${this.renderDevicePage(device)}`;
  }

  private renderDevicePage(device: PanelDevice): TemplateResult {
    const meta = [
      device.model,
      device.area_name,
      device.app_version ? `app ${device.app_version}` : null,
    ]
      .filter((part): part is string => Boolean(part))
      .join(" · ");
    return html`
      <section class="route device">
        <a class="back" href=${LANDING_ROUTE}>${STRINGS.backToList}</a>
        <h2>${deviceName(device)}</h2>
        ${meta ? html`<p class="meta">${meta}</p>` : nothing}
        <div class="tabs" role="tablist">
          ${DEVICE_TABS.map(
            (tab) => html`
              <button
                type="button"
                role="tab"
                class=${tab === this.tab ? "tab selected" : "tab"}
                aria-selected=${tab === this.tab}
                @click=${() => this.selectTab(tab)}
              >
                ${tabLabel(tab)}
              </button>
            `,
          )}
        </div>
        ${this.renderTab(device)}
      </section>
    `;
  }

  /** The tab body: Overview, Settings and Activity are filled, the rest are later work. */
  private renderTab(device: PanelDevice): TemplateResult {
    switch (this.tab) {
      case "overview":
        return this.renderOverview(device);
      case "settings":
        return this.renderSettings(device);
      case "activity":
        return html`<tabula-activity .hass=${this.hass} .device=${device}></tabula-activity>`;
      default:
        return html`<p class="tab-body">${STRINGS.tabPending}</p>`;
    }
  }

  /** The Settings tab: the schema-driven editor (P5.3), keyed by device id. */
  private renderSettings(device: PanelDevice): TemplateResult {
    return html`
      <tabula-settings-editor
        .hass=${this.hass}
        .deviceId=${device.device_id}
        @dirty-changed=${this.onSettingsDirty}
      ></tabula-settings-editor>
    `;
  }

  private renderOverview(device: PanelDevice): TemplateResult {
    const { overview } = STRINGS;
    const screen = device.screen;
    const screensaver =
      screen.screensaver_active === null
        ? overview.none
        : screen.screensaver_active
          ? `${overview.yes}${screen.screensaver_mode ? ` (${screen.screensaver_mode})` : ""}`
          : overview.no;
    const badge = settingsBadge(device, this.info?.schema_version);
    const platforms = Object.entries(device.entities);
    return html`
      <div class="overview">
        <section>
          <h3>${overview.identity}</h3>
          <dl>
            ${row(overview.deviceId, device.device_id)}
            ${row(overview.haDeviceId, device.ha_device_id)}
            ${row(overview.model, device.model ?? overview.none)}
            ${row(overview.area, device.area_name ?? STRINGS.noArea)}
          </dl>
        </section>
        <section>
          <h3>${overview.link}</h3>
          <dl>
            ${row(overview.connected, connectionLabel(device))}
            ${row(overview.lastSeen, device.last_seen ? formatWhen(device.last_seen) : overview.none)}
            ${row(overview.currentPage, device.current_page ?? overview.none)}
          </dl>
        </section>
        <section>
          <h3>${overview.versions}</h3>
          <dl>
            ${row(overview.appVersion, device.app_version ?? overview.none)}
            ${row(overview.osVersion, device.os_version ?? overview.none)}
            ${row(
              overview.protocolVersion,
              device.protocol_version === null ? overview.none : device.protocol_version,
            )}
            ${row(
              overview.settingsSchema,
              device.settings_schema_version === null
                ? overview.none
                : device.settings_schema_version,
            )}
          </dl>
        </section>
        <section>
          <h3>${overview.state}</h3>
          <dl>
            ${row(
              overview.screenOn,
              screen.screen_on === null
                ? overview.none
                : screen.screen_on
                  ? overview.yes
                  : overview.no,
            )}
            ${row(
              overview.brightness,
              screen.brightness === null ? overview.none : `${screen.brightness}%`,
            )}
            ${row(overview.screensaver, screensaver)}
          </dl>
        </section>
        <section>
          <h3>${overview.settings}</h3>
          <dl>
            ${row(overview.haRevision, device.settings.ha_revision)}
            ${row(
              overview.deviceRevision,
              device.settings.device_revision === null
                ? overview.none
                : device.settings.device_revision,
            )}
            ${row(overview.pending, settingsLabel(badge))}
          </dl>
        </section>
        <section>
          <h3>${overview.entities}</h3>
          ${
            platforms.length === 0
              ? html`<p>${overview.none}</p>`
              : html`<dl>
                  ${platforms.map(([platform, entities]) => row(platform, entities.length))}
                </dl>`
          }
        </section>
      </div>
    `;
  }
}
