import subprocess
from pathlib import Path

import pytest

from tools import check_repo

PROVENANCE = """# Provenance

## Restricted names

- Acme Widget

## Feature provenance
- not a name
"""


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    (tmp_path / "PROVENANCE.md").write_text(PROVENANCE)
    (tmp_path / "AGENTS.md").write_text("# rules\n")
    return tmp_path


def test_restricted_names_reads_only_its_section():
    assert check_repo.restricted_names(PROVENANCE) == ["Acme Widget"]


@pytest.mark.parametrize(
    "text", ["Acme Widget", "acme widget", "acme_widget", "AcmeWidget", "acme-widget"]
)
def test_name_pattern_matches_variants(text):
    assert check_repo.name_pattern("Acme Widget").search(text)


def test_name_pattern_ignores_partial():
    assert not check_repo.name_pattern("Acme Widget").search("widget only")


def test_flags_name_outside_allowed_paths(repo: Path):
    (repo / "src").mkdir()
    (repo / "src" / "app.py").write_text("x = 1\nAcmeWidgetClient = None\n")
    (repo / "README.md").write_text("Prior art: Acme Widget.\n")
    errors = check_repo.check_restricted_names(repo, check_repo.repo_files(repo))
    assert errors == ["src/app.py:2: restricted name (see AGENTS.md, Public framing)"]


def test_allows_decision_records(repo: Path):
    (repo / "docs" / "decisions").mkdir(parents=True)
    (repo / "docs" / "decisions" / "0009-x.md").write_text("Acme Widget\n")
    assert check_repo.check_restricted_names(repo, check_repo.repo_files(repo)) == []


def test_instruction_links(repo: Path):
    (repo / "CLAUDE.md").symlink_to("AGENTS.md")
    (repo / "GEMINI.md").write_text("divergent rules\n")
    errors = check_repo.check_instruction_links(repo)
    assert "GEMINI.md: must be a symlink to AGENTS.md" in errors
    assert not any(e.startswith("CLAUDE.md") for e in errors)
