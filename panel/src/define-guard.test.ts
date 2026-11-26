import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// HA can load a second copy of the bundle into a page that already has the
// first (a module URL change after an update). An unguarded define then throws
// and stops the new module before it reaches the panel element.
describe("custom element definitions", () => {
  it("every customElements.define is guarded by customElements.get", () => {
    const dir = join(__dirname);
    const unguarded: string[] = [];
    for (const name of readdirSync(dir)) {
      if (!name.endsWith(".ts") || name.endsWith(".test.ts")) continue;
      const lines = readFileSync(join(dir, name), "utf8").split("\n");
      lines.forEach((line, i) => {
        if (!line.includes("customElements.define(")) return;
        const context = lines.slice(Math.max(0, i - 2), i + 1).join("\n");
        if (!context.includes("customElements.get(")) unguarded.push(`${name}:${i + 1}`);
      });
    }
    expect(unguarded).toEqual([]);
  });
});
