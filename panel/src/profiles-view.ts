import {
  LitElement,
  html,
  nothing,
  unsafeCSS,
  type PropertyValueMap,
  type TemplateResult,
} from "lit";
import styles from "./styles.css?inline";
import { formatWhen } from "./devices";
import type { SettingsField } from "./generated/settings";
import { fieldDefault, titleFor } from "./settings";
import {
  diffSummary,
  displayValue,
  driftClass,
  driftKinds,
  fieldLabel,
  groupState,
  parseProfileValue,
  profileNameProblem,
  rowsByDevice,
  selectedValues,
  shareableSections,
  toggleGroup,
  toggleKey,
  valueText,
} from "./profiles";
import { STRINGS, errorLine } from "./strings";
import type {
  DriftMember,
  HomeAssistant,
  PanelProfile,
  PanelProfiles,
  ProfileDiff,
  ProfileDiffRow,
  ProfileSaveResult,
} from "./types";

/** The editor's draft: a name, the keys it covers, and each included key's value. */
interface Draft {
  profileId?: string;
  name: string;
  included: Set<string>;
  values: Map<string, unknown>;
}

/** A saved profile's draft: its name, keys and stored values. */
function draftOf(profile?: PanelProfile): Draft {
  if (profile === undefined) {
    // A new profile covers no keys at all (F-MGT-04, ADR 0035 item 9).
    return { name: "", included: new Set<string>(), values: new Map<string, unknown>() };
  }
  return {
    profileId: profile.profile_id,
    name: profile.name,
    included: new Set(Object.keys(profile.values)),
    values: new Map(Object.entries(profile.values)),
  };
}

/** The shareable sections, built once (the schema cannot change at runtime). */
const SECTIONS = shareableSections();

/**
 * The Tabularium's Profiles section (P5.6, F-MGT-02).
 *
 * Lists profiles with their members, edits one profile's covered keys and
 * values, previews what a save would change, and reports drift. The server owns
 * every rule (ADR 0035): this view never decides what may be shared, it only
 * stops offering what the schema says cannot be.
 */
export class TabulaProfiles extends LitElement {
  static override styles = unsafeCSS(styles);

  static override properties = {
    hass: { attribute: false },
    snapshot: { state: true },
    drift: { state: true },
    error: { state: true },
    loading: { state: true },
    draft: { state: true },
    preview: { state: true },
    nameProblem: { state: true },
    rejected: { state: true },
    status: { state: true },
    busy: { state: true },
  };

  declare hass?: HomeAssistant;
  declare snapshot?: PanelProfiles;
  declare drift: DriftMember[];
  declare error?: string;
  declare loading: boolean;
  declare draft?: Draft;
  declare preview?: ProfileDiffRow[];
  declare nameProblem: string | null;
  declare rejected: { key: string; reason: string }[];
  declare status?: string;
  declare busy: boolean;

  private requested = false;

  constructor() {
    super();
    this.drift = [];
    this.loading = true;
    this.nameProblem = null;
    this.rejected = [];
    this.busy = false;
  }

  protected override updated(changed: PropertyValueMap<this>): void {
    if (changed.has("hass") && this.hass && !this.requested) {
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
      const [snapshot, drift] = await Promise.all([
        hass.callWS<PanelProfiles>({ type: "tabula/panel_profiles" }),
        hass.callWS<{ members: DriftMember[] }>({ type: "tabula/panel_drift" }),
      ]);
      this.snapshot = snapshot;
      this.drift = drift.members;
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.loading = false;
    }
  }

  private deviceNameOf(deviceId: string): string {
    const device = (this.snapshot?.devices ?? []).find((item) => item.device_id === deviceId);
    return device === undefined ? deviceId.slice(0, 8) : (device.name ?? deviceId.slice(0, 8));
  }

  private readonly startNew = (): void => {
    this.draft = draftOf();
    this.preview = undefined;
    this.nameProblem = null;
    this.rejected = [];
    this.status = undefined;
  };

  private readonly startEdit = (profile: PanelProfile): void => {
    this.draft = draftOf(profile);
    this.preview = undefined;
    this.nameProblem = null;
    this.rejected = [];
    this.status = undefined;
  };

  private readonly cancel = (): void => {
    this.draft = undefined;
    this.preview = undefined;
    this.nameProblem = null;
    this.rejected = [];
  };

  private readonly onName = (event: Event): void => {
    if (this.draft === undefined) {
      return;
    }
    this.draft = { ...this.draft, name: (event.target as HTMLInputElement).value };
  };

