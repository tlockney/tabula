/**
 * The device page's Activity tab (packet P5.5): the latest screenshot with the
 * state it was taken in, and the command buttons the integration already
 * exposes.
 *
 * Nothing here talks to the device. The tab renders the entity ids the P5.2
 * `tabula/panel_devices` payload carries and presses them through
 * `button.press`, so every outcome is HA's: the pending state ends when the
 * capture lands (the image entity moves) or the call settles, and a failure is
 * shown as HA's own text. A press never writes a state on the device's behalf.
 */
import { LitElement, html, nothing, unsafeCSS, type PropertyValueMap } from "lit";
import styles from "./styles.css?inline";
import { formatWhen } from "./devices";
import { STRINGS, errorLine, stillRunningLine } from "./strings";
import type { HassStates, HomeAssistant, PanelDevice, PanelDeviceEntity } from "./types";
import type { TemplateResult } from "lit";

/**
 * Seconds a press may stay unacknowledged before the panel says "still running"
 * instead of implying progress (ADR 0002). Longer than the integration's
 * COMMAND_TIMEOUT so a real timeout error usually arrives first, shorter than
 * SCREENSHOT_TIMEOUT so a quiet capture reports in before HA does.
 */
export const PRESS_PENDING_TIMEOUT_S = 20;

/** A command button, classified by what it does to the tablet. */
export type CommandKind = "reload" | "go_home" | "restart_app" | "clear_cache" | "other";

export interface CommandButton {
  entityId: string;
  kind: CommandKind;
  /** The entity's translated name, from the payload's registry copy. */
  name: string;
}

/** The entity ids the tab needs, all taken from one device payload. */
export interface ActivityEntities {
  imageId: string | null;
  captureId: string | null;
  lastCaptureId: string | null;
  commands: CommandButton[];
}

/** What the screenshot area shows (F-MGT-07, ADR 0014). */
export type CaptureKind = "image" | "placeholder" | "none" | "unavailable" | "unsupported";

export interface CaptureView {
  kind: CaptureKind;
  /** The image URL to show, or null when there is nothing to show. */
  picture: string | null;
  /** When the last capture started (RFC 3339), from the timestamp sensor. */
  lastCapture: string | null;
  /** The image entity's state: the last capture's time when an image exists. */
  imageState: string | null;
  /** The sentence under the image. */
  note: string;
}

/** States HA uses for "no value", never a real entity state. */
const ABSENT_STATES = new Set(["unknown", "unavailable", "none", ""]);

/** States that make an entity unusable: it exists but has nothing to press. */
const UNAVAILABLE_STATES = new Set(["unavailable", "none", ""]);

/** One entity's live state, falling back to the payload's snapshot. */
export function stateOf(device: PanelDevice, entityId: string, states?: HassStates): string | null {
  const live = states?.[entityId];
  if (live !== undefined) {
    return live.state;
  }
  return findEntity(device, entityId)?.state ?? null;
}

function findEntity(device: PanelDevice, entityId: string): PanelDeviceEntity | undefined {
  return Object.values(device.entities)
    .flat()
    .find((entity) => entity.entity_id === entityId);
}

/** The image entity's picture URL, from HA's own state or the proxy path. */
export function pictureOf(entityId: string, states?: HassStates): string {
  const picture = states?.[entityId]?.attributes.entity_picture;
  if (typeof picture === "string" && picture.length > 0) {
    return picture;
  }
  return `/api/image_proxy/${entityId}`;
}

/**
 * The command kind a button names.
 *
 * The registry builds an entity id from the device's name and the entity's
 * translated name (`button.<device>_reload_dashboard`), and a user may rename
 * the entity afterwards, so the id is matched by its key suffix and the name as
 * a fallback. Both come from the payload P5.2 sends.
 */
export function commandKind(entityId: string, name?: string | null): CommandKind {
  const key = `${entityId} ${name ?? ""}`.toLowerCase();
  if (/restart/.test(key)) {
    return "restart_app";
  }
  if (/clear[\s_-]?cache/.test(key)) {
    return "clear_cache";
  }
  if (/go[\s_-]?home|start[\s_-]?page/.test(key)) {
    return "go_home";
  }
  if (/reload/.test(key)) {
    return "reload";
  }
  return "other";
}

