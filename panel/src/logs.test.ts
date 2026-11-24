import { describe, expect, it } from "vitest";
import "./logs";
import {
  LOG_STRINGS,
  LOG_VIEW_MAX_LINES,
  boundLines,
  filterLines,
  levelRank,
  lineClass,
  logText,
  tagsOf,
  type TabulaLogs,
} from "./logs";
import { device } from "./test-helpers";
import type { PanelLogLine, PanelLogs } from "./types";

function logLine(overrides: Partial<PanelLogLine> = {}): PanelLogLine {
  return {
    ts: "2026-11-24T20:15:30Z",
    level: "info",
    tag: "app",
    message: "registered with HA",
    ...overrides,
  };
}

function snapshot(overrides: Partial<PanelLogs> = {}): PanelLogs {
  return {
    device_id: "11111111-2222-3333-4444-555555555555",
    connected: true,
    last_seen: null,
    capacity: 2000,
    lines: [logLine({ message: "first" }), logLine({ message: "second", level: "warning" })],
    subscription: { active: false, viewers: 0, level: null, expires_at: null },
    ...overrides,
  };
}

const ALL = { levels: [] as const, tag: null, query: "" };

describe("the log helpers", () => {
  it("ranks the levels weakest first and treats an unknown one as lowest", () => {
    expect(levelRank("debug")).toBeLessThan(levelRank("info"));
    expect(levelRank("info")).toBeLessThan(levelRank("warning"));
    expect(levelRank("warning")).toBeLessThan(levelRank("error"));
    expect(levelRank("chatty")).toBe(-1);
  });

  it("filters by level, tag and text, oldest first by default", () => {
    const lines = [
      logLine({ message: "alpha" }),
      logLine({ message: "beta", level: "error", tag: "console" }),
      logLine({ message: "gamma" }),
    ];
    expect(filterLines(lines, ALL, false).map((line) => line.message)).toEqual([
      "alpha",
      "beta",
      "gamma",
    ]);
    expect(filterLines(lines, { ...ALL, levels: ["error"] }, false)).toHaveLength(1);
    expect(filterLines(lines, { ...ALL, tag: "console" }, false)).toHaveLength(1);
    expect(filterLines(lines, { ...ALL, query: "AM" }, false).map((line) => line.message)).toEqual([
      "gamma",
    ]);
  });

  it("reverses for the newest-first view without touching the buffer", () => {
    const lines = [logLine({ message: "one" }), logLine({ message: "two" })];
    expect(filterLines(lines, ALL, true).map((line) => line.message)).toEqual(["two", "one"]);
    expect(lines.map((line) => line.message)).toEqual(["one", "two"]);
  });

  it("bounds the view and drops the oldest rows", () => {
    const lines = Array.from({ length: LOG_VIEW_MAX_LINES + 10 }, (_, index) =>
      logLine({ message: `line ${index}` }),
    );
    const bounded = boundLines(lines);
    expect(bounded).toHaveLength(LOG_VIEW_MAX_LINES);
    expect(bounded[0].message).toBe("line 10");
    expect(bounded.at(-1)?.message).toBe(`line ${LOG_VIEW_MAX_LINES + 9}`);
  });

  it("lists the tags in the buffer once each", () => {
    expect(tagsOf([logLine({ tag: "app" }), logLine({ tag: "console" }), logLine()])).toEqual([
      "app",
      "console",
    ]);
  });

  it("marks console lines apart from the app's", () => {
    expect(lineClass(logLine({ tag: "console", level: "error" }))).toBe(
      "log-line level-error console",
    );
    expect(lineClass(logLine())).toBe("log-line level-info");
  });

  it("downloads the whole buffer, oldest first, whatever the view shows", () => {
    const text = logText([logLine({ message: "one" }), logLine({ message: "two" })], "Office");
    expect(text.split("\n")[0]).toContain("Office");
    expect(text.indexOf("one")).toBeLessThan(text.indexOf("two"));
    expect(text).toContain("INFO app: one");
  });
});

async function mountLogs(
  hass: unknown,
  overrides: Partial<PanelLogs> = {},
  deviceOverrides: Partial<ReturnType<typeof device>> = {},
) {
  const element = document.createElement("tabula-logs") as TabulaLogs;
  (element as unknown as { hass: unknown }).hass = hass;
  (element as unknown as { device: unknown }).device = device(deviceOverrides);
  document.body.append(element);
  await customElements.whenDefined("tabula-logs");
  for (let i = 0; i < 8; i += 1) {
    await Promise.resolve();
    await element.updateComplete;
  }
  return { element, snapshot: snapshot(overrides) };
}

/** A stream message, as `tabula/panel_logs_subscribe` sends it. */
type Push = {
  lines?: PanelLogLine[];
  subscription?: { active: boolean; expires_at: string | null };
};

