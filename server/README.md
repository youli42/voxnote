# voxnote server

录音同步、转写、整理与 AI 对接的自托管服务端。单进程（FastAPI + SQLite），
控制台是内置的 Web 页面（`web/`），无外部数据库依赖。

## 运行

### Windows / 开发机（Python 3.12+）

```powershell
cd server
python -m venv venv
venv\Scripts\pip install -r requirements.txt
venv\Scripts\python run.py            # http://127.0.0.1:8787
```

可选依赖：`pip install faster-whisper` 启用本地转写（GPU 优先，自动回退 CPU；
不装则本地引擎显示"不可用"，可用云端引擎或先只做同步）。
`pip install sherpa-onnx numpy` 启用 **Qwen3-ASR 句级引擎**（silero VAD 分句，
逐句起止时间可作字幕；自带说话人分离——按句声纹聚类，只认出一人的场次
不标说话人；设置 `sherpa_speakers=0` 可关。模型约 1GB，首次转写自动从
hf-mirror 下载）。转写稿可在控制台导出 SRT 字幕。

### Debian / Docker

```bash
cd server
docker compose up -d            # CPU
docker compose --profile gpu up -d   # NVIDIA GPU（需 nvidia-container-toolkit）
```

数据落在 `./data`（容器内 `/data/voxnote`）：`voxnote.db` + `audio/<yyyy.MM>/` +
`transcripts/<id>/`（可再生的导出镜像；重转写结果有差异时旧机器结果
存档在 `transcripts/<id>/versions/`，供控制台详情页的「转写版本」对比）。

## 与 App / S1 配对

1. 控制台 → 设置 → 设备与访问，复制**配对令牌**；
2. App 端「设置 → 服务器」填入服务器地址与令牌；
3. 同步协议（`/api/v1/sync`）：
   - `POST /upload`：multipart 音频 + `meta`（JSON：stamp/title/cat/mode/sid/device/recorded_at/dur_ms；
     `device` 是上传设备名，控制台按它显示来源标签）
   - `GET /records?since=<ms>`：增量枚举（游标 = updated_at，返回含 device 字段）
   - `GET /records/{id}/audio`、`GET /records/{id}/transcript`：下载音频 / 生效转写稿（JSONL）
   - `POST /records/{id}/transcribe`：App 端触发（重）转写（engine 空 = 默认引擎），
     校验与控制台同一套；新稿转好后设备靠增量拉取回填
   - `GET /asr-config`、`POST /asr-config`：App 设置页读 / 改服务器转写配置
     （默认引擎与 Whisper 模型；其余配置仍只在控制台改）

多台设备可以同时连同一台服务器：各设备推自己的录音、按各自游标拉回填；
同秒撞车时服务端给记录 id 加 `-N` 后缀，App 端按上传响应里的真实 id 对回。
App 的「云端」页可浏览全库（含其他设备的记录）、阅读转写稿并按需取回文稿/音频。

## 数据契约（与 App 端一致）

- **机器层 / 人工层分离**：转写结果整层替换；逐句修订（文字、说话人）只写人工层，
  重转写永不覆盖。生效内容 = 人工层优先。
- 音频文件是真源；SQLite 是索引与队列，`transcripts/` 镜像可随时重建。
- 删除记录 = 删音频 + 删全部数据，不可恢复（控制台有确认）。

## 安全注意

默认监听 `0.0.0.0`（局域网设备可连，手机同步即用此通道）；设备同步 API 始终需要配对令牌。
建议设置环境变量 `VOXNOTE_API_KEY` 开启控制台全站密钥（控制台收到 401 会提示输入）；
只在本机使用可 `--host 127.0.0.1`。首次绑 0.0.0.0 时 Windows 防火墙会弹允许提示，记得放行
专用网络。

## API 文档

启动后访问 `http://127.0.0.1:8787/api/docs`（OpenAPI 自动生成）。
