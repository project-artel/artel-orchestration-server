FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY worker.py .
RUN useradd --system --uid 10001 worker && mkdir /segments && chown worker /segments
USER worker
ENV SEGMENT_DIRECTORY=/segments
CMD ["uvicorn", "worker:app", "--host", "0.0.0.0", "--port", "8090", "--workers", "1"]
