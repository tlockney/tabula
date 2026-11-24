import { afterEach, describe, expect, it } from "vitest";
import "./panel";
import type { TabulaPanel } from "./shell";
import { LANDING_ROUTE, routeOf } from "./shell";
import type { HomeAssistant, PanelInfo } from "./types";

const INFO: PanelInfo = {
  version: "0.1.0",
  protocol_version: 1,
  schema_version: 3,
  devices: 2,
};

function fakeHass(
  callWS: (msg: { type: string; [key: string]: unknown }) => Promise<unknown>,
): HomeAssistant {
  return {
    callWS: <T>(msg: { type: string; [key: string]: unknown }) => callWS(msg) as Promise<T>,
  };
}

/** Let the element's pending request and re-render settle. */
async function settle(element: TabulaPanel): Promise<void> {
  for (let i = 0; i < 5; i += 1) {
    await Promise.resolve();
    await element.updateComplete;
  }
}

/** Mount the element with a fake hass and wait for the panel_info round trip. */
async function mount(hass: HomeAssistant): Promise<TabulaPanel> {
  const element = document.createElement("tabula-panel") as TabulaPanel;
  element.hass = hass;
  document.body.append(element);
  await customElements.whenDefined("tabula-panel");
  await settle(element);
  return element;
}

afterEach(() => {
  document.body.replaceChildren();
});

describe("tabula-panel shell", () => {
  it("shows the hub status line from tabula/panel_info", async () => {
    const asked: string[] = [];
    const element = await mount(
      fakeHass(async (msg) => {
        asked.push(msg.type);
        return INFO;
      }),
    );

    expect(asked).toEqual(["tabula/panel_info"]);
    const text = element.shadowRoot?.textContent ?? "";
    expect(text).toContain("Tabularium");
    expect(text).toContain("Tabula 0.1.0 · protocol 1 · settings schema 3 · 2 devices");
  });

  it("counts one device in the singular", async () => {
    const element = await mount(fakeHass(async () => ({ ...INFO, devices: 1 })));
    expect(element.shadowRoot?.textContent ?? "").toContain("1 device");
  });

  it("shows a visible error state when panel_info fails", async () => {
    const element = await mount(
      fakeHass(async () => {
        throw new Error("unauthorized");
      }),
    );

    const alert = element.shadowRoot?.querySelector("[role='alert']");
    expect(alert?.textContent ?? "").toContain("The Tabularium could not load its status");
    expect(alert?.textContent ?? "").toContain("unauthorized");
  });

  it("asks again when the error action is used", async () => {
    let calls = 0;
    const element = await mount(
      fakeHass(async () => {
        calls += 1;
        if (calls === 1) {
          throw new Error("network down");
        }
        return INFO;
      }),
    );
    expect(calls).toBe(1);

    const button = element.shadowRoot?.querySelector("button");
    expect(button).not.toBeNull();
    button?.click();
    await settle(element);

    expect(calls).toBe(2);
    const text = element.shadowRoot?.textContent ?? "";
    expect(text).toContain("Tabula 0.1.0 · protocol 1 · settings schema 3 · 2 devices");
    expect(element.shadowRoot?.querySelector("[role='alert']")).toBeNull();
  });

  it("renders the shell without a status line before hass arrives", async () => {
    const element = document.createElement("tabula-panel");
    document.body.append(element);
    await element.updateComplete;
    const text = element.shadowRoot?.textContent ?? "";
    expect(text).toContain("Tabularium");
    expect(text).toContain("Loading the hub's status");
    expect(text).not.toContain("Tabula 0.1.0");
  });
});

describe("hash routes", () => {
  it("keeps a hash route and falls back to the landing route", () => {
    expect(routeOf("#/devices/abc")).toBe("#/devices/abc");
    expect(LANDING_ROUTE).toBe("#/devices");
    expect(routeOf("")).toBe(LANDING_ROUTE);
    expect(routeOf("#something")).toBe(LANDING_ROUTE);
  });
});