/**
 * The ids the tab reads out of one payload entry.
 *
 * The entities are capability-gated by the integration, so a tablet without the
 * screenshot capability has no image, no capture button and no capture sensor:
 * the tab shows the capability note rather than a control HA would refuse.
 */
export function activityEntities(device: PanelDevice): ActivityEntities {
  const images = device.entities.image ?? [];
  const buttons = device.entities.button ?? [];
  const sensors = device.entities.sensor ?? [];
  const capture =
    buttons.find((entity) => entity.entity_id.endsWith("_capture_screenshot")) ?? null;
  const commands = buttons
    .filter((entity) => entity !== capture)
    .map((entity) => ({
      entityId: entity.entity_id,
      kind: commandKind(entity.entity_id, entity.name),
      name: entity.name ?? entity.entity_id,
    }))
    .sort((left, right) => left.name.localeCompare(right.name, undefined, { sensitivity: "base" }));
  return {
    imageId: images[0]?.entity_id ?? null,
    captureId: capture?.entity_id ?? null,
    lastCaptureId:
      sensors.find((entity) => entity.entity_id.endsWith("_last_capture"))?.entity_id ?? null,
    commands,
  };
}

/** Benign commands, and the configuration pair on their own (P5.5 item 3). */
export function commandGroups(commands: readonly CommandButton[]): {
  benign: CommandButton[];
  configuration: CommandButton[];
} {
  const isConfiguration = (kind: CommandKind): boolean =>
    kind === "restart_app" || kind === "clear_cache";
  return {
    benign: commands.filter((command) => !isConfiguration(command.kind)),
    configuration: commands.filter((command) => isConfiguration(command.kind)),
  };
}

/**
 * The screenshot area's state.
 *
 * ADR 0014: the image entity serves the bundled placeholder whenever it holds no
 * image, and a screen-off capture stores none, so the entity cannot say which of
 * its images is a real frame. The tab therefore reports what the entities say -
 * an image with its capture time, the placeholder when the entity holds no
 * image, unavailable, or no capability - and puts the device's reported screen
 * state beside it.
 */
export function captureView(
  device: PanelDevice,
  entities: ActivityEntities,
  states?: HassStates,
): CaptureView {
  const sensor = entities.lastCaptureId ? stateOf(device, entities.lastCaptureId, states) : null;
  const lastCapture = sensor !== null && !ABSENT_STATES.has(sensor) ? sensor : null;
  if (entities.imageId === null) {
    return {
      kind: "unsupported",
      picture: null,
      lastCapture,
      imageState: null,
      note: STRINGS.activity.unsupported,
    };
  }
  const imageState = stateOf(device, entities.imageId, states);
  const picture = pictureOf(entities.imageId, states);
  if (imageState === "unavailable") {
    return {
      kind: "unavailable",
      picture: null,
      lastCapture,
      imageState: null,
      note: device.connected
        ? STRINGS.activity.unavailableNote
        : `${STRINGS.activity.unavailableNote} ${STRINGS.activity.fromLastConnection}`,
    };
  }
  if (imageState === null || ABSENT_STATES.has(imageState)) {
    return {
      kind: lastCapture === null ? "none" : "placeholder",
      picture,
      lastCapture,
      imageState: null,
      note: lastCapture === null ? STRINGS.activity.noneNote : STRINGS.activity.placeholderNote,
    };
  }
  return {
    kind: "image",
    picture,
    lastCapture,
    imageState,
    note: lastCapture === null ? "" : `${STRINGS.activity.lastCapture}: ${formatWhen(lastCapture)}`,
  };
}

/** The caption: what the tablet reports about its screen right now. */
export function captionFor(device: PanelDevice): string {
  const { screen, current_page: page } = device;
  const parts: string[] = [];
  if (screen.screen_on !== null) {
    parts.push(screen.screen_on ? STRINGS.activity.screenOn : STRINGS.activity.screenOff);
  }
  if (screen.screensaver_active === true) {
    parts.push(
      screen.screensaver_mode
        ? `${STRINGS.activity.screensaver}: ${screen.screensaver_mode}`
        : STRINGS.activity.screensaver,
    );
  } else if (screen.screensaver_active === false) {
    parts.push(STRINGS.activity.noScreensaver);
  }
  if (page) {
    parts.push(`${STRINGS.activity.page} ${page}`);
  }
  return parts.length > 0 ? parts.join(" · ") : STRINGS.activity.noState;
}