  private readonly onKey = (key: string, checked: boolean): void => {
    if (this.draft === undefined) {
      return;
    }
    this.draft = { ...this.draft, included: toggleKey(this.draft.included, key, checked) };
  };

  private readonly onGroup = (fields: readonly SettingsField[], checked: boolean): void => {
    if (this.draft === undefined) {
      return;
    }
    this.draft = { ...this.draft, included: toggleGroup(fields, this.draft.included, checked) };
  };

  private readonly onValue = (field: SettingsField, raw: string): void => {
    if (this.draft === undefined) {
      return;
    }
    const parsed = parseProfileValue(field, raw);
    const values = new Map(this.draft.values);
    if (parsed.problem !== undefined) {
      // Keep the raw text so the input does not fight the user; the problem is
      // reported next to the key and the save refuses it.
      values.set(field.key, raw);
    } else {
      values.set(field.key, parsed.value);
    }
    this.draft = { ...this.draft, values };
  };

  private problems(): { key: string; reason: string }[] {
    const draft = this.draft;
    if (draft === undefined) {
      return [];
    }
    const out: { key: string; reason: string }[] = [];
    for (const field of SECTIONS.flatMap((section) => [
      ...section.fields,
      ...section.groups.flatMap((group) => group.fields),
    ])) {
      if (!draft.included.has(field.key)) {
        continue;
      }
      const value = draft.values.get(field.key) ?? fieldDefault(field);
      if (value === undefined) {
        continue;
      }
      const problem = parseProfileValue(field, valueText(value)).problem;
      if (problem !== undefined) {
        out.push({ key: field.key, reason: problem });
      }
    }
    return out;
  }

  private typedValues(): Record<string, unknown> {
    return selectedValues(this.draft?.included ?? new Set(), this.draft?.values ?? new Map());
  }

  /** The preview: the draft's values against the members, from the server. */
  private readonly showPreview = async (): Promise<void> => {
    const hass = this.hass;
    const draft = this.draft;
    if (!hass || !draft) {
      return;
    }
    this.error = undefined;
    this.busy = true;
    try {
      const diff = await hass.callWS<ProfileDiff>({
        type: "tabula/panel_profile_diff",
        ...(draft.profileId === undefined ? {} : { profile_id: draft.profileId }),
        values: this.typedValues(),
      });
      this.preview = diff.rows;
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.busy = false;
    }
  };

  private readonly save = async (): Promise<void> => {
    const hass = this.hass;
    const draft = this.draft;
    if (!hass || !draft) {
      return;
    }
    this.nameProblem = profileNameProblem(
      draft.name,
      this.snapshot?.profiles ?? [],
      draft.profileId,
    );
    if (this.nameProblem !== null) {
      return;
    }
    this.error = undefined;
    this.busy = true;
    try {
      const result = await hass.callWS<ProfileSaveResult>({
        type: "tabula/panel_profile_save",
        name: draft.name,
        ...(draft.profileId === undefined ? {} : { profile_id: draft.profileId }),
        values: this.typedValues(),
      });
      this.rejected = result.rejected;
      const applied = result.applied.filter((item) => item.revision !== null);
      const revision = applied.length
        ? Math.max(...applied.map((item) => item.revision ?? 0))
        : null;
      this.status = STRINGS.profiles.saved(applied.length, revision);
      this.draft = undefined;
      this.preview = undefined;
      await this.load();
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.busy = false;
    }
  };

  private readonly sync = async (profile: PanelProfile): Promise<void> => {
    const hass = this.hass;
    if (!hass) {
      return;
    }
    this.error = undefined;
    this.busy = true;
    try {
      const result = await hass.callWS<{ applied: { revision: number | null }[] }>({
        type: "tabula/panel_profile_sync",
        profile_id: profile.profile_id,
      });
      const updated = result.applied.filter((item) => item.revision !== null).length;
      this.status = STRINGS.profiles.syncDone(updated, result.applied.length - updated);
      await this.load();
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.busy = false;
    }
  };

  private readonly removeProfile = async (profile: PanelProfile): Promise<void> => {
    const hass = this.hass;
    if (!hass || !window.confirm(STRINGS.profiles.confirmDelete(profile.name))) {
      return;
    }
    this.error = undefined;
    this.busy = true;
    try {
      await hass.callWS({
        type: "tabula/panel_profile_delete",
        profile_id: profile.profile_id,
      });
      if (this.draft?.profileId === profile.profile_id) {
        this.draft = undefined;
      }
      this.status = STRINGS.profiles.deleteNote;
      await this.load();
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.busy = false;
    }
  };

