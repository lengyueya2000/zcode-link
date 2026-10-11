# ZCode 连接（Android）

一个轻量的 Android 应用：把电脑端 ZCode 的**手机远控**网页客户端装进原生外壳，并解决「连接地址会变」的问题——地址变了就粘贴/分享一条新的，历史记录会保留。

## 为什么是这个形态

电脑端 ZCode 开启「手机远控」后给出的地址形如：

```
https://zcode.z.ai/remote/v4?sid=…&hash=…&t=…&mid=…&name=PC01&app_version=3.14.4
```

这个地址返回的**本身就是一个完整的网页客户端**（React 单页应用，`/remote/v4/<version>/assets/…`）。也就是说手机端「客户端」就是那个网页，官方 App 也是包着它。

因此本应用**不重新实现 ZCode 的远控协议**，只做外壳 + 地址管理：

- 正确加载网页客户端（WebView，开启 JS / DOM Storage / 第三方 Cookie）
- 管理会变化的连接地址：粘贴、分享导入、历史记录、一键切换
- 基本的浏览体验：进度条、系统返回键走网页历史、加载失败可重试

> 备选路线（未采用）：直接实现 ZCode 的 relay 协议（WebSocket `wss://zcode.z.ai/ws` + `device_register_init` / `auth_challenge` / `auth_response` 握手，再接一套不透明的 RPC 桥）。工作量大、随 ZCode 升级即失效，且等于重造官方 App，所以没有做。

## 功能

| 功能 | 说明 |
| --- | --- |
| 网页客户端 | 在 WebView 中加载连接地址，登录态/会话 cookie 会保留 |
| 粘贴连接 | 从剪贴板一键粘贴并校验 |
| 分享导入 | 从聊天/备忘录把地址「分享」到本应用即可连接 |
| 链接直达 | 点 `zcode.z.ai` 链接可直接用本应用打开 |
| 地址历史 | 最多 20 条，显示设备名、sid、生成时间；可切换与删除 |
| 地址校验 | 校验域名/路径/sid/hash，不合规会明确提示 |
| 文件下载 | 点页面里的下载链接会交给系统默认浏览器下载（http/https）；网页内存里生成的 blob/data 内容由应用直接落盘到「下载」目录 |
| 自动恢复 | 记住上次地址，重开即自动连接 |

## 环境要求

- JDK 17 或更高（本机用 JDK 21 构建通过）
- Android SDK（本机用 `G:\android-sdk`，含 platform 35 / build-tools 35.0.1）
- Gradle 8.7+（本机 8.9）；`minSdk 24`，`targetSdk 35`

## 构建

```bash
cd G:\zcode\tgg\android

gradle assembleDebug      # 调试包，输出 app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease    # 正式包，输出 app/build/outputs/apk/release/app-release.apk
```

正式包启用了 R8 代码压缩与资源收缩（`isMinifyEnabled` / `isShrinkResources`），体积约为未压缩时的一半以下。

也可以在 Android Studio 里直接打开 `G:\zcode\tgg\android` 目录构建。

`local.properties` 里的 `sdk.dir` 指向本机 SDK；换机器时改这一行，或删掉它、改用 `ANDROID_HOME` 环境变量。

## 安装

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

或把 APK 传到手机，在「设置 → 应用 → 安装未知应用」里允许后点击安装。

## 签名

`keystore.properties` + `zcodelink.jks` 是**本机生成的自用自签名密钥**（口令均为 `zcodelink`），仅用于 sideload 安装。若要上架或正式分发，请换成你自己的 keystore，并妥善保管——丢失后无法覆盖升级已安装的应用。

两个文件都已在 `.gitignore` 中忽略，不会进入版本库。

## 使用

1. 电脑端 ZCode 打开「手机远控」，复制连接地址
2. 打开本应用 → 右上角链接图标（或首页「设置连接地址」）
3. 粘贴地址 → **保存并连接**
4. 地址失效/变化时，重复第 3 步粘一条新的；旧的仍在「已保存的地址」里

## 安全说明

- 连接地址里的 `hash` 是连接凭据，本应用只把它发往 `zcode.z.ai`，不写入日志、不发给任何第三方。
- WebView 关闭了本地文件访问（`allowFileAccess=false`、`allowContentAccess=false`）。网页内容无法调用原生能力，只有一个例外：`ZcDownload` 这个 JS 接口，仅用于把 blob/data 下载的内容交回应用写文件；它会校验当前页面确实来自 `zcode.z.ai`、清洗文件名、并限制大小（16 MB）。
- http/https 的下载请求交给系统默认浏览器，本应用不接管；应用自身也声明了 `zcode.z.ai` 的打开方式，所以挑浏览器时会用 `http://` 地址探测并按包名定向，避免 intent 被解析回自己。
- blob/data 下载由应用直接写入系统「下载」目录（Android 10+ 走 MediaStore），不额外申请存储权限。
- 混合内容禁止（`MIXED_CONTENT_NEVER_ALLOW`），证书异常一律拒绝，不提供绕过入口。
- 仅声明 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限；`allowBackup=false`，应用数据（含地址历史）不参与云备份。

## 已验证

在 Android 14（API 34，x86_64）模拟器上实测：应用启动、加载真实连接地址、ZCode 网页客户端完整渲染并成功连上桌面端（能列出电脑上的工作区与任务列表），地址解析与设备名（PC01）显示正确，无崩溃。

下载同样实测通过：点下载链接后由系统默认浏览器（`com.android.browser`）接走并成功下载，随后进入系统安装器，本应用没有被回环拉起、无崩溃；blob 内容经 JS 接口落盘，文件名与内容均正确。

## 已知限制

- 必须联网；连接地址失效时网页客户端会提示「手机连接已失效」，此时回到电脑端重新生成地址再粘贴即可。
- blob/data 形式的下载需要先把内容读进内存，上限 16 MB（峰值内存约为文件大小的 3 倍，宁可拒绝也不能把进程撑爆）；超过会提示「文件太大」，应用不会崩。http(s) 下载没有这个限制，交给系统浏览器处理。
- 交给浏览器下载时用的是浏览器自己的会话，**不带 WebView 里的登录态**。ZCode 附件通常是带签名/时效的直接下载地址，实测可正常下载；但如果某个下载地址依赖 WebView 的 cookie，浏览器里可能会失败。
- 若将来 ZCode 的网页客户端开始拒绝 WebView 的 User-Agent，需要在 `MainActivity.setupWebView()` 里改写 `settings.userAgentString`（当前保持默认 UA）。
