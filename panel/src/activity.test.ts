import { afterEach, describe, expect, it, vi } from "vitest";
import {
  PRESS_PENDING_TIMEOUT_S,
  activityEntities,
  captionFor,
  captureView,
  commandGroups,
  commandKind,
  disabledReason,
  type CommandButton,
} from "./activity";
import {
  activityDevice,
  device,
  fakeCallService,
  fakeHass,
  mountActivity,
  settle,
  statesFrom,
  updateHass,
  type ServiceCall,
} from "./test-helpers";
import type { HomeAssistant } from "./types";

const IMAGE = "image.office_kiosk_screenshot";
const CAPTURE = "button.office_kiosk_capture_screenshot";
const LAST_CAPTURE = "sensor.office_kiosk_last_capture";
const CAPTURED_AT = "2026-11-24T10:00:00+00:00";
const LATER = "2026-11-24T10:05:00+00:00";

afterEach(() => {
  document.body.replaceChildren();
  vi.useRealTimers();
});

/** A fake hass with the given live states and a recording service call. */
function hassWith(
  states: Record<string, string | null> = { [IMAGE]: CAPTURED_AT, [LAST_CAPTURE]: CAPTURED_AT },
  calls: ReturnType<typeof fakeCallService> = fakeCallService(),
  pictures: Record<string, string> = {},
): { hass: HomeAssistant; calls: ServiceCall[] } {
  return {
    hass: {
      ...fakeHass(async () => []),
      states: statesFrom(states, pictures),
      callService: calls.callService,
    },
    calls: calls.calls,
  };
}

function button(
  element: { shadowRoot: ShadowRoot | null },
  selector: string,
): HTMLButtonElement | null {
  return element.shadowRoot?.querySelector<HTMLButtonElement>(selector) ?? null;
}

function text(element: { shadowRoot: ShadowRoot | null }): string {
  return element.shadowRoot?.textContent ?? "";
}