/** A fake hass whose panel calls answer with one snapshot and a live stream. */
function fakeHass(snapshotValue: PanelLogs, pushes: Push[] = []) {
  const calls: { type: string }[] = [];
  let sink: ((message: Push) => void) | undefined;
  return {
    calls,
    /** Fire one stream message at the mounted view, as HA would. */
    push(message: Push) {
      sink?.(message);
    },
    /** How many stream commands the view has sent, one per open stream. */
    streamCount(): number {
      return calls.filter((call) => call.type === "tabula/panel_logs_subscribe").length;
    },
    callWS: async <T>(msg: { type: string }) => {
      calls.push(msg);
      return snapshotValue as T;
    },
    connection: {
      subscribeEvents: async () => async () => {},
      subscribeMessage: async <T>(callback: (message: T) => void, msg: { type: string }) => {
        calls.push(msg);
        sink = callback as (message: { lines?: PanelLogLine[] }) => void;
        for (const push of pushes) {
          callback(push as T);
        }
        return async () => {};
      },
    },
  };
}

describe("the logs view", () => {
  it("shows the buffered lines newest first and starts the stream", async () => {
    const hass = fakeHass(snapshot());
    const { element } = await mountLogs(hass);

    expect(hass.calls.map((call) => call.type)).toEqual([
      "tabula/panel_logs",
      "tabula/panel_logs_subscribe",
    ]);
    const rows = element.shadowRoot?.querySelectorAll(".log-line");
    expect(rows).toHaveLength(2);
    expect(rows?.[0].textContent).toContain("second");
  });

  it("keeps the DOM bounded when the stream runs long", async () => {
    const pushes = [
      {
        lines: Array.from({ length: LOG_VIEW_MAX_LINES + 25 }, (_, index) =>
          logLine({ message: `live ${index}` }),
        ),
      },
    ];
    const hass = fakeHass(snapshot({ lines: [] }), pushes);
    const { element } = await mountLogs(hass);

    expect(element.lines).toHaveLength(LOG_VIEW_MAX_LINES);
    expect(element.lines.at(-1)?.message).toBe(`live ${LOG_VIEW_MAX_LINES + 24}`);
  });

  it("pauses the view but keeps collecting, and releases on resume", async () => {
    const hass = fakeHass(snapshot());
    const { element } = await mountLogs(hass);

    const pause = element.shadowRoot?.querySelector<HTMLButtonElement>("[data-pause]");
    pause?.click();
    await element.updateComplete;
    expect(element.paused).toBe(true);
    expect(element.shadowRoot?.querySelector(".log-note")?.textContent).toContain("Paused");

    // A line that arrives while paused is held, not shown.
    hass.push({ lines: [logLine({ message: "while paused" })] });
    await element.updateComplete;
    expect(element.lines.map((line) => line.message)).not.toContain("while paused");

    pause?.click();
    await element.updateComplete;
    expect(element.paused).toBe(false);
    expect(element.lines.map((line) => line.message)).toContain("while paused");
  });

  it("reopens the stream after the device's drop ended it", async () => {
    const hass = fakeHass(snapshot());
    const { element } = await mountLogs(hass);
    expect(hass.streamCount()).toBe(1);

    // The device's connection ended: HA reports the subscription as off while
    // the view's own HA subscription is still open.
    hass.push({ subscription: { active: false, expires_at: null } });
    await element.updateComplete;
    expect(element.streaming).toBe(false);

    element.shadowRoot?.querySelector<HTMLButtonElement>(".log-status button")?.click();
    for (let i = 0; i < 8; i += 1) {
      await Promise.resolve();
      await element.updateComplete;
    }

    // The old stream is dropped and a new one asked for: without the drop the
    // view would still hold its unsubscribe and send nothing.
    expect(hass.streamCount()).toBe(2);
    expect(element.streaming).toBe(true);
  });

  it("clears the view without touching the buffer it was built from", async () => {
    const hass = fakeHass(snapshot());
    const { element } = await mountLogs(hass);

    element.shadowRoot?.querySelector<HTMLButtonElement>("[data-clear]")?.click();
    await element.updateComplete;

    expect(element.lines).toHaveLength(0);
    expect(element.shadowRoot?.querySelector("[data-empty]")).toBeTruthy();
  });

  it("shows an empty state and does not stream an offline device", async () => {
    const hass = fakeHass(
      snapshot({ connected: false, last_seen: "2026-11-24T20:00:00Z", lines: [] }),
    );
    const { element } = await mountLogs(hass, {}, { connected: false });

    expect(hass.calls.map((call) => call.type)).toEqual(["tabula/panel_logs"]);
    expect(element.streaming).toBe(false);
    expect(element.shadowRoot?.querySelector("[data-empty]")?.textContent).toContain(
      LOG_STRINGS.empty,
    );
  });

  it("toggles the order and filters through the toolbar", async () => {
    const hass = fakeHass(snapshot());
    const { element } = await mountLogs(hass);
    const firstRow = () => element.shadowRoot?.querySelector(".log-line")?.textContent;

    expect(firstRow()).toContain("second");
    element.shadowRoot?.querySelector<HTMLButtonElement>("[data-order]")?.click();
    await element.updateComplete;
    expect(firstRow()).toContain("first");

    element.shadowRoot?.querySelector<HTMLButtonElement>('[data-level="error"]')?.click();
    await element.updateComplete;
    expect(element.shadowRoot?.querySelectorAll(".log-line")).toHaveLength(0);
    expect(element.shadowRoot?.querySelector("[data-empty]")).toBeTruthy();
  });
});
