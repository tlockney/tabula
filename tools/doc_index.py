"""Indexes built from per-file records, so parallel PRs never edit shared lines.

- ADR index: read from each `docs/decisions/NNNN-*.md` header (title, Status,
  Packet). Nothing is committed; `make adr-index` prints the table.
- Provenance: the table in `PROVENANCE.md` is frozen; new rows live in one
  file per packet under `docs/provenance/`. `make provenance` prints the
  combined table.

`check()` validates both and is run by `tools/check_repo.py` (`make check`).

Usage: python -m tools.doc_index adr|provenance
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

ADR_DIR = "docs/decisions"
ADR_FILE = re.compile(r"^(\d{4})-[a-z0-9][a-z0-9-]*\.md$")
ADR_TITLE = re.compile(r"^# (\d{4}): (.+)$")

PROVENANCE_DIR = "docs/provenance"
# A packet ID (P3.15, P0.2r) or a task issue (issue-99).
PROVENANCE_FILE = re.compile(r"^(P\d+\.\d+[a-z]?|issue-\d+)\.md$")
PROVENANCE_HEADER = ("Behavior", "Source", "Packet")


@dataclass(frozen=True)
class Adr:
    number: str
    title: str
    status: str
    packet: str
    file: str


def _field(lines: list[str], name: str) -> str | None:
    prefix = f"{name}:"
    for line in lines[:12]:
        if line.startswith(prefix):
            return line[len(prefix) :].strip()
    return None


def read_adrs(root: Path) -> tuple[list[Adr], list[str]]:
    """Every ADR's header, sorted by number, plus problems found."""
    adrs: list[Adr] = []
    errors: list[str] = []
    seen: dict[str, str] = {}
    for path in sorted((root / ADR_DIR).glob("*.md")):
        if path.name in ("README.md", "TEMPLATE.md"):
            continue
        rel = f"{ADR_DIR}/{path.name}"
        match = ADR_FILE.match(path.name)
        if not match:
            errors.append(f"{rel}: ADR files are named NNNN-short-title.md")
            continue
        number = match.group(1)
        if number in seen:
            errors.append(
                f"{rel}: ADR number {number} is also used by {seen[number]}; renumber one"
            )
        seen[number] = rel
        lines = path.read_text(encoding="utf-8").splitlines()
        title = ADR_TITLE.match(lines[0]) if lines else None
        if not title or title.group(1) != number:
            errors.append(f"{rel}:1: first line must be '# {number}: Title'")
            continue
        status = _field(lines, "Status")
        if not status:
            errors.append(f"{rel}: missing 'Status:' line under the title")
            continue
        adrs.append(Adr(number, title.group(2).strip(), status, _field(lines, "Packet") or "", rel))
    return adrs, errors


def adr_table(adrs: list[Adr]) -> str:
    out = ["| ADR | Title | Status | Packet |", "| --- | --- | --- | --- |"]
    for a in adrs:
        name = Path(a.file).name
        out.append(f"| [{a.number}]({name}) | {a.title} | {a.status} | {a.packet} |")
    return "\n".join(out)


def _cells(line: str) -> list[str] | None:
    line = line.strip()
    if not (line.startswith("|") and line.endswith("|")):
        return None
    return [c.strip() for c in line[1:-1].split("|")]


def table_rows(text: str, where: str) -> tuple[list[list[str]], list[str]]:
    """Data rows of the first Behavior/Source/Packet table in text."""
    rows: list[list[str]] = []
    errors: list[str] = []
    lines = text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if _cells(line) == list(PROVENANCE_HEADER):
            start = i
            break
    if start is None:
        return [], [f"{where}: no '| Behavior | Source | Packet |' table"]
    for offset, line in enumerate(lines[start + 2 :], start=start + 3):
        cells = _cells(line)
        if cells is None:
            break
        if len(cells) != 3 or not all(cells):
            errors.append(f"{where}:{offset}: rows need three non-empty cells")
            continue
        rows.append(cells)
    if not rows and not errors:
        errors.append(f"{where}: table has no rows")
    return rows, errors


def read_provenance(root: Path) -> tuple[list[list[str]], list[str]]:
    """Frozen PROVENANCE.md rows, then each docs/provenance file's rows."""
    rows, errors = table_rows((root / "PROVENANCE.md").read_text(encoding="utf-8"), "PROVENANCE.md")
    directory = root / PROVENANCE_DIR
    for path in sorted(directory.glob("*.md")) if directory.is_dir() else []:
        if path.name == "README.md":
            continue
        rel = f"{PROVENANCE_DIR}/{path.name}"
        if not PROVENANCE_FILE.match(path.name):
            errors.append(f"{rel}: name it after the packet (P3.15.md) or task issue (issue-99.md)")
            continue
        more, problems = table_rows(path.read_text(encoding="utf-8"), rel)
        rows += more
        errors += problems
    return rows, errors


def provenance_table(rows: list[list[str]]) -> str:
    out = ["| Behavior | Source | Packet |", "| --- | --- | --- |"]
    out += [f"| {b} | {s} | {p} |" for b, s, p in rows]
    return "\n".join(out)


def check(root: Path) -> list[str]:
    return read_adrs(root)[1] + read_provenance(root)[1]


def main(argv: list[str]) -> int:
    if argv == ["adr"]:
        adrs, errors = read_adrs(ROOT)
        print(adr_table(adrs))
    elif argv == ["provenance"]:
        rows, errors = read_provenance(ROOT)
        print(provenance_table(rows))
    else:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    for e in errors:
        print(e, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
