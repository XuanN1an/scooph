# Scooph 实现计划：M1–M7

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 Android 应用，用 Git 仓库里的清单（bucket）统一管理手机上开源应用的检查、安装与更新。

**Architecture:** 两层。Go 引擎负责与外界打交道的一切——git 同步、清单解析、版本仲裁、下载与哈希——产出纯 JSON；Kotlin 层负责一切被 Android 生命周期绑死的事——扫描已装包、策略与状态推导、安装会话、前台队列、Compose 界面。跨语言只有一个类、一个 JSON 边界。状态的唯一定义处是 `Derive.kt`。

**Tech Stack:** Kotlin 2.1、Jetpack Compose + Material 3、Room、协程；Go 1.27、go-git、gomobile bind；Gradle 8.11、AGP 8.9、JDK 17。

**Spec:** `docs/specs/2026-11-18-scooph-design.md`

## Global Constraints

- `minSdk 26`、`compileSdk 36`、`targetSdk 36`；`applicationId dev.scooph.app`，Kotlin 包根 `dev.scooph`。
- 清单里 `sha256` 必填，64 位十六进制；缺失的条目整条丢弃并记原因，不降级放行。
- 应用的身份唯一键是 `package`（applicationId），**永不按显示名称匹配**。
- 清单里的未知字段一律忽略，不报错。
- 状态只有七个值，定义在 `domain/Derive.kt` 一处；UI 分组是它的投影，不得另立判断。
- 界面：没有底部标签栏，没有引导页，没有提示文案；需要说明文字才看得懂，就改布局。全局只有一个强调色。
- 安装队列串行，一次只装一个；失败与跳过不中断队列，签名变更除外。
- 队列不落库。
- 失败原因写在该行里，不藏进详情页。
- 所有网络与文件操作只发生在 Go 引擎里，Kotlin 不直接下载。

## Review Focus

规格没写、但真遇到就必须不崩的输入。每一条都在下面指定的任务里带一个测试。

1. **bucket 的 `apps/` 为空、不存在，或整个仓库只有一个 `bucket.json`** —— 应返回空条目列表并标为「空 bucket」，不是崩溃或无限重试。（T4、T6）
2. **条目里的 `apk.url` 返回 404、或返回一个 HTML 页面** —— 应报出可读的失败原因，且缓存目录里不留半截文件。（T15）
3. **两个 bucket 描述同一个包、版本相同但 sha256 不同** —— 必须确定性地取添加顺序更早的那个，不能随 Map 遍历顺序抖动。（T5）
4. **已装应用的 `versionCode` 为 0**（部分 ROM 与老应用如此） —— 不得产生负数比较或「本机版本更新」的误判，应视为可更新。（T5、T9）
5. **下载过程中存储写满** —— 报明确原因，删掉半截文件，队列继续下一个。（T15）

---

## 文件结构

```
scooph/
  settings.gradle.kts              仓库与模块声明
  build.gradle.kts                 根构建脚本
  gradle/libs.versions.toml        版本目录
  app/build.gradle.kts             应用模块
  app/src/main/AndroidManifest.xml 权限与组件声明
  app/src/main/kotlin/dev/scooph/
    MainActivity.kt                唯一 Activity，承载 Compose
    ScoophApp.kt                   Application，持有 DB 与引擎单例
    engine/EngineFacade.kt         包住 Go AAR，JSON 进 JSON 出
    domain/Models.kt               Policy / Relation / DerivedState / PlannedItem
    domain/Derive.kt               七值状态机（唯一）
    data/db/Entities.kt            Source / Entry / Tracked 三张表
    data/db/Daos.kt                三个 DAO
    data/db/AppDatabase.kt         Room 数据库
    data/Repository.kt             DB + 引擎 + 扫描的编排
    scan/PackageScanner.kt         读取已装包快照
    install/Installer.kt           PackageInstaller 会话
    install/InstallQueue.kt        串行队列（内存）
    install/InstallService.kt      前台服务
    ui/theme/Theme.kt              颜色与字重
    ui/main/MainScreen.kt          主屏
    ui/main/MainViewModel.kt       主屏状态
    ui/detail/DetailScreen.kt      详情页
    ui/detail/DetailViewModel.kt   详情状态
    ui/sources/SourcesScreen.kt    来源页
    ui/sources/EntriesScreen.kt    条目页
  engine/
    go.mod
    manifest/entry.go              条目解析与校验
    manifest/entry_test.go
    version/relation.go            版本关系与仲裁
    version/relation_test.go
    bucket/sync.go                 git 拉取与文件枚举
    bucket/sync_test.go
    download/fetch.go              下载、续传、sha256
    download/fetch_test.go
    api/engine.go                  gomobile 暴露面
    testdata/apps/                 样例清单
  Makefile                         make engine / apk / check
  docs/specs/                      设计文档
  docs/plans/                      本文件
```

---

## M1 · 骨架

### Task 1: Gradle 工程与空主屏

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/dev/scooph/MainActivity.kt`, `app/src/main/kotlin/dev/scooph/ui/theme/Theme.kt`
- Create: `.gitignore`（已存在则修改，确认含 `build/`、`.gradle/`、`local.properties`、`.superpowers/`）

**Interfaces:**
- Produces: `dev.scooph.MainActivity`，后续所有界面挂在它的 Compose 树里；`ScoophTheme` 包裹。

- [ ] **Step 1: 建工程并加上 `gradle wrapper`**

```bash
cd D:/WorkSpace/Scooph
gradle wrapper --gradle-version 8.11.1
```

版本目录 `gradle/libs.versions.toml` 固定：`agp = "8.9.1"`、`kotlin = "2.1.0"`、`composeBom = "2024.12.01"`、`room = "2.6.1"`、`coroutines = "1.9.0"`。

- [ ] **Step 2: 写一个会失败的构建检查**

`app/src/test/kotlin/dev/scooph/BuildSmokeTest.kt`：

```kotlin
class BuildSmokeTest {
    @Test fun `application id is dev scooph app`() {
        assertEquals("dev.scooph.app", "dev.scooph.app") // 占位，构建通过即删
    }
}
```

- [ ] **Step 3: 运行它**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.BuildSmokeTest'`
Expected: 初次运行因缺少 `app/build.gradle.kts` 配置而失败

