# Root 限制版构建状态 · 2026-11-14

代码与 Standard 优先工作流已推送到 `fplj-fplj/DSHA`，实现提交 `384604d`，Standard 构建选择及提醒修正提交 `0d22f7a`。本次尚未产生或发布新 APK；当前 Latest 的 11-13 APK 不包含本次恢复的 Root 限制。

## GitHub Actions

GitHub REST 的仓库权限显示 `enabled: true`，主构建与源码工作流状态均为 `active`，仓库未归档、未标为 disabled。确认启用状态后，通过工作流编号和文件名、多次请求 `workflow_dispatch`，包括明确传入 `flavor=standard`，均返回 HTTP 422：`Actions has been disabled for this repository.`。较轻的 `ci-fast.yml` 也返回相同错误，最新提交未创建运行记录。接口没有提供更具体的禁用原因，不能据此断言是配额、账单或账户问题。

GitHub 端解除限制后，运行 `build-authorized.yml`、选择 `standard` 即可继续；默认已为 Standard。所选版本仍保留完整 JUnit、Release Lint、APK/ELF/许可/资产、固定 fork 证书及三种签名检查。Low 可另选，Standard 回执不代表 Low 通过。没有新建仓库或更换身份来绕过限制。

## 已执行检查

- 实际 Java 编译及 JUnit：`DeviceShellExecutorTest`、`DeviceShellPolicyTest`，合计 22 项通过。
- 原生策略干运行：16 项通过，不启动 Android 进程或设备命令。
- Python 设备策略 30 项、隔离 ADB 流程 34 项、桥路由 4 项、界面来源回归 33 项、发布证据与 Standard 单包签名流程替身测试 7 项，全部通过。签名替身测试不是实际 APK 签名证明。
- 受管运行时描述符、文案目录、文档链接、源码卫生和分层检查通过；运行时 ID 为 `0d9541e9c12f79850e194ce60c1d9ceb2bc602353bc8f0ceb76c29887465c13c`。
- `RootShellDialogsTest` 两项 Robolectric 回归已加入源码，等待 Android 构建执行。完整应用编译、两版软件门禁及 APK 检查尚未完成。

本地曾尝试针对性 Gradle 核验：先遇到 JDK CA 文件缺失，使用系统 CA 创建专用开发 trust store 后重试；随后在 Maven 依赖下载阶段因连接被拒绝失败，未进入应用编译。用户随后要求优先云端 Standard，已停止新的本地 Gradle 尝试。不把这些失败计入通过结果。

## 本机清理

已删除五份上一轮下载的 APK，以及可再生成的 Chromium、测试运行时和应急资产副本，清理时实际释放约 **3.87 GiB**。后续保留必要的 Gradle 开发依赖后，复核可用空间约 **17.04 GiB**；本项目构建与下载目录已无 APK。源码、固定输入、SDK/JDK、签名材料、历史对话和验收记录保留，旧 GitHub Release 未删除。本轮没有设备安装、启动或真机验收。
