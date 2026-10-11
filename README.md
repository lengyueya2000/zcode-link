# ZCode Link

把 ZCode 桌面端的「手机远控」网页客户端装进一个原生 Android 外壳，重点解决同一个问题：**连接地址会变**。

- 粘贴或分享连接地址即可连接；
- 地址失效、变化时换一条，历史自动保留；
- 页面里的下载链接能真正保存到「下载」目录；
- 纯本地应用，不上传任何数据。

## 关于连接地址

ZCode 桌面端开启「手机远控」后给出的地址形如：

```
https://zcode.z.ai/remote/v4?sid=…&hash=…&t=…&mid=…&name=PC01&app_version=3.14.4
```

三个关键事实（实测得出）：

1. 这个地址**本身返回一个完整的网页客户端**——手机端的「客户端」就是它，官方 App 也只是它的外壳。
2. 地址里的 `hash` 是连接凭据、`t` 是时间戳，**会失效、会变化**。桌面端重新开启远控就会给出新地址，旧地址的网页客户端会提示「手机连接已失效」。
3. 本机磁盘上**只持久化了设备 SID**（`~/.zcode/v2/setting.json` 的 `webRemoteControlExternalRelayDevice.deviceSid`），完整地址（含 `hash`）不在配置或日志里，无法自动重建。

所以本应用的思路很简单：**地址由你提供（粘贴/分享/扫码），应用负责记住、校验、切换。**

## 构建与安装

```bash
cd android
gradle assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

需要 JDK 17+、Android SDK（compileSdk 35）。签名与详细说明见 [`android/README.md`](android/README.md)。

## 使用

1. 桌面端 ZCode → 开启「手机远控」，复制给出的连接地址。
2. 打开应用 → 工具栏「地址」→ 粘贴 → 保存并连接。
3. 地址变了：重复第 2 步换一条，旧地址仍留在列表里，可随时切回。

也可以直接把地址**分享**到本应用（支持系统「发送到」与 `https://zcode.z.ai` 链接打开），或在其他应用里复制后用「地址」面板的粘贴按钮导入。

## 安全

- 连接地址里的 `hash` 相当于连接凭据。应用只把它发往 `zcode.z.ai`，不做任何第三方请求，也没有统计/上报。
- 关闭了明文流量（`usesCleartextTraffic=false`）、证书异常一律拒绝、不暴露本地文件访问。
- 地址历史只保存在本机应用私有存储里（`SharedPreferences`），不随备份上传（`allowBackup=false`）。
- 请勿截图外发或在聊天里转发完整地址。

## 目录

```
android/            # Android 应用（Gradle 工程）
tools/              # 只读的 Electron app.asar 查看脚本（用于确认远控实现方式）
README.md
```

## 附：`tools/`

`tools/asar-*.cjs` 是只读的 Electron `app.asar` 查看脚本（`asar-extract.cjs` 按文件名筛选、`asar-locate.cjs` 按字节偏移反查文件、`asar-get.cjs` 按路径提取），用于确认 ZCode 远控客户端的实现方式。它们不修改任何原始文件。
