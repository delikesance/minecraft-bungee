.DEFAULT_GOAL := help
COMPOSE ?= compose.local.yaml
SERVER ?= creative
WORLD ?= world
ZIP ?=
OUT ?=
export COMPOSE SERVER WORLD ZIP OUT

.PHONY: export-all zip-all help status up stop configure export zip import restore inspect export-panel import-panel backup test-archives
help: ## Show operator commands
	@python3 scripts/world_archive.py help
status: ## Show container status
	@python3 scripts/world_archive.py status
up: ## Build/start locally; pull/start pinned images in production
	@python3 scripts/world_archive.py up
stop: ## Stop the selected Minecraft server
	@python3 scripts/world_archive.py stop
configure: ## Initialize missing secrets and private forwarding; preserve existing secrets
	@python3 scripts/world_archive.py configure
export: ## Create a portable server ZIP; briefly stop and resume only this server
	@python3 scripts/world_archive.py export
zip: export ## Alias for export
export-all: ## Create three server ZIPs and one panel ZIP
	@python3 scripts/world_archive.py export-all
zip-all: export-all ## Alias for export-all
import: ## Inspect, stage, back up and restore ZIP; resume only if previously running
	@python3 scripts/world_archive.py import
restore: import ## Alias for import
inspect: ## Inspect a portable ZIP or a plain Minecraft world ZIP without changing data
	@python3 scripts/world_archive.py inspect
export-panel: ## Export the panel database, roles and administrator separately
	@python3 scripts/world_archive.py export-panel
import-panel: ## Restore a panel ZIP with an automatic previous-state backup
	@python3 scripts/world_archive.py import-panel
backup: ## Full network backup, including secrets; leaves the network stopped
	@python3 scripts/world_archive.py backup
test-archives: ## Run isolated ZIP safety and restoration tests
	@python3 -m unittest discover -s scripts -p 'test_world_archive.py' -v
