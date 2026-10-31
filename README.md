# 抖音直播录制（DouyinLiveRec）

手机端抖音直播录制工具。粘贴直播间链接或房间号即可录制，支持开播自动监控、按时长/体积自动切片、录完无损重封装为 MP4 并存入系统相册。后台与锁屏持续录制（前台服务 + 常驻通知）。

> **合规提示**：本工具仅供个人录制自己观看的公开直播、留存资料使用。录制他人直播内容涉及著作权与平台服务条款，请勿用于二次传播、商业用途或任何侵权场景。使用者自行承担全部责任。

---

## 一、它怎么工作的

| 层 | 做什么 | 关键实现 |
|---|---|---|
| 解析层 | 从链接/房间号拿到真实流地址 | 内嵌无头 WebView 打开 `live.douyin.com/<房间号>`，通过 `shouldInterceptRequest` **加上** `fetch`/`XMLHttpRequest` 钩子与 `performance.getEntriesByType('resource')` 轮询三条通道拦截 `.flv`/`.m3u8` 请求 |
| 录制层 | 把流稳定下到本地 | OkHttp 流式读取 → 写字节；断流自动重连（指数退避，1s→15s，上限 10 次）；按时长或文件大小切片；HLS 自动识别 master playlist 并拆分片 |
| 收尾层 | 转成能播的文件 | 系统自带 `MediaExtractor` + `MediaMuxer` **无损重封装**（不转码、不降画质）→ MP4；再经 `MediaStore` 写入 `Movies/DouyinLiveRec` 并扫入相册 |
| 保活层 | 后台/锁屏不断 | 前台服务（`foregroundServiceType="dataSync"`）+ 常驻通知（带停止按钮）；监控模式同样拉起前台服务 |

**为什么用 WebView 拦截而不是直接调接口**：抖音直播间接口需要 `ttwid`/`msToken`/`a_bogus` 等签名参数，纯接口方案一改算法就失效。WebView 里跑的是抖音官方网页播放器，签名由它自己算，我们只借它的结果。

---

## 二、功能

- 粘贴直播间链接 / `v.douyin.com` 短链 / 整段分享文案 / 纯数字房间号，均可用
- 立即录制；或「开播自动监控」——每 15 秒轮询，主播开播自动开录、下播自动收尾
- 后台与锁屏持续录制，通知栏可一键停止
- 自动切片：按录制时长（分钟）或单文件体积（MB）
- 录完自动无损转 MP4，存入相册 `Movies/DouyinLiveRec`，同时保留 App 私有目录副本
- 录制历史（标题 / 房间号 / 时间 / 大小 / 成功标记），可直接打开或删除

---

## 三、怎么编译出 APK

本工程**不包含** `gradle-wrapper.jar`（二进制文件未入库），三条路任选：

### 方式 A：Android Studio（最简单）
1. Android Studio（Hedgehog 2023.1.1 或更新）→ `File → Open` → 选中本目录
2. 首次打开会提示生成 Gradle wrapper，点同意；JDK 用内置的 17
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`，传到手机安装（需允许「安装未知来源应用」）

### 方式 B：GitHub Actions（无需本地环境）
推送到 GitHub 后，仓库自带的 `.github/workflows/build.yml` 会：`setup-java 17` → 用 runner 自带 gradle 生成 wrapper → `./gradlew assembleDebug` → 把 `app-debug.apk` 作为 artifact 上传。在 Actions 页面下载即可。

### 方式 C：命令行
需要 JDK 17 与 Gradle 8.7：
```bash
cd DouyinLiveRec
gradle wrapper --gradle-version 8.7   # 生成 gradlew 与 wrapper jar
./gradlew assembleDebug               # Windows: gradlew.bat assembleDebug
```

### 环境要求
- JDK 17、AGP 8.5.0、Gradle 8.7、Kotlin 1.9.24、Compose Compiler 1.5.14
- `compileSdk 34` / `targetSdk 34` / `minSdk 26`（Android 8.0 及以上）
- 首次构建需联网下载依赖（约 300–500 MB）

---

## 四、怎么用

1. 抖音 App 里点直播间「分享 → 复制链接」，或直接记下房间号
2. 打开本 App，把内容粘贴进输入框（`7- #在抖音... https://v.douyin.com/xxxx/ 复制此链接` 这种整段都能识别）
3. 选是否「开播自动监控」、是否「自动存相册」，需要切片就填时长或体积（留空/0 = 不分片）
4. 点「立即录制」或「开始监控」
5. 录制中可在通知栏看到已录体积并停止；录完到「历史」页查看，文件同时在相册 `Movies/DouyinLiveRec`

**存储位置**
- 录制中间文件与成品：`Android/data/com.dylr.recorder/files/rec/`
- 相册副本：`Movies/DouyinLiveRec/`（Android 10+ 免权限；Android 8.0–9.0 首次启动会申请存储权限）

---

## 五、已知限制与风险（务必读）

1. **抖音改版即失效**：流地址的获取依赖抖音网页播放器的行为。若抖音改为纯 MSE（`blob:`）且屏蔽了 `fetch` 钩子，三条通道都可能拦不到，表现为「解析超时」。这不是 bug 而是对抗性依赖，需要跟进更新。
2. **可能需要人机验证**：部分网络环境打开直播间会要求登录或过验证码，此时解析会失败。本工具不内置登录。
3. **重封装偶发失败**：直播流被强制截断时 `MediaMuxer` 可能失败，此时**保留原始 `.flv`/`.ts` 文件**（不丢数据），只是相册里不可直接播放。
4. **单任务**：同一时刻只录一个直播间；监控可挂多个，但同时只会录一个。
5. **不做转码**：画质与源流一致，不提供清晰度切换/压缩。
6. **Android 14+ 前台服务限制**：从后台启动前台服务受限，本工具仅在用户点击操作时拉起，符合系统要求。

---

## 六、代码结构

```
app/src/main/java/com/dylr/recorder/
├── App.kt                      Application，初始化 Locator
├── core/
│   ├── Contract.kt             冻结接口契约（类型 + 两个接口 + Locator）
│   ├── resolve/                解析层
│   │   ├── RoomIdParser.kt         链接/短链/房间号 → 房间号
│   │   ├── WebViewSession.kt       无头 WebView + 三条抓流通道
│   │   ├── LiveUrlInterceptor.kt   shouldInterceptRequest 观察者
│   │   └── DefaultLiveResolver.kt  resolve() / probe()
│   └── record/                 录制层
│       ├── StreamDownloader.kt     OkHttp 流式下载 + 重连 + HLS 拆分片
│       ├── SegmentWriter.kt        单切片文件写入
│       ├── Mp4Remuxer.kt           无损重封装 MP4
│       ├── MediaStoreSaver.kt      写入相册
│       ├── HistoryStore.kt         历史持久化
│       ├── RecordService.kt        前台服务 + 常驻通知
│       └── DefaultRecorder.kt      状态机 + 监控 + 收尾
└── ui/                         Compose 界面
    ├── MainActivity.kt / MainScreen.kt / MainViewModel.kt
    ├── theme/Theme.kt
    └── screens/{HomeScreen,HistoryScreen,SettingsScreen}.kt
```

四层之间只通过 `core/Contract.kt` 通信，替换任意一层不影响其它层。
