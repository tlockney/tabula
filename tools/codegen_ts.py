"""Generate the TypeScript settings module the panel uses from schema/settings.schema.json.

Output: panel/src/generated/settings.ts (committed). `--check` (make codegen-drift)
fails when the file is stale; the panel's `npm run typecheck` compiles it.

The generator supports the JSON Schema constructs settings v3 uses — nested
objects, integers, booleans, strings and enums with min/max/length/pattern and
defaults, nullable scalar types (["integer", "null"] and friends), arrays of
scalars and of objects with maxItems and per-item checks, and required object
properties — and raises on anything else, exactly like tools/codegen.py, so a
future schema change forces a deliberate codegen update instead of silently
dropping constraints.

It emits three things from the same walk: the `Settings` interfaces, a flat
field table (dotted path, flat key, role, feature, type, enum values, bounds,
default) and a defaults document. The field table mirrors settings.py's
`_fields()` — same paths, same flat keys, same roles, features and defaults —
so the panel and the integration cannot disagree about a key. Roles and
features are the schema's x-tabula-role and x-tabula-feature tags; defaults come
only from the schema (docs/decisions/0002-constants.md).
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
OUT = ROOT / "panel" / "src" / "generated" / "settings.ts"

TS_RESERVED = {
    "any",
    "as",
    "await",
    "boolean",
    "break",
    "case",
    "catch",
    "class",
    "const",
    "constructor",
    "continue",
    "debugger",
    "declare",
    "default",
    "delete",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "finally",
    "for",
    "function",
    "if",
    "implements",
    "import",
    "in",
    "instanceof",
    "interface",
    "let",
    "new",
    "null",
    "number",
    "object",
    "package",
    "private",
    "protected",
    "public",
    "return",
    "static",
    "string",
    "super",
    "switch",
    "symbol",
    "this",
    "throw",
    "true",
    "try",
    "type",
    "typeof",
    "undefined",
    "unknown",
    "var",
    "void",
    "while",
    "with",
    "yield",
}

TS_IDENTIFIER = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")

NO_DEFAULT = object()  # "the schema sets no default", rendered as the NO_DEFAULT symbol


def nullable_ok(ptype: list[str]) -> bool:
    """True for the base-or-null type unions the generator supports."""
    return (
        len(ptype) == 2
        and "null" in ptype
        and set(ptype) - {"null"} <= {"integer", "boolean", "string"}
    )


def ts_pascal(name: str) -> str:
    """snake_case (or a dotted path) -> PascalCase; raises on unusable names."""
    parts = [p for p in re.split(r"[^0-9a-zA-Z]+", name) if p]
    out = "".join(p[:1].upper() + p[1:] for p in parts)
    if not out or out[0].isdigit():
        raise ValueError(f"cannot turn schema name {name!r} into a TypeScript identifier")
    return out


def ts_property(name: str) -> str:
    """A property name, quoted when it is not a bare identifier."""
    if TS_IDENTIFIER.match(name) and name not in TS_RESERVED:
        return name
    return json.dumps(name, ensure_ascii=False)


def ts_string(text: str) -> str:
    """A string literal (JSON escaping is valid TypeScript)."""
    return json.dumps(text, ensure_ascii=False)


def doc_text(text: str) -> str:
    """A schema description as a one-line doc comment body (comment-safe)."""
    return text.replace("*/", "*\u2044").replace("\n", " ").strip()


def ts_value(value: Any) -> str:
    """A schema default as a TypeScript literal."""
    if value is NO_DEFAULT:
        return "NO_DEFAULT"
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, str):
        return ts_string(value)
    if isinstance(value, list):
        return "[" + ", ".join(ts_value(item) for item in value) + "]"
    raise ValueError(f"unsupported default value {value!r}")


def render_item_property(prop: dict[str, Any]) -> str:
    """One SettingsItemProperty as TypeScript source."""
    return (
        "{ "
        f"name: {ts_string(prop['name'])}, "
        f"type: {ts_string(prop['type'])}, "
        f"enumValues: {ts_value(prop['enum'])}, "
        f"minimum: {ts_value(prop['minimum'])}, "
        f"maximum: {ts_value(prop['maximum'])}, "
        f"maxLength: {ts_value(prop['max_length'])}, "
        f"pattern: {ts_value(prop['pattern'])}, "
        f"nullable: {ts_value(prop['nullable'])}, "
        f"default: {ts_value(prop['default'])}, "
        f"help: {ts_string(prop['help'])}"
        " }"
    )


def render_items(items: dict[str, Any] | None) -> str:
    """A SettingsArrayItems value as TypeScript source (null for scalar leaves)."""
    if items is None:
        return "null"
    properties = ", ".join(render_item_property(prop) for prop in items["properties"])
    return (
        "{ "
        f"type: {ts_string(items['type'])}, "
        f"pattern: {ts_value(items['pattern'])}, "
        f"enumValues: {ts_value(items['enum'])}, "
        f"properties: [{properties}], "
        f"required: {ts_value(items['required'])}"
        " }"
    )


class Codegen:
    def __init__(self, schema: dict[str, Any]) -> None:
        self.schema = schema
        self.names: set[str] = set()
        self.type_aliases: list[str] = []
        self.interfaces: list[str] = []
        self.fields: list[dict[str, Any]] = []

    def unique(self, name: str, what: str) -> str:
        if name in self.names:
            raise ValueError(f"duplicate generated {what} name: {name}")
        self.names.add(name)
        return name

    def record(self, path: str, prop: dict[str, Any], type_name: str, **extra: Any) -> None:
        """Add a leaf to the field table; raises when the schema omits a tag."""
        for tag in ("x-tabula-role", "x-tabula-feature"):
            if tag not in prop:
                raise ValueError(f"{path}: leaf property needs {tag}")
        entry = {
            "path": path,
            "key": path.replace(".", "_"),
            "role": prop["x-tabula-role"],
            "feature": prop["x-tabula-feature"],
            "type": type_name,
            "enum": prop.get("enum"),
            "minimum": prop.get("minimum"),
            "maximum": prop.get("maxItems") if type_name == "array" else prop.get("maximum"),
            "min_length": prop.get("minLength"),
            "max_length": prop.get("maxLength"),
            "pattern": prop.get("pattern"),
            "default": prop.get("default", NO_DEFAULT),
            "array_items": None,
            "items": None,
            "help": doc_text(prop.get("description", "")),
        }
        entry.update(extra)
        self.fields.append(entry)

    def enum_alias(self, path: str, prop: dict[str, Any]) -> str:
        """Emit the string-literal union for an enum; returns its type name."""
        values = prop["enum"]
        name = self.unique(ts_pascal(f"settings {path}"), "type alias")
        union = " | ".join(ts_string(value) for value in values)
        self.type_aliases.append(f"/** Schema enum for {path}. */\nexport type {name} = {union};")
        return name

    def leaf(
        self, path: str, prop: dict[str, Any], required: bool = False, record: bool = True
    ) -> tuple[str, Any]:
        """(TypeScript type, default or NO_DEFAULT) for a scalar leaf."""
        if "enum" in prop:
            name = self.enum_alias(path, prop)
            default = prop.get("default")
            if default is None:
                raise ValueError(f"{path}: enum property needs a default")
            if default not in prop["enum"]:
                raise ValueError(f"{path}: default {default!r} not in enum values")
            if record:
                self.record(path, prop, "string")
            return name, default

        ptype = prop.get("type")
        nullable = False
        if isinstance(ptype, list):
            # Only base-or-null unions are supported; a required nullable
            # property would be meaningless.
            if not nullable_ok(ptype) or required:
                raise ValueError(f"{path}: unsupported property type {ptype}")
            ptype = (set(ptype) - {"null"}).pop()
            nullable = True

        default = prop.get("default")
        if nullable and default is not None:
            raise ValueError(f"{path}: nullable property must default to null")

        match ptype:
            case "integer":
                if record:
                    self.record(path, prop, "integer")
                if nullable:
                    return "number | null", NO_DEFAULT
                if default is None:
                    return ("number", NO_DEFAULT) if required else ("number | null", NO_DEFAULT)
                return "number", default
            case "boolean":
                if default is None and not nullable:
                    raise ValueError(f"{path}: boolean property needs a default")
                if record:
                    self.record(path, prop, "boolean")
                if nullable:
                    return "boolean | null", NO_DEFAULT
                return "boolean", default
            case "string":
                if record:
                    self.record(path, prop, "string")
                if nullable:
                    return "string | null", NO_DEFAULT
                if default is None:
                    return ("string", NO_DEFAULT) if required else ("string | null", NO_DEFAULT)
                return "string", default
        raise ValueError(f"{path}: unsupported property {json.dumps(prop)[:120]}")

    def item_type(self, path: str, items: dict[str, Any]) -> str:
        """The TypeScript element type for an array of scalars."""
        if "enum" in items:
            return self.enum_alias(f"{path}[]", items)
        match items.get("type"):
            case "integer":
                return "number"
            case "boolean":
                return "boolean"
            case "string":
                return "string"
        raise ValueError(f"{path}: unsupported array item {json.dumps(items)[:120]}")

    def array_leaf(self, path: str, prop: dict[str, Any]) -> tuple[str, Any]:
        """(TypeScript array type, default or NO_DEFAULT) for an array leaf.

        Like settings.py, the leaf is the whole array: the structure of the
        items stays in the schema (and in the generated item interface), never
        as extra field-table rows.
        """
        items = prop.get("items")
        if not isinstance(items, dict):
            raise ValueError(f"{path}: array property needs items")
        if "minItems" in prop:
            raise ValueError(f"{path}: unsupported array constraint minItems")
        if items.get("type") == "array":
            raise ValueError(f"{path}: unsupported array item {json.dumps(items)[:120]}")
        if prop.get("default") != []:
            raise ValueError(f"{path}: array property needs default []")
        default: Any = [] if "default" in prop else NO_DEFAULT
        if items.get("type") == "object":
            item_cls, _ = self.object_interface(
                f"{path}Item.", items, type_name=f"{path}Item", record=False
            )
            self.record(
                path, prop, "array", array_items="object", items=self.item_metadata(path, items)
            )
            return f"{item_cls}[]", default
        element = self.item_type(path, items)
        self.record(path, prop, "array", array_items="scalar", items=self.item_metadata(path, items))
        return f"{element}[]", default

    def item_metadata(self, path: str, items: dict[str, Any]) -> dict[str, Any]:
        """The items' own validation data, for the panel's array editors (P5.3).

        A scalar item carries its pattern and enum; an object item carries each
        property's type, bounds, enum, nullability (the schedule's "inherit"),
        default and description, so the editor renders the rows from the schema
        and a schema bump needs no new UI code.
        """
        if items.get("type") == "object":
            properties = [
                self.item_property(f"{path}.{pname}", pname, prop)
                for pname, prop in items.get("properties", {}).items()
            ]
            return {
                "type": "object",
                "pattern": None,
                "enum": None,
                "properties": properties,
                "required": list(items.get("required", [])),
            }
        return {
            "type": items.get("type", "string"),
            "pattern": items.get("pattern"),
            "enum": items.get("enum"),
            "properties": [],
            "required": [],
        }

    def item_property(self, path: str, name: str, prop: dict[str, Any]) -> dict[str, Any]:
        """One property of an array-of-objects item's schema."""
        ptype = prop.get("type", "string")
        nullable = isinstance(ptype, list)
        if nullable:
            if not nullable_ok(ptype):
                raise ValueError(f"{path}: unsupported item property type {ptype}")
            ptype = (set(ptype) - {"null"}).pop()
        if ptype not in ("string", "integer", "boolean"):
            raise ValueError(f"{path}: unsupported item property type {ptype!r}")
        if prop.get("enum") is not None:
            ptype = "string"
        return {
            "name": name,
            "type": ptype,
            "enum": prop.get("enum"),
            "minimum": prop.get("minimum"),
            "maximum": prop.get("maximum"),
            "max_length": prop.get("maxLength"),
            "pattern": prop.get("pattern"),
            "nullable": nullable,
            "default": prop.get("default", NO_DEFAULT),
            "help": doc_text(prop.get("description", "")),
        }

    def object_interface(
        self,
        path: str,
        obj: dict[str, Any],
        *,
        type_name: str | None = None,
        record: bool = True,
    ) -> tuple[str, dict[str, Any]]:
        """Emit an interface for an object; returns (type name, defaults subtree).

        The defaults subtree holds every leaf the schema gives a default, plus
        every nested group object (which materializes as its empty instance,
        like the Kotlin data classes). ``record=False`` is for objects inside an
        array leaf, whose members settings.py does not list separately.
        """
        cls = self.unique(
            ts_pascal(f"settings {type_name if type_name is not None else path.rstrip('.')}"),
            "interface",
        )
        required = set(obj.get("required", []))
        properties = obj.get("properties", {})
        unknown = required - set(properties)
        if unknown:
            raise ValueError(f"{path}: required lists unknown properties {sorted(unknown)}")
        if obj.get("additionalProperties") is not False:
            raise ValueError(f"{path}: objects must set additionalProperties: false")

        lines: list[str] = []
        defaults: dict[str, Any] = {}
        for pname, prop in properties.items():
            if pname in required and "default" in prop:
                # Required and defaulted contradict: a TypeScript default would
                # silently accept what JSON Schema rejects.
                raise ValueError(f"{path}{pname}: required property must not carry a default")
            fpath = f"{path}{pname}"
            desc = doc_text(prop.get("description", ""))
            doc = f"  /** {desc} */\n" if desc else ""
            member = ts_property(pname)
            if prop.get("type") == "object":
                sub_cls, sub_defaults = self.object_interface(fpath + ".", prop, record=record)
                lines.append(f"{doc}  {member}: {sub_cls};")
                defaults[pname] = sub_defaults
                continue
            if prop.get("type") == "array":
                expr, default = self.array_leaf(fpath, prop)
            else:
                expr, default = self.leaf(fpath, prop, required=pname in required, record=record)
            # A leaf without a default is optional unless the schema lists it as
            # required (then the type is non-nullable and decoding must supply it).
            optional = default is NO_DEFAULT and pname not in required
            lines.append(f"{doc}  {member}{'?' if optional else ''}: {expr};")
            if default is not NO_DEFAULT:
                defaults[pname] = default

        desc = doc_text(obj.get("description", ""))
        out = [f"/** {desc} */"] if desc else []
        out.append(f"export interface {cls} {{")
        out.extend(lines)
        out.append("}")
        self.interfaces.append("\n".join(out))
        return cls, defaults

    def generate(self) -> str:
        version = self.schema.get("x-tabula-schema-version")
        if not isinstance(version, int):
            raise ValueError("schema is missing x-tabula-schema-version")
        roles = self.schema.get("x-tabula-roles")
        if not isinstance(roles, list) or not roles:
            raise ValueError("schema is missing x-tabula-roles")

        root_props = self.schema.get("properties", {})
        defaults: dict[str, Any] = {}
        members: list[str] = []
        for pname, prop in root_props.items():
            if prop.get("type") != "object":
                raise ValueError(f"root property {pname} must be an object")
            cls, sub_defaults = self.object_interface(f"{pname}.", prop)
            desc = doc_text(prop.get("description", ""))
            doc = f"  /** {desc} */\n" if desc else ""
            members.append(f"{doc}  {ts_property(pname)}: {cls};")
            defaults[pname] = sub_defaults

        root = self.unique("Settings", "interface")
        self.interfaces.append(
            "\n".join(
                [
                    "/**",
                    " * Per-device settings, generated from schema/settings.schema.json.",
                    " * Every leaf carries its role (x-tabula-role) and the feature it",
                    " * implements (x-tabula-feature); profiles manage only 'shared' keys.",
                    " */",
                    f"export interface {root} {{",
                    *members,
                    "}",
                ]
            )
        )

        header = [
            "// Generated by tools/codegen_ts.py from schema/settings.schema.json. DO NOT EDIT.",
            "// Regeneration is checked by `make codegen-drift`; bounds and defaults come",
            "// from the schema (docs/decisions/0002-constants.md).",
            "",
            "/** The settings schema version this file was generated from. */",
            f"export const SCHEMA_VERSION = {version};",
            "",
            "/**",
            " * The leaf roles the schema declares (x-tabula-roles). Profiles may manage",
            " * only 'shared' keys (F-MGT-03); 'hardware' has no members yet.",
            " */",
            "export const SETTINGS_ROLES = ["
            + ", ".join(ts_string(role) for role in roles)
            + "] as const;",
            "export type SettingsRole = (typeof SETTINGS_ROLES)[number];",
            "",
            "/** Stands in for a leaf the schema gives no default. */",
            'export const NO_DEFAULT: unique symbol = Symbol("tabula.no-default");',
            "",
            "/**",
            " * One leaf of the settings document: the dotted path, the flat key the",
            " * integration's settings service uses, who may set it, the feature it",
            " * implements, and the schema's validation data. For array leaves `type`",
            ' * is "array", `arrayItems` says whether the elements are scalars or',
            " * objects, and `maximum` is the schema's maxItems (as in settings.py).",
            " */",
            "export interface SettingsField {",
            '  /** Dotted path in the settings document, e.g. "screensaver.mode". */',
            "  readonly path: string;",
            '  /** Flat key used by the settings service, e.g. "screensaver_mode". */',
            "  readonly key: string;",
            '  /** Who may set it: profiles manage only "shared" (F-MGT-03). */',
            "  readonly role: SettingsRole;",
            '  /** The docs/FEATURES.md item the key implements, or "independent design". */',
            "  readonly feature: string;",
            "  /** The leaf's schema type; every enum is a string with enumValues set. */",
            '  readonly type: "string" | "integer" | "boolean" | "array";',
            "  /** Allowed values of an enum leaf, in schema order; null when not an enum. */",
            "  readonly enumValues: readonly string[] | null;",
            "  /** Numeric minimum, or maxItems for an array leaf; null when unbounded. */",
            "  readonly minimum: number | null;",
            "  /** Numeric maximum, or maxItems for an array leaf; null when unbounded. */",
            "  readonly maximum: number | null;",
            "  /** Shortest string length in code points; null when unbounded. */",
            "  readonly minLength: number | null;",
            "  /** Longest string length in code points; null when unbounded. */",
            "  readonly maxLength: number | null;",
            "  /** JSON Schema regex a string must match; null when unrestricted. */",
            "  readonly pattern: string | null;",
            "  /** The schema default, or NO_DEFAULT when the schema sets none. */",
            "  readonly default:",
            "    | string",
            "    | number",
            "    | boolean",
            "    | null",
            "    | readonly unknown[]",
            "    | typeof NO_DEFAULT;",
            '  /** "object" for arrays of objects (schedule slots), "scalar" otherwise. */',
            '  readonly arrayItems: "object" | "scalar" | null;',
            "  /** Array leaves: the items' own schema data; null for other leaves. */",
            "  readonly items: SettingsArrayItems | null;",
            "  /** The leaf's description, shown as the editor's help text. */",
            "  readonly help: string;",
            "}",
            "",
            "/**",
            " * The items of an array leaf, as the schema defines them (P5.3's array",
            " * editors read this, so a schema bump needs no new UI code).",
            " */",
            "export interface SettingsArrayItems {",
            '  /** The item type: a scalar, or an object with properties below. */',
            '  readonly type: "string" | "integer" | "boolean" | "object";',
            "  /** Scalar items: the regex each item must match; null when unrestricted. */",
            "  readonly pattern: string | null;",
            "  /** Scalar items: allowed values when the item is an enum; null otherwise. */",
            "  readonly enumValues: readonly string[] | null;",
            "  /** Object items: their properties, in schema order; empty for scalars. */",
            "  readonly properties: readonly SettingsItemProperty[];",
            "  /** Object items: the properties every item must carry. */",
            "  readonly required: readonly string[];",
            "}",
            "",
            "/**",
            " * One property of an array-of-objects leaf's items (the schedule's slots",
            " * today). `nullable` is the schema's base-or-null union: the editor's",
            " * explicit \"inherit\" state (F-SSV-18).",
            " */",
            "export interface SettingsItemProperty {",
            "  /** The property's name inside each item, e.g. \"start\". */",
            "  readonly name: string;",
            "  /** The property's type; an enum property is a string. */",
            '  readonly type: "string" | "integer" | "boolean";',
            "  /** Allowed values, in schema order; null when not an enum. */",
            "  readonly enumValues: readonly string[] | null;",
            "  /** Numeric minimum; null when unbounded. */",
            "  readonly minimum: number | null;",
            "  /** Numeric maximum; null when unbounded. */",
            "  readonly maximum: number | null;",
            "  /** Longest string length in code points; null when unbounded. */",
            "  readonly maxLength: number | null;",
            "  /** JSON Schema regex a string must match; null when unrestricted. */",
            "  readonly pattern: string | null;",
            "  /** True when the schema also allows null (the \"inherit\" state). */",
            "  readonly nullable: boolean;",
            "  /** The property's default, or NO_DEFAULT when the schema sets none. */",
            "  readonly default:",
            "    | string",
            "    | number",
            "    | boolean",
            "    | null",
            "    | readonly unknown[]",
            "    | typeof NO_DEFAULT;",
            "  /** The property's description, shown under the control. */",
            "  readonly help: string;",
            "}",
            "",
        ]

        table = [
            "/** Every leaf of the schema, in document order (mirrors settings.py `_fields()`). */",
            "export const SETTINGS_FIELDS: readonly SettingsField[] = [",
        ]
        for field in self.fields:
            enum_values = (
                "null"
                if field["enum"] is None
                else "[" + ", ".join(ts_string(v) for v in field["enum"]) + "]"
            )
            table.append("  {")
            table.append(f"    path: {ts_string(field['path'])},")
            table.append(f"    key: {ts_string(field['key'])},")
            table.append(f"    role: {ts_string(field['role'])},")
            table.append(f"    feature: {ts_string(field['feature'])},")
            table.append(f"    type: {ts_string(field['type'])},")
            table.append(f"    enumValues: {enum_values},")
            table.append(f"    minimum: {ts_value(field['minimum'])},")
            table.append(f"    maximum: {ts_value(field['maximum'])},")
            table.append(f"    minLength: {ts_value(field['min_length'])},")
            table.append(f"    maxLength: {ts_value(field['max_length'])},")
            pattern = "null" if field["pattern"] is None else ts_string(field["pattern"])
            table.append(f"    pattern: {pattern},")
            table.append(f"    default: {ts_value(field['default'])},")
            items = field["array_items"]
            array_items = "null" if items is None else ts_string(items)
            table.append(f"    arrayItems: {array_items},")
            table.append(f"    items: {render_items(field['items'])},")
            table.append(f"    help: {ts_string(field['help'])},")
            table.append("  },")
        table.append("];")

        defaults_src = [
            "/**",
            " * The document with every defaulted leaf filled in, plus every group",
            " * object as its empty instance; the editor starts from this.",
            " */",
            f"export const SETTINGS_DEFAULTS: {root} = {render_defaults(defaults, 0)};",
        ]

        return (
            "\n".join(
                [*header, *self.type_aliases, "", *self.interfaces, "", *table, "", *defaults_src]
            )
            + "\n"
        )


def render_defaults(node: dict[str, Any], depth: int) -> str:
    """A nested defaults object as TypeScript source."""
    if not node:
        return "{}"
    pad = "  " * (depth + 1)
    lines = ["{"]
    for key, value in node.items():
        if isinstance(value, dict):
            rendered = render_defaults(value, depth + 1)
            lines.append(f"{pad}{ts_property(key)}: {rendered},")
        else:
            lines.append(f"{pad}{ts_property(key)}: {ts_value(value)},")
    lines.append("  " * depth + "}")
    return "\n".join(lines)


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
