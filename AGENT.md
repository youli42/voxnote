# voxnote 开发须知

Android「录音即笔记」应用：录音 → 转写 → 入库 → 阅读/搜索/打点。转写后端可插拔（尚未接入）。详见 [README](README.md) 与 docs/。

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

## 提交

- 中文 commit message，前缀 `feat|fix|ui|docs|refactor|build|test(scope):`。
- 隐私红线：日志、数据库、抓包、录音一律不进仓库（.gitignore 已拦）。
