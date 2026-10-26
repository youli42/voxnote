# voxnote 开发须知

「录音即笔记」：录音 → 转写 → 入库 → 阅读/搜索/打点。Android 原生 App + 自托管
服务端（转写后端可插拔）+ Web 控制台。详见 [README](README.md) 与 docs/。

## 构建（无 Gradle，PowerShell）

```powershell
pwsh -NoProfile -File tools/fetch_toolchain.ps1          # 首次拉工具链，可 -Proxy
pwsh -NoProfile -File android/build.ps1 -Toolchain <jdk> -Sdk <sdk>   # 构建，加 -Install 装机并启动
```

签名固定 `android/debug.keystore`，可覆盖安装。

## 代码约定

- 纯 Java、单 Activity（`android/src/dev/voxnote/`），无第三方依赖、无 Gradle；UI 全代码手写，公共件在 `Ui.java`。
- 厂商相关逻辑只放 `plugin/`（如 sogou），核心代码不得认识任何具体设备。
- 人写内容（标题/分类/标记）与机器派生内容（转写/摘要）分离落盘，前者永不被覆盖；数据契约见 docs/DATA-CONTRACT.md。
- 数据目录固定 `/sdcard/s1rec/`（旧名，勿改，改动需写迁移）。
- 目标机 Android 8.1 / API 27，无分区存储；代码需兼容 Android 12。

## 服务端与 Web 控制台（server/）

- FastAPI + SQLite 单进程；Web 控制台是单文件 `server/web/index.html`
  （原生 JS，无框架、无构建，改完刷新即生效）。页面切换用 hash 路由。
  Web 端允许第三方库：以源码形式 vendor 进 `server/web/vendor/`（不走 CDN，
  保持自托管离线可用）；Android 端仍维持无第三方依赖。
- 两层分离在控制台同样成立：逐句修订只写人工层（e_*），重转写只替换机器层；
  控制台的转写稿/波形/静音分析都是可再生的派生数据，不落契约。
- 记录库（lib）与录音流（stream）两页共用筛选状态语义（status/cat/q）；
  播放器能力（跳过静音、波形分析）抽在全局共享层（`WAVES`/`SKIP`），不要在
  单个页面里另起缓存。
- 端到端验证不碰真实数据：把 `server/data/` 复制成临时目录，
  `VOXNOTE_DATA=<临时目录> python run.py --port <空闲端口>` 起临时实例，
  浏览器走查完删掉临时目录。
- 同步/转录正在重设计（2026-10）：**结构以 docs/ARCHITECTURE.md（v1）为准**；
  现状问题见 docs/DIAGNOSIS.md，现流程见 docs/CURRENT-FLOWS.md，目标交互见 docs/USER-FLOWS.md，
  迁移与决策见 docs/REDESIGN.md。改这几块前先读，别在旧模型上继续加补丁。
- 加功能/改交互前先读 docs/DESIGN-PLAYBOOK.md（一条主线、一套状态、一处真相）；
  功能准入过不了就别做，别把设置项和入口继续堆上去。
- 开工前看 docs/GAP-ANALYSIS.md：P0 细节（修订重挂接 / 游标过期 / 保留策略 /
  安全边界 / 转写质量）没定之前，不要动同步与转写的实现。

## 提交

- 中文 commit message，前缀 `feat|fix|ui|docs|refactor|build|test(scope):`。
- 隐私红线：日志、数据库、抓包、录音一律不进仓库（.gitignore 已拦）。
- 内容要精简，便于回顾。如果有对应issues，要记录在前缀后
