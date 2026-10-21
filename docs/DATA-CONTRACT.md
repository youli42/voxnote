# 数据契约 v1

原则：**文件是真相，数据库只是索引**。整个目录必须能直接拷走、重建、被脚本消费，
所以格式只用 UTF-8 文本（JSON / JSONL / Markdown），不依赖 SQLite。

## 1. 目录

```
/sdcard/s1rec/
  index.jsonl                                    每场一行摘要
  sessions/<yyyyMMdd_HHmmss>_<sid>/
    record.json                                  会话元数据
    segments.jsonl                               逐句事实（机器派生，可重放）
    edits.jsonl                                  编辑与标记（append-only，永不覆盖）★
    transcript.md                                导出物（可删可重建）
  engine/<sid>/<engine_id>.<rev>.jsonl           引擎原始回包（重跑与审计）
  archive/<yyyyMMdd>/tmlog.tm.*                 原始输入快照（防清数据/刷机）
```

`<sid>` = 10 位秒级会话号；目录名前缀 = 本地时间 `yyyyMMdd_HHmmss`，仅供人读。

## 2. 标识符

| 对象 | 规则 | 性质 |
| --- | --- | --- |
| `sid` | 10 位秒级 epoch（毫秒截断）；`sid<=0` 视为脏数据丢弃 | 稳定 |
| `segId` | `sha1(sid "|" startMs "|" endMs "|" norm(text))` 取前 16 位 | 可重算 |
| `markId` | `sid "-" startMs "-" type` | 可重算 |
| `editRev` | 每台设备单调递增整数 + 设备标识 | 排序用 |

`norm(text)`：去首尾空白、内部连续空白折叠为单个空格、不做其他改写。
重放同一日志必须得到同一批 `segId`——这是编辑能稳定挂接、同步能去重的前提。

## 3. 对象

### 3.1 `index.jsonl`（每场一行）

```json
{"sid":1792518237,"start_ms":1792518237733,"start_time":"10-21 01:43",
 "duration_ms":105880,"mode":"会议","status":"ok","final_count":43,
 "audio":"/storage/emulated/0/2026.10/20261021_014357_meeting.wav",
 "dir":"sessions/20261021_014357_1792518237"}
```

### 3.2 `record.json`

```json
{"schema":1,"sid":1792518237,"start_ms":0,"end_ms":0,"duration_ms":0,
 "start_time":"…","mode":"会议","record_mode":"general","model":"iot","lang":"zh-cmn-Hans-CN",
 "speaker_count":1,"speaker_detect":true,
 "status":"ok","final_count":43,"partial_count":34,"error_count":0,
 "audio":"…","summary":"…","engine":{"id":"importer.sogou-log","version":"1"},
 "generated_at":0}
```

`engine` 字段用于"换后端重跑"：同一声音可产出多份事实，以 `engine.id + rev` 区分。

### 3.3 `segments.jsonl`

```json
{"k":"final","t":890,"e":1100,"text":"…","segId":"a1b2c3d4e5f6a7b8"}
{"k":"break","t":2000,"speaker":1}
```

`k`：`final` | `partial`（partial 默认不落盘，`KEEP_PARTIAL` 打开时才写） | `break`（说话人切换）。

### 3.4 `edits.jsonl`（下一步实现）

```json
{"editRev":12,"device":"s1-5404651270000911","ts":1792600000000,
 "op":"set","target":"a1b2c3d4e5f6a7b8","text":"改后的句子"}
{"editRev":13,"device":"s1-…","ts":1792600001000,"op":"star","target":"sid:1792518237"}
```

`op`：`set`（改文本）| `split` | `merge` | `delete` | `star` | `note`。
合成规则：`segments` 先应用全部 `edits`（按 `editRev` 升序）得到"当前视图"；
永不回写 `segments.jsonl`，因此重放日志不会丢编辑。

### 3.5 `marks.jsonl`

```json
{"markId":"1792519519-936-manual","sid":1792519519,"t":936,"type":"manual","text":""}
```

## 4. 状态枚举

| status | 含义 | 判定 |
| --- | --- | --- |
| `ok` | 有正文 | `final_count > 0` |
| `summary` | 仅有摘要 | 无正文但有 `summary` |
| `error` | 有声但识别失败 | 无正文、有错误记录 |
| `audio_only` | 有录音无文本 | 有音频、无正文无摘要无错误 |
| `empty` | 无任何内容 | 以上都不成立（通常不展示） |

**不允许"看起来正常的空稿"**：任何空白都必须落到上表某一项，并在 UI 显式呈现。

## 5. 幂等与重放

- 全量重扫输入 = 幂等：相同输入重复解析，`segId` 与内容一致；
- 增量导入必须按**文件身份**（inode）而非文件名记录游标：厂商日志会轮转改名，
  按名字记偏移会导致整文件重放；
- 上报行（含 `%22` / `json:`）先丢弃：它们是统计上报，内含锚点字样，会造成重复计数。

## 6. 兼容

- `schema` 版本在 `record.json` 中；升级时写迁移脚本，保留旧文件 30 天；
- 新增字段只加不删；删除字段先标记 `deprecated` 一个版本；
- 时间一律毫秒整数（与录音内时间轴对齐），展示层再格式化。
