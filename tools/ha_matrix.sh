#!/usr/bin/env bash
# Integration HA-version matrix: run the integration suite against one
# pytest-homeassistant-custom-component (phacc) release. Every phacc release
# pins its homeassistant release exactly, so the phacc version selects the HA
# version. CI runs one leg per release (.github/workflows/ci.yml); the
# floor/pin/latest pairs are recorded in ADR 0001 item 8.
#
# Registry only (AGENTS.md rule 7): each leg builds its own venv under
# build/ha-matrix/<phacc> from a fresh PyPI resolution, and the locked project
# environment is never touched: the phacc/ha-ffmpeg/mutagen pins are read from
# integration/pyproject.toml into the leg's requirements file, and the phacc
# pin is flipped there with a uv override file (uv's documented mechanism for
# "test against a different version without changing the lockfile") rather
# than by editing uv.lock. homeassistant, pytest and pytest-asyncio are
# deliberately unpinned here: every phacc release pins that whole harness
# stack to its own HA release.
set -euo pipefail

phacc="${1:?usage: tools/ha_matrix.sh <pytest-homeassistant-custom-component version> [python]}"
python="${2:-3.13}"  # phacc 0.13.375 (HA 2026.10) requires Python 3.14

leg="build/ha-matrix/$phacc"
mkdir -p "$leg"

# A pin exactly as it appears in integration/pyproject.toml's dev group.
pin() {
  sed -n "s/.*\"$1==\([0-9.]*\)\".*/\1/p" integration/pyproject.toml | head -1
}
pinned_phacc=$(pin pytest-homeassistant-custom-component)
[ -n "$pinned_phacc" ] || { echo "ha_matrix: phacc pin not found in integration/pyproject.toml" >&2; exit 2; }
pinned_ffmpeg=$(pin ha-ffmpeg)
pinned_mutagen=$(pin mutagen)
[ -n "$pinned_ffmpeg" ] && [ -n "$pinned_mutagen" ] || {
  echo "ha_matrix: ha-ffmpeg/mutagen pins not found in integration/pyproject.toml" >&2
  exit 2
}

# The integration test dependency set: the integration dev group, minus the
# packages phacc pins itself, plus jsonschema, which the suite's TLP validation
# imports (root dev group). tabula-mockdevice is installed editable from the
# checkout for the e2e pair test - editable, like the workspace install, so
# its schema path (repo-root-relative, mockdevice/src/mockdevice/codec.py)
# resolves against this checkout instead of a bare wheel's site-packages.
cat > "$leg/requirements.txt" <<EOF
pytest-homeassistant-custom-component==$pinned_phacc
ha-ffmpeg==$pinned_ffmpeg
mutagen==$pinned_mutagen
jsonschema[format-nongpl]>=4.23
EOF
echo "pytest-homeassistant-custom-component==$phacc" > "$leg/override.txt"

uv venv --clear --python "$python" "$leg/venv"
# --prerelease=allow: the floor's HA release pins an exact pre-release
# transitively (homeassistant 2025.1.0 -> aiohasupervisor==0.2.2b5), which uv
# refuses by default. Every HA/phacc dependency is an exact pin, so allowing
# pre-releases cannot shift any other package's resolution.
uv pip install --python "$leg/venv" --prerelease=allow \
  -r "$leg/requirements.txt" --overrides "$leg/override.txt" --editable ./mockdevice

# The assist satellite platform imports HA's conversation and audio-pipeline
# code. These are homeassistant dependencies that the wheel does not declare
# (they are in HA's requirements.txt, generated from package_constraints.txt),
# and their pins move with the HA release, so each is taken from the leg's own
# constraint file rather than from a single pin that would break the other legs.
constraints=$(echo "$leg"/venv/lib/python*/site-packages/homeassistant/package_constraints.txt)
[ -f "$constraints" ] || { echo "ha_matrix: package_constraints.txt not found" >&2; exit 2; }
for package in hassil home-assistant-intents pymicro-vad pyspeex-noise; do
  version=$(sed -n "s/^${package}==\\(.*\\)$/\\1/p" "$constraints")
  [ -n "$version" ] || { echo "ha_matrix: $package pin not found in $constraints" >&2; exit 2; }
  uv pip install --python "$leg/venv" --prerelease=allow "${package}==${version}"
  echo "ha_matrix: $package $version"
done

ha=$("$leg/venv/bin/python" -c 'import homeassistant.const; print(homeassistant.const.__version__)')
echo "== integration tests: pytest-homeassistant-custom-component $phacc, HA $ha, Python $python =="
"$leg/venv/bin/pytest" integration/tests