/** A promise a test settles by hand, to hold a press open. */
function deferred(): { promise: Promise<void>; resolve: () => void; reject: (r: Error) => void } {
  let resolve: () => void = () => undefined;
  let reject: (r: Error) => void = () => undefined;
  const promise = new Promise<void>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

// --- the payload's ids -------------------------------------------------------

describe("activityEntities", () => {
  it("reads the screenshot entities and the command buttons out of the payload", () => {
    const entities = activityEntities(activityDevice());
    expect(entities.imageId).toBe(IMAGE);
    expect(entities.captureId).toBe(CAPTURE);
    expect(entities.lastCaptureId).toBe(LAST_CAPTURE);
    expect(entities.commands.map((command) => command.kind).sort()).toEqual([
      "clear_cache",
      "go_home",
      "reload",
      "restart_app",
    ]);
    // The capture button is the screenshot's, not a command in the list.
    expect(entities.commands.some((command) => command.entityId === CAPTURE)).toBe(false);
  });

  it("has no screenshot ids for a tablet without the capability", () => {
    const entities = activityEntities(
      device({
        capabilities: ["screen"],
        entities: {
          button: [
            {
              entity_id: "button.office_kiosk_reload_dashboard",
              name: "Reload dashboard",
              state: "unknown",
            },
          ],
          sensor: [{ entity_id: "sensor.office_kiosk_battery", name: "Battery", state: "87" }],
        },
      }),
    );
    expect(entities.imageId).toBeNull();
    expect(entities.captureId).toBeNull();
    expect(entities.lastCaptureId).toBeNull();
    expect(entities.commands).toHaveLength(1);
  });

  it("classifies buttons by their key suffix, and anything else as other", () => {
    expect(commandKind("button.kiosk_restart_app")).toBe("restart_app");
    expect(commandKind("button.kiosk_clear_cache")).toBe("clear_cache");
    expect(commandKind("button.kiosk_go_to_start_page")).toBe("go_home");
    expect(commandKind("button.kiosk_reload_dashboard")).toBe("reload");
    expect(commandKind("button.kiosk_something_else")).toBe("other");
    // The registry can rename an entity: the translated name still says what it does.
    expect(commandKind("button.kiosk_thing", "Reload dashboard")).toBe("reload");
    expect(commandKind("button.kiosk_thing", "Go to start page")).toBe("go_home");
  });
});

describe("commandGroups", () => {
  it("separates the configuration actions from the benign ones", () => {
    const commands: CommandButton[] = [
      { entityId: "a", kind: "reload", name: "Reload dashboard" },
      { entityId: "b", kind: "restart_app", name: "Restart app" },
      { entityId: "c", kind: "clear_cache", name: "Clear cache" },
      { entityId: "d", kind: "go_home", name: "Go to start page" },
    ];
    const groups = commandGroups(commands);
    expect(groups.benign.map((command) => command.kind)).toEqual(["reload", "go_home"]);
    expect(groups.configuration.map((command) => command.kind)).toEqual([
      "restart_app",
      "clear_cache",
    ]);
  });
});

// --- the screenshot area -----------------------------------------------------

describe("captureView", () => {
  const entities = activityEntities(activityDevice());

  it("shows the image and the last capture's time", () => {
    const view = captureView(
      activityDevice(),
      entities,
      statesFrom({ [IMAGE]: CAPTURED_AT, [LAST_CAPTURE]: CAPTURED_AT }),
    );
    expect(view.kind).toBe("image");
    expect(view.lastCapture).toBe(CAPTURED_AT);
    expect(view.picture).toBe(`/api/image_proxy/${IMAGE}`);
    expect(view.note).toContain("Last capture");
  });

  it("prefers HA's entity_picture for the URL", () => {
    const view = captureView(
      activityDevice(),
      entities,
      statesFrom({ [IMAGE]: CAPTURED_AT }, { [IMAGE]: "/api/image_proxy/image.x?token=abc" }),
    );
    expect(view.picture).toBe("/api/image_proxy/image.x?token=abc");
  });

  it("shows the placeholder when a capture ran but the entity holds no image", () => {
    const view = captureView(
      activityDevice(),
      entities,
      statesFrom({ [IMAGE]: null, [LAST_CAPTURE]: CAPTURED_AT }),
    );
    expect(view.kind).toBe("placeholder");
    expect(view.lastCapture).toBe(CAPTURED_AT);
    expect(view.note).toContain("placeholder");
  });

  it("shows not-captured-yet when nothing has ever been captured", () => {
    const view = captureView(
      activityDevice(),
      entities,
      statesFrom({ [IMAGE]: null, [LAST_CAPTURE]: null }),
    );
    expect(view.kind).toBe("none");
    expect(view.lastCapture).toBeNull();
    expect(view.note).toContain("Nothing has been captured yet");
  });

  it("shows the last connection's capture when the entity is unavailable", () => {
    const view = captureView(
      activityDevice({ connected: false }),
      entities,
      statesFrom({ [IMAGE]: "unavailable", [LAST_CAPTURE]: "unavailable" }),
    );
    expect(view.kind).toBe("unavailable");
    expect(view.lastCapture).toBeNull();
    expect(view.note).toContain("From the last connection");
  });

  it("explains a tablet without the screenshot capability instead of showing an image", () => {
    const limited = device({ capabilities: ["screen"], entities: {} });
    const view = captureView(limited, activityEntities(limited));
    expect(view.kind).toBe("unsupported");
    expect(view.picture).toBeNull();
    expect(view.note).toContain("screenshot capability");
  });
});

describe("captionFor", () => {
  it("says the screen is on with no screensaver, on the start page", () => {
    expect(captionFor(activityDevice())).toBe("Screen on · No screensaver · Page /lovelace/0");
  });

  it("names the screensaver mode and the screen being off", () => {
    const off = activityDevice({
      screen: {
        screen_on: false,
        brightness: 20,
        screensaver_active: true,
        screensaver_mode: "clock",
      },
      current_page: "/lovelace/1",
    });
    expect(captionFor(off)).toBe("Screen off · Screensaver: clock · Page /lovelace/1");
  });

  it("says so when the tablet has reported no screen state", () => {
    const silent = activityDevice({
      screen: {
        screen_on: null,
        brightness: null,
        screensaver_active: null,
        screensaver_mode: null,
      },
      current_page: null,
    });
    expect(captionFor(silent)).toBe("The tablet has not reported its screen state.");
  });
});

describe("disabledReason", () => {
  it("lets a button with a real state be pressed", () => {
    const { hass } = hassWith({ [CAPTURE]: "unknown" });
    expect(disabledReason(activityDevice(), CAPTURE, hass.states)).toBeNull();
  });

  it("gives the offline reason for an offline tablet", () => {
    const offline = activityDevice({ connected: false, last_seen: "2026-11-24T09:00:00+00:00" });
    const { hass } = hassWith({ [CAPTURE]: "unavailable" });
    expect(disabledReason(offline, CAPTURE, hass.states)).toContain("offline");
  });

  it("gives the unavailable reason for an entity HA has no state for", () => {
    expect(disabledReason(activityDevice(), "button.missing", {})).toContain("unavailable");
  });
});

// --- the rendered tab --------------------------------------------------------

describe("tabula-activity", () => {
  it("renders the image, its caption, the capture time and the buttons by name", async () => {
    const { hass } = hassWith();
    const element = await mountActivity(hass, activityDevice());
    expect(button(element, "img.capture")?.getAttribute("src")).toBe(`/api/image_proxy/${IMAGE}`);
    expect(text(element)).toContain("Screen on · No screensaver · Page /lovelace/0");
    expect(text(element)).toContain("Last capture");
    expect(text(element)).toContain("Reload dashboard");
    expect(text(element)).toContain("Restart app");
    expect(text(element)).toContain("Go to start page");
    expect(text(element)).toContain("Clear cache");
    expect(text(element)).toContain("Capture screenshot");
  });

  it("keeps the configuration actions apart with the clear-cache note", async () => {
    const { hass } = hassWith();
    const element = await mountActivity(hass, activityDevice());
    const configuration = element.shadowRoot?.querySelector(".configuration");
    expect(configuration?.textContent ?? "").toContain("Restart app");
    expect(configuration?.textContent ?? "").toContain("Clear cache");
    expect(configuration?.textContent ?? "").toContain("removes the dashboard's stored data");
    expect(configuration?.textContent ?? "").not.toContain("Reload dashboard");
    const benign = element.shadowRoot?.querySelector(".commands > .command-row");
    expect(benign?.textContent ?? "").not.toContain("Restart app");
    expect(benign?.textContent ?? "").not.toContain("Clear cache");
  });

  it("disables every button with a reason when the tablet is offline", async () => {
    const offline = activityDevice({
      connected: false,
      last_seen: "2026-11-24T09:00:00+00:00",
      entities: {
        ...activityDevice().entities,
        button: (activityDevice().entities.button ?? []).map((entity) => ({
          ...entity,
          state: "unavailable",
        })),
        image: [{ entity_id: IMAGE, name: "Screenshot", state: "unavailable" }],
      },
    });
    const { hass } = hassWith({ [IMAGE]: "unavailable", [LAST_CAPTURE]: "unavailable" });
    const element = await mountActivity(hass, offline);
    const buttons = [...(element.shadowRoot?.querySelectorAll("button") ?? [])];
    expect(buttons).toHaveLength(5);
    expect(buttons.every((item) => item.disabled)).toBe(true);
    expect(text(element)).toContain("The tablet is offline");
    expect(text(element)).toContain("From the last connection");
  });

  it("shows no capture control for a tablet without the screenshot capability", async () => {
    const limited = device({ capabilities: ["screen"], entities: {} });
    const { hass } = hassWith({});
    const element = await mountActivity(hass, limited);
    expect(button(element, `button[data-command="capture"]`)).toBeNull();
    expect(text(element)).toContain("screenshot capability");
  });

  it("presses through button.press and reports HA's success", async () => {
    const calls = fakeCallService();
    const { hass } = hassWith({}, calls);
    const panelDevice = activityDevice();
    const element = await mountActivity(hass, panelDevice);
    button(element, 'button[data-command="reload"]')?.click();
    await settle(element);

    expect(calls.calls).toEqual([
      {
        domain: "button",
        service: "press",
        data: { entity_id: "button.office_kiosk_reload_dashboard" },
      },
    ]);
    expect(text(element)).toContain("Done.");
    // No optimistic state change on the device's behalf: the payload is untouched.
    expect(panelDevice.entities.button?.every((entity) => entity.state === "unknown")).toBe(true);
  });

  it("shows the error HA returned, verbatim", async () => {
    const calls = fakeCallService(async () => {
      throw new Error("The tablet is not connected, so the restart_app command was not sent.");
    });
    const { hass } = hassWith({}, calls);
    const element = await mountActivity(hass, activityDevice());
    button(element, 'button[data-command="restart_app"]')?.click();
    await settle(element);

    const alert = element.shadowRoot?.querySelector("[role='alert']");
    expect(alert?.textContent ?? "").toContain(
      "The tablet is not connected, so the restart_app command was not sent.",
    );
    expect(button(element, 'button[data-command="restart_app"]')?.disabled).toBe(false);
  });

  it("keeps the capture pending until the image changes, then reports it finished", async () => {
    const held = deferred();
    const calls = fakeCallService(() => held.promise);
    const first = hassWith({}, calls);
    const element = await mountActivity(first.hass, activityDevice());
    button(element, 'button[data-command="capture"]')?.click();
    await settle(element);

    expect(calls.calls[0]?.data).toEqual({ entity_id: CAPTURE });
    const capture = button(element, 'button[data-command="capture"]');
    expect(capture?.textContent ?? "").toContain("Capturing");
    expect(capture?.disabled).toBe(true);

    // HA publishes the new capture: the image entity's state moves.
    const second = hassWith({ [IMAGE]: LATER, [LAST_CAPTURE]: LATER }, calls);
    await updateHass(element, second.hass);

    expect(text(element)).toContain("Capture finished");
    expect(button(element, 'button[data-command="capture"]')?.disabled).toBe(false);
    held.resolve();
    await settle(element);
  });

  it("says a capture is still running rather than spinning forever", async () => {
    vi.useFakeTimers();
    const held = deferred();
    const calls = fakeCallService(() => held.promise);
    const { hass } = hassWith({}, calls);
    const element = await mountActivity(hass, activityDevice());
    button(element, 'button[data-command="capture"]')?.click();
    await settle(element);

    vi.advanceTimersByTime(PRESS_PENDING_TIMEOUT_S * 1000);
    await settle(element);

    expect(button(element, 'button[data-command="capture"]')?.textContent ?? "").toContain(
      "Still running",
    );
    expect(text(element)).toContain(`Still running after ${PRESS_PENDING_TIMEOUT_S} seconds`);
    held.resolve();
    await settle(element);
  });

  it("runs no timer of its own while idle: the tab follows hass, not a poll", async () => {
    vi.useFakeTimers();
    const { hass } = hassWith();
    await mountActivity(hass, activityDevice());
    expect(vi.getTimerCount()).toBe(0);
  });

  it("reports the outcome of the capture when HA rejects the press", async () => {
    const calls = fakeCallService(async () => {
      throw new Error("The screenshot command timed out.");
    });
    const { hass } = hassWith({}, calls);
    const element = await mountActivity(hass, activityDevice());
    button(element, 'button[data-command="capture"]')?.click();
    await settle(element);
    expect(text(element)).toContain("The screenshot command timed out.");
  });
});
