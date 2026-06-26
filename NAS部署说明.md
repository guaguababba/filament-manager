# NAS Docker 部署说明

## 环境要求

| 项目 | 说明 |
|------|------|
| NAS 系统 | 飞牛 OS（或其他支持 Docker 的 NAS） |
| Docker | 20.10+ |
| Docker Compose | v2.x |
| 访问域名 | `https://your-domain.com:your-port` |
| SSH | `ssh -p your-port your-username@your-nas-ip` |

---

## 一、初始化部署

### 1. 上传文件到 NAS

```bash
# 创建目录
ssh -p your-port your-username@your-nas-ip "mkdir -p /vol1/1000/filament-manager/templates /vol1/1000/filament-manager/logs"

# 上传文件
scp -P your-port app.py requirements.txt Dockerfile docker-compose.yml .env.example \
    your-username@your-nas-ip:/vol1/1000/filament-manager/
scp -P your-port templates/index.html \
    your-username@your-nas-ip:/vol1/1000/filament-manager/templates/
```

### 2. 配置环境变量

```bash
ssh -p your-port your-username@your-nas-ip
cd /vol1/1000/filament-manager
cp .env.example .env
nano .env  # 修改以下配置：
```

`.env` 关键配置：

```ini
# AI 接口（三选一）
DEEPSEEK_API_KEY=sk-your-key
# DASHSCOPE_API_KEY=sk-your-key
# VOLCANO_API_KEY=your-key

# NAS 访问域名
DOMAIN=https://your-domain.com:your-port

# Flask 密钥（生产环境请随机生成）
FLASK_SECRET_KEY=change-me-to-a-random-string-in-production
```

### 3. 构建并启动

```bash
cd /vol1/1000/filament-manager
docker compose up -d --build
```

---

## 二、HTTPS 反向代理配置（Nginx Proxy Manager 示例）

访问 `https://your-domain.com:your-port` 应看到 PWA 控制台首页。

### Nginx 配置参考

```nginx
server {
    listen your-port ssl http2;
    server_name your-domain.com;

    ssl_certificate     /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:5088;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
    }
}
```

> **注意**：如使用自签名证书，首次访问需在浏览器中手动信任证书。

---

## 三、更新部署

```bash
# 上传修改的文件
scp -P your-port app.py templates/index.html \
    your-username@your-nas-ip:/vol1/1000/filament-manager/

# 重新构建
ssh -p your-port your-username@your-nas-ip
cd /vol1/1000/filament-manager
docker compose up -d --build
```

---

## 四、常见问题

### 构建时 pip 下载慢

Dockerfile 默认使用阿里云 pip 镜像加速，如需更换编辑 Dockerfile 中 `-i` 参数。

### PWA 安装提示 HTTPS

PWA 需要 HTTPS 环境，请确保已配置反向代理和 SSL 证书。首次使用自签名证书时：

1. 用 Safari 打开 `https://your-domain.com:your-port`
2. 点击「显示详细信息」→「访问此网站」信任证书
3. 然后即可添加到主屏幕

### 数据备份

数据存储在容器内 `/app/data/inventory.db`，建议定期备份：

```bash
docker cp filament-manager:/app/data/inventory.db ./backup_$(date +%Y%m%d).db
```
