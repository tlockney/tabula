import { defineConfig } from "vite";

/**
 * The bundle is one ES module with its styles inside it: Home Assistant loads
 * `module_url` as a JS module, so a separate stylesheet would never be fetched.
 * The module imports `styles.css?inline` and registers the text with Lit.
 */
export default defineConfig({
  build: {
    target: "es2022",
    outDir: "../build/panel",
    emptyOutDir: true,
    cssCodeSplit: false,
    // A single module, no chunks: the panel is small and HA fetches it once.
    modulePreload: false,
    lib: {
      entry: "src/panel.ts",
      formats: ["es"],
      fileName: () => "panel.js",
    },
  },
});