  protected override render(): TemplateResult {
    return html`
      <section class="route profiles">
        <div class="toolbar">
          <h2>${STRINGS.profiles.title}</h2>
          <button type="button" class="primary" @click=${this.startNew}>
            ${STRINGS.profiles.newProfile}
          </button>
        </div>
        <p class="meta">${STRINGS.profiles.subtitle}</p>
        ${
          this.error
            ? html`<div class="error" role="alert">
                <strong>${STRINGS.profiles.loadError}</strong><span>${this.error}</span>
                <button type="button" @click=${this.load}>${STRINGS.profiles.retry}</button>
              </div>`
            : nothing
        }
        ${this.status ? html`<p class="status-line" role="status">${this.status}</p>` : nothing}
        ${this.loading ? html`<p>${STRINGS.profiles.loading}</p>` : this.renderList()}
        ${this.draft === undefined ? nothing : this.renderEditor()} ${this.renderDrift()}
      </section>
    `;
  }

  private renderList(): TemplateResult {
    const profiles = this.snapshot?.profiles ?? [];
    if (profiles.length === 0) {
      return html`<p class="empty-note">${STRINGS.profiles.empty}</p>`;
    }
    return html`
      <table>
        <thead>
          <tr>
            <th>${STRINGS.profiles.columnName}</th>
            <th>${STRINGS.profiles.columnMembers}</th>
            <th>${STRINGS.profiles.columnActions}</th>
          </tr>
        </thead>
        <tbody>
          ${profiles.map(
            (profile) => html`
              <tr data-profile=${profile.profile_id}>
                <td>
                  <strong>${profile.name}</strong>
                  ${
                    profile.builtin
                      ? html`<span class="badge builtin">${STRINGS.profiles.builtin}</span>`
                      : nothing
                  }
                  ${
                    profile.builtin
                      ? html`<p class="note">${STRINGS.profiles.builtinNote}</p>`
                      : html`<p class="note">
                          ${STRINGS.profiles.keyCount(Object.keys(profile.values).length)}
                        </p>`
                  }
                </td>
                <td>
                  ${
                    profile.member_count === 0
                      ? STRINGS.profiles.noMembers
                      : profile.members.map((id) => this.deviceNameOf(id)).join(", ")
                  }
                </td>
                <td class="actions">
                  ${
                    profile.builtin
                      ? nothing
                      : html`
                          <button type="button" @click=${() => this.startEdit(profile)}>
                            ${STRINGS.profiles.edit}
                          </button>
                          <button
                            type="button"
                            ?disabled=${this.busy}
                            @click=${() => this.sync(profile)}
                          >
                            ${STRINGS.profiles.sync}
                          </button>
                          <button
                            type="button"
                            ?disabled=${this.busy}
                            @click=${() => this.removeProfile(profile)}
                          >
                            ${STRINGS.profiles.delete}
                          </button>
                        `
                  }
                </td>
              </tr>
            `,
          )}
        </tbody>
      </table>
      <p class="note">${STRINGS.profiles.deleteNote}</p>
    `;
  }

