FROM python:3.11-slim

ARG GFMT_COMMIT=d46e9528578015b51d3b84dd91bf8f16e9ab850f
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN git clone https://github.com/leonboe1/GoogleFindMyTools vendor/GoogleFindMyTools \
 && git -C vendor/GoogleFindMyTools checkout "$GFMT_COMMIT"
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -r vendor/GoogleFindMyTools/requirements.txt

COPY server server
COPY web web

ENV TT_DATA_DIR=/data
VOLUME /data
EXPOSE 8000
CMD ["python", "-m", "server"]
