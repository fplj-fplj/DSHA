# 电脑 DSH 手机控制插件

`dsha-connect-device` 是真实 Node/Cordis 插件，入口 `index.js`，使用 DSH 正式 `defineTool` API。协议版本独立于 DSH 版本；支持的 DSH 基线是 0.2.1-rc.2 与 rc.3。插件不启动、不替换本机 DSH，也不保存手机凭据。

1. 运行同一用户的 `dsha-connect --dsh-url "http://127.0.0.1:实际端口/?token=实际启动令牌"`。令牌仅在本机换取 DSH Cookie；不进入 QR 或手机。
2. 打开 companion 输出的本机启动链接，用手机扫码配对。二维码三分钟有效，完成配对后换为独立长期密钥。
3. 在电脑自己的 DSH 安装此目录打包的插件，按正常 Cordis 插件入口加载。默认从用户配置目录的 `ipc.json` 发现 companion；可使用插件配置 `ipcPath` 或环境变量 `DSHA_CONNECT_IPC` 指定同一用户私有 IPC 文件。
4. 先读取手机状态，再开始任务。手机上可暂停、停止或撤销信任。撤销后需在电脑重新运行 companion 并扫码。

Windows 原生 companion 的状态在 `%LOCALAPPDATA%\DSHA\Connect`。WSL 内运行 Linux companion 和 DSH 时，状态在 `${XDG_CONFIG_HOME:-~/.config}/dsha-connect`；两者必须在同一用户运行域。无需管理员或系统服务。HTTP 目标只接受数字 loopback 地址及显式端口，不能转发任意网络地址。

截图使用工具输出的 image 内容。文件传输只能使用远端返回的内容通道，不能使用手机本地路径。断线、超时或缺少回执显示未知，不自动重试输入或业务提交。未配置有效 DSH Cookie时，静态网页可以加载而业务 API 会如实拒绝。
