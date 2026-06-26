FROM python:3.11-slim

WORKDIR /app

# 安装 tesseract-ocr 及中文语言包
RUN apt-get update && \
    apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-chi-sim && \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --default-timeout=120 -r requirements.txt

COPY app.py .
COPY templates/ templates/

RUN mkdir -p /app/data

EXPOSE 5088

CMD ["python", "app.py"]
