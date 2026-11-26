import {
  LitElement,
  html,
  nothing,
  unsafeCSS,
  type PropertyValueMap,
  type TemplateResult,
} from "lit";
import styles from "./styles.css?inline";
import { deviceName, formatWhen } from "./devices";
import type { HomeAssistant, PanelDevice, PanelLogLine, PanelLogs, UnsubscribeFunc } from "./types";

/** Rows the view keeps in the DOM: LOG_VIEW_MAX_LINES in ADR 0002 (P5.4). */
export const LOG_VIEW_MAX_LINES = 500;

/** The four levels, weakest first; the filter row and the wire use these names. */
export const LOG_LEVELS = ["debug", "info", "warning", "error"] as const;
export type LogLevel = (typeof LOG_LEVELS)[number];

/** Everything the log view shows, in one table this packet owns. */
export const LOG_STRINGS = {
  title: "Logs",
  loading: "Loading the buffered lines…",
  empty: "The device has not sent any lines yet.",
  emptyDevice: "No lines are buffered. Start the stream to see new ones.",
  offline: "The device is offline. Showing the lines it sent before the connection ended;",
  lastSeen: "last seen",
  stream: "Start the stream",
  stop: "Stop the stream",
  streaming: "Streaming",
  paused: "Paused",
  stopped: "Not streaming",
  follow: "Follow",
  pause: "Pause",
  clear: "Clear view",
  download: "Download",
  newestFirst: "Newest first",
  oldestFirst: "Oldest first",
  search: "Search lines",
  allLevels: "All levels",
  tag: "Any tag",
  capacity: "buffer",
  shown: "shown",
  pausedNote: "Paused: lines still arrive below and are shown when you resume.",
  clearNote: "Clearing hides these lines here; the device and Home Assistant keep their buffers.",
  expiresAt: "until",
  retry: "Try again",
  errorTitle: "The log view could not load",
} as const;

/** A filter set drawn by the toolbar. */
export interface LogFilter {
  levels: readonly LogLevel[];
  tag: string | null;
  query: string;
}

/** A line's level as a comparable rank; anything unknown sorts lowest. */
export function levelRank(level: string): number {
  const index = LOG_LEVELS.indexOf(level as LogLevel);
  return index < 0 ? -1 : index;
}

/**
 * The lines a viewer asked for, in the order they will be shown.
 *
 * The filters are the panel's, not the device's: a subscription's level limits
 * what the device sends, while these narrow what is already in the buffer (an
 * offline device's lines included).
 */
export function filterLines(
  lines: readonly PanelLogLine[],
  filter: LogFilter,
  newestFirst: boolean,
): PanelLogLine[] {
  const needle = filter.query.trim().toLowerCase();
  const levels = new Set(filter.levels.length ? filter.levels : LOG_LEVELS);
  const matching = lines.filter(
    (line) =>
      levels.has(line.level as LogLevel) &&
      (filter.tag === null || line.tag === filter.tag) &&
      (needle === "" || line.message.toLowerCase().includes(needle)),
  );
  return newestFirst ? matching.reverse() : matching;
}

/** The newest lines the view keeps: the oldest are dropped (ADR 0002). */
export function boundLines(lines: readonly PanelLogLine[]): PanelLogLine[] {
  return lines.length > LOG_VIEW_MAX_LINES ? lines.slice(-LOG_VIEW_MAX_LINES) : [...lines];
}

/** Every tag in the buffer, sorted, for the tag filter. */
export function tagsOf(lines: readonly PanelLogLine[]): string[] {
  return [...new Set(lines.map((line) => line.tag))].sort();
}

/** The downloaded text: one line each, oldest first, whatever the view shows. */
export function logText(lines: readonly PanelLogLine[], device: string): string {
  const header = `# Tabula log, ${device}, ${lines.length} lines\n`;
  return (
    header +
    lines
      .map((line) => `${line.ts} ${line.level.toUpperCase()} ${line.tag}: ${line.message}`)
      .join("\n") +
    "\n"
  );
}

/** A line's row class, so console lines read differently from the app's (P5.4). */
export function lineClass(line: PanelLogLine): string {
  return `log-line level-${line.level}${line.tag === "console" ? " console" : ""}`;
}

/**
 * The device page's Logs tab (P5.4, F-MGT-06).
 *
 * Starts with the buffered lines, then follows the live stream: the first render
 * is a `tabula/panel_logs` snapshot and, while the device is online, a
 * `tabula/panel_logs_subscribe` stream. Pausing stops the view from moving but
 * keeps collecting, the DOM is bounded by [LOG_VIEW_MAX_LINES], and clearing
 * hides the lines here without touching the device's or HA's buffer.
 */
export class TabulaLogs extends LitElement {
  static override styles = unsafeCSS(styles);

  static override properties = {
    hass: { attribute: false },
    device: { attribute: false },
    lines: { state: true },
    error: { state: true },
    loading: { state: true },
    paused: { state: true },
    newestFirst: { state: true },
    levels: { state: true },
    tag: { state: true },
    query: { state: true },
    streaming: { state: true },
    expiresAt: { state: true },
    capacity: { state: true },
    lastSeen: { state: true },
  };

