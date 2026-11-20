# 从源码构建

构建分两步：Go 引擎先 `gomobile bind` 成 AAR，再由 Gradle 打 APK。`Makefile` 已经串好这两步。

## 环境

| 依赖 | 要求 |
| --- | --- |
| JDK | 17 |
| Android SDK | `platforms;android-36`、`build-tools;35.0.0`、`platform-tools`、任意近期 `ndk` |
| Go | 1.27+（见 `engine/go.mod`） |
| gomobile | `make tools` 会按 `engine/go.mod` 里的版本装到 GOBIN |
| git / make | Windows 建议 Git Bash + GNU make |

`local.properties`（未设置 `ANDROID_HOME` 时需要）：

```properties
sdk.dir=D\:\\WorkSpace\\Scooph\\.android-sdk
```

## 最短路径

```bash
cd D:/WorkSpace/Scooph
make tools     # 首次构建：安装 gomobile（已装可跳过）
make apk       # = make engine + ./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 分步

```bash
# 1. Go 引擎 -> AAR（Kotlin 编译依赖它，必须先做）
cd engine
mkdir -p build
gomobile init
gomobile bind -target=android -androidapi 26 \
  -javapkg=dev.scooph.engine \
  -o build/scooph-engine.aar ./api

# 2. 打 APK
cd ..
./gradlew :app:assembleDebug
```

> 改了 Go 代码后必须重新 `make engine`，否则 Gradle 仍用旧 AAR。

## 版本号

```bash
./gradlew :app:assembleDebug -PscoophVersionCode=7 -PscoophVersionName=0.1.6
```

## 校验

```bash
make check     # gofmt + go test + gomobile bind + Kotlin 单测 + lint
SCOOPH_ROOM_TESTS=1 ./gradlew :app:testDebugUnitTest   # 需要真 SQLite 测试时
```

## 签名

- debug 构建自动使用 `~/.android/debug.keystore`，可直接安装；
- release 签名读仓库根目录的 `release.jks`（**不入库**，`.gitignore` 已忽略 `*.jks`），密码只从环境变量读：

```bash
# Git Bash
export SCOOPH_STORE_PASSWORD='...'
export SCOOPH_KEY_PASSWORD='...'
export SCOOPH_KEY_ALIAS='scooph'      # 与生成 keystore 时的 alias 一致
./gradlew :app:assembleRelease -PscoophVersionCode=7 -PscoophVersionName=0.1.6
```

```powershell
# PowerShell
$env:SCOOPH_STORE_PASSWORD = "..."
$env:SCOOPH_KEY_PASSWORD = "..."
$env:SCOOPH_KEY_ALIAS = "scooph"
.\gradlew :app:assembleRelease -PscoophVersionCode=7 -PscoophVersionName=0.1.6
```

- 密码没设置时 release 仍能构建，但产物是 `app-release-unsigned.apk`；
- 签名成功后产物是 `app/build/outputs/apk/release/app-release.apk`，验证：

```bash
.apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

> `release.jks` 丢了就再也无法给已安装用户发同签名更新，务必离线备份。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| `gomobile: command not found` | `make tools`；或 `export PATH="<仓库>/.toolchain/gobin:$PATH"` |
| `gomobile.exe: mkdir C:\...\Temp\gomobile-work-...: Access is denied` | gomobile 用 `TMP/TEMP` 建工作目录；Makefile 已把 `TMP/TEMP/GOTMPDIR` 都指向 `.toolchain/tmp`，重跑 `make engine` 即可 |
| `gomobile.exe: gobind was not found` | gomobile 用 `exec.LookPath("gobind")` 找工具；Makefile 已用 `cygpath` 把 GOBIN 转成 POSIX 形式加进 PATH，并补 `PATHEXT`。仍失败就 `make tools` 预装 gobind |
| `process_begin: CreateProcess(NULL, sed ...)` | 旧 Makefile 用 sed 解析版本；已改为固定 `X_MOBILE_VERSION`，重新拉取即可 |
| `Unresolved reference: Api/Engine` | AAR 缺失或过期：`make engine` |
| NDK 相关报错 | 安装 SDK `ndk`，或设置 `ANDROID_NDK_HOME` |
| SDK XML version 4 警告 | 无害，可忽略 |
| Go 模块下载慢 | `GOPROXY=https://goproxy.cn,direct` |
| APK 体积约 50MB | debug 包含全部 ABI 的 `libgojni.so`，发布可考虑 ABI split |
