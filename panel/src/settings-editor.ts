/**
 * The per-device settings editor (P5.3).
 *
 * The form is the schema: sections and collapsible groups come from
 * `SETTINGS_SECTIONS`, each control from its leaf's type and metadata, and both
 * structured lists from their `items` (the At a Glance entity row and the
 * schedule's slots). No per-key rendering code exists, so schema v4 and later
 * keys need no new UI work here.
 *
 * A save sends every changed leaf as one call, so it becomes one revision and
 * one push (ADR 0011). The server's `applied`/`rejected` answer is what the form
 * reports; the revision it saved is only called "applied on the tablet" once a
 * re-read shows the device's reported revision caught up.
 */
import { LitElement, html, nothing, unsafeCSS, type TemplateResult } from "lit";
import styles from "./styles.css?inline";
import type { SettingsField, SettingsItemProperty } from "./generated/settings";
import {
  SETTINGS_SECTIONS,
  changedValues,
  clientProblem,
  dirtySections,
  entityChoices,
  flattenSettings,
  isBehind,
  isColorField,
  labelFor,
  newItem,
  saveValues,
  settingValue,
  swatchColor,
  titleFor,
  withAlpha,
  type SettingsGroup,
  type SettingsSection,
  type SettingsValues,
} from "./settings";
import { STRINGS, errorLine } from "./strings";
import type { HomeAssistant, SettingsSaveResult, SettingsSnapshot } from "./types";

/** The element name the device page mounts for the Settings tab. */
export const SETTINGS_EDITOR_TAG = "tabula-settings-editor";

/** A custom event: the form's dirty state changed (the shell's leave guard). */
export const DIRTY_EVENT = "dirty-changed";

/** One value as an input's text. */
function textValue(value: unknown): string {
  return value === null || value === undefined ? "" : String(value);
}

/** One value as a number input's text ("" for unset). */
function numberValue(value: unknown): string {
  return typeof value === "number" ? String(value) : "";
}

export class TabulaSettingsEditor extends LitElement {
  static override styles = unsafeCSS(styles);

  static override properties = {
    hass: { attribute: false },
    deviceId: { attribute: false },
    snapshot: { state: true },
    error: { state: true },
    draft: { state: true },
    rejected: { state: true },
    result: { state: true },
    saving: { state: true },
  };

  /** Home Assistant's frontend interface, set by the device page. */
  declare hass?: HomeAssistant;
  /** The Tabula device id (the settings store's key). */
  declare deviceId?: string;
  declare snapshot?: SettingsSnapshot;
  /** The load error, shown with a retry. */
  declare error?: string;
  /** The form's values, flat by schema key. */
  declare draft: SettingsValues;
  /** Per-key rejection reasons from the last save, by flat key. */
  declare rejected: Record<string, string>;
  /** The last save's answer, kept for the summary line. */
  declare result?: SettingsSaveResult;
  declare saving: boolean;

  private original: SettingsValues = {};
  private loadedFor?: string;
  private loading = false;

  constructor() {
    super();
    this.draft = {};
    this.rejected = {};
    this.saving = false;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    window.addEventListener("beforeunload", this.onBeforeUnload);
  }

  override disconnectedCallback(): void {
    window.removeEventListener("beforeunload", this.onBeforeUnload);
    super.disconnectedCallback();
  }

  protected override updated(): void {
    if (this.hass && this.deviceId && this.loadedFor !== this.deviceId) {
      void this.load();
    }
  }

  /** True while the form holds changes a save has not taken. */
  get isDirty(): boolean {
    return Object.keys(changedValues(this.original, this.draft)).length > 0;
  }

  /** The leave-page guard: a dirty form asks before the browser unloads it. */
  private readonly onBeforeUnload = (event: BeforeUnloadEvent): void => {
    if (!this.isDirty) {
      return;
    }
    event.preventDefault();
    event.returnValue = "";
  };

