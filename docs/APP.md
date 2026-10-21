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

主键是录音文件名里的 `yyyyMMdd_HHmmss`。同一场录音的不同载体都能对齐到它：

| 文件 | 作用 |
| --- | --- |
| `/sdcard/2026.10/20261021_014357_meeting.wav` | 音频（`.wav` 优先，其次 `.m4a` / `.mp3`；跳过 `_denoise` 副本） |
| `/sdcard/2026.10/20261021_014357_meeting.md` | 正文（`[00:02.86 → 00:07.07]` 逐句） |
| `/sdcard/sogou/md/2026.10/20261021_014357_1792518237.md` | 旧模块镜像（常只有头部，用作兜底） |
| `/sdcard/s1rec/records/<stamp>_memo.m4a` | 本 App 自录 |

扫描范围是"设置里的录音目录（递归 2 层）+ `/sdcard/<年.月>/` + `/sdcard/sogou/md/<年.月>/`"，
不遍历整块 43 GB 存储。同一 key 命中多份文稿时，谁的逐句多就用谁，缺的字段互相补。

状态按数据契约推导：`done`（有正文）/ `raw`（有音频无正文）/ `failed`（有声但报错）/
`empty`（无音频无正文，卡片显式标注"无正文"，不伪造空稿）。

列表**按录音时间排**（默认新的在前）；冻结区右侧的「时间↓ / 时间↑」切换方向，
选择随设置保存。筛选行是「全部 / 转写失败 / 未分类 / 各分类」，其中「转写失败」
直接过滤 `status=failed`——改成时间序之后失败场次不再被顶到最前，所以单独留了入口。

## 2. 桥接 API（`window.voxnote`）

| 方法 | 说明 |
| --- | --- |
| `refresh()` | 重扫，返回 `{items, stats}` JSON |
| `patch(id, json)` | 用户态更新：`{"title"}` / `{"cat"}` / `{"marks":[…]}` |
| `configure(json)` | JS 把设置推过来（原生只关心 `recordDir`） |
| `open(path) / play / pause / seek(ms) / position / duration / playing / close` | 播放 |
| `startRec(title) / stopRec() / recMs() / recLevel() / recActive()` | 录音 |
| `transcribe(id)` | "转文字"；未接后端时返回 `{"ok":false,"message":"…"}`，UI 如实提示 |
| `version() / toast(msg)` | 杂项 |

`position` 由 JS 每 100 ms 问一次（`nativeTick`），进度条、句高亮、打点都基于它，
所以播放精度不依赖 `<audio>` 元素。

## 3. 用户态落盘

`/sdcard/s1rec/app-state.json`：

```json
{ "20261021_021120": { "title": "…", "cat": "c1", "marks": [10.9] } }
```

重扫、重解析 md 都不会碰它——对应架构里"人写的东西永不被覆盖"那条不变量。
目前的粒度是"改名/分类/标记"，逐句编辑（`edits.jsonl` 追加层）还没做。

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

## 5. 转录监控（旧 Magisk 模块内核）

`android/src/dev/voxnote/reader/` 里的 `LogParser` + `Store` 是旧模块"常驻 shell 守护 + 定时轮询
日志"的内核，现在由 `LogMonitor` 在 App 里直接调用，不再需要那个守护进程：

- `Bridge.refresh()` 顺手跑一次（一分钟内复用上次结果，所以切回 App 不会反复重读 3 MB 日志）；
- 设置页「转录监控」可手动 `立即扫描并导入`（强制重扫 + 重写 `/sdcard/s1rec/sessions/`）；
- 结果并进同一张卡片列表：同一时间戳的场次补正文、补摘要、补会话号，
  只有日志没有音频的场次也会作为卡片出现，并带「日志」标签；
- 卡片上的「转写 / 重写转写」走的也是这里：立刻重扫日志，有内容就报"已导入 N 句"，
  没有就如实说"日志里没有这场的内容"。

`startMs <= 0` 的会话（只有 summary 回写、没有引擎创建锚点）不参与合并——
它们既对不上录音文件，也会在 `sessions/` 下产出 1970 目录。

## 6. 已知限制 / 下一步

- **没有转写后端**：卡片上的「转文字」只会触发日志重扫并如实报告结果；
  接自建 API / 本地模型时，改的就是 `Bridge.transcribe`。
- **录音无暂停/波形存档**：只有开始/停止，波形是实时电平。
- **列表是全量扫描**：几十场没问题，上到几千场需要加索引（`DATA-CONTRACT` 里预留过）。
- **冻结搜索区是 JS 兜底**：本机 Chromium 59 的 `position:sticky` 不可靠，
  用滚动时改绝对定位实现，效果等价但不是原生 sticky。