/**
 * Why a button cannot be pressed, or null when it can.
 *
 * The panel never offers a command the integration will refuse: an offline
 * device's entities are unavailable, and a capability-gated entity does not
 * exist at all (so it is not rendered).
 */
export function disabledReason(
  device: PanelDevice,
  entityId: string,
  states?: HassStates,
): string | null {
  const state = stateOf(device, entityId, states);
  // A button that was never pressed has no state of its own ("unknown") and is
  // pressable; only a missing or unavailable entity is refused.
  if (state !== null && !UNAVAILABLE_STATES.has(state)) {
    return null;
  }
  return device.connected ? STRINGS.activity.unavailableReason : STRINGS.activity.offlineReason;
}

interface Outcome {
  ok: boolean;
  text: string;
}

/**
 * The Activity tab.
 *
 * `hass` is re-set by the frontend on every state change, which is how the tab
 * follows a capture to completion: there is no subscription of its own and no
 * timer except the still-running one.
 */
export class TabulaActivity extends LitElement {
  static override styles = unsafeCSS(styles);

  static override properties = {
    hass: { attribute: false },
    device: { attribute: false },
    pending: { state: true },
    stillRunning: { state: true },
    outcome: { state: true },
  };

  declare hass?: HomeAssistant;
  declare device?: PanelDevice;
  /** The entity id whose press is in flight, if any. */
  declare pending?: string;
  declare stillRunning: boolean;
  declare outcome?: Outcome;

  /** The image entity's state when the capture started, to spot the new one. */
  private stateBefore: string | null = null;
  private timer?: ReturnType<typeof setTimeout>;

  constructor() {
    super();
    this.stillRunning = false;
  }

  override disconnectedCallback(): void {
    this.clearTimer();
    super.disconnectedCallback();
  }

  protected override updated(changed: PropertyValueMap<this>): void {
    const device = this.device;
    if (!changed.has("hass") || device === undefined || this.pending === undefined) {
      return;
    }
    const entities = activityEntities(device);
    if (this.pending !== entities.captureId || entities.imageId === null) {
      return;
    }
    const state = stateOf(device, entities.imageId, this.hass?.states);
    if (state !== null && !ABSENT_STATES.has(state) && state !== this.stateBefore) {
      this.finish({ ok: true, text: STRINGS.activity.captured });
    }
  }

  private clearTimer(): void {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }

  private finish(outcome: Outcome): void {
    this.clearTimer();
    this.pending = undefined;
    this.stillRunning = false;
    this.outcome = outcome;
  }

  /** Press one button entity and report HA's outcome, never a guess. */
  private readonly press = async (entityId: string, capture: boolean): Promise<void> => {
    const hass = this.hass;
    const device = this.device;
    if (hass?.callService === undefined || device === undefined || this.pending !== undefined) {
      return;
    }
    const entities = activityEntities(device);
    this.pending = entityId;
    this.outcome = undefined;
    this.stillRunning = false;
    this.stateBefore =
      capture && entities.imageId !== null ? stateOf(device, entities.imageId, hass.states) : null;
    this.clearTimer();
    this.timer = setTimeout(() => {
      this.stillRunning = true;
    }, PRESS_PENDING_TIMEOUT_S * 1000);
    try {
      await hass.callService("button", "press", { entity_id: entityId });
      this.finish({
        ok: true,
        text: capture ? STRINGS.activity.captured : STRINGS.activity.done,
      });
    } catch (reason) {
      this.finish({ ok: false, text: errorLine(reason) });
    }
  };

  protected override render(): TemplateResult | typeof nothing {
    const device = this.device;
    if (device === undefined) {
      return nothing;
    }
    const entities = activityEntities(device);
    return html`
      <div class="activity">
        ${this.renderScreenshot(device, entities)} ${this.renderCommands(device, entities)}
        ${
          this.pending !== undefined && this.stillRunning
            ? html`<p class="reason" role="status">${stillRunningLine(PRESS_PENDING_TIMEOUT_S)}</p>`
            : nothing
        }
        ${
          this.outcome === undefined
            ? nothing
            : html`<p
                class=${this.outcome.ok ? "outcome ok" : "outcome error"}
                role=${this.outcome.ok ? "status" : "alert"}
              >
                ${this.outcome.text}
              </p>`
        }
      </div>
    `;
  }

