#!/usr/bin/env bash
# Install the toolchain `make setup` needs, from package registries only
# (PyPI, npm), so it works on cloud VMs that block arbitrary downloads.
# Idempotent. See docs/DEVELOPMENT.md.
set -euo pipefail

need() { command -v "$1" >/dev/null 2>&1; }
die() { echo "bootstrap: $*" >&2; exit 1; }

# uv manages Python and all Python dependencies.
if ! need uv; then
  need python3 || die "python3 not found; install Python 3.13+ or uv (https://docs.astral.sh/uv/)"
  echo "bootstrap: installing uv from PyPI"
  python3 -m pip install --user --quiet uv \
    || python3 -m pip install --user --quiet --break-system-packages uv \
    || die "could not install uv with pip"
  export PATH="$HOME/.local/bin:$PATH"
  need uv || die "uv installed but not on PATH; add ~/.local/bin to PATH"
fi

# Node builds the website (and the panel from Phase 5).
need node || die "node not found; install Node $(cat .node-version) or newer"
node_major=$(node -p 'process.versions.node.split(".")[0]')
[ "$node_major" -ge "$(cat .node-version)" ] || die "node $node_major found; need $(cat .node-version)+"

echo "bootstrap: uv $(uv --version | cut -d' ' -f2), node $(node --version)"
