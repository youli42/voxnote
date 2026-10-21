# voxnote

**任何一台 Android 手机上的"录音即笔记"**：按一下录下来，自动整理成能读、能搜、能打点的
日常记录——目标是长成一个 AI 随身笔记，管住你每天说过的话。

在 Android 设备本地完成 **录音 → 转写 → 结构化入库 → 阅读/编辑 → 同步/自动化** 的完整工具，
转录后端可插拔（自建云 API / 本地小模型 / 外部导入 / 人工），可移植到其他 Android 设备。

> 由 `Sogou_S1_transcript`（只做日志提取的 Magisk 脚本模块）演进而来：
> 日志解析从"唯一功能"降级为"数据入口之一"，目标是替代设备自带的录音 App。
>
> **命名**：产品名 `voxnote`（voice + note）。应用包名与代码命名空间已统一为
> `dev.voxnote`；**只保留数据契约里的旧名**——数据目录 `/sdcard/s1rec/`、设备侧偏好键
> `s1rec`、旧版前端的 `s1rec.v5` 设置键。这些改名会牵连已落盘的录音、分类与标记，要动得单独写迁移。

## 当前状态

| 能力 | 状态 |
| --- | --- |
| **原生 UI（v3.0）** | ✅ 列表 / 详情 / 搜索 / 筛选 / 左右滑归类 / 录音 / 设置全部 Java View，**不再有 WebView 界面** |
| 自录（`MediaRecorder` 16 kHz 单声道 AAC） | ✅ 可用 |
| 录音位置可选：公共目录 / 自定义目录（SAF）/ 应用私有目录 | ✅ 可用（无权限自动落私有） |
| 按 `年.月` 自动建日期文件夹 | ✅ 可用 |
| 卡片列表 + 搜索 + 分类筛选 + 左/右滑归类 | ✅ 可用 |
| 详情：逐句文稿、播放/拖动、打点、改名、分类 | ✅ 可用 |
| 用户态落盘（应用私有 `app-state.json`，机器派生不覆盖人工） | ✅ 可用 |
| 兼容 targetSdk 34（分区存储）+ 老系统（≤28 遗留路径） | ✅ 编译/装机通过；29+ 分支按规范实现，**未真机验证** |
| 旧版设置迁移（WebView `localStorage['s1rec.v5']` → 原生） | ✅ 一次性自动迁移，分类不丢 |
| 外部来源插件：厂商日志导入（会话 / 逐句 / 摘要 / 音频挂接） | ✅ 可用（设备定制，可整体拆除） |
| 可插拔转写引擎 | ⬜ 未开始（"转文字"当前如实提示未接入） |
| 编辑层（`edits.jsonl` 追加层） | ⬜ 部分：改名/分类/标记已落 app-state |
| 同步 / 自动化 | ⬜ 未开始 |

### 为什么从 H5 重写成原生，以及实测

旧版把原型 HTML 塞进 WebView（本机 WebView 永久停在 Chromium 59）。实测瓶颈不是"WebView 引擎慢"，
而是**每个卡片一个圆角裁剪层 + 全量 `innerHTML` 重绘 + 回前台在 JS 线程同步扫描**：

| 指标（同一台 S1） | 旧 H5 | 原生 v3.0 |
| --- | --- | --- |
| 列表滚动掉帧 / p50 / p90 | 92–96% / 32–36ms / 48–53ms | **3.2% / 6ms / 10ms** |
| 冷启动到首帧 | 469–541ms + 页面加载 1.59s | **346–393ms** |
| 回前台重扫 | 主线程冻结 ≈300ms | 后台线程，不冻结 |
| 进程 PSS | 80–118MB | **≈21MB** |
| 外部依赖 | 设备 WebView 版本、CSS/JS 补丁 | 无 |