- [ ] **Step 4: 把构建配好，让测试与 `assembleDebug` 都通过**

`app/build.gradle.kts` 关键项：`namespace = "dev.scooph"`、`applicationId = "dev.scooph.app"`、`minSdk = 26`、`compileSdk = 36`、`targetSdk = 36`、`compileOptions` 与 `kotlinOptions` 均为 JVM 17、`buildFeatures.compose = true`。

依赖：Compose BOM + activity-compose + lifecycle-viewmodel-compose + material3、room-runtime / room-ktx / ksp、coroutines-android、`robolectric` 与 `androidx.test:core` 作为 testImplementation。不引入导航库——路由用一个 `sealed class Route` 加 `when`，页面只有四个。

`AndroidManifest.xml` 此时就写全 spec §13 的六条权限，以及 `MainActivity` 的 `exported=true` + LAUNCHER。

`MainActivity.kt` 只做一件事：`setContent { ScoophTheme { MainScreen() } }`。此时 `MainScreen` 可以是 `Text("Scooph")` 的占位，会在 Task 11 被替换。

- [ ] **Step 5: 运行并提交**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle app
git commit -m "build: Gradle 工程与空主屏"
```

---

### Task 2: Go 引擎编进 APK

**Files:**
- Create: `engine/go.mod`, `engine/api/engine.go`, `app/src/main/kotlin/dev/scooph/engine/EngineFacade.kt`
- Modify: `app/build.gradle.kts`（加 AAR 依赖）

**Interfaces:**
- Produces: `dev.scooph.engine.Engine`（gomobile 生成），`NewEngine(rootDir: String)`，`Version(): String`。`EngineFacade` 包一层，后续任务只碰 `EngineFacade`。

- [ ] **Step 1: 确认工具链**

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
ls "$ANDROID_HOME/ndk" 2>/dev/null || echo "NO NDK"
```

NDK 缺失时用 `sdkmanager "ndk;27.0.12077973"` 安装并设 `ANDROID_NDK_HOME`。

- [ ] **Step 2: 写会失败的测试**

`app/src/test/kotlin/dev/scooph/engine/EngineSmokeTest.kt`：

```kotlin
class EngineSmokeTest {
    @Test fun `engine reports its version`() {
        val engine = EngineFacade(File(System.getProperty("java.io.tmpdir"), "scooph-test"))
        assertTrue(engine.version().isNotBlank())
    }
}
```

- [ ] **Step 3: 运行并确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.engine.EngineSmokeTest'`
Expected: FAIL，`Unresolved reference: EngineFacade`

- [ ] **Step 4: 实现最小引擎并接进构建**

`engine/go.mod`：`module github.com/scooph/scooph/engine`，`go 1.27`。

`engine/api/engine.go`：

```go
package api

type Engine struct{ root string }

func NewEngine(root string) *Engine { return &Engine{root: root} }

func (e *Engine) Version() string { return "0.1.0" }
```

构建：`make engine` 执行 `gomobile bind -target=android -androidapi 26 -javapkg=dev.scooph.engine -o engine/build/scooph-engine.aar ./api`。

`app/build.gradle.kts` 增加 `implementation(files("../engine/build/scooph-engine.aar"))`。

`EngineFacade` 构造时把 `rootDir` 建成目录，暴露 `version()`。

- [ ] **Step 5: 运行并提交**

Run: `make engine && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，APK 内含 `lib/*/libgojni.so`

```bash
git add engine app/build.gradle.kts app/src/main/kotlin/dev/scooph/engine app/src/test/kotlin/dev/scooph/engine
git commit -m "feat: Go 引擎编进 APK"
```

---

### Task 3: 一条命令检查全部

**Files:**
- Create: `Makefile`

**Interfaces:**
- Produces: `make check`（Go 测试 + Kotlin 单测 + lint）、`make engine`、`make apk`。

- [ ] **Step 1: 写 Makefile**

`check` 依次运行 `cd engine && go test ./...`、`./gradlew :app:testDebugUnitTest :app:lintDebug`、`gofmt -l` 非空即失败。

- [ ] **Step 2: 验证它此刻是失败的**

故意在 `engine/api/engine.go` 末尾加一个多余空格，运行 `make check`。
Expected: 非零退出，`gofmt` 报出该文件

- [ ] **Step 3: 撤掉空格，再跑**

Run: `make check`
Expected: 全绿

- [ ] **Step 4: 提交**

```bash
git add Makefile
git commit -m "build: make check 一条命令跑完所有检查"
```

---

## M2 · 数据打通

### Task 4: 清单条目解析

**Files:**
- Create: `engine/manifest/entry.go`, `engine/manifest/entry_test.go`, `engine/testdata/apps/com.fsck.k9.json`

**Interfaces:**
- Produces:
```go
type APK struct { URL string `json:"url"`; Size int64 `json:"size"`; SHA256 string `json:"sha256"` }
type Entry struct {
    Schema int `json:"schema"`; Package string `json:"package"`; Name string `json:"name"`
    VersionCode int64 `json:"versionCode"`; VersionName string `json:"versionName"`
    APK APK `json:"apk"`; Source string `json:"source"`; MinSdk int `json:"minSdk"`; Notes string `json:"notes"`
}
type Drop struct { File string `json:"file"`; Reason string `json:"reason"` }
func ParseEntry(fileName string, data []byte) (Entry, error)
func LoadDir(dir string) (entries []Entry, drops []Drop)
```
- 校验顺序固定：JSON 合法 → `package` 非空且等于文件名去扩展名 → `apk.sha256` 为 64 位十六进制 → `apk.url` 是 http/https → `versionCode >= 1`。任一不过，`ParseEntry` 返回 `*ValidationError{Field, Reason}`。

- [ ] **Step 1: 写失败测试**

`engine/manifest/entry_test.go`，表驱动，至少覆盖：

```go
func TestParseEntryRejectsMissingSHA256(t *testing.T) {
    _, err := ParseEntry("com.a.json", []byte(`{"package":"com.a","versionCode":1,"apk":{"url":"https://x/a.apk"}}`))
    var ve *ValidationError
    if !errors.As(err, &ve) || ve.Field != "apk.sha256" { t.Fatalf("got %v", err) }
}

func TestParseEntryIgnoresUnknownFields(t *testing.T) {
    e, err := ParseEntry("com.a.json", []byte(`{"package":"com.a","versionCode":1,"apk":{"url":"https://x/a.apk","sha256":"`+hex64+`"},"futureField":{"x":1}}`))
    if err != nil { t.Fatal(err) }
    if e.Package != "com.a" { t.Fatal(e.Package) }
}

func TestLoadDirEmptyAppsDirectoryReturnsEmpty(t *testing.T) { /* Review Focus 1 */ }
```

`hex64` 用一个 64 位十六进制常量。

- [ ] **Step 2: 运行确认失败**

Run: `cd engine && go test ./manifest/ -run TestParseEntry -v`
Expected: 编译失败，`undefined: ParseEntry`

- [ ] **Step 3: 实现**

按上面的校验顺序实现 `ParseEntry`；`LoadDir` 读目录下 `*.json`，单个文件失败只追加 `Drop`，不中断。`apps/` 不存在时返回空切片和空 `drops`，不报错（Review Focus 1）。

- [ ] **Step 4: 运行确认通过**

Run: `cd engine && go test ./manifest/ -v`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add engine/manifest engine/testdata
git commit -m "feat(engine): 清单条目解析与校验"
```

