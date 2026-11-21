"""Repository policy checks run by `make check`.

1. Tool instruction files are symlinks to AGENTS.md (AGENTS.md, "Start here").
2. Restricted names appear only in the files the public framing rule allows
   (AGENTS.md, "Public framing"). The names are read from PROVENANCE.md so that
   they never appear in code.

Exit status is non-zero when any check fails.
"""

from __future__ import annotations

import re
import subprocess
import sys
from collections.abc import Iterable
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

INSTRUCTION_LINKS = (
    "CLAUDE.md",
    "GEMINI.md",
    ".cursorrules",
    ".github/copilot-instructions.md",
)

# Paths (relative, POSIX) where restricted names may appear. A trailing slash
# allows everything under that directory.
ALLOWED_NAME_PATHS = (
    "README.md",
    "AGENTS.md",
    "PROVENANCE.md",
    "docs/PLAN.md",
    "docs/decisions/",
)

SECTION_HEADING = "## Restricted names"


def check_instruction_links(root: Path) -> list[str]:
    errors = []
    target = (root / "AGENTS.md").resolve()
    for rel in INSTRUCTION_LINKS:
        path = root / rel
        if not path.is_symlink():
            errors.append(f"{rel}: must be a symlink to AGENTS.md")
        elif path.resolve() != target:
            errors.append(f"{rel}: points to {path.resolve()}, expected AGENTS.md")
    return errors


def restricted_names(provenance_text: str) -> list[str]:
    """Return the list items under the restricted-names heading."""
    names: list[str] = []
    in_section = False
    for line in provenance_text.splitlines():
        if line.startswith("## "):
            in_section = line.strip() == SECTION_HEADING
            continue
        if in_section and line.startswith("- "):
            name = line[2:].strip()
            if name:
                names.append(name)
    return names


def name_pattern(name: str) -> re.Pattern[str]:
    """Match a name case-insensitively, also as an identifier (snake, kebab, camel)."""
    words = [re.escape(w) for w in name.split()]
    return re.compile(r"[\s_.\-]*".join(words), re.IGNORECASE)


def is_allowed(rel: str) -> bool:
    return any(rel.startswith(p) if p.endswith("/") else rel == p for p in ALLOWED_NAME_PATHS)


def repo_files(root: Path) -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    ).stdout
    return sorted({p for p in out.decode().split("\0") if p})


def check_restricted_names(root: Path, files: Iterable[str]) -> list[str]:
    provenance = root / "PROVENANCE.md"
    if not provenance.is_file():
        return ["PROVENANCE.md: missing"]
    names = restricted_names(provenance.read_text(encoding="utf-8"))
    if not names:
        return [f"PROVENANCE.md: no names under '{SECTION_HEADING}'"]
    patterns = [name_pattern(n) for n in names]

    errors = []
    for rel in files:
        path = root / rel
        if is_allowed(rel) or path.is_symlink() or not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for lineno, line in enumerate(text.splitlines(), start=1):
            if any(p.search(line) for p in patterns):
                errors.append(f"{rel}:{lineno}: restricted name (see AGENTS.md, Public framing)")
    return errors


def main() -> int:
    errors = check_instruction_links(ROOT)
    errors += check_restricted_names(ROOT, repo_files(ROOT))
    for e in errors:
        print(e, file=sys.stderr)
    if errors:
        print(f"check_repo: {len(errors)} problem(s)", file=sys.stderr)
        return 1
    print("check_repo: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
