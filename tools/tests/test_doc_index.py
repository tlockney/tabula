import subprocess
import sys
from pathlib import Path

import pytest

from tools import doc_index

TABLE = "| Behavior | Source | Packet |\n| --- | --- | --- |\n"


@pytest.fixture
def root(tmp_path: Path) -> Path:
    (tmp_path / "docs/decisions").mkdir(parents=True)
    (tmp_path / "docs/provenance").mkdir()
    (tmp_path / "docs/decisions/README.md").write_text("# index\n")
    (tmp_path / "docs/provenance/README.md").write_text("# how\n")
    (tmp_path / "PROVENANCE.md").write_text(f"# Provenance\n\n{TABLE}| old | F-A-01 | P0.5 |\n")
    return tmp_path


def adr(root: Path, name: str, text: str) -> None:
    (root / "docs/decisions" / name).write_text(text)


def test_adr_index_reads_headers_in_order(root: Path):
    adr(root, "0002-b.md", "# 0002: Bee\n\nStatus: accepted\nPacket: P1.1\n")
    adr(root, "0001-a.md", "# 0001: Ay\n\nStatus: proposed\n")
    adrs, errors = doc_index.read_adrs(root)
    assert errors == []
    assert [(a.number, a.title, a.status, a.packet) for a in adrs] == [
        ("0001", "Ay", "proposed", ""),
        ("0002", "Bee", "accepted", "P1.1"),
    ]
    assert "| [0002](0002-b.md) | Bee | accepted | P1.1 |" in doc_index.adr_table(adrs)


def test_duplicate_adr_numbers_fail(root: Path):
    adr(root, "0018-one.md", "# 0018: One\n\nStatus: accepted\n")
    adr(root, "0018-two.md", "# 0018: Two\n\nStatus: accepted\n")
    errors = doc_index.check(root)
    assert len(errors) == 1 and "also used by" in errors[0]


@pytest.mark.parametrize(
    ("name", "text"),
    [
        ("18-short.md", "# 0018: X\n\nStatus: accepted\n"),
        ("0018-x.md", "# 0019: X\n\nStatus: accepted\n"),
        ("0018-x.md", "# 0018: X\n\nno status\n"),
        ("0018-x.md", "# 0018: A | B\n\nStatus: accepted\n"),
    ],
)
def test_malformed_adrs_fail(root: Path, name: str, text: str):
    adr(root, name, text)
    assert len(doc_index.check(root)) == 1


def test_provenance_combines_frozen_and_packet_files(root: Path):
    (root / "docs/provenance/P3.15.md").write_text(f"# P3.15\n\n{TABLE}| new | F-B-02 | P3.15 |\n")
    issue = root / "docs/provenance/issue-99.md"
    issue.write_text(f"{TABLE}| task | independent design | #99 |\n")
    rows, errors = doc_index.read_provenance(root)
    assert errors == []
    assert [r[0] for r in rows] == ["old", "new", "task"]


@pytest.mark.parametrize(
    ("name", "text"),
    [
        ("start-page.md", f"{TABLE}| a | b | c |\n"),
        ("P3.15.md", f"{TABLE}| a | | c |\n"),
        ("P3.15.md", f"{TABLE}| a | b |\n"),
        ("P3.15.md", "| a | b | c |\n"),
        ("P3.15.md", TABLE),
        ("P3.15.md", "| Behavior | Source | Packet |\n| a | b | c |\n| d | e | f |\n"),
        ("P3.15.md", "| Behavior | Source | Packet |\n| --- | --- |\n| a | b | c |\n"),
        ("P3.15.md", f"{TABLE}| a | b | c |\n\nmore\n\n{TABLE}| d | e | f |\n"),
    ],
)
def test_malformed_provenance_files_fail(root: Path, name: str, text: str):
    (root / "docs/provenance" / name).write_text(text)
    assert len(doc_index.check(root)) == 1


def test_repo_itself_is_clean():
    assert doc_index.check(doc_index.ROOT) == []


def test_adr_packet_falls_back_to_issue(root: Path):
    adr(root, "0019-x.md", "# 0019: X\n\nStatus: accepted\nIssue: #105\n")
    assert doc_index.read_adrs(root)[0][0].packet == "#105"


def test_check_repo_runs_as_a_script():
    result = subprocess.run(
        [sys.executable, str(doc_index.ROOT / "tools/check_repo.py")],
        capture_output=True,
        text=True,
    )
    assert "ModuleNotFoundError" not in result.stderr