---

### Task 5: 版本关系与条目仲裁

**Files:**
- Create: `engine/version/relation.go`, `engine/version/relation_test.go`

**Interfaces:**
- Produces:
```go
type Relation string // "newer" | "same" | "older"
func Relate(entryVersionCode, installedVersionCode int64) Relation
type Candidate struct { Entry manifest.Entry; SourceID string; SourceOrder int }
func Resolve(cands []Candidate) (Candidate, bool)
```
- `Relate`：`entry > installed` → `newer`；`==` → `same`；`<` → `older`。`installed == 0` 视为未安装，一律 `newer`（Review Focus 4）。
- `Resolve`：先比 `Entry.VersionCode` 取最大；相同则 `SourceOrder` 取最小；再相同则 `SourceID` 字典序取最小。返回 `false` 当输入为空。

- [ ] **Step 1: 写失败测试**

```go
func TestRelateTreatsZeroInstalledAsNewer(t *testing.T) {
    if got := Relate(7000, 0); got != Newer { t.Fatalf("got %s", got) }
}

func TestResolvePrefersEarliestSourceOnTie(t *testing.T) {
    // 同版本、不同 sha256、SourceOrder 3 与 1 → 取 1（Review Focus 3）
    got, ok := Resolve([]Candidate{{Entry: mk(7000, "aaa"), SourceID: "z", SourceOrder: 3},
                                  {Entry: mk(7000, "bbb"), SourceID: "a", SourceOrder: 1}})
    if !ok || got.SourceID != "a" { t.Fatalf("got %+v", got) }
}

func TestResolveIsDeterministicAcrossShuffles(t *testing.T) {
    // 同一组输入打乱 20 次，结果必须一致
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd engine && go test ./version/ -v`
Expected: `undefined: Relate`

- [ ] **Step 3: 实现**

排序而非遍历比较，避免依赖 Map 顺序。`Resolve` 内部先 `sort.SliceStable`，再取首元素。

- [ ] **Step 4: 运行确认通过**

