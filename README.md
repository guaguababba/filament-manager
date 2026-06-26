# 个人3D打印全品类物料库存系统 / Personal 3D Printing Inventory Management System

A comprehensive inventory management system for 3D printing materials and accessories. Supports 6 major categories, QR code scanning, AI multimodal recognition, color swatches, inventory alerts, Excel import/export, PWA mobile support, and one-click Docker deployment.

一套面向上千种 3D 打印物料的库存管理系统，覆盖6大品类，支持二维码扫码出入库、AI 多模态识别入库、颜色色块、库存预警、PWA 移动端和 Docker 一键部署。

---

## 功能特性 / Features

| 功能 / Feature | 说明 / Description |
|---|---|
| 6大品类管理 | 3D打印耗材 / 紧固件 / 机械件 / 电子元器件 / 工具 / 耗材配件 |
| 二维码扫码出入库 | 每件物料自动生成二维码，扫码即可入库/出库 |
| AI多模态识别入库 | 拍照/语音/文字三种方式入库，AI自动提取物料信息 |
| 颜色色块系统 | 200+ 颜色可视化色块，耗材颜色一目了然 |
| 库存预警 | 库存低于阈值自动标记，及时提醒补货 |
| Excel导入导出 | 支持批量导入导出，二维码仅导出Excel格式 |
| PWA移动端 | 可添加到手机主屏幕，离线可用 |
| Docker一键部署 | docker compose up -d 即可运行 |
| AI多Provider | 支持 DeepSeek / 通义千问 / 火山引擎，可在设置页切换 |

---

## 技术栈 / Tech Stack

| 层级 | 技术 |
|---|---|
| 后端 | Python 3.11 / Flask / Gunicorn |
| 数据存储 | SQLite（本地轻量） |
| AI引擎 | DeepSeek / 通义千问 / 火山引擎 |
| 语音识别 | 火山引擎 ASR |
| 前端 | 原生 HTML/CSS/JS（SPA + PWA） |
| 容器化 | Docker + Docker Compose |
| 部署 | NAS（飞牛OS）/ 任意 Linux 服务器 |

---

## 快速开始 / Quick Start

### 前置条件 / Prerequisites

- Docker 20.10+ & Docker Compose v2
- AI API Key（DeepSeek / 通义千问 / 火山引擎 三选一）

### 步骤 / Steps

```bash
# 1. 克隆项目
git clone https://github.com/your-username/3d-print-inventory.git
cd 3d-print-inventory

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env，填入你的 AI API Key
nano .env

# 3. 构建并启动
docker compose up -d --build

# 4. 访问 http://localhost:5088
```

### 环境变量 / Environment Variables

| 变量 | 说明 | 示例 |
|---|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek API Key | `sk-xxx` |
| `DASHSCOPE_API_KEY` | 通义千问 API Key | `sk-xxx` |
| `VOLCANO_API_KEY` | 火山引擎 API Key | `xxx` |
| `VOLCANO_ASR_APP_ID` | 火山引擎语音识别App ID | `xxx` |
| `VOLCANO_ASR_TOKEN` | 火山引擎语音识别Token | `xxx` |
| `DOMAIN` | 外网访问域名（PWA需要） | `https://your-domain.com` |
| `FLASK_SECRET_KEY` | Flask 密钥 | 随机字符串 |

---

## 目录结构 / Directory Structure

```
.
├── app.py                 # Flask 后端主程序
├── templates/
│   └── index.html         # 前端 SPA 页面（PWA）
├── Dockerfile             # Docker 镜像构建
├── docker-compose.yml     # Docker Compose 编排
├── .env.example           # 环境变量模板
├── requirements.txt       # Python 依赖
├── data/                  # SQLite 数据库（运行时生成）
├── logs/                  # 日志 & AI配置（运行时生成）
├── README.md              # 项目说明
├── .gitignore             # Git 忽略规则
├── NAS部署说明.md          # NAS 部署详细指南
└── Siri快捷指令配置指南.md  # iPhone Siri 快捷指令配置
```

---

## 品类说明 / Categories

| 品类 | 典型物料 |
|---|---|
| 3D打印耗材 | PLA / PETG / ABS / TPU / 树脂 等线材与液体 |
| 紧固件 | 螺丝 / 螺母 / 垫片 / 弹簧 |
| 机械件 | 喷嘴 / 热端 / 挤出机齿轮 / 皮带 / 轴承 |
| 电子元器件 | 步进电机 / 限位开关 / 热敏电阻 / 加热棒 / 主板 |
| 工具 | 钳子 / 扳手 / 卡尺 / 铲刀 / 胶水 |
| 耗材配件 | 胶水 / 润滑脂 / PTFE管 / 打印平台贴膜 |

---

## API 接口 / API Endpoints

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/` | PWA 控制台首页 |
| `GET` | `/health` | 健康检查 |
| `GET` | `/manifest.json` | PWA 清单 |
| `GET` | `/api/inventory` | 库存列表（分页/搜索/筛选） |
| `GET` | `/api/inventory/<id>/detail` | 物料详情（含二维码） |
| `POST` | `/api/inbound/manual` | 手动入库 |
| `POST` | `/api/inbound/photo` | 拍照入库（AI识别） |
| `POST` | `/api/inbound/voice` | 语音入库（AI识别） |
| `POST` | `/api/outbound` | 出库 |
| `POST` | `/api/outbound/voice` | 语音出库 |
| `POST` | `/api/inventory/restock` | 快速补货 |
| `POST` | `/api/qrcode/export` | 导出二维码（Excel） |
| `POST` | `/api/inventory/import` | 批量导入（Excel） |
| `POST` | `/api/inventory/export` | 批量导出（Excel） |
| `GET` | `/api/qrcode/<id>` | 获取物料二维码图片 |
| `GET` | `/api/ai/config` | 获取AI配置 |
| `POST` | `/api/ai/config` | 更新AI配置 |

---

## 截图说明 / Screenshots

> 部署后访问首页即可看到 PWA 控制台，包含库存列表、品类筛选、颜色色块、入库/出库按钮、二维码弹窗等界面。

---

## 相关文档 / Related Docs

- [NAS部署说明.md](./NAS部署说明.md) — NAS Docker 详细部署指南
- [Siri快捷指令配置指南.md](./Siri快捷指令配置指南.md) — iPhone Siri 语音操作配置

---

## License

MIT License
