# App 实现说明（v1.4）

界面原型是一份单文件 HTML；装机版本把它原样搬进 WebView，再加一层原生数据桥。
这样做的理由很实际：原型里那套卡片 / 抽屉 / 滑动 / 波形的手感已经调好了，
重写成 Java View 只会得到一份更难维护的仿制品；而"读 /sdcard、播音频、开麦录音"
本来就该由原生做。

```
android/assets/index.html   ← 原型（改了数据来源与手势兜底）
        │  window.voxnote（@JavascriptInterface）
android/src/dev/voxnote/
        Bridge      桥：refresh / patch / configure / 播放 / 录音 / transcribe
        Library     扫描录音目录 → 卡片
        MdParser    md → 标题/摘要/逐句
        Item        一条记录 + 序列化成 JS 要的字段
        Player      MediaPlayer 壳
        Recorder    MediaRecorder → 16 kHz 单声道 AAC
        State       app-state.json（改名/分类/标记）
        MainActivity  WebView 宿主、权限、返回键
```

## 1. 一条记录怎么来的

主键是录音文件名里的 `yyyyMMdd_HHmmss`。同一场录音的不同载体都能对齐到它。

**自己的录音**放在当前存储目标里（见 §2），一律按 `年.月` 建日期子目录：

| 位置 | 典型路径 |
| --- | --- |
| 公共目录（≤28） | `/sdcard/Music/voxnote/2026.10/<stamp>_memo.m4a` |
| 公共目录（29+） | MediaStore 音频，`RELATIVE_PATH = Music/voxnote/2026.10` |
| 自定义目录（SAF） | 用户选的文件夹下 `/2026.10/<stamp>_memo.m4a` |
| 应用私有 | `/sdcard/Android/data/dev.voxnote/files/records/2026.10/…` |

**外部来源**（插件）另外给场次：厂商的 `*.wav`、旁车 `*.md`、以及日志里解析出的逐句。
同一 key 命中多份时，谁的逐句多就用谁，缺的字段互相补；`.wav` 优先于 `.mp3`，`_denoise` 副本跳过。

状态按数据契约推导：`done`（有正文）/ `raw`（有音频无正文）/ `failed`（有声但报错）/
`empty`（无音频无正文，卡片显式标注"无正文"，不伪造空稿）。

列表**按录音时间排**（默认新的在前）；冻结区右侧的「时间↓ / 时间↑」切换方向，
选择随设置保存。筛选行是「全部 / 转写失败 / 未分类 / 各分类」，其中「转写失败」
直接过滤 `status=failed`——改成时间序之后失败场次不再被顶到最前，所以单独留了入口。

## 2. 存储策略（`Storage`）

三种目标，按"能不能用"自动降级，**用户在设置里选**：

| 模式 | 写入方式 | 需要权限 |
| --- | --- | --- |
| `public` 公共目录 | ≤28 直接写 `/sdcard/<子目录>/年.月`；29+ 写 `MediaStore.Audio`（先 `IS_PENDING=1`，写完清掉） | ≤28 需要 WRITE；29+ 不需要 |
| `saf` 自定义目录 | 系统文件选择器（`ACTION_OPEN_DOCUMENT_TREE`）拿持久授权，`DocumentsContract` 建目录/写文件 | 不需要 |
| `private` 应用私有 | `getExternalFilesDir()/records/年.月` | 不需要 |

默认 `public`，但**没有存储权限、没选目录、写入失败时自动落到 `private`**，
设置页会显示"实际写入"到底是哪儿，以及"没有权限"的提示和授予按钮。

录音流程固定为：先录到私有临时文件 → `Storage.publish()` 发布到当前目标 → 重扫。
写入只有这一条路径，切换目标不会影响已录内容（旧位置仍会被扫描到）。

## 3. 桥接 API（`window.voxnote`）