  private renderScreenshot(device: PanelDevice, entities: ActivityEntities): TemplateResult {
    const { activity } = STRINGS;
    const view = captureView(device, entities, this.hass?.states);
    const captureId = entities.captureId;
    const reason = captureId === null ? null : disabledReason(device, captureId, this.hass?.states);
    const label =
      captureId !== null && this.pending === captureId
        ? this.stillRunning
          ? activity.stillRunning
          : activity.capturing
        : captureId === null
          ? null
          : (this.nameOf(device, captureId) ?? activity.capture);
    return html`
      <section class="screenshot">
        <h3>${activity.title}</h3>
        ${
          view.kind === "unsupported"
            ? html`<p class="note">${view.note}</p>`
            : html`
                ${
                  view.picture === null
                    ? nothing
                    : html`<figure>
                        <img class="capture" src=${view.picture} alt=${activity.alt} />
                        <figcaption class="caption">
                          <span class="caption-label">${activity.caption}</span>
                          ${captionFor(device)}
                        </figcaption>
                      </figure>`
                }
                ${view.kind === "image" ? nothing : html`<p class="note">${view.note}</p>`}
                <p class="last-capture">
                  ${activity.lastCapture}:
                  ${view.lastCapture === null ? activity.never : formatWhen(view.lastCapture)}
                </p>
              `
        }
        ${
          captureId === null || label === null
            ? nothing
            : html`<div class="command">
                <button
                  type="button"
                  data-command="capture"
                  ?disabled=${reason !== null || this.pending !== undefined}
                  @click=${() => void this.press(captureId, true)}
                >
                  ${label}
                </button>
                ${reason === null ? nothing : html`<span class="reason">${reason}</span>`}
              </div>`
        }
      </section>
    `;
  }

  private renderCommands(device: PanelDevice, entities: ActivityEntities): TemplateResult {
    const { activity } = STRINGS;
    const groups = commandGroups(entities.commands);
    return html`
      <section class="commands">
        <h3>${activity.commandsTitle}</h3>
        ${
          entities.commands.length === 0
            ? html`<p class="note">${activity.noCommands}</p>`
            : nothing
        }
        ${
          groups.benign.length === 0
            ? nothing
            : html`<div class="command-row">
                ${groups.benign.map((command) => this.renderCommand(device, command))}
              </div>`
        }
        ${
          groups.configuration.length === 0
            ? nothing
            : html`<div class="configuration">
                <h4>${activity.configuration}</h4>
                <p class="note">${activity.configurationNote}</p>
                <div class="command-row">
                  ${groups.configuration.map((command) => this.renderCommand(device, command))}
                </div>
                ${
                  groups.configuration.some((command) => command.kind === "clear_cache")
                    ? html`<p class="note">${activity.clearCacheNote}</p>`
                    : nothing
                }
              </div>`
        }
      </section>
    `;
  }

  private renderCommand(device: PanelDevice, command: CommandButton): TemplateResult {
    const reason = disabledReason(device, command.entityId, this.hass?.states);
    const label =
      this.pending === command.entityId
        ? this.stillRunning
          ? STRINGS.activity.stillRunning
          : STRINGS.activity.running
        : command.name;
    return html`<div class="command">
      <button
        type="button"
        data-command=${command.kind}
        ?disabled=${reason !== null || this.pending !== undefined}
        @click=${() => void this.press(command.entityId, false)}
      >
        ${label}
      </button>
      ${reason === null ? nothing : html`<span class="reason">${reason}</span>`}
    </div>`;
  }

  private nameOf(device: PanelDevice, entityId: string): string | null {
    return findEntity(device, entityId)?.name ?? null;
  }
}

if (customElements.get("tabula-activity") === undefined) {
  customElements.define("tabula-activity", TabulaActivity);
}

declare global {
  interface HTMLElementTagNameMap {
    "tabula-activity": TabulaActivity;
  }
}
