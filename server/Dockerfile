FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY run.py .
COPY voxnote_server ./voxnote_server
COPY web ./web

ENV VOXNOTE_DATA=/data/voxnote
VOLUME ["/data/voxnote"]

EXPOSE 8787
CMD ["python", "run.py", "--host", "0.0.0.0", "--port", "8787"]
