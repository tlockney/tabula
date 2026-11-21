"""Generate the Kotlin settings classes from schema/settings.schema.json.

Output: android/core/src/main/kotlin/net/lockney/tabula/core/settings/Settings.kt
(committed). `--check` (make codegen-drift) fails when the file is stale.

The generator supports the JSON Schema constructs settings v1 uses — nested
objects, integers, booleans, strings and enums with min/max/length/pattern and
defaults — and raises on anything else, so a future schema change forces a
deliberate codegen update instead of silently dropping constraints.

Bounds and defaults are the schema's (chosen in docs/decisions/0002-constants.md);
validation is emitted as `require` in data-class init blocks, so a decoded
Settings value that exists is a valid one. A property without a default is
nullable (identity.name, connection.ha_url); a group object HA does not send
materializes as its empty instance, which is the same reading as "a key HA does
not send keeps the device default" (docs/PROTOCOL.md 4.5).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "schema" / "settings.schema.json"
OUT = (
    ROOT
    / "android"
    / "core"
    / "src"
    / "main"
    / "kotlin"
    / "net"
    / "lockney"
    / "tabula"
    / "core"
    / "settings"
    / "Settings.kt"
)

KOTLIN_HARD_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
    "in", "interface", "is", "null", "object", "package", "return", "super",
    "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when",
    "while",
}


def kotlin_name(name: str, upper: bool = False) -> str:
    """snake_case -> camelCase (or PascalCase); raises on unusable names."""
    parts = [p for p in re.split(r"[^0-9a-zA-Z]+", name) if p]
    out = "".join(p[:1].upper() + p[1:] for p in parts)
    if not upper and out:
        out = out[:1].lower() + out[1:]
    if not out or out[0].isdigit() or out.lower() in KOTLIN_HARD_KEYWORDS:
        raise ValueError(f"cannot turn schema name {name!r} into a Kotlin identifier")
    return out


def kotlin_string(text: str) -> str:
    """A JSON-Schema regex as a Kotlin string literal."""
    escaped = text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return f'"{escaped}"'


def kdoc(text: str) -> str:
    """Schema description as a one-line KDoc body (comment-safe)."""
    return text.replace("*/", "*\u2044").replace("\n", " ").strip()


class Codegen:
    def __init__(self, schema: dict[str, Any]) -> None:
        self.schema = schema
        self.class_names: set[str] = set()
        self.enum_defs: list[str] = []  # enum class sources, in first-use order
        self.pattern_defs: list[str] = []  # private Regex vals
        self._enum_index: dict[str, str] = {}  # enum values tuple -> class name
        self._pattern_index: dict[str, str] = {}  # schema pattern -> val name

    def unique(self, name: str, what: str) -> str:
        if name in self.class_names:
            raise ValueError(f"duplicate generated {what} name: {name}")
        self.class_names.add(name)
        return name

    def pattern_val(self, pattern: str, name: str) -> str:
        """The private Regex val for a schema pattern, declaring it once."""
        if pattern not in self._pattern_index:
            self._pattern_index[pattern] = name
            self.pattern_defs.append(
                f"/** Schema pattern for {name}: {pattern} */\n"
                f"private val {name}: Regex = Regex({kotlin_string(pattern)})"
            )
        elif self._pattern_index[pattern] != name:
            raise ValueError(f"pattern reused with two names: {pattern}")
        return self._pattern_index[pattern]

    def leaf(
        self, path: str, name: str, prop: dict[str, Any]
    ) -> tuple[str, str, list[str]]:
        """(Kotlin type, default expression, init checks using {v} as the value)."""
        if "enum" in prop:
            values = prop["enum"]
            default = prop.get("default")
            if default is None:
                raise ValueError(f"{path}: enum property needs a default")
            if default not in values:
                raise ValueError(f"{path}: default {default!r} not in enum values")
            cls = self.unique(kotlin_name(name, upper=True), "enum class")
            cases = ",\n".join(
                f'    @SerialName({kotlin_string(v)}) {kotlin_name(v, upper=True)}' for v in values
            )
            self.enum_defs.append(
                f"/** Schema enum for {path}. */\n@Serializable\nenum class {cls} {{\n{cases},\n}}"
            )
            self._enum_index["\x00".join(values)] = cls
            return cls, f"{cls}.{kotlin_name(default, upper=True)}", []

        match prop.get("type"):
            case "integer":
                checks = []
                minimum, maximum = prop.get("minimum"), prop.get("maximum")
                lo = "Int.MIN_VALUE" if minimum is None else minimum
                hi = "Int.MAX_VALUE" if maximum is None else maximum
                if minimum is not None or maximum is not None:
                    checks.append(
                        f'require(%V% in {lo}..{hi}) {{ "{path} must be {lo}..{hi}" }}'
                    )
                default = prop.get("default")
                if default is None:
                    return "Int?", "null", checks
                return "Int", str(default), checks
            case "boolean":
                default = prop.get("default")
                if default is None:
                    raise ValueError(f"{path}: boolean property needs a default")
                return "Boolean", "true" if default else "false", []
            case "string":
                checks = []
                minimum, maximum = prop.get("minLength"), prop.get("maxLength")
                lo = 0 if minimum is None else minimum
                hi = "Int.MAX_VALUE" if maximum is None else maximum
                if minimum is not None or maximum is not None:
                    checks.append(
                        f'require(%V%.length in {lo}..{hi}) {{ "{path} must be {lo}-{hi} characters" }}'
                    )
                if "pattern" in prop:
                    val = self.pattern_val(prop["pattern"], f"{kotlin_name(path, upper=True)}Pattern")
                    checks.append(
                        f'require({val}.matches(%V%)) {{ "{path} does not match the schema pattern" }}'
                    )
                default = prop.get("default")
                if default is None:
                    return "String?", "null", checks
                return "String", kotlin_string(default), checks
        raise ValueError(f"{path}: unsupported property {json.dumps(prop)[:120]}")

    def object_class(self, path: str, name: str, obj: dict[str, Any]) -> tuple[str, str]:
        """Emit a data class for an object; returns (class name, default expression)."""
        cls = self.unique(kotlin_name(name, upper=True), "data class")
        fields: list[str] = []
        init_lines: list[str] = []
        for pname, prop in obj.get("properties", {}).items():
            fpath = f"{path}{pname}"
            field = kotlin_name(pname)
            serial = "" if field == pname else f'@SerialName("{pname}") '
            desc = kdoc(prop.get("description", ""))
            doc = f"    /** {desc} */\n" if desc else ""
            if prop.get("type") == "object":
                sub_cls, default = self.object_class(fpath + ".", pname, prop)
                fields.append(f"{doc}    {serial}val {field}: {sub_cls} = {default}")
            else:
                typ, default, checks = self.leaf(fpath, pname, prop)
                fields.append(f"{doc}    {serial}val {field}: {typ} = {default}")
                if typ.endswith("?"):
                    init_lines.extend(
                        f"        {field}?.let {{ {check.replace('%V%', 'it')} }}" for check in checks
                    )
                else:
                    init_lines.extend(f"        {check.replace('%V%', field)}" for check in checks)

        extra_props = obj.get("additionalProperties")
        if extra_props is not False:
            raise ValueError(f"{path}: objects must set additionalProperties: false")

        out = []
        desc = kdoc(obj.get("description", ""))
        if desc:
            out.append(f"/** {desc} */")
        out.append("@Serializable")
        if init_lines:
            out.append(f"data class {cls}(")
            out.append(",\n".join(fields))
            out.append(") {")
            out.append("    init {")
            out.extend(init_lines)
            out.append("    }")
            out.append("}")
        else:
            out.append(f"data class {cls}(")
            out.append(",\n".join(fields))
            out.append(")")
        self.class_sources.append("\n".join(out))
        return cls, f"{cls}()"

    def generate(self) -> str:
        self.class_sources: list[str] = []
        version = self.schema.get("x-tabula-schema-version")
        if not isinstance(version, int):
            raise ValueError("schema is missing x-tabula-schema-version")

        root_props = self.schema.get("properties", {})
        fields: list[str] = []
        for pname, prop in root_props.items():
            if prop.get("type") != "object":
                raise ValueError(f"root property {pname} must be an object")
            cls, default = self.object_class(f"{pname}.", pname, prop)
            field = kotlin_name(pname)
            serial = "" if field == pname else f'@SerialName("{pname}") '
            desc = kdoc(prop.get("description", ""))
            doc = f"    /** {desc} */\n" if desc else ""
            fields.append(f"{doc}    {serial}val {field}: {cls} = {default}")

        root = self.unique("Settings", "data class")
        root_src = [
            "/**",
            " * Per-device settings, generated from schema/settings.schema.json.",
            " * Every property carries its role in x-tabula-role (profiles manage only",
            " * 'shared' keys, F-MGT-03/F-MGT-04); HA is authoritative for revisions.",
            " */",
            "@Serializable",
            f"data class {root}(",
            ",\n".join(fields),
            ")",
        ]
        self.class_sources.append("\n".join(root_src))

        header = [
            "// Generated by tools/codegen.py from schema/settings.schema.json. DO NOT EDIT.",
            "// Regeneration is checked by `make codegen-drift`; bounds and defaults come",
            "// from the schema (docs/decisions/0002-constants.md).",
            "",
            "package net.lockney.tabula.core.settings",
            "",
            "import kotlinx.serialization.SerialName",
            "import kotlinx.serialization.Serializable",
            "",
            "/** The settings schema version this file was generated from.",
            " * Same value as the schema's x-tabula-schema-version; the integration and",
            " * the device compare it via register and set_settings (PROTOCOL 4.2, 4.5).",
            " */",
            f"const val SCHEMA_VERSION: Int = {version}",
            "",
        ]
        body = self.pattern_defs + self.enum_defs + self.class_sources
        return "\n".join(header) + "\n" + "\n\n".join(body) + "\n"


def generate(schema: dict[str, Any]) -> str:
    return Codegen(schema).generate()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the output is stale")
    args = parser.parse_args()

    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    output = generate(schema)

    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.is_file() else None
        if current != output:
            print(
                f"codegen-drift: {OUT.relative_to(ROOT)} does not match the schema; "
                "run `make codegen` and commit the result",
                file=sys.stderr,
            )
            return 1
        print(f"codegen-drift: ok ({OUT.relative_to(ROOT)} up to date)")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(output, encoding="utf-8")
    print(f"codegen: wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
