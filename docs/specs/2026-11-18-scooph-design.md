# Scooph — 设计文档

日期：2026-11-18
状态：待审阅

---

## 1. 为什么做这个

手机里装了一批开源应用，来源分散（GitHub Releases、F-Droid、作者自己的站点）。每个应用出新版本时，要找到它的页面、跳到浏览器、定位下载地址、下好 APK、点开安装，才算走完一个。应用一多，这就是一串重复的机械动作，而且每次都要在应用界面和浏览器之间来回切换。

要的是一个统一入口：所有被管理的应用的最新版本都在一个地方看得到，想全都更新时点一下，剩下交给程序跑完。核心是「把 N 次跳转压缩成 N 次确认」，而不是追求免确认的静默安装。

## 2. 目标

1. 用一份可共享的清单（bucket）描述「一个应用从哪来、现在是什么版本、怎么下」。
2. 用户可以添加任意数量的 bucket，来源不限（任何公开 Git 仓库）。
3. 自动扫描本机已安装应用，与 bucket 清单比对，得出「归我管的应用」和「有新版本的应用」。
4. 单应用更新，或一键更新全部。
5. 每个应用有三种更新策略：跟随、跳过某一版、停止跟踪。
6. 能通过 bucket 安装本机还没有的应用。

## 3. 非目标

- 静默安装、免确认安装（不依赖 root、无障碍、设备管理器）。
- 依赖解析。APK 没有依赖概念。
- 后台自动安装。只做后台自动**检查**。
- 桌面端、iOS。
- bucket 的签名验证（格式保留字段，v1 不实现）。
- 上架 Google Play 的合规包装。v1 以侧载分发为前提。
- 应用内的发行说明全文渲染（清单里的 notes 只显示纯文本）。

## 4. 反目标

以下任何一种情况，即使功能都能跑通，也算没做成：

- 主屏或详情页需要一句说明文字才能看懂。
- 需要常驻后台服务才能维持状态。
- 更新流程里弹出我们自己的模态框（系统的安装确认框除外）。
- 同一个状态在 UI 和数据库里各定义一次，改一处忘一处。

## 5. 约束（平台决定，不可协商）

| 约束 | 后果 |
|---|---|
| Android 每次安装都必须经过系统确认界面 | 「一键更新」= N 次确认，不是零次。UI 必须让这 N 次确认来得连贯。 |
| 国产 ROM（小米/HyperOS 等）额外有安装校验、风险提示、倒计时 | 一个包可能不止点一次。这种情况不特殊处理，只保证不卡死、可跳过。 |
| 更新已装应用要求签名一致 | 签名不一致时系统直接拒装。我们要在下载后、提交安装前自己先查一次，给出清楚的原因，而不是让系统报一个含糊的错。 |
| 降级安装默认被拒 | 只有当 versionCode 更高时才提供「更新」。 |
| 枚举全部已安装应用需要 `QUERY_ALL_PACKAGES` | 侧载无碍；上架 Play 需要申报用途。 |
| Android 10+ 不允许执行应用可写目录里的文件 | 若将来要用独立可执行文件形态运行 Go，只能走 jniLibs。v1 不走这条路（见 §7）。 |

## 6. 已确认的决定（用户决定，不可改）

1. **界面风格**：向苹果备忘录看齐。砍掉引导页、提示文案、说明文字、底部标签栏。页面结构本身要能自解释；需要靠附加文字才看得懂，就改布局。
2. **主屏结构**：分组列表。可更新在上，已是最新在下，已忽略单独一组。右上角一个「全部更新」。
3. **二级页面**：点行尾箭头进入。三个更新策略互斥、三选一、当前状态带勾（方案 F）。主按钮为描边样式，整页只有当前勾选那一行是蓝色。
4. **执行更新时不开新页面**：主屏就地变形（方案 G），每一行自己显示下载进度、等待确认、已装好、已跳过及原因。
5. **bucket 不限来源**：任何人、任何 Git 仓库都可以是 bucket，用户可以加多个。
6. **技术栈**：UI 用 Kotlin + Jetpack Compose，业务引擎用 Go。
7. **安装确认的摩擦可接受**：确认点击是可以接受的代价，把「跳转+搜索+下载」压缩成「确认」已经是收益。
8. **单机优先**：不依赖自建服务器，离线可用（同步 bucket 除外）。

## 7. 架构

### 7.1 分层

