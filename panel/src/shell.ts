import {
  LitElement,
  html,
  nothing,
  unsafeCSS,
  type PropertyValueMap,
  type TemplateResult,
} from "lit";
import styles from "./styles.css?inline";
import { STRINGS, errorLine, statusLine } from "./strings";
import type { HomeAssistant, PanelInfo } from "./types";

/** Hash route the panel lands on; P5.2 adds the device detail route. */
export const LANDING_ROUTE = "#/devices";

/** A hash route, falling back to the landing route for anything else. */
export function routeOf(hash: string): string {
  return hash.startsWith("#/") ? hash : LANDING_ROUTE;
}

/**
 * The panel shell: proves registration, admin auth and the panel WebSocket
 * namespace, and shows where the later pages hang. It holds no device data.
 */
export class TabulaPanel extends LitElement {
  static override styles = unsafeCSS(styles);

  static override properties = {
    hass: { attribute: false },
    info: { state: true },
    error: { state: true },
    route: { state: true },
  };

  /** Home Assistant's frontend interface, set by the panel host. */
  declare hass?: HomeAssistant;
  declare info?: PanelInfo;
  declare error?: string;
  declare route: string;

  private requested = false;

  constructor() {
    super();
    this.route = routeOf(window.location?.hash ?? "");
  }

  override connectedCallback(): void {
    super.connectedCallback();
    window.addEventListener("hashchange", this.onHashChange);
  }

  override disconnectedCallback(): void {
    window.removeEventListener("hashchange", this.onHashChange);
    super.disconnectedCallback();
  }

  private readonly onHashChange = (): void => {
    this.route = routeOf(window.location?.hash ?? "");
  };

  protected override updated(changed: PropertyValueMap<this>): void {
    // `hass` can arrive before or after the first render, so the request starts
    // the first time it is present rather than from connectedCallback.
    if (changed.has("hass") && this.hass && !this.requested && !this.info) {
      this.requested = true;
      void this.load();
    }
  }

  private async load(): Promise<void> {
    const hass = this.hass;
    if (!hass) {
      return;
    }
    this.error = undefined;
    try {
      this.info = await hass.callWS<PanelInfo>({ type: "tabula/panel_info" });
    } catch (reason) {
      this.info = undefined;
      this.error = errorLine(reason);
    }
  }

  private readonly retry = (): void => {
    this.requested = false;
    this.requestUpdate();
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
        ${this.error ? this.renderError() : nothing} ${this.info ? this.renderRoute() : nothing}
      </main>
    `;
  }

  private renderError(): TemplateResult {
    return html`
      <div class="error" role="alert">
        <strong>${STRINGS.errorTitle}</strong>
        <span>${this.error}</span>
        <button type="button" @click=${this.retry}>${STRINGS.retry}</button>
      </div>
    `;
  }

  private renderRoute(): TemplateResult {
    return html`
      <section class="route">
        <h2>${STRINGS.devicesRoute}</h2>
        <p>${STRINGS.devicesPlaceholder}</p>
      </section>
    `;
  }
}
