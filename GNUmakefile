CLOJURE ?= clj
BUILD_VERSION := $(if $(AGILADMIN_VERSION),$(AGILADMIN_VERSION),$(shell if [ -d .git ]; then v=$$(git describe --tags --match 'v[0-9]*.[0-9]*.[0-9]*' --abbrev=0 2>/dev/null | sed 's/^v//'); if [ -n "$$v" ]; then printf '%s' "$$v"; else printf '%s' DEV-SNAPSHOT; fi; else printf '%s' DEV-SNAPSHOT; fi))
PREFIX ?= /opt
DESTDIR ?=
APP_NAME ?= agiladmin
APP_HOME ?= $(PREFIX)/$(APP_NAME)
SYSTEMD_UNIT_DIR ?= /etc/systemd/system
DEFAULT_INSTANCE ?= $(if $(AGILADMIN_INSTANCE),$(AGILADMIN_INSTANCE),main)
POCKETBASE_APP_DIR ?= $(APP_HOME)/pocketbase
POCKETBASE_MIGRATIONS_DIR ?= $(POCKETBASE_APP_DIR)/migrations
JAR ?= target/$(BUILD_VERSION)-standalone.jar

.PHONY: help test test-e2e test-pocketbase-integration run dev run-pocketbase build install clean

help:
	@printf '%s\n' \
	  'Available targets:' \
	  '  make test   Run the Midje test suite' \
	  '  make test-e2e  Run Playwright login/upload browser tests with dev auth' \
	  '  make test-pocketbase-integration  Run opt-in live PocketBase integration tests' \
	  '  make run    Start the web application with dev auth fallback' \
	  '  make dev    Start with request-time code reload enabled' \
	  '  make run-pocketbase CONF=doc/agiladmin.pocketbase.yaml  Start with a PocketBase config' \
	  '  make build  Build the standalone uberjar' \
	  '  make install  Install the jar, docs, examples, and systemd units' \
	  '  make clean  Remove build outputs'

test:
	$(CLOJURE) -M:test

test-e2e:
	npm run test:e2e

test-pocketbase-integration:
	AGILADMIN_PB_IT=1 \
	AGILADMIN_PB_BASE_URL=$${AGILADMIN_PB_BASE_URL:-http://127.0.0.1:8090} \
	AGILADMIN_PB_USERS_COLLECTION=$${AGILADMIN_PB_USERS_COLLECTION:-users} \
	AGILADMIN_PB_SUPERUSER_EMAIL=$${AGILADMIN_PB_SUPERUSER_EMAIL:-admin@example.org} \
	AGILADMIN_PB_SUPERUSER_PASSWORD=$${AGILADMIN_PB_SUPERUSER_PASSWORD:-change-me} \
	AGILADMIN_PB_IT_USER_EMAIL=$${AGILADMIN_PB_IT_USER_EMAIL:-agiladmin-it@example.org} \
	AGILADMIN_PB_IT_USER_PASSWORD=$${AGILADMIN_PB_IT_USER_PASSWORD:-agiladmin-it-secret} \
	$(CLOJURE) -M:test

run:
	AGILADMIN_DEV_AUTH=1 $(CLOJURE) -M:run

dev:
	AGILADMIN_DEV_AUTH=1 AGILADMIN_DEV_RELOAD=1 $(CLOJURE) -M:run

CONF ?= doc/agiladmin.pocketbase.yaml

run-pocketbase:
	AGILADMIN_CONF=$(CONF) $(CLOJURE) -M:run

build:
	$(CLOJURE) -T:build uber
	@printf 'Built %s\n' "$(JAR)"

install: build
	install -d "$(DESTDIR)$(APP_HOME)/lib" \
		"$(DESTDIR)$(APP_HOME)/doc" \
		"$(DESTDIR)$(APP_HOME)/etc" \
		"$(DESTDIR)$(APP_HOME)/$(DEFAULT_INSTANCE)" \
		"$(DESTDIR)$(APP_HOME)/run" \
		"$(DESTDIR)$(APP_HOME)/log" \
		"$(DESTDIR)$(POCKETBASE_MIGRATIONS_DIR)" \
		"$(DESTDIR)$(SYSTEMD_UNIT_DIR)"
	install -m 0644 "$(JAR)" "$(DESTDIR)$(APP_HOME)/lib/agiladmin.jar"
	install -m 0644 README.md "$(DESTDIR)$(APP_HOME)/doc/README.md"
	install -m 0644 LICENSE.txt "$(DESTDIR)$(APP_HOME)/doc/LICENSE.txt"
	sed \
		-e 's|@APP_HOME@|$(APP_HOME)|g' \
		-e 's|@INSTANCE@|$(DEFAULT_INSTANCE)|g' \
		doc/agiladmin.pocketbase.yaml.in > "$(DESTDIR)$(APP_HOME)/$(DEFAULT_INSTANCE)/agiladmin.yaml"
	chmod 0644 "$(DESTDIR)$(APP_HOME)/$(DEFAULT_INSTANCE)/agiladmin.yaml"
	sed \
		-e 's|@APP_HOME@|$(APP_HOME)|g' \
		-e 's|@INSTANCE@|$(DEFAULT_INSTANCE)|g' \
		doc/agiladmin.pocketbase.yaml.in > "$(DESTDIR)$(APP_HOME)/$(DEFAULT_INSTANCE)/agiladmin.yaml.example"
	chmod 0644 "$(DESTDIR)$(APP_HOME)/$(DEFAULT_INSTANCE)/agiladmin.yaml.example"
	install -m 0644 packaging/systemd/pocketbase.env.example "$(DESTDIR)$(APP_HOME)/etc/pocketbase.env.example"
	install -m 0644 pb_migrations/*.js "$(DESTDIR)$(POCKETBASE_MIGRATIONS_DIR)/"
	sed \
		-e 's|@APP_HOME@|$(APP_HOME)|g' \
		-e 's|@APP_NAME@|$(APP_NAME)|g' \
		packaging/systemd/agiladmin@.service.in > "$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)@.service"
	chmod 0644 "$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)@.service"
	sed \
		-e 's|@APP_HOME@|$(APP_HOME)|g' \
		-e 's|@APP_NAME@|$(APP_NAME)|g' \
		packaging/systemd/pocketbase.service.in > "$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)-pocketbase.service"
	chmod 0644 "$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)-pocketbase.service"
	@printf '%s\n' \
	  "Installed $(APP_NAME) under $(DESTDIR)$(APP_HOME)" \
	  "Systemd unit: $(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)@.service" \
	  "Systemd unit: $(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)-pocketbase.service" \
	  "Default Agiladmin instance: $(DEFAULT_INSTANCE)" \
	  "PocketBase env example: $(DESTDIR)$(APP_HOME)/etc/pocketbase.env.example" \
	  "To enable on the target host: systemctl enable --now $(APP_NAME)-pocketbase.service $(APP_NAME)@$(DEFAULT_INSTANCE).service"

clean:
	$(CLOJURE) -T:build clean