  private renderEditor(): TemplateResult {
    const draft = this.draft as Draft;
    const problems = this.problems();
    return html`
      <section class="profile-editor" data-profile-editor>
        <h3>
          ${
            draft.profileId === undefined
              ? STRINGS.profiles.editorTitle
              : STRINGS.profiles.editorTitleEdit(draft.name)
          }
        </h3>
        <label class="profile-name">
          <span>${STRINGS.profiles.name}</span>
          <input
            type="text"
            .value=${draft.name}
            placeholder=${STRINGS.profiles.namePlaceholder}
            @input=${this.onName}
          />
        </label>
        ${this.nameProblem ? html`<p class="problem">${this.nameProblem}</p>` : nothing}
        <p class="note">${STRINGS.profiles.keysNote}</p>
        ${SECTIONS.map((section) => {
          const fields = [...section.fields, ...section.groups.flatMap((group) => group.fields)];
          const state = groupState(fields, draft.included);
          return html`
            <details class="settings-section" data-section=${section.id} open>
              <summary>
                <label class="group-toggle">
                  <input
                    type="checkbox"
                    .checked=${state === "all"}
                    .indeterminate=${state === "some"}
                    @change=${(event: Event) =>
                      this.onGroup(fields, (event.target as HTMLInputElement).checked)}
                  />
                  <strong>${section.title}</strong>
                </label>
              </summary>
              ${section.fields.map((field) => this.renderKey(field))}
              ${section.groups.map(
                (group) => html`
                  <div class="settings-group" data-group=${group.id}>
                    <h5>${group.title}</h5>
                    ${group.fields.map((field) => this.renderKey(field))}
                  </div>
                `,
              )}
            </details>
          `;
        })}
        ${
          draft.included.size === 0
            ? html`<p class="empty-note">${STRINGS.profiles.noKeys}</p>`
            : nothing
        }
        ${
          problems.length
            ? html`<p class="problem" role="alert">${problems[0].reason}</p>`
            : nothing
        }
        <div class="actions">
          <button type="button" class="primary" ?disabled=${this.busy} @click=${this.save}>
            ${this.busy ? STRINGS.profiles.saving : STRINGS.profiles.save}
          </button>
          <button type="button" ?disabled=${this.busy} @click=${this.showPreview}>
            ${STRINGS.profiles.preview}
          </button>
          <button type="button" @click=${this.cancel}>${STRINGS.profiles.cancel}</button>
        </div>
        ${
          this.rejected.length
            ? html`<div class="save-summary" role="status">
                <p class="rejected-count">${STRINGS.profiles.rejected(this.rejected.length)}</p>
                <ul class="rejected-list">
                  ${this.rejected.map(
                    (item) => html`<li><code>${item.key}</code>: ${item.reason}</li>`,
                  )}
                </ul>
              </div>`
            : nothing
        }
        ${this.renderPreview()}
      </section>
    `;
  }

  private renderKey(field: SettingsField): TemplateResult {
    const draft = this.draft as Draft;
    const included = draft.included.has(field.key);
    const value = draft.values.get(field.key) ?? fieldDefault(field);
    const options = field.enumValues;
    return html`
      <div class="profile-key" data-key=${field.key}>
        <label>
          <input
            type="checkbox"
            .checked=${included}
            @change=${(event: Event) =>
              this.onKey(field.key, (event.target as HTMLInputElement).checked)}
          />
          <span>${fieldLabel(field)}</span>
        </label>
        ${
          included
            ? options !== null
              ? html`<select
                  .value=${valueText(value)}
                  @change=${(event: Event) =>
                    this.onValue(field, (event.target as HTMLSelectElement).value)}
                >
                  ${options.map(
                    (option) =>
                      html`<option value=${option} ?selected=${option === value}>
                        ${option}
                      </option>`,
                  )}
                </select>`
              : field.type === "boolean"
                ? html`<input
                    type="checkbox"
                    .checked=${value === true}
                    @change=${(event: Event) =>
                      this.onValue(
                        field,
                        (event.target as HTMLInputElement).checked ? "true" : "false",
                      )}
                  />`
                : field.type === "array"
                  ? html`<textarea
                      rows="2"
                      .value=${valueText(value)}
                      @change=${(event: Event) =>
                        this.onValue(field, (event.target as HTMLTextAreaElement).value)}
                    ></textarea>`
                  : html`<input
                      type=${field.type === "integer" ? "number" : "text"}
                      .value=${valueText(value)}
                      @change=${(event: Event) =>
                        this.onValue(field, (event.target as HTMLInputElement).value)}
                    />`
            : nothing
        }
      </div>
    `;
  }

