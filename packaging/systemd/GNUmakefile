REPO_ROOT ?= $(abspath ../..)
DESTDIR ?=
APP_NAME ?= agiladmin
PREFIX ?= /opt
APP_HOME ?= $(PREFIX)/$(APP_NAME)
SYSTEMD_UNIT_DIR ?= /etc/systemd/system
DEFAULT_INSTANCE ?= $(if $(AGILADMIN_INSTANCE),$(AGILADMIN_INSTANCE),main)
AGILADMIN_VERSION ?= $(shell if git -C "$(REPO_ROOT)" rev-parse --git-dir >/dev/null 2>&1; then v=$$(git -C "$(REPO_ROOT)" describe --tags --match 'v[0-9]*.[0-9]*.[0-9]*' --abbrev=0 2>/dev/null | sed 's/^v//'); if [ -n "$$v" ]; then printf '%s' "$$v"; else printf '%s' "DEV-SNAPSHOT"; fi; else printf '%s' "DEV-SNAPSHOT"; fi)

UNIT_TARGETS := \
	$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)@.service \
	$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)-pocketbase.service

.PHONY: all help render enable clean

all: render

help:
	@printf '%s\n' \
	  'Available targets:' \
	  '  make render  Render the systemd unit templates into SYSTEMD_UNIT_DIR' \
	  '  make clean   Remove rendered unit files from SYSTEMD_UNIT_DIR' \
	  '' \
	  'Variables:' \
	  '  APP_NAME=$(APP_NAME)' \
	  '  APP_HOME=$(APP_HOME)' \
	  '  SYSTEMD_UNIT_DIR=$(SYSTEMD_UNIT_DIR)' \
	  '  DEFAULT_INSTANCE=$(DEFAULT_INSTANCE)' \
	  '  DESTDIR=$(DESTDIR)' \
	  '  AGILADMIN_VERSION=$(AGILADMIN_VERSION)'

render: $(UNIT_TARGETS)

enable:
	systemctl daemon-reload
	systemctl enable --now "$(APP_NAME)-pocketbase.service" "$(APP_NAME)@$(DEFAULT_INSTANCE).service"

$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)@.service: agiladmin@.service.in
	install -d "$(dir $@)"
	sed \
		-e 's|@APP_HOME@|$(APP_HOME)|g' \
		-e 's|@APP_NAME@|$(APP_NAME)|g' \
		-e 's|@AGILADMIN_VERSION@|$(AGILADMIN_VERSION)|g' \
		"$<" > "$@"
	chmod 0644 "$@"

$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)-pocketbase.service: pocketbase.service.in
	install -d "$(dir $@)"
	sed \
		-e 's|@APP_HOME@|$(APP_HOME)|g' \
		-e 's|@APP_NAME@|$(APP_NAME)|g' \
		-e 's|@AGILADMIN_VERSION@|$(AGILADMIN_VERSION)|g' \
		"$<" > "$@"
	chmod 0644 "$@"

clean:
	rm -f $(UNIT_TARGETS)
