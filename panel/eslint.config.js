import js from "@eslint/js";
import tseslint from "typescript-eslint";

export default tseslint.config(
  {
    // src/generated is written by tools/codegen_ts.py and gated by `make codegen-drift`.
    ignores: ["node_modules/**", "src/generated/**", "coverage/**"],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.ts"],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "module",
    },
  },
);
