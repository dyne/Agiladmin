CLOJURE ?= clj
AGILADMIN_VERSION ?= $(shell if [ -d .git ]; then v=$$(git describe --tags --match 'v[0-9]*.[0-9]*.[0-9]*' --abbrev=0 2>/dev/null | sed 's/^v//'); if [ -n "$$v" ]; then printf '%s' "$$v"; else printf '%s' DEV-SNAPSHOT; fi; else printf '%s' DEV-SNAPSHOT; fi)
PREFIX ?= /usr/local
DESTDIR ?=
APP_NAME ?= agiladmin
APP_HOME ?= $(PREFIX)/$(APP_NAME)
SYSTEMD_UNIT_DIR ?= $(PREFIX)/lib/systemd/system
POCKETBASE_APP_DIR ?= $(APP_HOME)/pocketbase
POCKETBASE_MIGRATIONS_DIR ?= $(POCKETBASE_APP_DIR)/migrations
JAR ?= target/$(AGILADMIN_VERSION)-standalone.jar

.PHONY: help test test-pocketbase-integration run dev run-pocketbase build install clean

help:
	@printf '%s\n' \
	  'Available targets:' \
	  '  make test   Run the Midje test suite' \
	  '  make test-pocketbase-integration  Run opt-in live PocketBase integration tests' \
	  '  make run    Start the web application with dev auth fallback' \
	  '  make dev    Start with request-time code reload enabled' \
	  '  make run-pocketbase CONF=doc/agiladmin.pocketbase.yaml  Start with a PocketBase config' \
	  '  make build  Build the standalone uberjar' \
	  '  make install  Install the jar, docs, examples, and systemd units' \
	  '  make clean  Remove build outputs'

test:
	$(CLOJURE) -M:test

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
	AGILADMIN_VERSION=$(AGILADMIN_VERSION) $(CLOJURE) -T:build uber
	@printf 'Built %s\n' "$(JAR)"

install: build
	install -d "$(DESTDIR)$(APP_HOME)/lib" \
		"$(DESTDIR)$(APP_HOME)/doc" \
		"$(DESTDIR)$(APP_HOME)/etc" \
		"$(DESTDIR)$(APP_HOME)/etc/main" \
		"$(DESTDIR)$(APP_HOME)/run" \
		"$(DESTDIR)$(APP_HOME)/log" \
		"$(DESTDIR)$(POCKETBASE_MIGRATIONS_DIR)" \
		"$(DESTDIR)$(SYSTEMD_UNIT_DIR)"
	install -m 0644 "$(JAR)" "$(DESTDIR)$(APP_HOME)/lib/agiladmin.jar"
	install -m 0644 README.md "$(DESTDIR)$(APP_HOME)/doc/README.md"
	install -m 0644 LICENSE.txt "$(DESTDIR)$(APP_HOME)/doc/LICENSE.txt"
	install -m 0644 doc/agiladmin.pocketbase.yaml "$(DESTDIR)$(APP_HOME)/etc/main/agiladmin.yaml"
	install -m 0644 doc/agiladmin.pocketbase.yaml "$(DESTDIR)$(APP_HOME)/etc/main/agiladmin.yaml.example"
	install -m 0644 packaging/systemd/pocketbase.env.example "$(DESTDIR)$(APP_HOME)/etc/pocketbase.env.example"
	install -m 0644 pb_migrations/*.js "$(DESTDIR)$(POCKETBASE_MIGRATIONS_DIR)/"
	sed \
		-e 's|@APP_HOME@|$(APP_HOME)|g' \
		-e 's|@APP_NAME@|$(APP_NAME)|g' \
		-e 's|@AGILADMIN_VERSION@|$(AGILADMIN_VERSION)|g' \
		packaging/systemd/agiladmin@.service.in > "$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)@.service"
	chmod 0644 "$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)@.service"
	sed \
		-e 's|@APP_HOME@|$(APP_HOME)|g' \
		-e 's|@APP_NAME@|$(APP_NAME)|g' \
		-e 's|@AGILADMIN_VERSION@|$(AGILADMIN_VERSION)|g' \
		packaging/systemd/pocketbase.service.in > "$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)-pocketbase.service"
	chmod 0644 "$(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)-pocketbase.service"
	@printf '%s\n' \
	  "Installed $(APP_NAME) under $(DESTDIR)$(APP_HOME)" \
	  "Systemd unit: $(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)@.service" \
	  "Systemd unit: $(DESTDIR)$(SYSTEMD_UNIT_DIR)/$(APP_NAME)-pocketbase.service" \
	  "PocketBase env example: $(DESTDIR)$(APP_HOME)/etc/pocketbase.env.example" \
	  "To enable on the target host: systemctl enable --now $(APP_NAME)-pocketbase.service $(APP_NAME)@main.service"

clean:
	$(CLOJURE) -T:build clean
