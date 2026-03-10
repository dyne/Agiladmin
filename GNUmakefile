CLOJURE ?= clj
JAR ?= target/0.4.0-SNAPSHOT-standalone.jar

.PHONY: help test run run-pocketbase build clean

help:
	@printf '%s\n' \
	  'Available targets:' \
	  '  make test   Run the Midje test suite' \
	  '  make run    Start the web application with dev auth fallback' \
	  '  make run-pocketbase CONF=doc/agiladmin.pocketbase.yaml  Start with a PocketBase config' \
	  '  make build  Build the standalone uberjar' \
	  '  make clean  Remove build outputs'

test:
	$(CLOJURE) -M:test

run:
	AGILADMIN_DEV_AUTH=1 $(CLOJURE) -M:run

CONF ?= doc/agiladmin.pocketbase.yaml

run-pocketbase:
	AGILADMIN_CONF=$(CONF) $(CLOJURE) -M:run

build:
	$(CLOJURE) -T:build uber
	@printf 'Built %s\n' "$(JAR)"

clean:
	$(CLOJURE) -T:build clean