Run: `cd engine && go test ./version/ -v`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add engine/version
git commit -m "feat(engine): 版本关系与确定性仲裁"
```

---

### Task 6: bucket 同步

**Files:**
- Create: `engine/bucket/sync.go`, `engine/bucket/sync_test.go`

**Interfaces:**
- Produces:
```go
type Result struct { Commit string `json:"commit"`; Files []string `json:"files"`; Empty bool `json:"empty"` }
func Sync(dir, url string) (Result, error)
```
- 已存在则 `Fetch` + 快进到 `origin/HEAD`；不存在则 `PlainClone` 浅克隆（`Depth: 1`）。
- `Files` 是 `apps/*.json` 的相对路径，排序后返回。
- 没有 `apps/` 或目录为空 → `Empty: true`，`err == nil`（Review Focus 1）。

- [ ] **Step 1: 写失败测试**

在 `t.TempDir()` 里用 `git init --bare` 加一个工作仓库造 fixture，推入 3 个文件；测试 `Sync` 返回 3 个 `Files`、`Empty == false`；再推入第 4 个文件后第二次 `Sync` 返回 4 个；另建一个只有 `bucket.json` 的仓库，断言 `Empty == true` 且无错误。

- [ ] **Step 2: 运行确认失败**

Run: `cd engine && go test ./bucket/ -v`
Expected: `undefined: Sync`

- [ ] **Step 3: 实现**

用 `go-git` v5。网络仓库不支持认证，遇到鉴权失败返回 `ErrPrivateRepo`（spec §10.6）。

- [ ] **Step 4: 运行确认通过**

Run: `cd engine && go test ./bucket/ -v`
Expected: PASS（测试需要本机 `git` 命令造 fixture，缺失时 `t.Skip`）

- [ ] **Step 5: 提交**

```bash
git add engine/bucket engine/go.mod engine/go.sum
git commit -m "feat(engine): bucket git 同步"
```

---

### Task 7: 三张表与 DAO

**Files:**
- Create: `app/src/main/kotlin/dev/scooph/data/db/Entities.kt`, `Daos.kt`, `AppDatabase.kt`
- Create: `app/src/test/kotlin/dev/scooph/data/db/EntityMappingTest.kt`

**Interfaces:**
- Produces:
```kotlin
@Entity(tableName = "sources") data class SourceEntity(
    @PrimaryKey val url: String, val name: String, val lastSyncAt: Long,
    val lastCommit: String?, val status: String, val addedOrder: Int)

@Entity(tableName = "entries", primaryKeys = ["packageName", "sourceId"])
data class EntryEntity(
    val packageName: String, val sourceId: String, val name: String,
    val versionCode: Long, val versionName: String, val apkUrl: String,
    val sha256: String, val size: Long, val notes: String?)

@Entity(tableName = "tracked") data class TrackedEntity(
    @PrimaryKey val packageName: String, val policy: String, val skipVersionCode: Long,
    val installedVersionCode: Long, val signature: String?, val installedAt: Long,
    val lastCheckedAt: Long, val lastError: String?)
```
- `SourceDao.upsert/observeAll/delete`、`EntryDao.replaceForSource(sourceId, entries)/observeAll/deleteForSource`、`TrackedDao.upsert/get/observeAll`。

- [ ] **Step 1: 写失败测试**

用 Robolectric + in-memory Room，断言：同 `packageName` 不同 `sourceId` 可共存；`replaceForSource` 只删该来源的旧条目；`TrackedEntity` 在 `entries` 全删后仍在。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.data.db.EntityMappingTest'`
Expected: FAIL，缺类

- [ ] **Step 3: 实现**

Room 2.6.1，`exportSchema = false`，`allowMainThreadQueries` 关闭。`policy` 存字符串而不是枚举序号——字符串可读，改枚举顺序不毁数据。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.data.db.EntityMappingTest'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/data app/src/test/kotlin/dev/scooph/data
git commit -m "feat: Room 三表与 DAO"
```

---

### Task 8: 扫描已装应用

**Files:**
- Create: `app/src/main/kotlin/dev/scooph/scan/PackageScanner.kt`
- Create: `app/src/test/kotlin/dev/scooph/scan/ScanSnapshotTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class InstalledApp(val packageName: String, val versionCode: Long,
                        val signature: String?, val hasSplits: Boolean, val label: String)
interface PackageScanner { suspend fun scan(): List<InstalledApp> }
```
- `signature` 的算法与 spec §10.3 一致：`PackageInfo.signingInfo.apkContentsSigners` → 每个 `Signature.toByteArray()` 做 SHA-256 → 十六进制 → 排序 → 拼接 → 再 SHA-256。
- `hasSplits`：`splitSourceDirs != null && splitSourceDirs.isNotEmpty()`。

- [ ] **Step 1: 写失败测试**

把「签名字符串归一化」抽成无 Android 依赖的纯函数 `fun fingerprint(certs: List<ByteArray>): String`，测试：单证书稳定、两证书换序结果相同、空列表返回空串。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.scan.ScanSnapshotTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

`AndroidPackageScanner` 用 `PackageManager.getInstalledPackages(GET_SIGNING_CERTIFICATES)`，在 `Dispatchers.IO` 上跑。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.scan.ScanSnapshotTest'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/scan app/src/test/kotlin/dev/scooph/scan
git commit -m "feat: 已装应用快照与签名指纹"
```

---

### Task 9: 七值状态机

**Files:**
- Create: `app/src/main/kotlin/dev/scooph/domain/Models.kt`, `app/src/main/kotlin/dev/scooph/domain/Derive.kt`
- Create: `app/src/test/kotlin/dev/scooph/domain/DeriveTest.kt`

**Interfaces:**
- Produces:
```kotlin
enum class Policy { FOLLOW, SKIP_VERSION, UNTRACKED }
enum class Relation { NEWER, SAME, OLDER }
enum class DerivedState { NO_SOURCE, NOT_INSTALLED, IGNORED, UP_TO_DATE, AHEAD, SKIPPED, UPDATABLE }

fun derive(policy: Policy?, relation: Relation?, skipVersionCode: Long, entryVersionCode: Long): DerivedState
```
- 判定顺序严格照 spec §9.2：① `relation == null` → `NO_SOURCE` ② `policy == null` → `NOT_INSTALLED` ③ `UNTRACKED` → `IGNORED` ④ `SAME` → `UP_TO_DATE` ⑤ `OLDER` → `AHEAD` ⑥ `SKIP_VERSION && entryVersionCode == skipVersionCode` → `SKIPPED` ⑦ 其余 `UPDATABLE`。

- [ ] **Step 1: 写失败测试**

穷举 3 个 policy × 3 个 relation + 两种 null 组合，共 11 条断言，逐条写死期望值。额外两条：

```kotlin
@Test fun `skip then newer version comes back as updatable`() {
    assertEquals(DerivedState.UPDATABLE, derive(Policy.SKIP_VERSION, Relation.NEWER, 7000, 7001))
}
@Test fun `zero installed version is updatable`() { // Review Focus 4
    assertEquals(DerivedState.UPDATABLE, derive(Policy.FOLLOW, Relation.NEWER, 0, 1))
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.domain.DeriveTest'`
Expected: FAIL，`unresolved reference: derive`

- [ ] **Step 3: 实现**

一个 `when` 表达式，返回顺序与 spec 一致。不要写成 `if/else` 链，也不要在这里读数据库。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.domain.DeriveTest'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/domain app/src/test/kotlin/dev/scooph/domain
git commit -m "feat: 七值状态机"
```

---

### Task 10: 引擎的 JSON 面

**Files:**
- Modify: `engine/api/engine.go`
- Create: `engine/api/engine_test.go`
- Create: `app/src/main/kotlin/dev/scooph/engine/EngineFacade.kt`（扩充）, `app/src/main/kotlin/dev/scooph/engine/Dto.kt`

**Interfaces:**
- Produces（Go 侧）：
```go
func (e *Engine) Sync(url string) (string, error)   // → {"commit":"…","files":["apps/a.json"],"empty":false}
func (e *Engine) Load(url string) (string, error)   // → {"entries":[…],"drops":[{"file":"…","reason":"…"}]}
func (e *Engine) Plan(req string) (string, error)   // 见下
```
  `Plan` 入参：
```json
{"candidates":[{"entry":{…},"sourceId":"…","sourceOrder":0}],
 "installed":[{"package":"com.a","versionCode":6804}]}
```
  出参：
```json
{"items":[{"package":"com.a","name":"A","relation":"newer","entryVersionCode":7000,
           "installedVersionCode":6804,"apkUrl":"…","sha256":"…","size":1,"sourceId":"…"}]}
```
- 生产（Kotlin 侧）：`EngineFacade.sync(url): SyncResultDto`、`load(url): LoadResultDto`、`plan(candidates, installed): PlanResultDto`，内部 `JSONObject` 编解码，对外只暴露数据类。

- [ ] **Step 1: 写失败测试**

`engine/api/engine_test.go`：给定两个候选和一个已装快照，断言 `items[0].relation == "newer"`；候选为空时 `items` 是空数组而不是 `null`。Kotlin 侧 `EngineJsonTest` 断言 DTO 能往返解析出与 Go 测试相同的 JSON 字面量。

- [ ] **Step 2: 运行确认失败**

Run: `cd engine && go test ./api/ -v`
Expected: `undefined: (Engine).Plan`

- [ ] **Step 3: 实现**

`engine/api/engine.go` 只做编解码与调用 `manifest`/`version`/`bucket`，不写业务判断。`items` 用 `make([]Item, 0)` 初始化，避免 `null`。

- [ ] **Step 4: 运行确认通过**

Run: `cd engine && go test ./api/ -v && cd .. && ./gradlew :app:testDebugUnitTest --tests 'dev.scooph.engine.EngineJsonTest'`
Expected: 两侧都 PASS

- [ ] **Step 5: 提交**

```bash
git add engine/api app/src/main/kotlin/dev/scooph/engine app/src/test/kotlin/dev/scooph/engine
git commit -m "feat: 引擎 JSON 面与 Kotlin 门面"
```

---

### Task 11: 主屏三组

**Files:**
- Create: `app/src/main/kotlin/dev/scooph/data/Repository.kt`, `app/src/main/kotlin/dev/scooph/ui/main/MainViewModel.kt`, `ui/main/MainScreen.kt`, `app/src/main/kotlin/dev/scooph/ScoophApp.kt`
- Modify: `MainActivity.kt`
- Create: `app/src/test/kotlin/dev/scooph/ui/main/MainViewModelTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class MainRow(val packageName: String, val name: String, val state: DerivedState,
                   val installedVersionName: String, val entryVersionName: String,
                   val skipVersionCode: Long, val error: String?)

data class MainUiState(val updatable: List<MainRow>, val upToDate: List<MainRow>,
                       val ignored: List<MainRow>, val syncing: Boolean)

class MainViewModel(...) : ViewModel() {
    val state: StateFlow<MainUiState>
    fun refresh()
}
```
- `Repository.refresh()`：对每个 source 调 `sync` + `load`，把 entries 写库，扫包，调 `plan`，再用 `derive` 组装 `MainUiState`。

- [ ] **Step 1: 写失败测试**

假 Repository 返回三组混合数据，断言：`IGNORED` 与 `SKIPPED` 都进 `ignored`；`AHEAD` 与 `UP_TO_DATE` 都进 `upToDate`；`NO_SOURCE` 与 `NOT_INSTALLED` 不出现在任何组。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.ui.main.MainViewModelTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

主屏按 spec §11.1：大标题「应用」，左上「来源」，右上「全部更新」（Task 19 之前先不接动作）。分组用小号灰字标签「可更新 · N」/「已是最新 · N」/「已忽略 · N」，组内是白底圆角行，行左图标 30dp、名称 15sp、副行 12sp 灰字显示版本变化。

- [ ] **Step 4: 构建并在真机/模拟器上检查**

Run: `./gradlew :app:installDebug`
手工确认：三组顺序固定、空组不显示标题、无任何提示性文字。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph app/src/test/kotlin/dev/scooph/ui
git commit -m "feat: 主屏三组"
```

---

### Task 12: 首次启动接上官方 bucket

**Files:**
- Modify: `app/src/main/kotlin/dev/scooph/ScoophApp.kt`, `data/Repository.kt`
- Create: `app/src/main/kotlin/dev/scooph/data/Defaults.kt`

**Interfaces:**
- Produces: `const val DEFAULT_BUCKET_URL = "https://github.com/scooph/scooph-bucket"`；`Repository.ensureDefaultSource(): Unit`，当 `sources` 为空时插入它并同步一次。

- [ ] **Step 1: 写失败测试**

假 DAO 计数为 0 时，`ensureDefaultSource()` 后 `sources` 有一条且 `url == DEFAULT_BUCKET_URL`；已有 source 时不再插入。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.data.DefaultSourceTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

在 `ScoophApp.onCreate` 里一个 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 上调用；失败只记日志，不弹任何东西。

- [ ] **Step 4: 运行确认通过并手工验证**

Run: `./gradlew :app:installDebug`
首次安装（先 `adb shell pm clear dev.scooph.app`）后应看到有内容或「空 bucket」的列表，全程无引导页。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/data app/src/main/kotlin/dev/scooph/ScoophApp.kt
git commit -m "feat: 首次启动接入官方 bucket"
```

---

## M3 · 详情页与策略

### Task 13: 详情页

**Files:**
- Create: `ui/detail/DetailViewModel.kt`, `ui/detail/DetailScreen.kt`, `app/src/test/kotlin/dev/scooph/ui/detail/DetailViewModelTest.kt`
- Modify: `MainScreen.kt`（行尾箭头导航）, `MainActivity.kt`（导航宿主）

**Interfaces:**
- Produces:
```kotlin
data class DetailUiState(val packageName: String, val name: String, val state: DerivedState,
                         val installedVersionName: String, val entryVersionName: String,
                         val policy: Policy, val skipVersionCode: Long, val sourceLabel: String,
                         val signatureChanged: Boolean)
```
- 导航不引入库：`sealed class Route { object Main; data class Detail(pkg: String); object Sources; data class Entries(sourceId: String) }`，在 `MainActivity` 里一个 `when` 分发，返回键由 `BackHandler` 处理。本任务只加 `Main`、`Detail`。

- [ ] **Step 1: 写失败测试**

给定 `policy = SKIP_VERSION` 且 `skipVersionCode == entryVersionCode`，`DetailUiState.policy` 为 `SKIP_VERSION`，且 `state == SKIPPED`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.ui.detail.DetailViewModelTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

布局照 spec §11（方案 F）：居中图标 62dp、名称 21sp、包名 11sp 等宽、版本双列（当前/最新，最新为强调色）、描述性主按钮（描边、强调色文字）「更新到 x」、策略组三行互斥带勾、来源组一行。「更新」按钮本任务只记录点击，不执行。

- [ ] **Step 4: 构建并手工验证**

Run: `./gradlew :app:installDebug`
确认：整页只有一处强调色（勾选行 + 描边按钮边框）；没有说明文字。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/ui/detail app/src/main/kotlin/dev/scooph/ui/main app/src/main/kotlin/dev/scooph/MainActivity.kt app/src/test/kotlin/dev/scooph/ui/detail
git commit -m "feat: 详情页"
```

---

### Task 14: 三个策略落库

**Files:**
- Modify: `ui/detail/DetailViewModel.kt`, `data/Repository.kt`
- Create: `app/src/test/kotlin/dev/scooph/data/PolicyPersistenceTest.kt`

**Interfaces:**
- Produces: `Repository.setPolicy(packageName: String, policy: Policy, skipVersionCode: Long)`。选 `SKIP_VERSION` 时 `skipVersionCode` 写入当前条目版本；选 `FOLLOW` 或 `UNTRACKED` 时写 0。

- [ ] **Step 1: 写失败测试**

`setPolicy(SKIP_VERSION, 7000)` 后读回 `policy == SKIP_VERSION && skipVersionCode == 7000`；再 `setPolicy(FOLLOW, 0)` 后 `skipVersionCode == 0`；`UNTRACKED` 的应用在 `refresh()` 后仍为 `UNTRACKED`（墓碑不被同步覆盖）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.data.PolicyPersistenceTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

`Repository.refresh()` 里对已存在 `tracked` 的行只更新 `installedVersionCode` 与 `lastCheckedAt`，绝不写 `policy`。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.data.PolicyPersistenceTest'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/ui/detail app/src/main/kotlin/dev/scooph/data app/src/test/kotlin/dev/scooph/data
git commit -m "feat: 三个更新策略持久化"
```

---

## M4 · 单个应用更新

### Task 15: 下载、校验与失败分类

**Files:**
- Create: `engine/download/fetch.go`, `engine/download/fetch_test.go`
- Modify: `engine/api/engine.go`（加 `Fetch`）

**Interfaces:**
- Produces:
```go
type Progress interface { OnProgress(done, total int64) }
type FetchResult struct { Path string `json:"path"`; Size int64 `json:"size"`; SHA256 string `json:"sha256"` }
func Fetch(url, destPath, wantSHA256 string, p Progress) (FetchResult, error)
var ErrHashMismatch = errors.New("sha256 mismatch")
var ErrPrivateRepo = errors.New("private repository not supported")
```
- 半截文件写到 `destPath + ".part"`，校验通过才 `os.Rename`；任何失败都删掉 `.part`。

- [ ] **Step 1: 写失败测试**

用 `httptest.Server`：

```go
func TestFetchRejectsHTMLResponse(t *testing.T)   { /* 200 + text/html → 错误含 status 与 content-type；目录下无 .part */ }
func TestFetchDeletesPartialOnHashMismatch(t *testing.T)
func TestFetchReportsDiskFull(t *testing.T)       { /* Review Focus 5：写满后返回可读错误，无残留 */ }
func TestFetchRetriesFromOffset(t *testing.T)     { /* 已存在 .part 时带 Range 头续传 */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd engine && go test ./download/ -v`
Expected: `undefined: Fetch`

- [ ] **Step 3: 实现**

流式 `io.Copy` 到 `sha256.New()` 的同时写文件，边下边算；`Content-Length` 未知时 `total` 取 0。

- [ ] **Step 4: 运行确认通过**

Run: `cd engine && go test ./download/ -v`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add engine/download engine/api
git commit -m "feat(engine): 下载、续传与校验"
```

---

### Task 16: 签名锚点

**Files:**
- Modify: `data/Repository.kt`, `scan/PackageScanner.kt`
- Create: `app/src/test/kotlin/dev/scooph/data/AnchorTest.kt`, `app/src/main/kotlin/dev/scooph/install/ApkInspector.kt`

**Interfaces:**
- Produces:
```kotlin
object ApkInspector {
    fun fingerprintOf(apk: File): String  // 与 PackageScanner.fingerprint 同一算法
}
```
- `Repository.refresh()` 中：扫描到的已装应用若 `tracked.signature` 为空且 `policy != UNTRACKED`，写入扫描到的签名（spec §10.3：扫描即建锚）。

- [ ] **Step 1: 写失败测试**

已装应用有签名、`tracked` 无签名 → `refresh()` 后 `tracked.signature` 等于扫描值；`UNTRACKED` 的应用不建锚。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.data.AnchorTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

`ApkInspector` 用 `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` 读签名证书，再走同一个 `fingerprint`。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.data.AnchorTest'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/install app/src/main/kotlin/dev/scooph/data app/src/test/kotlin/dev/scooph/data
git commit -m "feat: 签名锚点"
```

---

### Task 17: 安装会话

**Files:**
- Create: `install/Installer.kt`, `install/InstallResult.kt`
- Create: `app/src/test/kotlin/dev/scooph/install/InstallResultTest.kt`

**Interfaces:**
- Produces:
```kotlin
sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data object Cancelled : InstallOutcome
    data object NeedsUserAction : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome
}
interface Installer { suspend fun install(apk: File, packageName: String): InstallOutcome }
```
- 会话参数：`MODE_FULL_INSTALL`、`setAppPackageName(packageName)`、`setInstallerPackageName(context.packageName)`；`commit` 用 `PendingIntent.getBroadcast` 接收 `Intent.ACTION_INSTALL_COMMIT` 的结果。

- [ ] **Step 1: 写失败测试**

把 `PackageInstaller.SessionInfo` 的状态码映射抽成纯函数 `fun toOutcome(status: Int, message: String?): InstallOutcome`，测试 4 个分支：`STATUS_SUCCESS`、`STATUS_FAILURE_ABORTED` → `Cancelled`、`STATUS_PENDING_USER_ACTION` → `NeedsUserAction`、其余 → `Failed`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.install.InstallResultTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

`install()` 用 `suspendCancellableCoroutine` 包住广播接收；先检查 `canRequestPackageInstalls()`，为 false 时返回 `Failed("需要安装未知应用的权限")` 并附上跳转用的 `Intent`。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.install.InstallResultTest'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/install app/src/test/kotlin/dev/scooph/install
git commit -m "feat: 安装会话与结果映射"
```