```
┌─────────────────────────────────────────────┐
│ app/  Kotlin + Compose                      │
│  UI ─ ViewModel ─ Repository                │
│  Android 生命周期、权限、安装会话、通知        │
└──────────────────┬──────────────────────────┘
                   │ 一个 AAR，一个类，JSON 进 JSON 出
┌──────────────────▼──────────────────────────┐
│ engine/  Go（gomobile bind）                 │
│  bucket 同步 · 清单解析 · 版本比较            │
│  下载 · 哈希校验 · 生成更新计划               │
│  不碰任何 Android API                        │
└─────────────────────────────────────────────┘
```

**边界只有一处**：`Engine` 一个类型，方法入参出参全是 JSON 字符串。理由：

- Go 侧可以用纯 `go test` 覆盖，不需要模拟器。
- Kotlin 侧可以塞假实现做 UI 测试。
- JSON 契约同时是给人看的文档和给智能体读的接口，任何一侧都能独立修改另一半。

### 7.2 职责划分（为什么不是 Go 全包）

Go 负责「算」：同步、解析、比对、下载、校验、产出计划。
Kotlin 负责「碰 Android」：安装会话、用户确认、前台服务、通知、权限。

更新队列的调度放在 Kotlin。安装流程天然被 Activity/Service 生命周期绑死，把队列搬进 Go 需要一整套跨语言状态回传，违反「简单」原则。Go 每次只做一件事并返回结果。

### 7.3 Engine 对外接口

```go
type Engine struct{}

// 同步一个 bucket，返回同步结果与条目数
func (e *Engine) SyncBucket(bucketJSON string) (string, error)

// 输入：bucket 条目 + 本机已装应用快照，输出：可更新计划
func (e *Engine) Plan(requestJSON string) (string, error)

// 下载到指定路径，返回校验结果
func (e *Engine) Fetch(taskJSON string, p Progress) (string, error)

type Progress interface {
    OnProgress(packageName string, done, total int64)
}
```

所有 JSON schema 集中放在 `schema/`，由 Go 测试和 Kotlin 数据类共同引用。

### 7.4 仓库结构

```
scooph/
  app/                 Android 应用（Kotlin + Compose）
  engine/              Go 模块
    bucket/            git 同步与仓库布局
    manifest/          清单解析与校验
    version/           版本比较
    download/          HTTP 下载、断点、sha256
    api/               gomobile 暴露层（JSON 编解码）
  schema/              JSON Schema 契约
  docs/specs/          设计文档
  Makefile             make check / make apk / make engine
```

## 8. Bucket 契约

bucket 是一个 Git 仓库，布局固定：

```
bucket.json
apps/<packageName>.json
```

`bucket.json`：

```json
{
  "schema": 1,
  "name": "示例 bucket",
  "maintainer": "可选",
  "signature": null
}
```

`apps/<packageName>.json`（一个应用一个文件，文件名必须等于 `package`）：

```json
{
  "schema": 1,
  "package": "com.fsck.k9",
  "name": "K-9 Mail",
  "versionCode": 7000,
  "versionName": "7.000",
  "apk": {
    "url": "https://example.org/k9-7000.apk",
    "size": 6512345,
    "sha256": "9f2c…"
  },
  "source": "https://github.com/thundernest/k-9",
  "minSdk": 26,
  "notes": "可选的纯文本"
}
```

解析规则：

- `sha256` 必填。缺失的条目整条丢弃并记原因，不做降级放行。
- 未知字段忽略，不报错。这是格式能演进的前提。
- 一个文件坏掉只影响这一个条目，不中断整次同步。
- `versionCode` 是唯一可靠的版本序。`versionName` 只用于显示。
- `package` 必须是合法 applicationId，也是本机比对的唯一键。**不按应用名称匹配。**

## 9. 数据模型（Room）

三张表，职责不重叠：

- `sources`：id、name、url、lastSyncAt、lastCommit、status。
- `entries`：`(packageName, sourceId)` 复合主键、name、versionCode、versionName、apkUrl、sha256、size、notes、fetchedAt。属于 bucket，可整表重建。
- `tracked`：packageName（主键）、policy、skipVersionCode、installedVersionCode、installSignature、installedAt、lastCheckedAt、lastError。属于本机，不随 bucket 增删而丢失。

### 9.1 多个 bucket 提到同一个包时的仲裁

`entries` 用复合键，多个 bucket 可以同时拥有同一个包。每次推导状态前，先为每个 package 选一条**生效条目**：

1. 取 `versionCode` 最大者。
2. 相同则取 bucket 添加顺序更早者。
3. 再相同则取包名字典序更小的 sourceId，保证结果确定。

