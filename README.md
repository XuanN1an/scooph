# Scooph

用 bucket 管理散落在各处的 Android 应用：集中检查版本、按策略更新、一键批量安装。

UI 用 Kotlin + Jetpack Compose，版本比较与下载校验在 Go 引擎里（gomobile bind 成 AAR）。

## 功能

- **bucket 驱动**：任何公开 Git 仓库都可以是来源；一个包一个 `apps/<packageName>.json`
- **多来源**：同一应用被多个 bucket 描述时，按「版本最高 → 先添加 → 包名字典序」确定性仲裁
- **三种策略**：正常更新 / 跳过这一版 / 忽略后续更新
- **单个更新**：下载 → sha256 校验 → 签名锚点比对 → 系统安装确认
- **一键更新**：串行队列，主屏就地显示进度与失败原因；签名变更会暂停队列
- **签名安全**：扫描即建锚；换签需人工「接受并更新」，分包安装直接跳过并说明原因
- **缓存**：同版本复用已校验 APK；来源页可手动清理

## 仓库

| 仓库 | 内容 |
| --- | --- |
| `scooph`（本仓库） | Android 应用、Go 引擎、设计文档 |
| [`scooph-bucket`](https://github.com/XuanN1an/scooph-bucket) | 官方清单仓库：格式规范、条目、校验 CI |

两个仓库互不依赖构建：App 不把 bucket 当 submodule，bucket 是运行时从 Git 拉取。

## 构建

需要 JDK 17、Android SDK（platform 36 / build-tools / NDK）、Go 1.27+。

```bash
make tools     # 安装 gomobile 到 .toolchain/gobin
make apk       # Go 引擎 -> AAR，再打 debug APK
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

详细步骤与常见构建问题见 [docs/build.md](docs/build.md)。

## 测试

```bash
make check     # gofmt + go test + gomobile bind + Kotlin 单测 + lint
```

真机验收清单见 [docs/acceptance.md](docs/acceptance.md)，设计文档见 [docs/specs](docs/specs)，任务计划见 [docs/plans](docs/plans)。

## 运行要求

Android 8.0+（minSdk 26），需要允许「安装未知应用」。

## License

GNU General Public License v3.0，见 [LICENSE](LICENSE)。