---

### Task 18: 单包更新端到端

**Files:**
- Modify: `data/Repository.kt`, `ui/detail/DetailViewModel.kt`
- Create: `app/src/test/kotlin/dev/scooph/data/UpdateFlowTest.kt`

**Interfaces:**
- Produces: `Repository.updateOne(packageName: String, onProgress: (Long, Long) -> Unit): UpdateOutcome`，内部依次：`Fetch` → `ApkInspector.fingerprintOf` 比对锚点 → `Installer.install` → 成功则写 `installedVersionCode` 与签名、`policy` 回 `FOLLOW`。

- [ ] **Step 1: 写失败测试**

用假引擎与假安装器覆盖四条路径：哈希不符 → `Failed("sha256 不一致")` 且 `tracked` 未变；签名不一致 → `Failed("签名已变更")` 且不调用安装器；用户取消 → `Cancelled` 且 `tracked` 未变；成功 → `installedVersionCode` 与签名更新、`policy == FOLLOW`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.data.UpdateFlowTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

失败原因字符串集中在 `UpdateOutcome` 的构造处，UI 只显示不再拼接。

- [ ] **Step 4: 运行确认通过并手工验证一次真机安装**

Run: `./gradlew :app:installDebug`
在真机上对一个真实应用走完下载 → 系统确认 → 装好 → 行内变「已是最新」。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/data app/src/main/kotlin/dev/scooph/ui/detail app/src/test/kotlin/dev/scooph/data
git commit -m "feat: 单包更新端到端"
```

---

## M5 · 一键更新

### Task 19: 串行队列与前台服务

**Files:**
- Create: `install/InstallQueue.kt`, `install/InstallService.kt`
- Create: `app/src/test/kotlin/dev/scooph/install/InstallQueueTest.kt`
- Modify: `AndroidManifest.xml`, `MainViewModel.kt`

**Interfaces:**
- Produces:
```kotlin
data class QueueItem(val packageName: String, val state: QueueState)
sealed interface QueueState { data object Waiting; data object Downloading; data object AwaitingConfirm;
                              data object Done; data class Skipped(val reason: String); data class Failed(val reason: String) }
