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
	$(UV) sync --locked --all-packages --all-groups
	$(NPM) ci --no-fund --no-audit

# --- Quality ---

.PHONY: check
check: lint frames test android site ## Everything CI checks

.PHONY: lint
lint: ## Lint, format check and repo policy checks
	git ls-files -z '*.sh' | xargs -0 $(UV) run shellcheck -x --source-path=SCRIPTDIR
	$(UV) run ruff check .
	$(UV) run ruff format --check .
	$(UV) run python tools/check_repo.py

.PHONY: fmt
fmt: ## Apply formatting and auto-fixable lint
	$(UV) run ruff format .
	$(UV) run ruff check --fix .

.PHONY: test
test: ## Run tests (repo, mock device, integration incl. conformance against it)
	$(UV) run pytest

# --- Integration ---

.PHONY: integration-test
integration-test: ## Custom integration tests (P1.2+), including the mock e2e pair
	$(UV) run pytest integration/tests

# HA-version matrix (ADR 0001 item 8): one leg per supported release. The
# pytest-homeassistant-custom-component (phacc) version pins its homeassistant
# release exactly; legs resolve from PyPI into build/ha-matrix/, so the
# project's uv.lock is never edited. HA_PYTHON is required by newer phacc
# releases (0.13.375+, HA 2026.10, needs 3.14).
.PHONY: test-ha-matrix
test-ha-matrix: ## Integration tests on one HA release: HA_PHACC=<phacc version> [HA_PYTHON=<python>]
	@test -n "$(HA_PHACC)" || { echo "usage: make test-ha-matrix HA_PHACC=<pytest-homeassistant-custom-component version> [HA_PYTHON=<python>]" >&2; exit 2; }
	@tools/ha_matrix.sh "$(HA_PHACC)" "$(HA_PYTHON)"

# --- Mock device ---

.PHONY: mock-test
mock-test: ## Mock device unit tests and conformance suite (also part of make test)
	$(UV) run pytest mockdevice/tests mockdevice/conformance

.PHONY: conformance
conformance: ## Conformance suite against TABULA_HA_URL with a paired TABULA_STORE
	@test -n "$${TABULA_HA_URL:-}" || { echo "set TABULA_HA_URL and TABULA_STORE" >&2; exit 2; }
	$(UV) run pytest mockdevice/conformance

# --- Android ---

.PHONY: setup-android
setup-android: ## Verify or install the Android toolchain (JDK 17-21, Android SDK)
	@tools/setup_android.sh

.PHONY: android-test
android-test: ## Android unit tests via the Gradle wrapper (skipped without a JDK)
	@tools/android_test.sh

.PHONY: android-apk
android-apk: ## Build the debug APK for device provisioning (skipped without a JDK)
	@tools/android_build.sh

.PHONY: codegen
codegen: ## Regenerate the Kotlin settings classes from the schema
	$(UV) run python tools/codegen.py

.PHONY: codegen-drift
codegen-drift: ## Fail if the generated Kotlin settings classes are stale
	$(UV) run python tools/codegen.py --check

.PHONY: android
android: codegen-drift android-test ## Codegen drift check and Android unit tests

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
