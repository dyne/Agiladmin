CLOJURE ?= clj
JAR ?= target/0.4.0-SNAPSHOT-standalone.jar

.PHONY: help test run build clean

help:
	@printf '%s\n' \
	  'Available targets:' \
	  '  make test   Run the Midje test suite' \
	  '  make run    Start the web application' \
	  '  make build  Build the standalone uberjar' \
	  '  make clean  Remove build outputs'

test:
	$(CLOJURE) -M:test

run:
	$(CLOJURE) -M:run

build:
	$(CLOJURE) -T:build uber
	@printf 'Built %s\n' "$(JAR)"

clean:
	$(CLOJURE) -T:build clean
