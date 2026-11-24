import { defineConfig } from "vitest/config";

/** Vitest runs the sources directly; `vite.config.ts` owns the bundle build. */
export default defineConfig({
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts"],
    // The shell reads window.location.hash, so every test gets a clean URL.
    restoreMocks: true,
  },
});
