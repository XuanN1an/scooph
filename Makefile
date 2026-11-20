# Scooph 构建入口。
#
#   make engine   gomobile bind 出 engine/build/scooph-engine.aar
#   make apk      构建引擎并打 Debug APK
#   make check    格式化检查 + Go 测试 + Kotlin 单测 + lint（默认目标）
#
# 所有工具与路径都可用 `make VAR=...` 或环境变量覆盖。本机沙箱把 Android SDK
# 与工具链缓存放在仓库内（local.properties、.toolchain/，均已 gitignore），
# 这里只做自动探测，不改变构建本身的逻辑。

SHELL := /bin/bash
.DEFAULT_GOAL := check

GO          ?= go
GOFMT       ?= gofmt
GOMOBILE    ?= gomobile
GRADLEW     ?= ./gradlew
ANDROID_API ?= 26

ENGINE_AAR := engine/build/scooph-engine.aar
GO_FILES   := $(shell find engine -name '*.go' -not -path '*/build/*' 2>/dev/null)

# Gradle 已把 Android SDK 位置写在 local.properties，make 复用同一处配置。
ANDROID_HOME     ?= $(shell sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)
ANDROID_SDK_ROOT ?= $(ANDROID_HOME)
# gomobile 需要 NDK，默认取 SDK 下版本号最大的 NDK。
ANDROID_NDK_HOME ?= $(shell ls -d $(ANDROID_HOME)/ndk/* 2>/dev/null | sort -V | tail -1)

# 本机沙箱工具链：仓库内 .toolchain/ 存在时，用其中的 Go 缓存与 gomobile，
# 避免写仓库外的目录；普通开发机没有该目录，走系统默认环境。
TOOLCHAIN := $(CURDIR)/.toolchain
ifeq ($(wildcard $(TOOLCHAIN)/gopath),)
GO_ENV    :=
GOBIN_DIR := $(shell $(GO) env GOBIN)
ifeq ($(strip $(GOBIN_DIR)),)
GOBIN_DIR := $(shell $(GO) env GOPATH)/bin
endif
else
GO_ENV    := GOMODCACHE="$(TOOLCHAIN)/go-modcache" GOCACHE="$(TOOLCHAIN)/go-cache" GOPATH="$(TOOLCHAIN)/gopath" GOBIN="$(TOOLCHAIN)/gobin" GOTOOLCHAIN=local
GOBIN_DIR := $(TOOLCHAIN)/gobin
endif

# gomobile 可能在 PATH，也可能只装在仓库内 GOBIN：优先用绝对路径，避免 bash 里找不到。
X_MOBILE_VERSION := $(shell sed -n 's/.*golang.org\/x\/mobile \(v[^ ]*\).*/\1/p' engine/go.mod)
ifeq ($(OS),Windows_NT)
GOMOBILE_BIN := $(or $(wildcard $(GOBIN_DIR)/gomobile.exe),$(wildcard $(GOBIN_DIR)/gomobile),$(GOMOBILE))
else
GOMOBILE_BIN := $(or $(wildcard $(GOBIN_DIR)/gomobile),$(GOMOBILE))
endif

.PHONY: tools engine apk check gofmt-check engine-test app-check

tools:
	@echo "==> make tools: gomobile@$(X_MOBILE_VERSION) -> $(GOBIN_DIR)"
	@test -n "$(X_MOBILE_VERSION)" || { echo "错误：读不到 engine/go.mod 里的 golang.org/x/mobile 版本" >&2; exit 1; }
	@cd engine && $(GO_ENV) $(GO) install golang.org/x/mobile/cmd/gomobile@$(X_MOBILE_VERSION)

engine:
	@echo "==> make engine: gomobile bind -> $(ENGINE_AAR)"
	@test -n "$(ANDROID_HOME)" || { echo "错误：ANDROID_HOME 为空，且 local.properties 里没有 sdk.dir" >&2; exit 1; }
	@test -n "$(ANDROID_NDK_HOME)" || { echo "错误：未找到 NDK，请安装 SDK ndk 或设置 ANDROID_NDK_HOME" >&2; exit 1; }
	@if ! command -v "$(GOMOBILE_BIN)" >/dev/null 2>&1 && [ ! -x "$(GOMOBILE_BIN)" ]; then \
		echo "错误：找不到 gomobile。先运行 make tools，或 export PATH=\"$(GOBIN_DIR):\$$PATH\"" >&2; exit 1; fi
	@cd engine && mkdir -p build && \
		export PATH="$(GOBIN_DIR):$$PATH" && \
		export ANDROID_HOME="$(ANDROID_HOME)" ANDROID_SDK_ROOT="$(ANDROID_HOME)" ANDROID_NDK_HOME="$(ANDROID_NDK_HOME)" && \
		$(GO_ENV) "$(GOMOBILE_BIN)" init && \
		$(GO_ENV) "$(GOMOBILE_BIN)" bind -target=android -androidapi $(ANDROID_API) -javapkg=dev.scooph.engine -o build/scooph-engine.aar ./api
	@ls -l $(ENGINE_AAR)

apk: engine
	@echo "==> make apk: assembleDebug"
	@bash $(GRADLEW) :app:assembleDebug --console=plain
	@bash -c 'ls -l app/build/outputs/apk/debug/*.apk'

check: gofmt-check engine-test engine app-check
	@echo "==> make check: 全部通过"

gofmt-check:
	@echo "==> gofmt"
	@files="$$($(GOFMT) -l $(GO_FILES))"; \
	if [ -n "$$files" ]; then \
		echo "以下 Go 文件未格式化（请运行 gofmt -w）：" >&2; \
		echo "$$files" >&2; \
		exit 1; \
	fi

engine-test:
	@echo "==> go test ./... (engine)"
	cd engine && $(GO_ENV) $(GO) test ./...

app-check:
	@echo "==> Kotlin 单测 + lint"
	@bash $(GRADLEW) :app:testDebugUnitTest :app:lintDebug --console=plain
