# App 实现说明（v3.0，原生 UI）

v2.x 的界面是单文件 H5 塞在 WebView 里。v3.0 把它逐屏重写成 Java View：
**核心数据层不动，UI 层换栈**。旧 H5 留在 git 历史里（例如
`git show 3e5a444:android/assets/index.html`），`android/assets/` 现在只剩一个一次性迁移页。

重写的原因与实测数据见 [README](../README.md#为什么从-h5-重写成原生以及实测)。
一句话：这台设备的 WebView 永久停在 Chromium 59，旧 H5 的圆角裁剪层 + 全量重绘 + 同步扫描
把列表压到 ~30fps；原生 View 在同样数据、同样视觉下是 ~60fps，启动与内存也回到正常水平。

## 1. 界面结构

```
HomeActivity                 录音页 + 设置页，底部两个 tab
 ├ ListView                  卡片列表（自带 view 回收）
 │   ├ header                录音面板（标题输入 / 计时 / 波形 / 录音键）
 │   └ SwipeRow             一行 = 左/右滑容器 + CardRow
 │        └ CardRow         标题 / 待处理原因 / 摘要 / 待办 / 关键词 / 元信息
 ├ DetailSheet              底部抽屉：播放 / 进度打点 / 逐句跳转 / 改名 / 分类 / 重转
 ├ Picker                  通用底部选择器（分类、按键、模型语言、存储目标……）
 └ SettingsScreen           分类 / 按键 / 转写 API / 存储 / 开关 / 外部来源 / 关于
```

自绘控件（`Ui.java` 提供颜色、dp/sp、圆角背景、Ripple、常用控件）：

| 控件 | 用途 |
| --- | --- |
| `RecordButton` | 录音键：内块在圆↔圆角方块之间过渡 |
| `WaveView` | 38 根电平条，`Recorder.level()` 喂数据 |
| `SeekBarView` | 详情进度条：轨道 + 进度 + 打点 + 拖拽 |
| `ToggleView` | 设置页胶囊开关 |
| `TabItem` | 底部导航（线性图标用 Canvas 画） |

搜索/筛选条固定在页面顶部（旧版是滚到顶后 JS 钉住）；功能相同，实现更稳，也少一个每帧读布局的
滚动监听。**不引入 AndroidX / Compose**：`ListView` + 手写 View 足够，构建链保持无 Gradle。

## 2. 一条记录怎么来的

主键是录音文件名里的 `yyyyMMdd_HHmmss`。同一场录音的不同载体都能对齐到它。

**自己的录音**放在当前存储目标里（见 §3），一律按 `年.月` 建日期子目录：

| 位置 | 典型路径 |
| --- | --- |
| 公共目录（≤28） | `/sdcard/Music/voxnote/2026.10/<stamp>_memo.m4a` |
| 公共目录（29+） | MediaStore 音频，`RELATIVE_PATH = Music/voxnote/2026.10` |
| 自定义目录（SAF） | 用户选的文件夹下 `/2026.10/<stamp>_memo.m4a` |
| 应用私有 | `/sdcard/Android/data/dev.voxnote/files/records/2026.10/…` |

**外部来源**（插件）另外给场次：厂商的 `*.wav`、旁车 `*.md`、以及日志里解析出的逐句。
同一 key 命中多份时，谁的逐句多就用谁，缺的字段互相补；`.wav` 优先于 `.mp3`，`_denoise` 副本跳过。

状态按数据契约推导：`done`（有正文）/ `raw`（有音频无正文）/ `failed`（有声但报错）/
`empty`（无音频无正文）。列表按录音时间排（可切换方向），筛选行是
「全部 / 转写失败 / 未分类 / 各分类」。

## 3. 存储策略（`Storage`）

三种目标，按"能不能用"自动降级，**用户在设置里选**：

| 模式 | 写入方式 | 需要权限 |
| --- | --- | --- |
| `public` 公共目录 | ≤28 直接写 `/sdcard/<子目录>/年.月`；29+ 写 `MediaStore.Audio`（先 `IS_PENDING=1`，写完清掉） | ≤28 需要 WRITE；29+ 不需要 |
| `saf` 自定义目录 | 系统文件选择器（`ACTION_OPEN_DOCUMENT_TREE`）拿持久授权，`DocumentsContract` 建目录/写文件 | 不需要 |
| `private` 应用私有 | `getExternalFilesDir()/records/年.月` | 不需要 |

默认 `public`，**没有存储权限、没选目录、写入失败时自动落到 `private`**。
录音流程固定为：先录到私有临时文件 → `Storage.publish()` 发布到当前目标 → 重扫。

## 4. 数据流与线程模型

```
Library/Storage/Plugin(IO) ──► DataCenter.io（单线程） ──► RecordVm 列表 ──► Handler ──► UI
        ▲                                                         │
        └──────────── State(app-state.json) ◄── patch(改名/分类/标记)
```

- `DataCenter.load()` 在**后台单线程**里扫描文件、跑插件、读 `State`，合成 `RecordVm`，
  再回主线程一次性替换列表。回前台重扫不再冻结输入/滚动。
- 用户态写入（`patch`）也走同一后台线程；改名在详情里有 350ms 防抖。
- `RecordVm` 把"事实"（`Item`）与"用户态"（`State`）合在一起，并派生摘要/待办/关键词——
  旧版是在 JS 里做的 `deriveAll`，现在只在卡片需要时计算一次。
- `ListView` 只回收可见行；`CardRow.bind()` 只改文字和可见性，不新建控件。

## 5. 设置与迁移（`UiSettings` / `LegacySettingsImporter`）

原生设置的键与旧版 `localStorage['s1rec.v5']` 一一对应：分类、左右滑目标、开始录音键、
标记键、转写 API、排序、显示摘要、录完自动转写。存 `SharedPreferences('voxnote.ui')`。

**升级迁移**：第一次启动若 `legacyImported=false`，用 1×1 的隐藏 WebView 加载
`assets/legacy_export.html`（与旧页面同源 `file://`，读得到同一份 localStorage），
把 JSON 交给 `UiSettings.importLegacy()`，随后立即销毁 WebView 并落标记。
分类是用户手写数据，迁移失败会回退默认值，但不会阻塞启动。
`app-state.json`（改名/分类/标记）一直在 Java 侧，不受影响。

删除分类时 `State.clearCat()` 会把挂在该分类下的记录清成未分类（一次落盘），
避免留下悬空 cat id；旧 H5 只改内存，重启后会对不上。

## 6. 按键

`HomeActivity.onKeyDown` 统一处理：

- 捕获模式（设置页点「自定义按键」）：任意键绑定为开始录音键，返回键取消；
- 详情打开且正在播放：标记键（空格/回车/音量上/下）→ 打点；
- 否则若匹配开始录音键（音量上/下或自定义）→ 开始/停止录音，并自动切回录音页；
- 输入框聚焦时不触发录音快捷键。

## 7. 性能基线

同一台 S1 AI Recorder（Android 8.1，SC9832E，2GB）：

| 指标 | 旧 H5 | 原生 v3.0 |
| --- | --- | --- |
| 列表滚动掉帧 / p50 / p90 | 92–96% / 32–36ms / 48–53ms | **3.2% / 6ms / 10ms** |
| 冷启动到首帧（`am start -W`） | 469–541ms + 页面 1.59s | **346–393ms** |
| 回前台重扫 | 主线程冻结 ≈300ms | 后台线程 |
| PSS | 80–118MB | **≈21MB** |
| APK | 113KB | **84KB** |

复现命令：

```powershell
adb shell dumpsys gfxinfo dev.voxnote reset
# 在列表上滑动几次后：
adb shell dumpsys gfxinfo dev.voxnote | Select-String 'Total frames|Janky|percentile'
adb shell am force-stop dev.voxnote; adb shell am start -W -n dev.voxnote/.HomeActivity
adb shell dumpsys meminfo dev.voxnote | Select-String 'TOTAL:'
```

## 8. 兼容层

- `minSdk 27`、`targetSdk 34`；API 29+ 的 MediaStore 写入分支按规范实现，**未在 29+ 真机验证**
  （这台设备是 API 27，无分区存储）。
- SAF 分支：系统选择器能正常打开、授权持久化按标准实现；把录音发布到 SAF 目录这条路径
  与旧版代码相同，仍建议在真机上挑一次目录验证。
- 屏幕 720×1280 / density 420：所有尺寸按 dp/sp，不依赖 WebView 的 CSS 像素与 text autosizing。
- 没有 WebView 界面后，不再需要 `inset` / `gap` / `pointer` / `position:sticky` 这些
  Chromium 59 补丁；旧版补丁表随 H5 一起移除了。

## 9. 外部来源插件（设备定制，可拆）

核心不认识任何具体设备。凡是为某台机器定制的导入，都实现在
`android/src/dev/voxnote/plugin/<名字>/` 里，只依赖一个接口：

```java
interface ContentSource {
    String id(); String label(); boolean available();
    List<Item> scan(boolean force);   // 交付物是 Item
    JSONObject info();
}
```

`Plugins` 用**类名字符串 + 反射**创建（不是编译期引用），所以：

- 删掉 `android/src/dev/voxnote/plugin/` 整个目录 → 核心照常编译、App 照常启动，
  设置页不再出现「外部来源」；
- 插件内部再糙也伤不到核心：`DataCenter` 对每个插件 `try/catch`，一个坏了只是少了它的数据，
  `available()=false` 时 UI 直接隐藏。

现有插件 `plugin/sogou`（搜狗 S1）：把厂商日志（`LogParser`）、厂商月目录的
`*.wav`/`*.md`、旧模块镜像合并成 Item。设置页的「扫描并导入」会强扫并写回镜像。

## 10. 已知限制 / 下一步

- **没有转写后端**：卡片上的「转文字」/详情里的「重新转写」只会触发外部来源重扫并如实报告结果；
  接自建 API / 本地模型时，改的就是 `DataCenter`/`DetailSheet` 的调用点。
- **用户态在私有目录**：卸载会丢分类/标记，需要"导出/导入"（未做）。
- **录音无暂停/波形存档**：只有开始/停止，波形是实时电平；最长 1 小时自动停。
- **列表仍是全量扫描**：几十场没问题，上到几千场需要加 SQLite 索引与分页
  （`DATA-CONTRACT` 里预留过）。
- **29+ 存储与 SAF 写入未真机验证**（见 §8）。
- **迁移页依赖系统 WebView**：只跑一次；若设备 WebView 被禁用，分类会回退默认值。