class InstallQueue(...) { val items: StateFlow<List<QueueItem>>; fun start(packages: List<String>); fun cancel() }
```
- 串行 `for` 循环，逐项 `updateOne`；`Cancelled` → `Skipped("已取消")`；`Failed("签名已变更")` → 队列暂停并置 `AwaitingConfirm`；其余失败继续。
- 前台服务 `dataSync` 类型，只在队列运行期间存活。

- [ ] **Step 1: 写失败测试**

假 `updateOne` 依次返回 `Success/ Cancelled / Failed("网络断开") / Failed("签名已变更") / Success`。断言：调用顺序严格串行；前四项之后仍继续到第五项；遇到签名变更时停住不再调用后续项；`items` 最终状态与 spec §10.4 的表格一致。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.install.InstallQueueTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

队列只持有 `packageName` 与状态，不持有已下载文件；进程死亡后自然消失（spec §10.4）。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.install.InstallQueueTest'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/install app/src/main/AndroidManifest.xml app/src/main/kotlin/dev/scooph/ui/main app/src/test/kotlin/dev/scooph/install
git commit -m "feat: 串行更新队列"
```

---

### Task 20: 主屏就地变形

**Files:**
- Modify: `ui/main/MainScreen.kt`, `ui/main/MainViewModel.kt`
- Create: `app/src/test/kotlin/dev/scooph/ui/main/InPlaceProgressTest.kt`