  private renderPreview(): TemplateResult | typeof nothing {
    const rows = this.preview;
    if (rows === undefined) {
      return nothing;
    }
    const summary = diffSummary(rows);
    const grouped = rowsByDevice(rows);
    return html`
      <div class="profile-preview" data-preview>
        <h4>${STRINGS.profiles.previewTitle}</h4>
        ${
          rows.length === 0 || (summary.changing === 0 && summary.overridden === 0)
            ? html`<p class="note">${STRINGS.profiles.previewNone}</p>`
            : html`<p class="note">
                ${STRINGS.profiles.previewLine(summary.changing, summary.overridden)}
              </p>`
        }
        ${[...grouped].map(
          ([deviceId, deviceRows]) => html`
            <div class="preview-device">
              <h5>${this.deviceNameOf(deviceId)}</h5>
              <table>
                <thead>
                  <tr>
                    <th>${STRINGS.profiles.columnKey}</th>
                    <th>${STRINGS.profiles.columnProfileValue}</th>
                    <th>${STRINGS.profiles.columnDeviceValue}</th>
                    <th>${STRINGS.profiles.columnChange}</th>
                  </tr>
                </thead>
                <tbody>
                  ${deviceRows.map(
                    (row) => html`
                      <tr data-key=${row.key} class=${row.changes ? "changes" : ""}>
                        <td>${titleFor(row.key.replace(/_/g, "."))}</td>
                        <td>${displayValue(row.effective_value)}</td>
                        <td>${displayValue(row.device_value, row.device_unset)}</td>
                        <td>
                          ${
                            row.overridden
                              ? STRINGS.profiles.overridden
                              : row.changes
                                ? STRINGS.profiles.willChange
                                : STRINGS.profiles.unchanged
                          }
                        </td>
                      </tr>
                    `,
                  )}
                </tbody>
              </table>
            </div>
          `,
        )}
      </div>
    `;
  }

  private renderDrift(): TemplateResult {
    const members = this.drift;
    return html`
      <section class="drift" data-drift>
        <h3>${STRINGS.profiles.driftTitle}</h3>
        <p class="note">${STRINGS.profiles.driftNote}</p>
        ${
          members.length === 0
            ? html`<p class="note">${STRINGS.profiles.driftNone}</p>`
            : html`<table>
                <thead>
                  <tr>
                    <th>${STRINGS.profiles.driftDevice}</th>
                    <th>${STRINGS.profiles.driftProfile}</th>
                    <th>${STRINGS.profiles.driftState}</th>
                    <th>${STRINGS.profiles.driftWhy}</th>
                  </tr>
                </thead>
                <tbody>
                  ${members.map(
                    (member) => html`
                      <tr data-device=${member.device_id} class=${driftClass(member)}>
                        <td>
                          ${member.name ?? member.device_id.slice(0, 8)}
                          <span class="note">
                            ${
                              member.connected
                                ? STRINGS.online
                                : `${STRINGS.offline}${member.last_seen ? ` · ${formatWhen(member.last_seen)}` : ""}`
                            }
                          </span>
                        </td>
                        <td>${member.profile_name}</td>
                        <td>
                          ${
                            member.device_revision === null
                              ? STRINGS.profiles.driftNeverReported
                              : `${member.device_revision} / ${member.ha_revision}`
                          }
                        </td>
                        <td>
                          ${driftKinds(member).map(
                            (kind) => html`<span class="badge drift">${kind}</span>`,
                          )}
                        </td>
                      </tr>
                    `,
                  )}
                </tbody>
              </table>`
        }
      </section>
    `;
  }
}

/**
 * The device page's assignment control (P5.6 step 6).
 *
 * Picks the profile the tablet belongs to, syncs it on demand, and lists the
 * keys this device overrides so one click puts the profile's value back
 * (ADR 0035 item 4).
 */
export class TabulaProfileAssign extends LitElement {
  static override styles = unsafeCSS(styles);

  static override properties = {
    hass: { attribute: false },
    deviceId: { attribute: false },
    snapshot: { state: true },
    error: { state: true },
    status: { state: true },
    busy: { state: true },
  };

  declare hass?: HomeAssistant;
  declare deviceId?: string;
  declare snapshot?: PanelProfiles;
  declare error?: string;
  declare status?: string;
  declare busy: boolean;

  private requested = false;

  constructor() {
    super();
    this.busy = false;
  }

  protected override updated(changed: PropertyValueMap<this>): void {
    if (changed.has("hass") || changed.has("deviceId")) {
      if (this.hass && this.deviceId && (!this.requested || changed.has("deviceId"))) {
        this.requested = true;
        void this.load();
      }
    }
  }

  private async load(): Promise<void> {
    const hass = this.hass;
    if (!hass) {
      return;
    }
    this.error = undefined;
    try {
      this.snapshot = await hass.callWS<PanelProfiles>({ type: "tabula/panel_profiles" });
    } catch (reason) {
      this.error = errorLine(reason);
    }
  }

  private get device() {
    return (this.snapshot?.devices ?? []).find((item) => item.device_id === this.deviceId);
  }

  private get profile(): PanelProfile | undefined {
    const assigned = this.device?.profile_id;
    return (this.snapshot?.profiles ?? []).find((item) => item.profile_id === assigned);
  }

