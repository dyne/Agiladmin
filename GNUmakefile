CLOJURE ?= clj
JAR ?= target/0.4.0-SNAPSHOT-standalone.jar
DEV_AUTH ?= 0

.PHONY: help test test-pocketbase-integration run dev dev-auth run-pocketbase build clean

help:
	@printf '%s\n' \
	  'Available targets:' \
	  '  make test   Run the Midje test suite' \
	  '  make test-pocketbase-integration  Run opt-in live PocketBase integration tests' \
	  '  make run    Start the web application with dev auth fallback' \
	  '  make dev    Start with request-time code reload enabled' \
	  '  make dev-auth  Start with request-time code reload and dev auth users' \
	  '  make run-pocketbase CONF=doc/agiladmin.pocketbase.yaml  Start with a PocketBase config' \
	  '  make build  Build the standalone uberjar' \
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
	AGILADMIN_CONF=$(CONF) AGILADMIN_DEV_AUTH=$(DEV_AUTH) AGILADMIN_DEV_RELOAD=1 $(CLOJURE) -M:run

dev-auth:
	AGILADMIN_DEV_AUTH=1 AGILADMIN_DEV_RELOAD=1 $(CLOJURE) -M:run

CONF ?= doc/agiladmin.pocketbase.yaml

run-pocketbase:
	AGILADMIN_CONF=$(CONF) $(CLOJURE) -M:run

build:
	$(CLOJURE) -T:build uber
	@printf 'Built %s\n' "$(JAR)"

clean:
	$(CLOJURE) -T:build clean
