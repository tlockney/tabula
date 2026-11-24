import { afterEach, describe, expect, it } from "vitest";
import { LANDING_ROUTE, routeOf } from "./shell";
import { INFO, fakeHass, mount, settle } from "./test-helpers";

afterEach(() => {
  document.body.replaceChildren();
  window.location.hash = "";
});

describe("tabula-panel shell", () => {
  it("shows the hub status line from tabula/panel_info", async () => {
    const asked: string[] = [];
    const element = await mount(
      fakeHass(async (msg) => {
        asked.push(msg.type);
        return msg.type === "tabula/panel_info" ? INFO : [];
      }),
    );

    expect(asked).toContain("tabula/panel_info");
    expect(asked).toContain("tabula/panel_devices");
    const text = element.shadowRoot?.textContent ?? "";
    expect(text).toContain("Tabularium");
    expect(text).toContain("Tabula 0.1.0 · protocol 1 · settings schema 3 · 2 devices");
  });

  it("counts one device in the singular", async () => {
    const element = await mount(
      fakeHass(async (msg) => (msg.type === "tabula/panel_info" ? { ...INFO, devices: 1 } : [])),
    );
    expect(element.shadowRoot?.textContent ?? "").toContain("1 device");
  });

  it("shows a visible error state when panel_info fails", async () => {
    const element = await mount(
      fakeHass(async (msg) => {
        if (msg.type === "tabula/panel_info") {
          throw new Error("unauthorized");
        }
        return [];
      }),
    );

    const alert = element.shadowRoot?.querySelector("[role='alert']");
    expect(alert?.textContent ?? "").toContain("The Tabularium could not load its status");
    expect(alert?.textContent ?? "").toContain("unauthorized");
  });

  it("asks again when the error action is used", async () => {
    let calls = 0;
    const element = await mount(
      fakeHass(async (msg) => {
        if (msg.type !== "tabula/panel_info") {
          return [];
        }
        calls += 1;
        if (calls === 1) {
          throw new Error("network down");
        }
        return INFO;
      }),
    );
    expect(calls).toBe(1);

    const button = element.shadowRoot?.querySelector("[role='alert'] button") as HTMLElement | null;
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