测量方法见 [docs/APP.md §7 性能基线](docs/APP.md#7-性能基线)；数据来自 `dumpsys gfxinfo` / `am start -W` / `dumpsys meminfo`。

**分层原则**：核心（录音 / 存储 / 阅读 / 编辑）不认识任何具体设备；凡是为某台机器定制的
导入，都做成 `dev.voxnote.plugin.*` 下的插件，核心只依赖 `ContentSource` 接口。
把 `android/src/dev/voxnote/plugin/` 整个目录删掉，App 照常编译、照常运行，只是没有"外部来源"。

## 架构一句话

**机器派生的东西可以无限重放，人写的东西永不被覆盖，导出物随时可删可重建。**

四层：`原始归档` → `事实层（逐句）` → `意图层（编辑/标记）` → `视图层（md/jsonl/卡片）`，
外加一个**可删除的** SQLite 索引缓存。详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与
[docs/DATA-CONTRACT.md](docs/DATA-CONTRACT.md)。

## 目录

```
android/            安卓应用（无 Gradle 的最小构建链）
  assets/legacy_export.html   仅用于一次性迁移旧版 WebView 设置
  src/dev/voxnote/            核心：Storage / Library / MdParser / Player / Recorder / State / ContentSource
                              UI：HomeActivity / DetailSheet / SettingsScreen / CardRow / SwipeRow / Ui …
  src/dev/voxnote/plugin/sogou/
                              设备定制插件：厂商日志与月目录导入（可整个删掉）
docs/               需求、架构、数据契约、App 实现说明
tools/              工具链获取脚本
```

## 构建与安装

首次需要拉工具链（JDK / build-tools / platform，约 300 MB，支持代理）：

```powershell
pwsh -NoProfile -File tools/fetch_toolchain.ps1 -Proxy http://127.0.0.1:10808
```

然后构建 / 安装（已有工具链时用 `-Toolchain` 指 JDK、`-Sdk` 指 build-tools/platforms）：

```powershell
pwsh -NoProfile -File android/build.ps1 -Toolchain <jdk根> -Sdk <android-sdk根>
pwsh -NoProfile -File android/build.ps1 -Toolchain <jdk根> -Sdk <android-sdk根> -Install
```

构建链是 `javac → jar → d8 → aapt2 → zipalign → apksigner`，不依赖 Gradle 与网络；
类文件多时先打成 `classes.jar` 再给 d8，避免超过 Windows 命令行长度。
`-Install` 安装后启动 `.HomeActivity`。
签名用 `android/debug.keystore`（首次自动生成，位置在 build 目录之外，所以反复构建不会换签名、能覆盖安装）。
`-Sdk` 目录里有多个 platform 时自动挑 API 最高的 `android.jar`（当前 API 36），
`targetSdk` 由 `build.ps1` 里的 `$TargetSdk` 控制（现为 34），`minSdk` 为 27。

> 直接拷 `android/` 到别的机器时，`-Toolchain` 与 `-Sdk` 也可以指向 `tools/fetch_toolchain.ps1`
> 拉下来的同一套目录（那个脚本产出 `{jdk,build-tools,platforms}`，此时两个参数填同一个根即可）。

## 从 2.x（H5 版）升级

原生版第一次启动会把旧 WebView 的 `localStorage['s1rec.v5']`（分类、左右滑映射、按键、转写 API）
读出来写进 SharedPreferences，然后不再使用 WebView。分类是用户手写数据，迁移失败会回退默认值但不会卡启动。
`app-state.json`（改名/分类/标记）本来就在 Java 侧，不受影响。

## 设备事实（S1 AI Recorder，实测）

- Android 8.1 / API 27，无分区存储：普通 App 持存储权限即可读写 `/sdcard/**`，**不需要 root**；
- 720×1280 触摸屏，可做阅读与编辑；`heapgrowthlimit 192m`（native 推理不受此限）；
- 主体来源：`/sdcard/sogou/log/app/tmlog.tm*`（逐句正文，只增不删）、
  `/sdcard/sogou/log/statistics/tmlog.tm*`（按键/启停事件流）、`/sdcard/<年.月>/*.wav`（音频）。

## 隐私边界

设备日志、数据库、录音、抓包一律不进仓库（见 [.gitignore](.gitignore)）；
解析产物只写设备本地 `/sdcard/s1rec/`。