规则只写在 `resolveEntry(package)` 一个函数里。详情页显示的「来源」就是这条生效条目所属的 bucket，可追溯。

某个 bucket 被删除后，它的 `entries` 随之消失，`tracked` 不受影响；如果没有别的 bucket 提供这个包，该应用进入「已安装但无来源」，退出主屏，不显示、不检查。

### 9.2 状态是推导出来的，不落库

`policy` 只有三个值，是全 app 唯一的策略定义：

```
FOLLOW                      跟随
SKIP_VERSION(versionCode)   跳过指定版本，等下一个
UNTRACKED                   用户显式停止跟踪（墓碑，永不被自动改回）
```

显示状态由 `policy` + 生效条目 + 本机快照推导，纯函数 `derive(tracked, entry, installed)`：

```
IGNORED      policy = UNTRACKED                    → 已忽略组
SKIPPED      policy = SKIP_VERSION 且 entry.versionCode == skipVersionCode
                                                   → 已忽略组
UPDATABLE    entry.versionCode > installed.versionCode
                                                   → 可更新组
UP_TO_DATE   entry.versionCode == installed.versionCode
                                                   → 已是最新组
AHEAD        entry.versionCode < installed.versionCode
                                                   → 已是最新组，行内标「本机版本更新」，无更新动作
NO_SOURCE    没有生效条目                            → 不显示
NOT_INSTALLED 本机未安装                             → 不显示，只在来源页出现
```

三个边界情况按上面的顺序判定，前一条命中即返回：

- `SKIP_VERSION` 且 `entry.versionCode > skipVersionCode`：跳过的只是那一版，出了更新的版本就回到 **UPDATABLE**。更新成功后 `policy` 自动回到 `FOLLOW`。这就是「跳过这一版，等下一个」。
- `AHEAD`：bucket 落后于本机（或用户从别的渠道装了更高版本）。这一档不提供更新，也不报错，因为这不是故障。
- `lastError` 是覆盖层，不是独立状态：上面任何一个状态都可以带一条失败原因，显示在同一行，不改变分组。

`ERROR` 因此不是一个状态值，而是一个可选的附注字段。这样状态机是七个值的全函数，没有落空的输入。

## 10. 关键流程

### 10.1 同步 bucket

用户添加 Git URL → `SyncBucket` 克隆或增量拉取到应用私有目录 → 逐文件解析进 `entries` → 记录 commit 与时间。失败保留上一次成功的快照，只更新 `sources.status`。

### 10.2 检查更新

`QUERY_ALL_PACKAGES` 扫描已装应用 → 生成 `(packageName, versionCode, signature)` 快照 → 与 `entries` 内联 → 按 §9 推导状态。只在本地算，不发网络请求。

### 10.3 单应用更新与签名锚点

下载 → 校验 sha256 → 比对签名锚点 → 一致则提交安装会话 → 用户确认 → 回写状态。

**锚点的定义**：取 APK 的签名证书集合（`Signature` 数组，不是整包摘要），每个证书的 DER 字节做 SHA-256，按字典序拼接后再做一次 SHA-256，得到一个十六进制串。比较的是这个串。这样与 APK 的编译方式、压缩方式无关。

**锚点的建立时机**：扫描到已安装应用、且该应用不是 `UNTRACKED` 时，若 `installSignature` 为空，立即从 `PackageManager` 读取已装签名写入。也就是说，**用户第一次更新一个「本来就在手机上」的应用时，基线已经存在**。不依赖「第一次成功安装」才建锚。

**证书轮换**：合法轮换时 APK 的签名集合会同时包含新证书和旧证书（v3 rotation lineage）。判定规则是「集合包含旧锚点里的任一证书即视为一致」，随后把锚点更新为新集合。这样轮换不会把正常更新永久判死。

**不一致时**：不提交安装，该行显示「签名已变更」，队列在此暂停，等用户处理。详情页此时才出现一个「接受并更新」的按钮。接受后先更新锚点再执行，且这个动作永远不会进入「一键更新」的自动流程，必须人工点。这是 v1 唯一一个条件出现的界面元素。

**分包安装（split APK）**：bucket 契约是单个 APK。若本机该应用是分包安装（`splitSourceDirs` 非空），用单 APK 覆盖会有无法预期的结果，直接跳过，原因写明「该应用为分包安装，暂不支持」。扫描时就记录这个标志，不等到安装失败才发现。