  declare hass?: HomeAssistant;
  declare device?: PanelDevice;
  declare lines: PanelLogLine[];
  declare error?: string;
  declare loading: boolean;
  declare paused: boolean;
  declare newestFirst: boolean;
  declare levels: LogLevel[];
  declare tag: string | null;
  declare query: string;
  declare streaming: boolean;
  declare expiresAt: string | null;
  declare capacity: number;
  declare lastSeen: string | null;

  /** Lines that arrived while paused; released on resume so nothing is lost. */
  private held: PanelLogLine[] = [];
  private unsubscribe?: UnsubscribeFunc;
  private requested = false;

  constructor() {
    super();
    this.lines = [];
    this.loading = true;
    this.paused = false;
    this.newestFirst = true;
    this.levels = [];
    this.tag = null;
    this.query = "";
    this.streaming = false;
    this.expiresAt = null;
    this.capacity = 0;
    this.lastSeen = null;
  }

  override disconnectedCallback(): void {
    this.stopStream();
    super.disconnectedCallback();
  }

  protected override updated(changed: PropertyValueMap<this>): void {
    if ((changed.has("hass") || changed.has("device")) && this.hass && this.device) {
      if (!this.requested) {
        this.requested = true;
        void this.load();
      }
    }
  }

  /** The snapshot, then the stream when the device is online. */
  private async load(): Promise<void> {
    const hass = this.hass;
    const device = this.device;
    if (!hass || !device) {
      return;
    }
    this.error = undefined;
    this.loading = true;
    try {
      const snapshot = await hass.callWS<PanelLogs>({
        type: "tabula/panel_logs",
        device_id: device.device_id,
      });
      this.lines = boundLines(snapshot.lines);
      this.capacity = snapshot.capacity;
      this.lastSeen = snapshot.last_seen;
      this.streaming = snapshot.subscription.active;
      this.expiresAt = snapshot.subscription.expires_at;
      if (device.connected) {
        await this.startStream();
      }
    } catch (reason) {
      this.error = reason instanceof Error ? reason.message : String(reason);
    } finally {
      this.loading = false;
    }
  }

  private async startStream(): Promise<void> {
    const hass = this.hass;
    const device = this.device;
    const connection = hass?.connection;
    if (!connection?.subscribeMessage || !device || this.unsubscribe) {
      return;
    }
    try {
      this.unsubscribe = await connection.subscribeMessage(
        (message: Partial<PanelLogs> & { lines?: PanelLogLine[] }) => this.onMessage(message),
        {
          type: "tabula/panel_logs_subscribe",
          device_id: device.device_id,
          enabled: true,
          ...(this.levels.length === 1 ? { level: this.levels[0] } : {}),
        },
      );
      this.streaming = true;
    } catch (reason) {
      // A refused stream is not a broken view: the buffer stays readable.
      this.streaming = false;
      this.error = reason instanceof Error ? reason.message : String(reason);
    }
  }

  private async stopStream(): Promise<void> {
    const unsubscribe = this.unsubscribe;
    this.unsubscribe = undefined;
    this.streaming = false;
    if (unsubscribe) {
      await unsubscribe();
    }
  }

  private onMessage(message: {
    lines?: PanelLogLine[];
    subscription?: { active: boolean; expires_at: string | null };
  }): void {
    if (message.lines) {
      // Paused: keep collecting so resuming shows what was missed.
      if (this.paused) {
        this.held = boundLines([...this.held, ...message.lines]);
        return;
      }
      this.lines = boundLines([...this.lines, ...message.lines]);
    }
    if (message.subscription) {
      this.streaming = message.subscription.active;
      this.expiresAt = message.subscription.expires_at;
    }
  }

  private readonly toggleStream = (): void => {
    if (this.streaming) {
      void this.stopStream();
    } else {
      void this.restartStream();
    }
  };

  /**
   * Ask for the stream again after a device drop ended it (P5.4).
   *
   * A drop leaves HA's subscription open, so the view still holds its own
   * unsubscribe: [startStream] would return on it and the button would do
   * nothing. Dropping it first lets the device hear `enabled: true` again.
   */
  private async restartStream(): Promise<void> {
    await this.stopStream();
    await this.startStream();
  }

  /** Resuming releases what arrived while paused. */
  private readonly togglePause = (): void => {
    if (this.paused && this.held.length) {
      this.lines = boundLines([...this.lines, ...this.held]);
      this.held = [];
    }
    this.paused = !this.paused;
  };

  private readonly clearView = (): void => {
    this.lines = [];
    this.held = [];
  };

  private readonly download = (): void => {
    const device = this.device;
    const text = logText(this.lines, device ? deviceName(device) : "device");
    const url = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `${device ? deviceName(device).replace(/\s+/g, "-").toLowerCase() : "tabula"}-log.txt`;
    link.click();
    URL.revokeObjectURL(url);
  };

  private readonly onSearch = (event: Event): void => {
    this.query = (event.target as HTMLInputElement).value;
  };