  private readonly assign = async (event: Event): Promise<void> => {
    const hass = this.hass;
    const deviceId = this.deviceId;
    if (!hass || !deviceId) {
      return;
    }
    const profileId = (event.target as HTMLSelectElement).value;
    this.error = undefined;
    this.busy = true;
    try {
      const result = await hass.callWS<{ applied: { applied: string[] } }>({
        type: "tabula/panel_profile_assign",
        device_id: deviceId,
        profile_id: profileId,
      });
      this.status = STRINGS.profileAssign.changed(result.applied.applied.length);
      await this.load();
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.busy = false;
    }
  };

  private readonly sync = async (): Promise<void> => {
    const hass = this.hass;
    const deviceId = this.deviceId;
    const profile = this.profile;
    if (!hass || !deviceId || !profile) {
      return;
    }
    this.error = undefined;
    this.busy = true;
    try {
      const result = await hass.callWS<{ applied: { applied: string[] }[] }>({
        type: "tabula/panel_profile_sync",
        profile_id: profile.profile_id,
        device_id: deviceId,
      });
      const applied = result.applied[0]?.applied.length ?? 0;
      this.status = STRINGS.profileAssign.changed(applied);
      await this.load();
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.busy = false;
    }
  };

  private readonly clear = async (key: string): Promise<void> => {
    const hass = this.hass;
    const deviceId = this.deviceId;
    if (!hass || !deviceId) {
      return;
    }
    this.error = undefined;
    this.busy = true;
    try {
      const result = await hass.callWS<{ applied: { applied: string[] } }>({
        type: "tabula/panel_profile_override",
        device_id: deviceId,
        key,
        value: null,
      });
      this.status = STRINGS.profileAssign.changed(result.applied.applied.length);
      await this.load();
    } catch (reason) {
      this.error = errorLine(reason);
    } finally {
      this.busy = false;
    }
  };

  protected override render(): TemplateResult {
    const { profileAssign } = STRINGS;
    const device = this.device;
    const overrides = Object.entries(device?.overrides ?? {});
    if (this.snapshot?.devices === undefined || this.snapshot.profiles === undefined) {
      return html`<section class="profile-assign">
        <h3>${profileAssign.title}</h3>
        <p class="note">${this.error ?? profileAssign.loading}</p>
      </section>`;
    }
    const profile = this.profile;
    return html`
      <section class="profile-assign" data-profile-assign>
        <h3>${profileAssign.title}</h3>
        ${this.error ? html`<p class="problem" role="alert">${this.error}</p>` : nothing}
        <div class="profile-controls">
          <select .value=${profile?.profile_id ?? "empty"} @change=${this.assign}>
            ${(this.snapshot.profiles ?? []).map(
              (item) =>
                html`<option
                  value=${item.profile_id}
                  ?selected=${item.profile_id === profile?.profile_id}
                >
                  ${item.name}
                </option>`,
            )}
          </select>
          <button
            type="button"
            ?disabled=${this.busy || profile === undefined || profile.builtin}
            @click=${this.sync}
          >
            ${profileAssign.sync}
          </button>
        </div>
        <p class="note">
          ${
            profile === undefined || profile.builtin || Object.keys(profile.values).length === 0
              ? profileAssign.none
              : STRINGS.profileAssign.sharedKeys(Object.keys(profile.values).length)
          }
        </p>
        ${
          overrides.length
            ? html`<div class="profile-overrides">
                <h4>${profileAssign.overrides}</h4>
                <ul>
                  ${overrides.map(
                    ([key, value]) =>
                      html`<li>
                        <code>${key}</code>: ${displayValue(value)}
                        <button
                          type="button"
                          ?disabled=${this.busy}
                          @click=${() => this.clear(key)}
                        >
                          ${profileAssign.clear}
                        </button>
                      </li>`,
                  )}
                </ul>
                <p class="note">${profileAssign.clearNote}</p>
              </div>`
            : nothing
        }
        ${this.status ? html`<p class="status-line" role="status">${this.status}</p>` : nothing}
      </section>
    `;
  }
}

if (!customElements.get("tabula-profiles")) {
  customElements.define("tabula-profiles", TabulaProfiles);
}
if (!customElements.get("tabula-profile-assign")) {
  customElements.define("tabula-profile-assign", TabulaProfileAssign);
}

declare global {
  interface HTMLElementTagNameMap {
    "tabula-profiles": TabulaProfiles;
    "tabula-profile-assign": TabulaProfileAssign;
  }
}
