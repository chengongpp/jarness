# Jarness build. `make` packages everything into dist/.

MVN ?= mvn
CARGO ?= cargo

DIST := dist
CLI_JAR := jarness-cli/target/jarness.jar
DIST_JAR := $(DIST)/jarness.jar
EXECUTOR_MANIFEST := executor/Cargo.toml
EXECUTOR_BIN := executor/target/release/jarness-executor
DIST_EXECUTOR := $(DIST)/executor/jarness-executor

.PHONY: all dist jar executor test it clean

all: dist

## dist: package the runtime and stage dist/jarness.jar, dist/mods/*.jar, dist/executor/*
dist: jar executor
	@echo "==> $(DIST_JAR) + $(DIST_EXECUTOR)"

## jar: package the shaded runtime jar
jar:
	$(MVN) -q package -DskipTests
	mkdir -p $(DIST)
	cp $(CLI_JAR) $(DIST_JAR)

## executor: build the Rust executor and stage it under dist/
executor:
	$(CARGO) build --release --manifest-path $(EXECUTOR_MANIFEST)
	mkdir -p $(DIST)/executor
	cp $(EXECUTOR_BIN) $(DIST_EXECUTOR)

## test: unit tests (integration tests excluded)
test:
	$(MVN) -q test

## it: live-endpoint integration tests (uses installed providers.toml, falls back to tmp/endpoint.txt)
it: executor
	$(MVN) -pl jarness-core test -Djarness.it=true -Djarness.excludedGroups= -Dgroups=integration \
		-Djarness.executor=$(abspath $(DIST_EXECUTOR))

## clean: remove build outputs and dist
clean:
	$(MVN) -q clean
	rm -rf $(DIST)
