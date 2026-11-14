# Root 限制版构建状态 · 2026-11-14

**Standard 已由 GitHub Actions 构建、验证并发布为 Latest**：[下载 APK](https://github.com/fplj-fplj/DSHA/releases/download/root-guard-20261114-standard-84ef24c19086/dsha-0.2.1-rc.2.apk) · [Release](https://github.com/fplj-fplj/DSHA/releases/tag/root-guard-20261114-standard-84ef24c19086) · [签名回执](https://github.com/fplj-fplj/DSHA/releases/download/root-guard-20261114-standard-84ef24c19086/build-receipt.json)。本轮不发布 Low APK。

APK 对应源码为 `84ef24c190864161640452c1740dbd82063ed026`，云端源码工作区干净。Root 实现提交为 `384604d`，Standard 构建选择及提醒修正提交为 `0d22f7a`；后续两次提交只修正测试，发布后的说明更新不属于 APK 构建源码。

## 已发布 APK 与云端结果

| 项目 | 实际结果 |
| --- | --- |
| 版本 | `0.2.1-rc.2-20261114.0530-root-guard-dsh0.2.1-rc.2` / code190 |
| 身份 | `dsh.android.client`、非调试、Standard / minSdk 30 / arm64-v8a |
| 大小 | 292,189,787 字节 |
| APK SHA-256 | `424bf92f91df9aed4d69798508f40687e65b9bd845bc56d7f25a026ef07ecd75` |
| 固定 fork 证书 SHA-256 | `d94a0ba027c1be34f2a9c4d0319a2d2b5eed784bd47f235f2c33951207c16d26` |
| Standard 发布任务 | [43560114597](https://github.com/fplj-fplj/DSHA/actions/runs/43560114597)，成功 |
| 同源码独立检查 | [43560081743](https://github.com/fplj-fplj/DSHA/actions/runs/43560081743)，成功 |

Standard 发布任务重新运行 JUnit：**2,127 项，0 失败、0 错误、1 跳过**。Release Lint、打包、插件事务/升级、应急运行时、浏览器、APK 资产/许可/ELF/清单等该工作流检查均通过；包检查回执为 `PASS_UNSIGNED_PACKAGE`，随后独立签名步骤核对既有 fork 证书、v1/v2/v3、16 KiB zipalign、非调试清单和本次变更资产。Lint 通过不表示没有警告。

独立源码任务通过 1,044 个纳入格式清单的 Java 文件检查，以及文档、文案、输入契约、来源卫生和八组隔离桥接/源码回归。实际 JUnit XML 为 Standard/Low 各 386 类、2,127 项，均零失败/错误，分别跳过 1/74 项；两版 `RootShellDialogsTest` 各两项全部通过。这里的 Low 结果仅为源码编译和 Debug 单元测试，不代表 Low Release Lint、APK 或设备验收。

发布后核对 GitHub Latest 指向本标签，Release 仅含 Standard APK、其 `.sha256` 及 `build-receipt.json`。GitHub 附件大小和服务器 SHA-256 与回执完全一致；本机只取回小型报告与回执，没有重新下载 APK。没有执行设备安装、启动、布局仪器测试或真机验收。Root 限制范围和递归 READ 子链接的已知缺口见[功能说明](root-restrictions-20261114.md)。

## GitHub Actions

最初 GitHub REST 的仓库权限显示 `enabled: true`，主构建与源码工作流状态均为 `active`，但多次请求 `workflow_dispatch` 均返回 HTTP 422：`Actions has been disabled for this repository.`。用户重新开启 Workflow 后，Standard 构建成功触发；该障碍已经解除。接口没有提供此前禁用的具体原因，不据此推断配额、账单或账户问题。

恢复后的云端执行记录分别保留：

1. [第一次构建 43558608293](https://github.com/fplj-fplj/DSHA/actions/runs/43558608293)，源码 `198d1ef`：应用 Java 编译通过，Standard JUnit 共 2,127 项，4 项失败、0 项错误、1 项跳过。三项旧界面断言未更新为 Root 例外说明，一项弹窗测试没有处理 Robolectric 主线程回调。提交 `4601106` 修正测试断言及回调处理，未改变生产实现。
2. [第二次构建 43559474517](https://github.com/fplj-fplj/DSHA/actions/runs/43559474517)，源码 `4601106`：Debug/Release 应用 Java 编译通过，测试编译因 `Shadows.shadowOf` 重载依赖编译环境缺少的 `FingerprintManager` 类型失败，JUnit 未执行。提交 `84ef24c` 改用项目已有的 `ShadowLooper.idleMainLooper()`，继续保留真实点击和取消断言。
3. [第三次 Standard 构建 43560114597](https://github.com/fplj-fplj/DSHA/actions/runs/43560114597)，源码 `84ef24c190864161640452c1740dbd82063ed026`：全部步骤通过，生成并发布上述 Standard APK。前两次失败记录保留，不称一次连续全绿。

`build-authorized.yml` 默认选择 `standard`，仍保留完整 JUnit、Release Lint、APK/ELF/许可/资产、固定 fork 证书及三种签名检查。Low 可另选，Standard 回执不代表 Low 通过。没有在本机重新运行 Gradle，也没有新建仓库或更换身份。

## 前期本机检查

- 实际 Java 编译及 JUnit：`DeviceShellExecutorTest`、`DeviceShellPolicyTest`，合计 22 项通过。
- 原生策略干运行：16 项通过，不启动 Android 进程或设备命令。
- Python 设备策略 30 项、隔离 ADB 流程 34 项、桥路由 4 项、界面来源回归 33 项、发布证据与 Standard 单包签名流程替身测试 7 项，全部通过。签名替身测试不是实际 APK 签名证明。
- 受管运行时描述符、文案目录、文档链接、源码卫生和分层检查通过；运行时 ID 为 `0d9541e9c12f79850e194ce60c1d9ceb2bc602353bc8f0ceb76c29887465c13c`。
- `RootShellDialogsTest` 两项 Robolectric 回归验证启用必须实际点击，以及取消不启用 Root；云端执行结果与上述失败记录分开说明。

本地曾尝试针对性 Gradle 核验：先遇到 JDK CA 文件缺失，使用系统 CA 创建专用开发 trust store 后重试；随后在 Maven 依赖下载阶段因连接被拒绝失败，未进入应用编译。用户随后要求优先云端 Standard，已停止新的本地 Gradle 尝试。不把这些失败计入通过结果。

## 本机清理

已删除五份上一轮下载的 APK，以及可再生成的 Chromium、测试运行时和应急资产副本，清理时实际释放约 **3.87 GiB**。后续保留必要的 Gradle 开发依赖与云端报告后，复核可用空间约 **16.98 GiB**；本项目构建与下载目录已无 APK。源码、固定输入、SDK/JDK、签名材料、历史对话和验收记录保留，旧 GitHub Release 未删除。本轮没有设备安装、启动或真机验收。