**Interfaces:**
- Produces: `MainUiState` 增加 `queue: List<QueueItem>` 与 `active: Boolean`；行副行文本按 `QueueState` 投影：`正在下载 62%` / `等你确认安装` / `已装好` / `跳过：<原因>` / `失败：<原因>`。

- [ ] **Step 1: 写失败测试**

给定队列状态混合，断言每一行副行文案与 spec §10.4 完全一致，且 `active == true` 时右上角显示进度 `3 / 6`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.ui.main.InPlaceProgressTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

不新增页面、不弹对话框。队列结束时把 `active` 置 false，主屏回到普通三组。

- [ ] **Step 4: 构建并手工验证**

Run: `./gradlew :app:installDebug`
点「全部更新」，确认：不跳页、不弹自定义对话框、系统确认框关掉后回到同一行、被取消的那一项回到可更新组。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/ui/main app/src/test/kotlin/dev/scooph/ui/main
git commit -m "feat: 主屏就地显示更新进度"
```

---

### Task 21: 结束汇总通知

**Files:**
- Modify: `install/InstallService.kt`, `AndroidManifest.xml`（通知权限）
- Create: `app/src/test/kotlin/dev/scooph/install/SummaryTextTest.kt`

**Interfaces:**
- Produces: `fun summaryText(items: List<QueueItem>): String`，形如「更新完成 · 成功 4，跳过 1，失败 1」。

- [ ] **Step 1: 写失败测试**

三种组合断言文案：全成功、含跳过与失败、全部失败（此时文案以「全部失败」开头）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.install.SummaryTextTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

只在队列结束时发一条通知；进行中不逐条发通知。`POST_NOTIFICATIONS` 未授权时静默跳过发通知，不影响队列。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.install.SummaryTextTest'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/install app/src/main/AndroidManifest.xml
git commit -m "feat: 更新结束汇总通知"
```

---

## M6 · 来源与安装新应用

### Task 22: 来源页

**Files:**
- Create: `ui/sources/SourcesScreen.kt`, `ui/sources/SourcesViewModel.kt`
- Create: `app/src/test/kotlin/dev/scooph/ui/sources/SourcesViewModelTest.kt`
- Modify: `MainScreen.kt`（左上入口）

**Interfaces:**
- Produces: `SourcesUiState(val sources: List<SourceRow>)`，`SourceRow(url, name, entryCount, lastSyncAt, status)`；动作 `add(url)`, `remove(url)`, `sync(url)`。

- [ ] **Step 1: 写失败测试**

新增非法 URL（非 http/https）返回可读错误；删除最后一个 bucket 后 `tracked` 记录仍在（`NO_SOURCE` 而不是数据消失）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.ui.sources.SourcesViewModelTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

每行显示名称、条目数、上次同步时间（相对时间，超过 7 天显示日期）。添加用一个输入行，不弹对话框。

- [ ] **Step 4: 构建并手工验证**

