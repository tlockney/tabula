# Tabula build entry point. CI runs these same targets; see docs/DEVELOPMENT.md.
# Component targets (integration, android, panel) are added by the packets that
# create those components.

SHELL := bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help
MAKEFLAGS += --no-print-directory

UV  := uv
# Isolate uv from user- and system-level config: a global uv.toml (such as a
# rolling exclude-newer) would otherwise make `uv sync --locked` fail. See
# tools/uv.toml and docs/DEVELOPMENT.md.
export UV_CONFIG_FILE := $(CURDIR)/tools/uv.toml
# Same for the env-var forms of resolution settings.
unexport UV_EXCLUDE_NEWER UV_RESOLUTION UV_PRERELEASE UV_FORK_STRATEGY
NPM := npm --prefix website

.PHONY: help
help: ## List targets
	@grep -hE '^[a-z][a-z-]*:.*## ' $(MAKEFILE_LIST) \
	  | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

# --- Setup ---

.PHONY: setup
setup: ## Install pinned tools and dependencies (idempotent)
	@tools/bootstrap.sh
	$(UV) sync --locked
	$(NPM) ci --no-fund --no-audit

# --- Quality ---

.PHONY: check
check: lint frames test site ## Everything CI checks

.PHONY: lint
lint: ## Lint, format check and repo policy checks
	$(UV) run ruff check .
	$(UV) run ruff format --check .
	$(UV) run python tools/check_repo.py

.PHONY: fmt
fmt: ## Apply formatting and auto-fixable lint
	$(UV) run ruff format .
	$(UV) run ruff check --fix .

.PHONY: test
test: ## Run tests
	$(UV) run pytest

# --- Mock device ---

.PHONY: mock-test
mock-test: ## Mock device unit tests and conformance suite (also part of make test)
	$(UV) run pytest mockdevice/tests mockdevice/conformance

.PHONY: conformance
conformance: ## Conformance suite against TABULA_HA_URL with a paired TABULA_STORE
	@test -n "$${TABULA_HA_URL:-}" || { echo "set TABULA_HA_URL and TABULA_STORE" >&2; exit 2; }
	$(UV) run pytest mockdevice/conformance

# --- Protocol ---

.PHONY: frames
frames: ## Validate golden TLP fixtures against the protocol schema
	$(UV) run python tools/validate_frames.py

# --- Website ---

.PHONY: site
site: ## Build the website into build/site
	$(NPM) run build

.PHONY: site-dev
site-dev: ## Serve the website with live reload
	$(NPM) run dev

# --- Worktrees ---

.PHONY: worktree
worktree: ## Create .worktrees/$(BRANCH) on a new branch from origin/main
	@test -n "$(BRANCH)" || { echo "usage: make worktree BRANCH=name" >&2; exit 2; }
	git fetch origin main
	git worktree add -b "$(BRANCH)" ".worktrees/$(BRANCH)" origin/main
	@echo "next: cd .worktrees/$(BRANCH) && make setup"

# --- Housekeeping ---

.PHONY: clean
clean: ## Remove build output
	rm -rf build