  private emitDirty(): void {
    this.dispatchEvent(
      new CustomEvent<boolean>(DIRTY_EVENT, {
        detail: this.isDirty,
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** Load the snapshot and start the form from it. */
  private async load(): Promise<void> {
    const hass = this.hass;
    const deviceId = this.deviceId;
    if (!hass || !deviceId || this.loading) {
      return;
    }
    this.loading = true;
    // One attempt per device: a failed load stays failed until the user retries
    // (updated() re-runs on every state change, so it must not loop).
    this.loadedFor = deviceId;
    this.error = undefined;
    try {
      const snapshot = await hass.callWS<SettingsSnapshot>({
        type: "tabula/panel_settings",
        device_id: deviceId,
      });
      this.snapshot = snapshot;
      this.original = flattenSettings(snapshot.settings);
      this.draft = { ...this.original };
      this.rejected = {};
      this.result = undefined;
      this.emitDirty();
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.loading = false;
    }
  }

  private readonly retry = (): void => {
    this.loadedFor = undefined;
    void this.load();
  };

  private setValue(field: SettingsField, value: unknown): void {
    this.draft = { ...this.draft, [field.key]: value };
    if (field.key in this.rejected) {
      // Editing a rejected key clears its reason: the next save judges it again.
      this.rejected = Object.fromEntries(
        Object.entries(this.rejected).filter(([key]) => key !== field.key),
      );
    }
    this.emitDirty();
  }

  private readonly revert = (): void => {
    this.draft = { ...this.original };
    this.rejected = {};
    this.result = undefined;
    this.emitDirty();
  };

  /** Save every changed leaf as one revision, then re-read for the ACK state. */
  private readonly save = async (): Promise<void> => {
    const hass = this.hass;
    const snapshot = this.snapshot;
    if (!hass || !snapshot || this.saving) {
      return;
    }
    const values = saveValues(this.original, this.draft);
    if (Object.keys(values).length === 0) {
      return;
    }
    this.saving = true;
    this.error = undefined;
    try {
      const result = await hass.callWS<SettingsSaveResult>({
        type: "tabula/panel_settings_set",
        device_id: snapshot.device_id,
        values,
      });
      this.result = result;
      this.rejected = Object.fromEntries(result.rejected.map((item) => [item.key, item.reason]));
      // Applied keys are the form's new baseline; rejected ones stay dirty.
      const original = { ...this.original };
      for (const key of result.applied) {
        original[key] = this.draft[key];
      }
      this.original = original;
      if (result.revision !== null) {
        // The device's own revision decides "applied", so ask again.
        await this.reload();
      }
      this.emitDirty();
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.saving = false;
    }
  };

  /** Re-read the snapshot without resetting the form (the ACK state). */
  private async reload(): Promise<void> {
    const hass = this.hass;
    const deviceId = this.deviceId;
    if (!hass || !deviceId) {
      return;
    }
    try {
      this.snapshot = await hass.callWS<SettingsSnapshot>({
        type: "tabula/panel_settings",
        device_id: deviceId,
      });
    } catch {
      // The save itself succeeded; a failed re-read only costs the ACK line.
    }
  }

  protected override render(): TemplateResult {
    if (this.error && this.snapshot === undefined) {
      return html`
        <div class="error" role="alert">
          <strong>${STRINGS.settings.loadError}</strong>
          <span>${this.error}</span>
          <button type="button" @click=${this.retry}>${STRINGS.retry}</button>
        </div>
      `;
    }
    if (this.snapshot === undefined) {
      return html`<p class="tab-body">${STRINGS.settings.loading}</p>`;
    }
    return html`
      ${this.renderStatus()} ${this.renderActions()}
      <div class="settings-form">
        ${SETTINGS_SECTIONS.map((section) => this.renderSection(section))}
      </div>
      ${this.renderActions()}
    `;
  }

  private renderStatus(): TemplateResult {
    const snapshot = this.snapshot;
    if (snapshot === undefined) {
      return html``;
    }
    const dirty = dirtySections(changedValues(this.original, this.draft));
    return html`
      <div class="settings-status">
        <p class="meta">
          ${STRINGS.settings.revisionLine(snapshot.ha_revision, snapshot.device_revision)}
          ${
            snapshot.device_schema_version === null
              ? nothing
              : html` · schema ${snapshot.device_schema_version}/${snapshot.schema_version}`
          }
        </p>
        ${
          dirty.length > 0
            ? html`<p class="dirty" role="status">${STRINGS.settings.sectionDirty}</p>`
            : nothing
        }
        ${this.result ? this.renderSummary(this.result) : nothing}
        ${this.error ? html`<p class="error-line" role="alert">${this.error}</p>` : nothing}
      </div>
    `;
  }

  private renderSummary(result: SettingsSaveResult): TemplateResult {
    const snapshot = this.snapshot;
    const applied = result.applied.length;
    const rejected = result.rejected.length;
    let delivery: string = STRINGS.settings.awaitingAck;
    if (snapshot !== undefined && result.revision !== null) {
      if (!snapshot.connected) {
        delivery = STRINGS.settings.pushedOnReconnect;
      } else if (snapshot.device_revision === result.revision) {
        delivery = STRINGS.settings.appliedOnDevice;
      }
    }
    return html`
      <div class="save-summary" role="status">
        ${
          applied > 0
            ? html`<p class="applied">${STRINGS.settings.applied(applied)}. ${delivery}</p>`
            : html`<p class="rejected-all">${STRINGS.settings.nothingApplied}</p>`
        }
        ${
          rejected > 0
            ? html`<p class="rejected-count">${STRINGS.settings.rejectedSummary(rejected)}</p>`
            : nothing
        }
      </div>
    `;
  }

  private renderActions(): TemplateResult {
    const dirty = this.isDirty;
    return html`
      <div class="actions">
        <button
          type="button"
          class="primary"
          ?disabled=${!dirty || this.saving}
          @click=${this.save}
        >
          ${this.saving ? STRINGS.settings.saving : STRINGS.settings.save}
        </button>
        <button type="button" ?disabled=${!dirty || this.saving} @click=${this.revert}>
          ${STRINGS.settings.revert}
        </button>
      </div>
    `;
  }

  private renderSection(section: SettingsSection): TemplateResult {
    const dirty = dirtySections(changedValues(this.original, this.draft)).includes(section.id);
    return html`
      <section class="settings-section" data-section=${section.id}>
        <h3>
          ${section.title}
          ${
            dirty
              ? html`<span class="section-dirty">${STRINGS.settings.sectionDirty}</span>`
              : nothing
          }
        </h3>
        ${section.fields.map((field) => this.renderField(field))}
        ${section.groups.map((group) => this.renderGroup(group))}
      </section>
    `;
  }

  private renderGroup(group: SettingsGroup): TemplateResult {
    return html`
      <details class="settings-group" data-group=${group.id} open>
        <summary>${group.title}</summary>
        ${group.fields.map((field) => this.renderField(field))}
      </details>
    `;
  }

  private renderField(field: SettingsField): TemplateResult {
    const value = settingValue(this.draft, field);
    const rejected = this.rejected[field.key];
    const problem = clientProblem(field, value);
    const behind = isBehind(
      field,
      this.snapshot?.field_since ?? {},
      this.snapshot?.device_schema_version ?? null,
    );
    const id = `setting-${field.key}`;
    return html`
      <div class=${rejected ? "field invalid" : "field"} data-key=${field.key}>
        <label for=${id}>${labelFor(field)}</label>
        ${this.renderControl(field, value, id)}
        ${field.help ? html`<p class="help">${field.help}</p>` : nothing}
        ${behind ? html`<p class="behind">${STRINGS.settings.behindNote}</p>` : nothing}
        ${
          rejected
            ? html`<p class="rejected" role="alert">
                <span class="tag">${STRINGS.settings.rejectedField}</span> ${rejected}
              </p>`
            : problem
              ? html`<p class="problem">${problem}</p>`
              : nothing
        }
      </div>
    `;
  }

  private renderControl(field: SettingsField, value: unknown, id: string): TemplateResult {
    if (field.type === "boolean") {
      return html`<input
        id=${id}
        type="checkbox"
        role="switch"
        .checked=${value === true}
        @change=${(event: Event) =>
          this.setValue(field, (event.target as HTMLInputElement).checked)}
      />`;
    }
    if (field.type === "array") {
      return field.items !== null && field.items.type === "object"
        ? this.renderObjectArray(field)
        : this.renderScalarArray(field);
    }
    if (field.enumValues !== null) {
      return html`<select
        id=${id}
        .value=${textValue(value)}
        @change=${(event: Event) => this.setValue(field, (event.target as HTMLSelectElement).value)}
      >
        ${value === undefined ? html`<option value=""></option>` : nothing}
        ${field.enumValues.map(
          (option) =>
            html`<option value=${option} ?selected=${option === value}>
              ${titleFor(option)}
            </option>`,
        )}
      </select>`;
    }
    if (field.type === "integer") {
      return html`
        <input
          id=${id}
          type="number"
          min=${field.minimum ?? nothing}
          max=${field.maximum ?? nothing}
          .value=${numberValue(value)}
          @input=${(event: Event) => this.setValue(field, numberFrom(event))}
        />
        ${
          field.minimum !== null && field.maximum !== null
            ? html`<input
                class="slider"
                type="range"
                aria-label=${labelFor(field)}
                min=${field.minimum}
                max=${field.maximum}
                .value=${numberValue(value)}
                @input=${(event: Event) => this.setValue(field, numberFrom(event))}
              />`
            : nothing
        }
      `;
    }
    if (isColorField(field)) {
      return html`
        <input
          id=${id}
          type="text"
          .value=${textValue(value)}
          @input=${(event: Event) => this.setValue(field, (event.target as HTMLInputElement).value)}
        />
        <input
          class="swatch"
          type="color"
          aria-label=${`${labelFor(field)} ${STRINGS.settings.colorSwatch}`}
          .value=${swatchColor(value)}
          @input=${(event: Event) =>
            this.setValue(field, withAlpha(value, (event.target as HTMLInputElement).value))}
        />
      `;
    }
    return html`<input
      id=${id}
      type="text"
      maxlength=${field.maxLength ?? nothing}
      .value=${textValue(value)}
      @input=${(event: Event) => this.setValue(field, (event.target as HTMLInputElement).value)}
    />`;
  }

  /** The At a Glance row: one entity per row, add/remove/reorder (F-AAG-01). */
  private renderScalarArray(field: SettingsField): TemplateResult {
    const rows = Array.isArray(settingValue(this.draft, field))
      ? (settingValue(this.draft, field) as unknown[])
      : [];
    const atLimit = field.maximum !== null && rows.length >= field.maximum;
    const choices = entityChoices(this.hass?.states, field.items);
    return html`
      <datalist id=${`tabula-choices-${field.key}`}>
        ${choices.map((entityId) => html`<option value=${entityId}></option>`)}
      </datalist>
      <div class="rows">
        ${rows.map(
          (row, index) => html`
            <div class="row" data-row=${index}>
              <input
                type="text"
                list=${`tabula-choices-${field.key}`}
                aria-label=${`${labelFor(field)} ${index + 1}`}
                placeholder=${STRINGS.settings.entityPlaceholder}
                .value=${textValue(row)}
                @input=${(event: Event) =>
                  this.setRowValue(field, index, (event.target as HTMLInputElement).value)}
              />
              ${this.renderRowButtons(field, rows, index)}
            </div>
          `,
        )}
      </div>
      <button
        type="button"
        class="add"
        ?disabled=${atLimit}
        @click=${() => this.addRow(field, rows)}
      >
        ${STRINGS.settings.addRow}
      </button>
    `;
  }

  /** The schedule's slots: a row per slot, its properties from the schema. */
  private renderObjectArray(field: SettingsField): TemplateResult {
    const items = field.items;
    if (items === null) {
      return html``;
    }
    const value = settingValue(this.draft, field);
    const rows = (Array.isArray(value) ? value : []) as Record<string, unknown>[];
    const atLimit = field.maximum !== null && rows.length >= field.maximum;
    return html`
      <div class="rows">
        ${rows.map(
          (row, index) => html`
            <div class="row slot" data-row=${index}>
              ${items.properties.map((property) =>
                this.renderItemProperty(field, rows, index, property, row[property.name]),
              )}
              ${this.renderRowButtons(field, rows, index)}
            </div>
          `,
        )}
      </div>
      <button
        type="button"
        class="add"
        ?disabled=${atLimit}
        @click=${() => this.addRow(field, rows, items)}
      >
        ${STRINGS.settings.addRow}
      </button>
    `;
  }

  private renderItemProperty(
    field: SettingsField,
    rows: Record<string, unknown>[],
    index: number,
    property: SettingsItemProperty,
    value: unknown,
  ): TemplateResult {
    const label = titleFor(property.name);
    const change = (next: unknown): void => {
      const row = { ...rows[index], [property.name]: next };
      this.setRow(field, rows, index, row);
    };
    if (property.enumValues !== null) {
      return html`<label class="slot-field">
        <span>${label}</span>
        <select
          .value=${textValue(value)}
          @change=${(event: Event) => change((event.target as HTMLSelectElement).value)}
        >
          ${value === undefined || value === null ? html`<option value=""></option>` : nothing}
          ${property.enumValues.map(
            (option) =>
              html`<option value=${option} ?selected=${option === value}>
                ${titleFor(option)}
              </option>`,
          )}
        </select>
      </label>`;
    }
    if (property.type === "boolean") {
      const state = value === null ? "inherit" : value ? "on" : "off";
      return html`<label class="slot-field">
        <span>${label}</span>
        <select
          .value=${state}
          @change=${(event: Event) => {
            const picked = (event.target as HTMLSelectElement).value;
            change(picked === "inherit" ? null : picked === "on");
          }}
        >
          ${property.nullable ? html`<option value="inherit">${STRINGS.settings.inherit}</option>` : nothing}
          <option value="on">${STRINGS.settings.on}</option>
          <option value="off">${STRINGS.settings.off}</option>
        </select>
      </label>`;
    }
    if (property.nullable) {
      return html`<label class="slot-field">
        <span>${label}</span>
        <span class="inherit-row">
          <input
            type="checkbox"
            .checked=${value === null}
            @change=${(event: Event) =>
              change((event.target as HTMLInputElement).checked ? null : (property.minimum ?? 0))}
          />
          <span>${STRINGS.settings.inherit}</span>
          ${
            value === null
              ? nothing
              : html`<input
                  type="number"
                  min=${property.minimum ?? nothing}
                  max=${property.maximum ?? nothing}
                  .value=${numberValue(value)}
                  @input=${(event: Event) => change(numberFrom(event))}
                />`
          }
        </span>
      </label>`;
    }
    return html`<label class="slot-field">
      <span>${label}</span>
      <input
        type="text"
        maxlength=${property.maxLength ?? nothing}
        placeholder=${property.pattern?.includes(":") ? "HH:MM" : nothing}
        .value=${textValue(value)}
        @input=${(event: Event) => change((event.target as HTMLInputElement).value)}
      />
    </label>`;
  }

  private renderRowButtons(field: SettingsField, rows: unknown[], index: number): TemplateResult {
    return html`
      <span class="row-actions">
        <button
          type="button"
          ?disabled=${index === 0}
          aria-label=${STRINGS.settings.moveUpLabel(index)}
          title=${STRINGS.settings.moveUp}
          @click=${() => this.moveRow(field, rows, index, -1)}
        >
          ↑
        </button>
        <button
          type="button"
          ?disabled=${index === rows.length - 1}
          aria-label=${STRINGS.settings.moveDownLabel(index)}
          title=${STRINGS.settings.moveDown}
          @click=${() => this.moveRow(field, rows, index, 1)}
        >
          ↓
        </button>
        <button
          type="button"
          aria-label=${STRINGS.settings.removeRowLabel(index)}
          title=${STRINGS.settings.removeRow}
          @click=${() => this.removeRow(field, rows, index)}
        >
          ✕
        </button>
      </span>
    `;
  }

  private addRow(field: SettingsField, rows: unknown[], items = field.items): void {
    const row = items !== null && items.type === "object" ? newItem(items) : "";
    this.setValue(field, [...rows, row]);
  }

  private removeRow(field: SettingsField, rows: unknown[], index: number): void {
    this.setValue(
      field,
      rows.filter((_, position) => position !== index),
    );
  }

  private moveRow(field: SettingsField, rows: unknown[], index: number, by: number): void {
    const next = [...rows];
    const target = index + by;
    if (target < 0 || target >= next.length) {
      return;
    }
    [next[index], next[target]] = [next[target], next[index]];
    this.setValue(field, next);
  }

  private setRowValue(field: SettingsField, index: number, value: string): void {
    const rows = Array.isArray(settingValue(this.draft, field))
      ? [...(settingValue(this.draft, field) as unknown[])]
      : [];
    rows[index] = value;
    this.setValue(field, rows);
  }

  private setRow(
    field: SettingsField,
    rows: Record<string, unknown>[],
    index: number,
    row: Record<string, unknown>,
  ): void {
    // The rows shown are sorted for display; write back in the same order, and
    // let saveValues sort again when the list is submitted (schema order).
    const next = [...rows];
    next[index] = row;
    this.setValue(field, next);
  }
}

/** A number input's value; a blank or unparsable input writes 0, which the
 * client check then flags when 0 is out of the schema's range. */
function numberFrom(event: Event): number {
  const raw = (event.target as HTMLInputElement).value;
  const parsed = Number(raw);
  return raw.trim() === "" || Number.isNaN(parsed) ? 0 : parsed;
}

if (!customElements.get(SETTINGS_EDITOR_TAG)) {
  customElements.define(SETTINGS_EDITOR_TAG, TabulaSettingsEditor);
}