| 方法 | 说明 |
| --- | --- |
| `refresh()` | 重扫，返回 `{items, stats, sources, storage}` |
| `patch(id, json)` | 用户态更新：`{"title"}` / `{"cat"}` / `{"marks":[…]}` |
| `setStorage(mode, sub)` / `storageInfoJson()` | 切存储目标 / 读当前策略 |
| `pickSafDir()` / `clearSafTree()` / `requestStoragePerms()` | 系统文件夹选择器 / 清除授权 / 申请权限 |
| `scanSources(id)` | 强制扫某个外部来源（设置页的"扫描并导入"） |
| `open(path, uri) / play / pause / seek(ms) / position / duration / playing / close` | 播放，路径与 `content://` 二选一 |
| `startRec(title) / stopRec() / recMs() / recLevel() / recActive()` | 录音；`stopRec` 返回 `path:…`/`uri:…` |
| `transcribe(id)` | "转文字"；当前=重跑外部来源，没有就如实提示 |
| `version() / toast(msg)` | 杂项 |

`position` 由 JS 每 100 ms 问一次（`nativeTick`），进度条、句高亮、打点都基于它，
所以播放精度不依赖 `<audio>` 元素。

## 4. 用户态落盘

应用私有目录 `filesDir/app-state.json`（不放公共目录）：

```json
{ "20261021_021120": { "title": "…", "cat": "c1", "marks": [10.9] } }
```

重扫、重解析都不会碰它——对应"人写的东西永不被覆盖"那条不变量。
老版本放在 `/sdcard/s1rec/app-state.json`，首次启动读不到私有文件时会去老路径捞一次并迁过来。
粒度目前是"改名/分类/标记"，逐句编辑（`edits.jsonl`）还没做。
注意：私有目录在**卸载时会被清空**，所以正式版还需要"导出/导入"（未做）。

## 4. 兼容层：本机 WebView 是 Chromium 59

原型按新浏览器写的，直接跑会错位。已在 CSS/JS 里补了等价写法（不改变桌面浏览器的观感）：

| 原型用法 | Chromium 59 的问题 | 处理 |
| --- | --- | --- |
| `inset:0` | 不支持 → `.view` 失去四边定位，内容被裁到屏幕外 | 显式写 `top/right/bottom/left` |
| `gap`（flex） | 不支持 → 标签、元信息挤在一起 | 用 `margin` 兜底 |
| `env(safe-area-inset-*)` | 不支持 → `calc()` 里的 `var(--sb)` 整条失效 | `:root{--sb:0px;--st:0px}` |
| `Element.scrollTo` | 不存在（61+） | 改 `scrollTop = 0` |
| `pointerdown/move/up` | 触摸屏上收不到 | 有 `ontouchstart` 时走 touch 事件，否则走鼠标 |
| `placeholder:center`、`place-items` | 部分不支持 | `.rec-btn` 用 flex 居中 |

## 5. 外部来源插件（设备定制，可拆）

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
- 插件内部再糙也伤不到核心：`Bridge` 对每个插件 `try/catch`，一个坏了只是少了它的数据，
  `available()=false` 时 UI 直接隐藏。

现有插件 `plugin/sogou`（搜狗 S1）：把厂商日志（`LogParser`）、厂商月目录的
`*.wav`/`*.md`、旧模块镜像合并成 Item。它只对"装了那个厂商 App 且目录可读"的设备有意义，
在分区存储的新系统上 `available()` 自然为 false——**这是预期行为，不是 bug**。

它不是核心能力，也不打算适配新系统：新设备要导入，应该是**另一个插件**
（用户用 SAF 授权一个文件夹），而不是改这个。

## 6. 已知限制 / 下一步

- **29+ 的存储分支未真机验证**：MediaStore 写入/查询、SAF 写入按规范实现并在本机
  （API 27）验证了编译与旧路径，但手头没有 Android 10+ 设备，属于"待验证"。
- **没有转写后端**：卡片上的「转文字」只会触发外部来源重扫并如实报告结果；
  接自建 API / 本地模型时，改的就是 `Bridge.transcribe`。
- **用户态在私有目录**：卸载会丢分类/标记，需要"导出/导入"（未做）。
- **录音无暂停/波形存档**：只有开始/停止，波形是实时电平。
- **列表是全量扫描**：几十场没问题，上到几千场需要加索引（`DATA-CONTRACT` 里预留过）。
- **冻结搜索区是 JS 兜底**：本机 Chromium 59 的 `position:sticky` 不可靠，
  用滚动时改绝对定位实现，效果等价但不是原生 sticky。
