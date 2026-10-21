.PHONY: help doctor fmt fmt-check vet test test-go test-integration test-android test-setup lint build build-go build-aoa build-android shellcheck check clean

GO ?= go
RELAY_BIN ?= relay-bin
ANDROID_DIR := android-client
GRADLE := $(ANDROID_DIR)/gradlew

help:
	@echo "NetcoN OpenTether development targets:"
	@echo "  make doctor          Check local development prerequisites"
	@echo "  make fmt             Format Go source files"
	@echo "  make fmt-check       Verify Go formatting without changing files"
	@echo "  make vet             Run go vet"
	@echo "  make test            Run Go and Android tests"
	@echo "  make test-go         Run Go tests with the race detector"
	@echo "  make test-integration Run deterministic session/protocol tests"
	@echo "  make test-android    Run Android unit tests"
	@echo "  make test-setup      Run device-free setup/install shell tests"
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

fmt-check:
	@set -eu; \
	files="$$(find . -type f -name '*.go' -not -path './.git/*' -print)"; \
	if [ -z "$$files" ]; then \
		echo "No Go files found; formatting check passed."; \
	else \
		formatted="$$(gofmt -l $$files)"; \
		if [ -n "$$formatted" ]; then \
			echo "The following Go files are not gofmt-formatted:"; \
			printf '%s\\n' "$$formatted"; \
			exit 1; \
		fi; \
	fi

test: test-go test-android

vet:
	$(GO) vet ./...

test-go:
	$(GO) test -race -count=1 -timeout=120s ./...

# Deterministic, non-privileged relay tests. These use pipes/test doubles and
# never open /dev/net/tun, invoke adb, or change host networking.
test-integration:
	$(GO) test -race -count=1 -timeout=120s -run 'Test(Session|DirectWriter|FlushWriter|ListenPort|ParseAdbDevices|ParseAdbReverseList|Adb|SetupReverse|Poll|NewAdbWatcher|Network|ParseDefaultInterface|Tun|BuildFrame|ReadFrame|WriteFrame|Roundtrip|Config|ConnectionState|Oversized)' ./internal/... -v

test-android:
	cd $(ANDROID_DIR) && ./gradlew test --no-daemon --stacktrace

test-setup:
	bash tests/adb-install-test.sh

lint:
	cd $(ANDROID_DIR) && ./gradlew lint --no-daemon --stacktrace

build: build-go build-android

build-go:
	$(GO) build -trimpath -buildvcs=true -o $(RELAY_BIN) ./cmd/opentether

build-aoa:
	$(GO) build -trimpath -buildvcs=true -tags aoa -o $(RELAY_BIN) ./cmd/opentether

build-android:
	cd $(ANDROID_DIR) && ./gradlew assembleDebug --no-daemon --stacktrace

shellcheck:
	@set -eu; \
	scripts="$$(find . -type f -name '*.sh' -not -path './.git/*' -print)"; \
	if [ -z "$$scripts" ]; then \
		echo "No shell scripts found; ShellCheck passed."; \
	else \
		bash -n $$scripts; \
		shellcheck $$scripts; \
	fi

check: fmt-check vet test lint build-aoa test-setup shellcheck

clean:
	rm -f "$(RELAY_BIN)"
	$(GO) clean -cache -testcache
	cd $(ANDROID_DIR) && ./gradlew clean --no-daemon