  private readonly onTag = (event: Event): void => {
    const value = (event.target as HTMLSelectElement).value;
    this.tag = value === "" ? null : value;
  };

  private readonly toggleLevel = (level: LogLevel): void => {
    this.levels = this.levels.includes(level)
      ? this.levels.filter((item) => item !== level)
      : [...this.levels, level];
  };

  protected override render(): TemplateResult {
    const device = this.device;
    if (!device) {
      return html`<p class="tab-body">${LOG_STRINGS.loading}</p>`;
    }
    return html`
      <div class="logs">
        ${this.renderStatus(device)} ${this.renderToolbar()} ${this.renderNote(device)}
        ${this.renderLines()}
      </div>
    `;
  }

  private renderStatus(device: PanelDevice): TemplateResult {
    const state = !device.connected
      ? `${LOG_STRINGS.offline} ${LOG_STRINGS.lastSeen} ${formatWhen(this.lastSeen ?? device.last_seen ?? "")}`
      : this.streaming
        ? `${LOG_STRINGS.streaming}${this.expiresAt ? ` ${LOG_STRINGS.expiresAt} ${formatWhen(this.expiresAt)}` : ""}`
        : LOG_STRINGS.stopped;
    const shown = filterLines(this.lines, this.filter(), this.newestFirst).length;
    return html`
      <div class="log-status" role="status">
        <span
          class=${this.streaming ? "badge streaming" : "badge paused"}
          data-state=${this.streaming ? "streaming" : "stopped"}
        >
          ${state}
        </span>
        <span class="counts" data-counts>
          ${shown} ${LOG_STRINGS.shown} ·
          ${this.lines.length}/${this.capacity || LOG_VIEW_MAX_LINES} ${LOG_STRINGS.capacity}
        </span>
        <button type="button" ?disabled=${!device.connected} @click=${this.toggleStream}>
          ${this.streaming ? LOG_STRINGS.stop : LOG_STRINGS.stream}
        </button>
      </div>
    `;
  }

  private renderToolbar(): TemplateResult {
    return html`
      <div class="log-toolbar">
        <button type="button" @click=${() => (this.newestFirst = !this.newestFirst)} data-order>
          ${this.newestFirst ? LOG_STRINGS.newestFirst : LOG_STRINGS.oldestFirst}
        </button>
        <button
          type="button"
          class=${this.paused ? "selected" : ""}
          @click=${this.togglePause}
          data-pause
        >
          ${this.paused ? LOG_STRINGS.pause : LOG_STRINGS.follow}
        </button>
        <input
          type="search"
          aria-label=${LOG_STRINGS.search}
          placeholder=${LOG_STRINGS.search}
          .value=${this.query}
          @input=${this.onSearch}
        />
        <select aria-label=${LOG_STRINGS.tag} @change=${this.onTag}>
          <option value="">${LOG_STRINGS.tag}</option>
          ${tagsOf(this.lines).map(
            (tag) => html`<option value=${tag} ?selected=${tag === this.tag}>${tag}</option>`,
          )}
        </select>
        ${LOG_LEVELS.map(
          (level) =>
            html`<button
              type="button"
              class=${this.levels.includes(level) ? "level selected" : "level"}
              @click=${() => this.toggleLevel(level)}
              data-level=${level}
            >
              ${level}
            </button>`,
        )}
        <button type="button" @click=${this.clearView} data-clear>${LOG_STRINGS.clear}</button>
        <button type="button" @click=${this.download} data-download>${LOG_STRINGS.download}</button>
      </div>
    `;
  }

  private renderNote(device: PanelDevice): TemplateResult | typeof nothing {
    if (!device.connected) {
      return nothing;
    }
    if (this.paused) {
      return html`<p class="log-note" role="note">${LOG_STRINGS.pausedNote}</p>`;
    }
    return nothing;
  }

  private renderLines(): TemplateResult {
    if (this.loading) {
      return html`<p class="tab-body">${LOG_STRINGS.loading}</p>`;
    }
    if (this.error) {
      return html`<div class="error" role="alert">
        <strong>${LOG_STRINGS.errorTitle}</strong>
        <span>${this.error}</span>
        <button type="button" @click=${() => void this.load()}>${LOG_STRINGS.retry}</button>
      </div>`;
    }
    const visible = filterLines(this.lines, this.filter(), this.newestFirst);
    if (visible.length === 0) {
      return html`<p class="log-empty" data-empty>
        ${this.device?.connected ? LOG_STRINGS.emptyDevice : LOG_STRINGS.empty}
      </p>`;
    }
    return html`
      <ol class="log-lines" data-count=${visible.length}>
        ${visible.map(
          (line) =>
            html`<li class=${lineClass(line)}>
              <time>${line.ts}</time>
              <span class="tag">${line.tag}</span>
              <span class="message">${line.message}</span>
            </li>`,
        )}
      </ol>
    `;
  }

  private filter(): LogFilter {
    return { levels: this.levels, tag: this.tag, query: this.query };
  }
}

if (!customElements.get("tabula-logs")) {
  customElements.define("tabula-logs", TabulaLogs);
}
