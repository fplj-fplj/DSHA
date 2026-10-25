# DSHA Connect 电脑伴随程序

这是单独的用户级程序，不安装或替换电脑自己的 DSH，不需要管理员，也不创建系统服务。Windows 使用 `dsha-connect.exe`；WSL 使用 Linux 包的 `./dsha-connect`，与 DSH 和插件运行在同一侧。

先启动电脑现有 DSH，再运行：

```powershell
.\dsha-connect.exe --dsh-url "http://127.0.0.1:实际端口/?token=DSH本次启动令牌"
```

打开终端给出的本机启动链接，手机扫码并确认电脑身份。二维码三分钟有效。首次配对的高熵票据与记住设备后的长期密钥分离；丢失最后配对确认时仍可用已保存的新密钥完成重连。电脑重启 companion 后，手机需重新连接建立本次会话身份，长期信任无需再次扫码。

局域网 TCP/QUIC 监听端口保存在私有状态，重启继续使用；端口被其它程序占用时明确报错。电脑 IP 改变时，已信任状态的页面显示“更新电脑地址”二维码。这个二维码不含长期密钥，手机只允许更新已有同一 PC 身份，并在验证既有 Noise 身份及长期密钥后保存新地址。每个状态目录只允许一个 companion 实例，防止重复启动覆盖信任状态。

电脑 DSH 安装同包提供的 `dsha-connect-device-0.1.1.tgz`。它是正常 Cordis bundle；只增加真实手机状态、任务和屏幕工具。工具任务归属从执行它的实际 DSH agent 会话和轮次读取，模型不能指定别的会话。截图存入电脑 DSH 的真实附件存储并以 image 内容交给模型。手机上的暂停、停止、敏感确认和系统权限继续生效。停止助手会清除队列并等待实际 Agent 空闲；超时或断线只表示结果未知。

文件上传、下载和分享使用远程网页及手机文件选择器，不把手机路径当作电脑可读文件。伴随程序只转发登记的本机 DSH HTTP/WS 流，目标限数字 loopback 地址和显式端口；不代理任意网络地址。

Windows 私有状态默认 `%LOCALAPPDATA%\DSHA\Connect`，目录 DACL 限当前用户。WSL 默认 `${XDG_CONFIG_HOME:-~/.config}/dsha-connect`，目录 0700、文件 0600。代码和私有身份/会话 Cookie 分开，不要把状态目录打包分发。自定义 `--state-dir` 同样限制权限。插件默认读取同一用户的 `ipc.json`；可用 `DSHA_CONNECT_IPC` 指定它。

同网络可直接连接。需要中继时，显式配置部署者给出的真实地址：

```text
dsha-connect --discovery https://实际发现服务 --relay /dns4/实际中继主机/tcp/端口/tls/ws/p2p/实际中继PeerID --dsh-url 本机DSH启动链接
```

发现服务保存签名的短期地址和独立发现 capability，不持有应用配对密钥、聊天或屏幕内容。中继流仍由端点 Noise 身份和配对密钥鉴权。没有预填一个并不存在的生产服务。中继可用性、NAT 和防火墙仍影响实际连通。

发现服务暂不可用时，已有长期信任的 companion 继续接受局域网连接，页面显示发现服务不可用并后台有界重试；不把这个状态写成公网就绪。已有手机绑定由 PC 身份签名恢复到发现租约，长期离线不需要延长首次扫码窗口。尚待完成的撤销仍先完成安全门，再创建新发现邀请。

在电脑页面撤销手机信任，或退出后运行 `dsha-connect --revoke`。撤销立即退休当前流及待完成配对密钥；再次配对需新二维码。使用发现服务时，新邀请前还要完成签名撤销旧发现租约，离线时会明确拒绝创建新邀请。退出程序会停止远程连接；已有电脑 DSH 与用户数据保留。

从源码构建：需要 Go 1.26+、Python 3，执行 `python build-packages.py --go /path/to/go --out /path/to/output`。`go.sum` 固定模块，包内附依赖许可证。测试使用仓库当前锁定 DSH：`DSHA_GO=... DSHA_PC_EVIDENCE=... node test-real-host.mjs`；设置 `DSHA_PC_FRONTEND=1` 还验证真实网页文件按钮与 RemoteFileScript。宿主测试不能代替 Android 真机 SAF、屏幕输入或全设备矩阵。