**首次安装**：本机没有该应用时没有基线，记录下这次安装的签名作为锚。


### 10.4 一键更新

队列在内存里，不落库。Kotlin 启动前台服务（`dataSync`）+ 通知，串行执行：一次只装一个，因为系统确认界面本身就是串行的。

每一项的状态直接投影到主屏对应行：

```
下载中 62% → 等待安装确认 → 已装好 ✓
                          → 跳过：哈希不符
                          → 跳过：分包安装
                          → 失败：网络断开
```

跳过和失败都继续下一个。唯一的例外是签名变更：它需要人做决定，所以队列在这里停住（见 §10.3）。

结束条件只有两个：队列走完，或用户主动中止。结束时通知汇总：成功几个、跳过几个、失败几个。原因写在行里，不藏进详情页。

**用户取消系统确认框**：系统只给一个「取消」信号，区分不了「误关」和「不想装这个」。按可恢复的那一侧处理——该行停在「等待安装确认」，队列暂停，点这一行可以重新唤起，不需要重新下载。

**切后台、锁屏**：下载继续，因为不需要界面。轮到需要确认的时候停下，发一条通知「等待确认：NewPipe」，点通知回到主屏同一位置。不尝试在后台绕过确认。

**进程被杀**：队列消失，这是刻意的。重启后回到普通列表，状态由版本号重新推导，所以不会丢信息。已下载的 APK 按 sha256 命名留在缓存里，下次更新直接命中，不重下。

**关于前台服务**：只有更新进行中才有前台服务，不更新时没有任何常驻进程。这跟 §4 的反目标不冲突——反目标禁止的是「为了维持状态」而常驻。Android 15+ 对 `dataSync` 类型有每日额度，这一层不需要长跑，队列本来就要靠人点确认。

### 10.5 自动接管的范围

同步 bucket 之后，凡是与本机已装应用匹配的条目，自动建成 `tracked` 并置 `FOLLOW`。用户不需要逐个「接管」——这正是这个 App 存在的理由。

已经有 `tracked` 记录的应用不会被同步改写。`UNTRACKED` 是用户显式决策的墓碑，同步和扫描都不得把它改回 `FOLLOW`。唯一的改回路径是用户在详情页手动改。

因此第一次添加一个覆盖几十个应用的 bucket 时，主屏会一次性多出这些应用。这是预期行为：分组本身负责降噪，只有真有新版本的才在上面。

### 10.6 私有仓库

v1 只支持公开仓库。凭据存储、Keystore、token 输入界面都不做。加 bucket 时若遇到鉴权失败，报错要写明「私有仓库暂不支持」，而不是一个网络错误码。

这条约束影响 §2 第 2 条的措辞：「任何公开 Git 仓库」。


### 10.7 权限获取时机

不做前置引导页。权限在真正需要的那一刻才申请：

- 第一次点更新且未授权「安装未知应用」→ 直接跳系统设置页，回来后队列继续。
- 通知权限在第一次启动队列时申请。
- `QUERY_ALL_PACKAGES` 是清单权限，无运行时弹窗。

## 11. 界面

### 11.1 页面清单

只有三处，没有底部标签栏：

1. **主屏**：分组列表 + 右上「全部更新」。三组：可更新、已是最新、已忽略。已忽略组里既有用户主动停止跟踪的，也有只跳过当前版本的，行内文案区分这两者。
2. **详情页**：点行尾箭头进入。应用图标、名称、包名、当前/最新版本、描边主按钮「更新到 x」、三个互斥策略、来源。仅在签名变更时多出一个「接受并更新」。
3. **来源页**：bucket 列表，可添加、同步、删除，可进入某个 bucket 的条目列表安装新应用。

### 11.2 布局规则

- 层级用字重和留白表达，不用分隔线、不用卡片阴影、不用彩色标签。
- 一个页面只回答一个问题。
- 全局只有一个强调色，用在当前状态和主操作上。
- 空状态是一句话，不是插图和按钮堆。
- 深色模式跟随系统，用 Material 3 的 color roles，不手写两套颜色。

### 11.3 已经画过的界面

原型在 `.superpowers/brainstorm/`（本地存在，已 gitignore），作为视觉参考而非规格。文字规格以上文为准。已确认的三项：主屏分组式（方案 A）、详情页状态式加描边按钮（方案 F）、更新时就地变形不开新页（方案 G）。

## 12. 技术栈

