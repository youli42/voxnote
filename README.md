# s1rec

在 Android 设备本地完成 **录音 → 转写 → 结构化入库 → 阅读/编辑 → 同步/自动化** 的完整工具，
转录后端可插拔（自建云 API / 本地小模型 / 外部导入 / 人工），可移植到其他 Android 设备。

> 本项目由 `Sogou_S1_transcript`（只做日志提取的 Magisk 脚本模块）演进而来：
> 日志解析从"唯一功能"降级为"数据入口之一"，目标是最终替代设备自带的录音 App。

## 当前状态

| 能力 | 状态 |
| --- | --- |
| 录音目录扫描（`/sdcard/<年.月>/*.wav` + 旁车 `*.md`，以及 `sogou/md/` 镜像） | ✅ 可用 |
| 卡片列表 + 搜索 + 分类筛选 + 左/右滑归类 | ✅ 可用 |
| 详情：逐句文稿、播放/拖动、打点、改名、分类 | ✅ 可用 |
| 用户态落盘（`/sdcard/s1rec/app-state.json`，机器派生不覆盖人工） | ✅ 可用 |
| 自录（`MediaRecorder` 16 kHz 单声道 AAC，落盘即出现在列表） | ✅ 可用 |
| 厂商日志导入（会话 / 逐句 / 摘要 / 错误 / 音频挂接） | ⬜ 内核保留（`reader/`），UI 待接（见分支 `feat/log-monitor`） |
| 可插拔转写引擎 | ⬜ 未开始（"转文字"当前如实提示未接入） |
| 编辑层（`edits.jsonl` 追加层） | ⬜ 部分：改名/分类/标记已落 app-state，尚未做追加式编辑流 |
| 同步 / 自动化 | ⬜ 未开始 |

界面是你给的原型（`deepseek_html_*.html`）的移植：**H5 负责观感与手势，Java 负责文件与媒体**，
两边通过 `window.S1N` 桥接，详见 [docs/APP.md](docs/APP.md)。


## 架构一句话

**机器派生的东西可以无限重放，人写的东西永不被覆盖，导出物随时可删可重建。**

四层：`原始归档` → `事实层（逐句）` → `意图层（编辑/标记）` → `视图层（md/jsonl/卡片）`，
外加一个**可删除的** SQLite 索引缓存。详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与
[docs/DATA-CONTRACT.md](docs/DATA-CONTRACT.md)。

## 目录

```
android/            安卓应用（当前为无 Gradle 的最小构建链）
  assets/index.html     原型移植后的界面（真实数据由 JS 桥驱动）
  src/dev/s1rec/        MainActivity（WebView 宿主）/ Bridge / Library / MdParser /
                        Player / Recorder / State
  src/dev/s1rec/reader/ 日志导入内核（LogParser / Store / Session），待接入 UI
docs/               需求、架构、数据契约、App 实现说明
tools/              工具链获取脚本
```

## 构建与安装

首次需要拉工具链（JDK / build-tools / platform，约 300 MB，支持代理）：

```powershell
pwsh -NoProfile -File tools/fetch_toolchain.ps1 -Proxy http://127.0.0.1:10808
```

然后构建 / 安装（已有工具链时可用 `$env:S1REC_TOOLCHAIN` 或 `-Toolchain` 指过去）：

```powershell
pwsh -NoProfile -File android/build.ps1            # 只出 APK
pwsh -NoProfile -File android/build.ps1 -Install   # 出包 + adb install + 拉起
```

构建链是 `javac → d8 → aapt2 → zipalign → apksigner`，不依赖 Gradle 与网络；
`aapt2 link -A assets` 负责把 H5 打进包里。签名用 `android/debug.keystore`（首次自动生成，
位置在 build 目录之外，所以反复构建不会换签名、能覆盖安装）。
迁移到 Gradle/AGP 时，`android/src`、`android/assets` 与 `AndroidManifest.xml` 可原样沿用。

## 设备事实（S1 AI Recorder，实测）

- Android 8.1 / API 27，无分区存储：普通 App 持存储权限即可读写 `/sdcard/**`，**不需要 root**；
- 720×1280 触摸屏，可做阅读与编辑；`heapgrowthlimit 192m`（native 推理不受此限）；
- 主体来源：`/sdcard/sogou/log/app/tmlog.tm*`（逐句正文，只增不删）、
  `/sdcard/sogou/log/statistics/tmlog.tm*`（按键/启停事件流）、`/sdcard/<年.月>/*.wav`（音频）。

## 隐私边界

设备日志、数据库、录音、抓包一律不进仓库（见 [.gitignore](.gitignore)）；
解析产物只写设备本地 `/sdcard/s1rec/`。
