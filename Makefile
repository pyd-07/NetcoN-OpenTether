.PHONY: help doctor fmt test test-go test-android lint build build-go build-aoa build-android shellcheck check clean

GO ?= go
ANDROID_DIR := android-client
GRADLE := $(ANDROID_DIR)/gradlew

help:
	@echo "NetcoN OpenTether development targets:"
	@echo "  make doctor          Check local development prerequisites"
	@echo "  make fmt             Format Go source files"
	@echo "  make test            Run Go and Android tests"
	@echo "  make test-go         Run Go tests with the race detector"
	@echo "  make test-android    Run Android unit tests"
	@echo "  make lint            Run Android lint"
	@echo "  make build           Build Go relays and debug APK"
	@echo "  make build-go        Build the standard Go relay"
	@echo "  make build-aoa       Build the AOA-enabled Go relay"
	@echo "  make build-android   Build the Android debug APK"
	@echo "  make shellcheck      Check shell scripts"
	@echo "  make check           Run the local CI-equivalent checks"
	@echo "  make clean           Remove generated build artifacts"

doctor:
	@set -eu; \
	command -v $(GO) >/dev/null || { echo "go is required"; exit 1; }; \
	command -v java >/dev/null || { echo "java is required"; exit 1; }; \
	command -v make >/dev/null || { echo "make is required"; exit 1; }; \
	command -v shellcheck >/dev/null || { echo "shellcheck is required"; exit 1; }; \
	command -v pkg-config >/dev/null || { echo "pkg-config is required for AOA builds"; exit 1; }; \
	$(GO) version; \
	java -version 2>&1 | head -n 1; \
	$(GRADLE) --version >/dev/null; \
	echo "Development environment looks ready."

fmt:
	$(GO) fmt ./...

test: test-go test-android

test-go:
	$(GO) test -race ./...

test-android:
	cd $(ANDROID_DIR) && ./gradlew test --no-daemon --stacktrace

lint:
	cd $(ANDROID_DIR) && ./gradlew lint --no-daemon --stacktrace

build: build-go build-android

build-go:
	$(GO) build -trimpath -buildvcs=true ./...

build-aoa:
	$(GO) build -trimpath -buildvcs=true -tags aoa ./...

build-android:
	cd $(ANDROID_DIR) && ./gradlew assembleDebug --no-daemon --stacktrace

shellcheck:
	@set -eu; \
	scripts="$$(find . -type f -name '*.sh' -not -path './.git/*' -print)"; \
	if [ -z "$$scripts" ]; then \
		echo "No shell scripts found; ShellCheck passed."; \
	else \
		shellcheck $$scripts; \
	fi

check: fmt test lint build-aoa shellcheck

clean:
	$(GO) clean -cache -testcache
	cd $(ANDROID_DIR) && ./gradlew clean --no-daemon
