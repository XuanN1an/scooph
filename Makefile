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

.PHONY: engine apk check gofmt-check engine-test app-check

engine:
	@echo "==> make engine: gomobile bind -> $(ENGINE_AAR)"
	@test -n "$(ANDROID_HOME)" || { echo "错误：ANDROID_HOME 为空，且 local.properties 里没有 sdk.dir" >&2; exit 1; }
	@test -n "$(ANDROID_NDK_HOME)" || { echo "错误：未找到 NDK，请安装 SDK ndk 或设置 ANDROID_NDK_HOME" >&2; exit 1; }
	@cd engine && mkdir -p build && \
		export PATH="$(GOBIN_DIR):$$PATH" && \
		export ANDROID_HOME="$(ANDROID_HOME)" ANDROID_SDK_ROOT="$(ANDROID_HOME)" ANDROID_NDK_HOME="$(ANDROID_NDK_HOME)" && \
		$(GO_ENV) $(GOMOBILE) init && \
		$(GO_ENV) $(GOMOBILE) bind -target=android -androidapi $(ANDROID_API) -javapkg=dev.scooph.engine -o build/scooph-engine.aar ./api
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
