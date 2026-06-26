# ==================== 个人3D打印全品类物料库存系统 ====================
# 基于 Python 3.11 Slim 镜像构建
# v3: SQLite本地存储 + 颜色色块
FROM python:3.11-slim

# 设置工作目录
WORKDIR /app

# 安装系统依赖（Pillow 等库需要的底层支持）
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    libjpeg62-turbo-dev \
    libpng-dev \
    libwebp-dev \
    libtiff-dev \
    zlib1g-dev \
    libfreetype6-dev \
    liblcms2-dev \
    && rm -rf /var/lib/apt/lists/*

# 复制 Python 依赖文件
COPY requirements.txt .

# 安装 Python 依赖
RUN pip install --no-cache-dir -r requirements.txt \
    -i https://pypi.tuna.tsinghua.edu.cn/simple

# 创建持久化目录
RUN mkdir -p /app/logs /app/data

# 复制项目文件
COPY app.py .
COPY templates/ ./templates/

# 创建非 root 用户运行服务（安全最佳实践）
RUN useradd -m -u 1000 appuser && chown -R appuser:appuser /app
USER appuser

# 暴露端口
EXPOSE 5088

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:5088/health')" || exit 1

# 启动 Gunicorn（生产级 WSGI 服务器）
CMD ["gunicorn", "--bind", "0.0.0.0:5088", "--workers", "2", "--threads", "4", "--timeout", "120", "--access-logfile", "-", "--error-logfile", "-", "app:app"]