Run: `./gradlew :app:installDebug`
添加一个真实 bucket URL，确认同步后条目数变化。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/ui/sources app/src/main/kotlin/dev/scooph/ui/main app/src/test/kotlin/dev/scooph/ui/sources
git commit -m "feat: 来源页"
```

---

### Task 23: 条目页与搜索

**Files:**
- Create: `ui/sources/EntriesScreen.kt`, `ui/sources/EntriesViewModel.kt`
- Create: `app/src/test/kotlin/dev/scooph/ui/sources/EntriesViewModelTest.kt`

**Interfaces:**
- Produces: `EntriesUiState(val query: String, val rows: List<EntryRow>)`，`EntryRow(packageName, name, versionName, installed: Boolean, state: DerivedState?)`；`onQueryChange(String)` 按名称与包名做大小写不敏感的子串匹配。

- [ ] **Step 1: 写失败测试**

查询「k9」命中 `K-9 Mail`；查询 `com.fsck` 也命中同一个；空查询返回全部；排序为已安装在前、其余按名称，断言前两条顺序。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.ui.sources.EntriesViewModelTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

顶部搜索框常驻；行右侧按 spec §11.4 三态显示。

- [ ] **Step 4: 构建并手工验证**

Run: `./gradlew :app:installDebug`
输入关键字，确认列表实时过滤且已安装项排在前面。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/ui/sources app/src/test/kotlin/dev/scooph/ui/sources
git commit -m "feat: 条目页与搜索"
```

---

### Task 24: 从条目页安装新应用

**Files:**
- Modify: `ui/sources/EntriesScreen.kt`, `data/Repository.kt`

**Interfaces:**
- Produces: `Repository.installNew(packageName: String, onProgress: (Long, Long) -> Unit): UpdateOutcome`，与 `updateOne` 共用同一条管道，仅在成功后额外写入首次锚点与 `tracked(policy = FOLLOW)`。

- [ ] **Step 1: 写失败测试**

未安装条目安装成功 → `tracked` 出现且 `policy == FOLLOW`、`signature` 等于新装 APK 的指纹；安装失败 → 不产生 `tracked`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.data.InstallNewTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

点未安装行直接开始安装（不跳详情页）；点已安装行导航到 `Detail`。

- [ ] **Step 4: 运行确认通过并手工装一个真应用**

Run: `./gradlew :app:installDebug`
装完回到主屏，该应用应已成行出现。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/scooph/ui/sources app/src/main/kotlin/dev/scooph/data app/src/test/kotlin/dev/scooph/data
git commit -m "feat: 从条目页安装新应用"
```

---

## M7 · 打磨

### Task 25: 空状态、深色模式、错误文案

**Files:**
- Modify: `ui/theme/Theme.kt`, `ui/main/MainScreen.kt`, `ui/sources/*.kt`
- Create: `app/src/main/kotlin/dev/scooph/ui/EmptyStateText.kt`, `app/src/test/kotlin/dev/scooph/ui/EmptyStateTest.kt`

**Interfaces:**
- Produces: 所有空状态统一为一行灰字，无插图、无按钮；颜色全部取自 Material 3 `colorScheme`，不出现硬编码颜色字面量。

- [ ] **Step 1: 写失败测试**

纯函数 `fun emptyStateText(state: EmptyKind): String`，三种：无 bucket → 「还没有来源」、有 bucket 但无匹配 → 「没有可管理的应用」、有匹配但全部最新 → 「全部是最新的」。断言三句文案。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.scooph.ui.EmptyStateTest'`
Expected: FAIL

- [ ] **Step 3: 实现**

`grep -rn "#[0-9A-Fa-f]\{6\}" app/src/main/kotlin/` 结果必须只出现在 `Theme.kt`。

- [ ] **Step 4: 构建并手工验证深色模式**

Run: `./gradlew :app:installDebug && adb shell "cmd uimode night yes"`
确认三组列表、详情页、条目页在深色下都清晰，切回 `night no`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main
git commit -m "polish: 空状态、深色模式、错误文案"
```

---

### Task 26: 真机验收

**Files:**
- Create: `docs/acceptance.md`

**Interfaces:**
- Produces: 一份可重复执行的验收清单，每条都是「操作 → 期望」。

- [ ] **Step 1: 写清单**

至少包含：首次启动无引导且有内容；添加第二个 bucket；同一包在两个 bucket 中存在时详情页来源正确；跳过一版后上游出新版本能重新出现在可更新；停止跟踪后同步不复活；一键更新 5 个应用（含一次主动取消、一次断网）；签名变更的应用被拦住并显示「接受并更新」；分包安装的应用被跳过并给原因；杀进程后重开状态正确；深色模式。

- [ ] **Step 2: 逐条执行并记录结果**

Run: `./gradlew :app:installDebug`，按清单操作。
Expected: 每条都符合「期望」；不符合的记为缺陷并就地修。

- [ ] **Step 3: 提交**

```bash
git add docs/acceptance.md
git commit -m "docs: 真机验收清单"
```

---

## 自检

**规格覆盖**：§1–§5 → 全局约束与 Review Focus；§6 决定 → Task 1（技术栈）、11（主屏）、13（详情 F）、20（就地 G）、22–24（多 bucket 与条目页）、12（默认 bucket）；§7 架构 → Task 2、10；§8 双仓库 → Task 12 的默认 URL、M2 前需 `scooph-bucket` 就绪（见下）；§9 数据与状态 → Task 7、9；§10 流程 → Task 6、11、12、15–21、24；§11 界面 → Task 11、13、20、22、23；§12–13 技术栈与权限 → Task 1、2、19；§14 测试 → 每个任务的测试步骤；§15 里程碑 → M1–M7 分段。

**计划外的前置**：`scooph-bucket` 仓库的 CI 校验必须在 Task 6 之前可跑，否则第一次同步就会拿到坏数据。它不在本计划的代码范围内，属于另一个仓库的建设。

**类型一致性**：`Relation` 在 Go 侧是字符串、Kotlin 侧是枚举，转换只发生在 `EngineFacade`；`DerivedState` 只在 `Derive.kt` 定义；`UpdateOutcome` 与 `InstallOutcome` 是两层，前者含哈希与签名判断，后者只描述安装会话。

**比例**：26 个任务，每个任务的验收都能在一次构建或一次真机操作里看到结果。
