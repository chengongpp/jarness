# Jarness build. `make` packages everything into dist/.

MVN ?= mvn

DIST := dist
CLI_JAR := jarness-cli/target/jarness.jar
DIST_JAR := $(DIST)/jarness.jar

.PHONY: all dist test it clean

all: dist

## dist: package the runtime and stage dist/jarness.jar + dist/mods/*.jar
dist:
	$(MVN) -q package -DskipTests
	mkdir -p $(DIST)
	cp $(CLI_JAR) $(DIST_JAR)
	@echo "==> $(DIST_JAR)"

## test: unit tests (integration tests excluded)
test:
	$(MVN) -q test

## it: live-endpoint integration tests (needs tmp/endpoint.txt)
it:
	$(MVN) -pl jarness-core test -Djarness.it=true -Djarness.excludedGroups= -Dgroups=integration

## clean: remove build outputs and dist
clean:
	$(MVN) -q clean
	rm -rf $(DIST)