| 层 | 选择 |
|---|---|
| 语言 | Kotlin 2.x、Go 1.27 |
| UI | Jetpack Compose + Material 3 |
| 状态 | ViewModel + StateFlow，UI 是状态的纯投影 |
| 存储 | Room；bucket 快照落文件系统 |
| 后台 | Foreground Service（安装队列）、WorkManager（定期检查） |
| Git | go-git（纯 Go，无 cgo） |
| 跨语言 | gomobile bind 产出 AAR |
| 构建 | Gradle（app）+ Makefile（engine、一键检查） |
| 平台 | minSdk 26，compileSdk / targetSdk 36 |

## 13. 权限清单

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
```

不需要存储权限：下载落在应用私有缓存目录，安装通过 PackageInstaller 会话流式写入。

## 14. 测试策略

| 层 | 方式 |
|---|---|
| Go 清单解析 | 表驱动：缺 sha256、未知字段、文件名与 package 不符、损坏 JSON |
| Go 版本比较 | versionCode 大小、相等、降级拒绝 |
| Go bucket 同步 | 本地临时 git 仓库做 fixture |
| Kotlin 状态投影 | `derive()` 七值全函数：每个 policy × 版本关系组合都有断言，不允许落空 |
| Kotlin 队列 | 假安装器，验证失败继续、串行、状态回写 |
| 手工 | 真机安装循环。系统确认框无法自动化，这一层接受手工验证 |

`make check` 一条命令跑完 Go 测试、Kotlin 单测和 lint。

## 15. 里程碑

- **M1** 骨架：Gradle 工程跑起来，Go AAR 编进 APK并被调用，主屏是空列表。
- **M2** bucket 同步 + 清单解析 + 扫描已装 + 主屏三组真实数据。
- **M3** 详情页 + 三个策略持久化。
- **M4** 单包更新走通：下载、校验、签名比对、安装确认、状态回写。
- **M5** 一键更新就地队列 + 失败继续 + 通知汇总。
- **M6** 来源页 + 从 bucket 安装新应用。
- **M7** 打磨：空状态、错误文案、深色模式、真实 bucket 试用。

每个里程碑结束时应用可运行，不留半截功能。

## 16. 留给构建者的决定

- 具体的间距、字号、圆角 token 数值。
- 通知文案。
- 先中文还是中英双语（默认：先中文，字符串资源可切换）。
- 应用图标与名称的最终形态（暂定名 Scooph，包名 `dev.scooph.app`）。
- CI 是否接入（默认：本地 `make check` 足够，暂不搭 CI）。
- bucket 条目列表要不要搜索框（默认：不做，条目少时列表本身就是搜索）。

## 17. 我的判断（构建者可推翻，但要知道为什么）

1. **用 gomobile bind 而不是手写 JNI**：少一层胶水，代价是多一条 NDK 构建链。若这成为主要故障源，退回 `c-shared` + JNI 的代价可控。
2. **用 go-git 而不是下载 tarball**：增量拉取、支持任意 Git 主机、不依赖 GitHub API 限流。代价是 Go 侧多几 MB。
3. **队列调度放 Kotlin 而不是 Go**：见 §7.2。
4. **v1 不做 bucket 签名验证**：真正的防线是「已装应用签名钉死」，它挡住了最危险的一类攻击（把已装应用替换成另一个）。对新安装应用的供应链风险，v1 靠「来源可见 + 哈希固定 + 用户确认」承担，并在格式里预留签名位。
5. **不做「已安装但不在 bucket 中」的管理，也不显示它们**：手机上几百个应用，列出来没意义，还会让「检查更新」付出一次全量扫描的代价。这类应用对 Scooph 不存在。扫描只在需要比对时进行。
6. **更新队列只活在内存里**：进程结束队列就没了。因为队列的每一项都能从版本号重新推导出来，持久化它等于给状态制造第二个真相。已下载的 APK 留在缓存，价值在那儿，不在队列里。
7. **自动接管，不做「逐个接管」流程**：加一个 bucket 就接管所有匹配的已装应用。需要用户逐个确认的设计不适合这个产品——用户要的正是「不用一个一个点」。
8. **`entries` 用复合键加一条仲裁函数**：允许多个 bucket 描述同一个包，谁生效由一个函数算，不在各处各写一遍。
9. **签名锚点在扫描时就建立，不等到第一次安装**：否则「更新一个本来就在手机上的应用」这件事根本没有基线，§10.3 的检查会静默失效。
10. **后台不推进确认**：不看界面就不推进。宁可停下等，也不做任何绕过确认的尝试。
