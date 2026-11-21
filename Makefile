# Tabula build entry point. CI runs these same targets; see docs/DEVELOPMENT.md.
# Component targets (integration, mockdevice, android, panel) are added by the
# packets that create those components.

SHELL := bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help
MAKEFLAGS += --no-print-directory

UV  := uv
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
check: lint test site ## Everything CI checks

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
