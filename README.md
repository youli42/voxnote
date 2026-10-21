# s1rec

在 Android 设备本地完成 **录音 → 转写 → 结构化入库 → 阅读/编辑 → 同步/自动化** 的完整工具，
转录后端可插拔（自建云 API / 本地小模型 / 外部导入 / 人工），可移植到其他 Android 设备。

> 本项目由 `Sogou_S1_transcript`（只做日志提取的 Magisk 脚本模块）演进而来：
> 日志解析从"唯一功能"降级为"数据入口之一"，目标是最终替代设备自带的录音 App。

## 当前状态

| 能力 | 状态 |
| --- | --- |
| 厂商日志导入（会话 / 逐句 / 摘要 / 错误 / 音频挂接） | ✅ 可用（`android/src/.../reader`） |
| 卡片列表 + 逐句文稿呈现 | ✅ 可用 |
| 结构化数据落盘（`/sdcard/s1rec/`） | ✅ 可用 |
| 录音采集（`AudioRecord`） | ⬜ 未开始 |
| 可插拔转写引擎 | ⬜ 未开始 |
| 编辑层（`edits.jsonl`，机器派生不覆盖人工） | ⬜ 未开始 |
| 同步 / 自动化 | ⬜ 未开始 |

## 架构一句话

**机器派生的东西可以无限重放，人写的东西永不被覆盖，导出物随时可删可重建。**

四层：`原始归档` → `事实层（逐句）` → `意图层（编辑/标记）` → `视图层（md/jsonl/卡片）`，
外加一个**可删除的** SQLite 索引缓存。详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与
[docs/DATA-CONTRACT.md](docs/DATA-CONTRACT.md)。

## 目录

```
android/            安卓应用（当前为无 Gradle 的最小构建链）
  src/dev/s1rec/reader/   日志导入 + 呈现（MVP，已可运行）
docs/               需求、架构、数据契约
tools/              工具链获取脚本
```

## 构建与安装

首次需要拉工具链（JDK / build-tools / platform，约 300 MB，支持代理）：

```powershell
pwsh -NoProfile -File tools/fetch_toolchain.ps1 -Proxy http://127.0.0.1:10808
```

然后构建 / 安装：

```powershell
pwsh -NoProfile -File android/build.ps1            # 只出 APK
pwsh -NoProfile -File android/build.ps1 -Install   # 出包 + adb install + 拉起
```

构建链是 `javac → d8 → aapt2 → zipalign → apksigner`，不依赖 Gradle 与网络。
迁移到 Gradle/AGP 时，`android/src` 与 `AndroidManifest.xml` 可原样沿用。

## 设备事实（S1 AI Recorder，实测）

- Android 8.1 / API 27，无分区存储：普通 App 持存储权限即可读写 `/sdcard/**`，**不需要 root**；
- 720×1280 触摸屏，可做阅读与编辑；`heapgrowthlimit 192m`（native 推理不受此限）；
- 主体来源：`/sdcard/sogou/log/app/tmlog.tm*`（逐句正文，只增不删）、
  `/sdcard/sogou/log/statistics/tmlog.tm*`（按键/启停事件流）、`/sdcard/<年.月>/*.wav`（音频）。

## 隐私边界

设备日志、数据库、录音、抓包一律不进仓库（见 [.gitignore](.gitignore)）；
解析产物只写设备本地 `/sdcard/s1rec/`。
