FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY run.py .
COPY voxnote_server ./voxnote_server
COPY web ./web

ENV VOXNOTE_DATA=/data/voxnote
# 容器对外暴露（--host 0.0.0.0）时建议设置全站访问密钥：
# 控制台首次请求会提示输入；App 同步走配对令牌不受影响。
# ENV VOXNOTE_API_KEY=change-me
VOLUME ["/data/voxnote"]

EXPOSE 8787
CMD ["python", "run.py", "--host", "0.0.0.0", "--port", "8787"]
