# ZCode 连接工具

围绕 ZCode「手机远控」的两个工具，重点解决同一个问题：**连接地址会变**。

| 目录 | 是什么 | 在哪运行 |
| --- | --- | --- |
| [`android/`](android/README.md) | **Android 应用**：把 ZCode 手机远控网页客户端装进原生外壳，管理会变化的连接地址 | 手机 |
| [`server.js`](server.js) + `public/` | 电脑端连接助手：把连接地址变成二维码，并保留地址历史 | 电脑 |

两者可以独立使用，也可以配合：电脑上用它生成二维码/留档，手机上用 App 粘贴或分享导入。

## 关于连接地址

ZCode 桌面端开启「手机远控」后给出的地址形如：

```
https://zcode.z.ai/remote/v4?sid=…&hash=…&t=…&mid=…&name=PC01&app_version=3.14.4
```

两个关键事实（实测得出）：

1. 这个地址**本身返回一个完整的网页客户端**——手机端的「客户端」就是它。
2. 地址里的 `hash` 是连接凭据、`t` 是时间戳，**会失效、会变化**。桌面端重新开启远控就会给出新地址，旧地址的网页客户端会提示「手机连接已失效」。
3. 本机磁盘上**只持久化了设备 SID**（`~/.zcode/v2/setting.json` 的 `webRemoteControlExternalRelayDevice.deviceSid`），完整地址（含 `hash`）不在配置或日志里，无法自动重建。

所以两个工具都采用同一个思路：**地址由你提供（粘贴/分享/扫码），工具负责记住、校验、切换。**

---

## ① Android 应用（`android/`）

WebView 外壳 + 地址管理。粘贴或分享连接地址即可连接；地址变了换一条，历史保留。

```bash
cd G:\zcode\tgg\android
gradle assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

完整说明见 [`android/README.md`](android/README.md)（构建、签名、安全说明、已验证环境）。

---

## ② 电脑端连接助手（`server.js`）

本地网页工具：粘贴连接地址 → 生成手机可扫的二维码；同时读取本机 ZCode 配置显示当前设备 SID，并校验地址是否属于这台电脑。

```bash
cd G:\zcode\tgg
node server.js            # 打开 http://127.0.0.1:8787/
npm run start:open        # 启动后自动打开浏览器
```

- 二维码在浏览器**本地**生成（内置 MIT 许可的 `qrcode-generator`），含签名的地址不会发往任何外部服务。
- 支持「读取剪贴板」「自动监听剪贴板」（ZCode 复制新地址后自动刷新二维码）、地址历史（去重、可载入/复制/删除）。
- 默认只监听 `127.0.0.1`；`--port` / `--host` / `--open` 可选，`ZCODE_HOME` 可覆盖配置目录。
- 历史记录写入 `data/links.json`（含完整地址与签名，权限 0600，勿分享）。

### HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 存活检查 |
| GET | `/api/device` | 本机 ZCode 远程设备信息 |
| POST | `/api/parse` | `{url}` → 解析结果（不落盘） |
| GET | `/api/links` | 历史地址列表（hash 已脱敏） |
| POST | `/api/links` | `{url}` 保存一条（按完整 URL 去重，最多 50 条） |
| DELETE | `/api/links?id=<id>` | 删除一条 |

---

## 安全

连接地址里的 `hash` 相当于连接凭据。两个工具都不会把它发往 `zcode.z.ai` 之外的任何地方：二维码本地生成，App 只加载该地址本身。请勿截图外发或在聊天里转发完整地址。

## 附：`tools/`

`tools/asar-*.cjs` 是只读的 Electron `app.asar` 查看脚本（`asar-extract.cjs` 按文件名筛选、`asar-locate.cjs` 按字节偏移反查文件、`asar-get.cjs` 按路径提取），用于确认 ZCode 的远控实现方式。它们不修改原始文件。

```
tgg/
├── android/                  # ① Android 应用（Gradle 工程）
├── server.js                 # ② 电脑端连接助手
├── public/                   # ② 的网页界面 + 内置二维码库
├── data/links.json           # ② 的地址历史（运行时生成，勿分享）
├── tools/                    # asar 只读查看脚本
└── README.md
```
