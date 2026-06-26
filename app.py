"""
个人3D打印全品类物料库存系统 — Flask 后端主程序 (v3)
==================================================
数据存储：本地 SQLite 数据库（/app/data/inventory.db）
AI引擎：支持 DeepSeek / 通义千问 / 火山引擎 / 自定义（用户可在设置页面配置）
语音识别：火山引擎ASR 或 大模型音频理解
部署：Docker 容器化，Gunicorn 生产服务器

v3 更新:
- 废弃飞书多维表格，改用本地 SQLite 存储
- 新增颜色色块系统（COLOR_MAP）
"""

import os
import json
import time
import math
import base64
import hashlib
import re
import io
import logging
import sqlite3
import threading
from datetime import datetime, timezone, timedelta
from functools import wraps

import requests
import qrcode
from PIL import Image
import openpyxl
from openpyxl.styles import Font, Alignment, Border, Side, PatternFill
from openpyxl.drawing.image import Image as XLImage
from openpyxl.utils import get_column_letter
from flask import Flask, request, jsonify, render_template, send_file
from flask_cors import CORS
from dotenv import load_dotenv

# ==================== 初始化 ====================
load_dotenv()

app = Flask(__name__)
app.secret_key = os.getenv("FLASK_SECRET_KEY", "filament-manager-secret-key-change-me")
CORS(app)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

TZ_BEIJING = timezone(timedelta(hours=8))

def beijing_now():
    return datetime.now(TZ_BEIJING).strftime("%Y-%m-%d %H:%M:%S")

def beijing_date():
    return datetime.now(TZ_BEIJING).strftime("%Y-%m-%d")


# ==================== AI 配置持久化系统 ====================

AI_CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs", "ai_config.json")

AI_PROVIDERS = {
    "deepseek": {
        "name": "DeepSeek",
        "base_url": "https://api.deepseek.com",
        "models": ["deepseek-chat", "deepseek-reasoner"],
        "default_model": "deepseek-chat",
        "supports_vision": True,
        "supports_audio": False,
    },
    "qwen": {
        "name": "通义千问",
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "models": ["qwen-vl-plus", "qwen-vl-max", "qwen-plus", "qwen-max", "qwen-audio-turbo"],
        "default_model": "qwen-vl-plus",
        "supports_vision": True,
        "supports_audio": True,
    },
    "volcano": {
        "name": "火山引擎",
        "base_url": "https://ark.cn-beijing.volces.com/api/v3",
        "models": ["doubao-vision-pro-32k", "doubao-pro-32k", "doubao-lite-32k"],
        "default_model": "doubao-vision-pro-32k",
        "supports_vision": True,
        "supports_audio": False,
    },
    "custom": {
        "name": "自定义",
        "base_url": "",
        "models": [],
        "default_model": "",
        "supports_vision": True,
        "supports_audio": False,
    },
}

DEFAULT_AI_CONFIG = {
    "provider": "",
    "api_key": "",
    "api_base_url": "",
    "model": "",
    "use_for_asr": False,
}

_ai_config_cache = None

def load_ai_config():
    config = dict(DEFAULT_AI_CONFIG)
    try:
        if os.path.exists(AI_CONFIG_FILE):
            with open(AI_CONFIG_FILE, 'r', encoding='utf-8') as f:
                saved = json.load(f)
                config.update(saved)
                if config.get("provider") and config.get("api_key"):
                    return config
    except Exception as e:
        logger.warning(f"读取AI配置文件失败: {e}")

    if os.getenv("DEEPSEEK_API_KEY"):
        config["provider"] = "deepseek"
        config["api_key"] = os.getenv("DEEPSEEK_API_KEY")
        config["api_base_url"] = os.getenv("DEEPSEEK_API_BASE", "https://api.deepseek.com")
        config["model"] = "deepseek-chat"
    elif os.getenv("DASHSCOPE_API_KEY"):
        config["provider"] = "qwen"
        config["api_key"] = os.getenv("DASHSCOPE_API_KEY")
        config["api_base_url"] = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        config["model"] = "qwen-vl-plus"
    elif os.getenv("VOLCANO_API_KEY"):
        config["provider"] = "volcano"
        config["api_key"] = os.getenv("VOLCANO_API_KEY")
        config["api_base_url"] = "https://ark.cn-beijing.volces.com/api/v3"
        config["model"] = "doubao-vision-pro-32k"

    if os.getenv("VOLCANO_ASR_APP_ID"):
        config["volcano_asr_app_id"] = os.getenv("VOLCANO_ASR_APP_ID")
        config["volcano_asr_token"] = os.getenv("VOLCANO_ASR_TOKEN", "")
    if os.getenv("VOLCANO_ASR_CLUSTER"):
        config["volcano_asr_cluster"] = os.getenv("VOLCANO_ASR_CLUSTER", "volcengine_input_common")
    return config

def save_ai_config(config):
    os.makedirs(os.path.dirname(AI_CONFIG_FILE), exist_ok=True)
    with open(AI_CONFIG_FILE, 'w', encoding='utf-8') as f:
        json.dump(config, f, ensure_ascii=False, indent=2)
    global _ai_config_cache
    _ai_config_cache = None

def get_ai_config():
    global _ai_config_cache
    if _ai_config_cache is None:
        _ai_config_cache = load_ai_config()
    return _ai_config_cache


# ==================== 颜色色块映射 ====================

COLOR_MAP = {
    "黑色": "#000000",
    "白色": "#FFFFFF",
    "灰色": "#808080",
    "透明": "#E8E8E8",
    "红色": "#FF0000",
    "蓝色": "#0000FF",
    "绿色": "#00FF00",
    "黄色": "#FFFF00",
    "橙色": "#FFA500",
    "紫色": "#800080",
    "棕色": "#8B4513",
    "粉色": "#FFC0CB",
    "银色": "#C0C0C0",
    "金色": "#FFD700",
    "肤色": "#FFDAB9",
}


# ==================== 配置常量 ====================

CATEGORIES = {
    "3d_printing_filament": {
        "name": "3D打印耗材",
        "icon": "filament",
        "aggregation_dims": ["material", "color", "diameter"],
        "extra_fields": ["filament_material", "filament_color", "filament_diameter"],
        "default_threshold": 2,
    },
    "hardware_fastener": {
        "name": "五金紧固件",
        "icon": "screw",
        "aggregation_dims": ["material_name", "spec"],
        "extra_fields": [],
        "default_threshold": 10,
    },
    "electronic_component": {
        "name": "电子元件",
        "icon": "chip",
        "aggregation_dims": ["material_name", "spec"],
        "extra_fields": [],
        "default_threshold": 5,
    },
    "tool_spare_part": {
        "name": "工具备件",
        "icon": "tool",
        "aggregation_dims": ["material_name", "spec"],
        "extra_fields": [],
        "default_threshold": 3,
    },
    "mechanical_transmission": {
        "name": "机械传动件",
        "icon": "gear",
        "aggregation_dims": ["material_name", "spec"],
        "extra_fields": [],
        "default_threshold": 3,
    },
    "consumable_auxiliary": {
        "name": "耗材辅料",
        "icon": "auxiliary",
        "aggregation_dims": ["material_name", "spec"],
        "extra_fields": [],
        "default_threshold": 5,
    },
}

STATUS_OPTIONS = ["在库", "已用完"]
CATEGORY_OPTIONS = [c["name"] for c in CATEGORIES.values()]
FILAMENT_MATERIALS = ["PLA", "PETG", "ABS", "ASA", "TPU", "尼龙", "PC", "PP", "PLA+", "PLA-CF", "PETG-CF", "其他"]
FILAMENT_COLORS = list(COLOR_MAP.keys()) + ["其他"]


# ==================== SQLite 数据层 ====================

DB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
DB_PATH = os.path.join(DB_DIR, "inventory.db")
_db_local = threading.local()

def get_db():
    if not hasattr(_db_local, 'conn') or _db_local.conn is None:
        os.makedirs(DB_DIR, exist_ok=True)
        _db_local.conn = sqlite3.connect(DB_PATH, check_same_thread=False)
        _db_local.conn.row_factory = sqlite3.Row
        _db_local.conn.execute("PRAGMA journal_mode=WAL")
        _db_local.conn.execute("PRAGMA foreign_keys=ON")
    return _db_local.conn

def init_db():
    conn = get_db()
    conn.execute('''
        CREATE TABLE IF NOT EXISTS inventory (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            material_name TEXT NOT NULL DEFAULT '',
            category TEXT NOT NULL DEFAULT '',
            spec TEXT DEFAULT '',
            quantity REAL DEFAULT 0,
            location TEXT DEFAULT '',
            brand TEXT DEFAULT '',
            remark TEXT DEFAULT '',
            status TEXT DEFAULT '在库',
            inbound_date TEXT DEFAULT '',
            last_operation_time TEXT DEFAULT '',
            threshold REAL DEFAULT 5,
            filament_material TEXT DEFAULT '',
            filament_color TEXT DEFAULT '',
            filament_diameter REAL DEFAULT 1.75,
            created_time TEXT DEFAULT '',
            qrcode_base64 TEXT DEFAULT ''
        )
    ''')
    # 迁移：为旧数据库添加 qrcode_base64 列
    try:
        conn.execute("ALTER TABLE inventory ADD COLUMN qrcode_base64 TEXT DEFAULT ''")
    except:
        pass
    conn.commit()
    logger.info("[SQLite] 数据库初始化完成")


# 模块导入时自动初始化数据库（Gunicorn 多 worker 均会执行，IF NOT EXISTS 保证幂等）
init_db()


def row_to_dict(row):
    """将 SQLite Row 转换为与旧 API 兼容的字典"""
    return {
        "record_id": str(row["id"]),
        "created_time": row["created_time"] or "",
        "material_name": row["material_name"] or "",
        "category": row["category"] or "",
        "spec": row["spec"] or "",
        "quantity": float(row["quantity"] or 0),
        "location": row["location"] or "",
        "brand": row["brand"] or "",
        "remark": row["remark"] or "",
        "status": row["status"] or "在库",
        "inbound_date": row["inbound_date"] or "",
        "last_operation_time": row["last_operation_time"] or "",
        "threshold": float(row["threshold"] or 5),
        "filament_material": row["filament_material"] or "",
        "filament_color": row["filament_color"] or "",
        "filament_diameter": float(row["filament_diameter"] or 1.75),
        "qrcode_base64": row["qrcode_base64"] if "qrcode_base64" in row.keys() else "",
    }

def db_get_all(where_clause="", params=None):
    conn = get_db()
    sql = "SELECT * FROM inventory"
    if where_clause:
        sql += f" WHERE {where_clause}"
    sql += " ORDER BY id DESC"
    rows = conn.execute(sql, params or ()).fetchall()
    return [row_to_dict(r) for r in rows]

def db_get_one(record_id):
    conn = get_db()
    row = conn.execute("SELECT * FROM inventory WHERE id = ?", (int(record_id),)).fetchone()
    if not row:
        raise Exception(f"记录 {record_id} 不存在")
    return row_to_dict(row)

def db_insert(fields):
    conn = get_db()
    now = beijing_now()
    cursor = conn.execute('''
        INSERT INTO inventory
        (material_name, category, spec, quantity, location, brand, remark,
         status, inbound_date, last_operation_time, threshold,
         filament_material, filament_color, filament_diameter, created_time, qrcode_base64)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (
        fields.get("material_name", ""),
        fields.get("category", ""),
        fields.get("spec", ""),
        float(fields.get("quantity", 0)),
        fields.get("location", ""),
        fields.get("brand", ""),
        fields.get("remark", ""),
        fields.get("status", "在库"),
        fields.get("inbound_date", beijing_date()),
        fields.get("last_operation_time", now),
        float(fields.get("threshold", 5)),
        fields.get("filament_material", ""),
        fields.get("filament_color", ""),
        float(fields.get("filament_diameter", 1.75)),
        now,
        "",  # qrcode_base64 先留空，commit 后回填
    ))
    rid = cursor.lastrowid
    # 自动生成二维码并回填
    try:
        qr_b64 = generate_qrcode_base64(str(rid))
        conn.execute("UPDATE inventory SET qrcode_base64 = ? WHERE id = ?", (qr_b64, rid))
    except Exception as e:
        logger.warning(f"生成二维码失败 (id={rid}): {e}")
    conn.commit()
    return str(rid)

def db_update(record_id, fields):
    conn = get_db()
    set_parts = []
    values = []
    for col, val in fields.items():
        set_parts.append(f"{col} = ?")
        values.append(val)
    values.append(int(record_id))
    conn.execute(f"UPDATE inventory SET {', '.join(set_parts)} WHERE id = ?", values)
    conn.commit()

def db_delete(record_id):
    conn = get_db()
    conn.execute("DELETE FROM inventory WHERE id = ?", (int(record_id),))
    conn.commit()


# ==================== 辅助函数 ====================

def generate_qrcode_base64(data_str):
    """生成二维码的 base64 PNG 字符串"""
    qr = qrcode.QRCode(box_size=6, border=2)
    qr.add_data(data_str)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("utf-8")


def get_category_key(category_name):
    for key, info in CATEGORIES.items():
        if info["name"] == category_name:
            return key
    return None

def is_filament_category(category_name):
    return category_name == "3D打印耗材" or get_category_key(category_name) == "3d_printing_filament"

def parse_number(s):
    if isinstance(s, (int, float)):
        return float(s)
    if not s:
        return 0
    nums = re.findall(r'[\d.]+', str(s))
    return float(nums[0]) if nums else 0

def get_default_threshold(category_name):
    for key, info in CATEGORIES.items():
        if info["name"] == category_name:
            return info["default_threshold"]
    return 5


# ==================== 通用 AI 调用 ====================

def call_ai_chat(messages, max_tokens=1024, temperature=0.1):
    config = get_ai_config()
    api_key = config.get("api_key", "")
    api_base = config.get("api_base_url", "").rstrip("/")
    model = config.get("model", "")
    if not api_key:
        raise Exception("AI API Key 未配置，请在设置页面配置")
    if not api_base:
        raise Exception("AI API Base URL 未配置，请在设置页面配置")
    if not model:
        raise Exception("AI 模型名称未配置，请在设置页面配置")
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    try:
        resp = requests.post(f"{api_base}/v1/chat/completions", json=payload, headers=headers, timeout=90)
        data = resp.json()
        if "choices" not in data:
            err_msg = data.get("error", {}).get("message", str(data))
            raise Exception(f"AI API 错误: {err_msg}")
        return data["choices"][0]["message"]["content"]
    except requests.exceptions.RequestException as e:
        raise Exception(f"AI API 网络错误: {str(e)}")


# ==================== 图片识别 ====================

def recognize_image_with_ai(image_path):
    config = get_ai_config()
    with open(image_path, "rb") as f:
        img_data = f.read()
    img_base64 = base64.b64encode(img_data).decode("utf-8")
    ext = os.path.splitext(image_path)[1].lower()
    mime_map = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp"}
    mime_type = mime_map.get(ext, "image/jpeg")
    prompt = """请识别这张图片中的物料信息，提取以下字段并以JSON格式返回（不要包含任何其他文字）：

{
  "material_name": "物料名称",
  "category": "物料类别（从以下选一个：3D打印耗材、五金紧固件、电子元件、工具备件、机械传动件、耗材辅料）",
  "spec": "规格参数",
  "quantity": 数量（数字）,
  "color": "颜色（如适用）",
  "brand": "品牌（如能识别）",
  "diameter": "线材直径（仅3D耗材，默认1.75）"
}

如果是3D打印线材，额外识别材质（PLA/PETG/ABS等）。
如果无法识别某个字段，请留空字符串。"""
    messages = [{
        "role": "user",
        "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{img_base64}"}}
        ]
    }]
    try:
        content = call_ai_chat(messages, max_tokens=1024, temperature=0.1)
        json_match = re.search(r'\{.*\}', content, re.DOTALL)
        if json_match:
            result = json.loads(json_match.group(0))
        else:
            result = {"material_name": content.strip()}
        result["_raw_response"] = content
        return result
    except Exception as e:
        logger.error(f"[AI] 图片识别失败: {e}")
        return {"error": str(e)}


# ==================== 语音转文字 ====================

def speech_to_text_volcano(audio_data):
    app_id = os.getenv("VOLCANO_ASR_APP_ID", "")
    token = os.getenv("VOLCANO_ASR_TOKEN", "")
    cluster = os.getenv("VOLCANO_ASR_CLUSTER", "volcengine_input_common")
    if not app_id or not token:
        return {"error": "火山引擎ASR未配置"}
    try:
        url = "https://openspeech.bytedance.com/api/v1/asr"
        headers = {"Authorization": f"Bearer; {token}"}
        files = {"audio": ("recording.wav", audio_data, "audio/wav")}
        params = {"appid": app_id, "cluster": cluster, "language": "zh-CN"}
        resp = requests.post(url, params=params, headers=headers, files=files, timeout=30)
        data = resp.json()
        if data.get("code") == 1000:
            return {"text": data.get("result", [{}])[0].get("text", "")}
        else:
            return {"error": data.get("message", "ASR识别失败"), "raw": data}
    except Exception as e:
        logger.error(f"[火山ASR] 语音识别失败: {e}")
        return {"error": f"ASR服务异常: {str(e)}"}

def speech_to_text_llm(audio_data):
    config = get_ai_config()
    provider = config.get("provider", "")
    provider_info = AI_PROVIDERS.get(provider, {})
    if not provider_info.get("supports_audio"):
        return {"error": f"当前提供商 {provider_info.get('name', provider)} 不支持音频理解"}
    audio_base64 = base64.b64encode(audio_data).decode("utf-8")
    messages = [{
        "role": "user",
        "content": [
            {"type": "text", "text": "请将这段音频转写为中文文字，只输出转写文本，不要添加任何解释或标点以外的内容。"},
            {"type": "audio_url", "audio_url": {"url": f"data:audio/wav;base64,{audio_base64}"}}
        ]
    }]
    try:
        content = call_ai_chat(messages, max_tokens=512, temperature=0)
        return {"text": content.strip()}
    except Exception as e:
        return {"error": f"大模型ASR失败: {str(e)}"}

def speech_to_text(audio_data):
    config = get_ai_config()
    if config.get("use_for_asr"):
        result = speech_to_text_llm(audio_data)
        if "error" not in result:
            return result
        logger.warning(f"[ASR] 大模型ASR失败，回退到火山ASR: {result['error']}")
    result = speech_to_text_volcano(audio_data)
    if "error" not in result:
        return result
    return {"error": "所有语音识别方式均失败，请检查AI API配置或火山ASR配置"}


# ==================== AI 语音指令解析 ====================

def parse_voice_inbound(text):
    result = {
        "material_name": "", "category": "", "spec": "", "quantity": 1,
        "color": "", "brand": "", "location": "", "diameter": "1.75",
    }
    if any(kw in text for kw in ["PLA", "PETG", "ABS", "ASA", "TPU", "尼龙", "线材", "耗材", "3D"]):
        result["category"] = "3D打印耗材"
        for mat in FILAMENT_MATERIALS:
            if mat in text:
                result["material_name"] = mat
                break
        for color in FILAMENT_COLORS:
            if color in text:
                result["color"] = color
                break
        diam_match = re.search(r'(\d+\.?\d*)\s*mm', text)
        if diam_match:
            result["diameter"] = diam_match.group(1)
    elif any(kw in text for kw in ["螺丝", "螺母", "螺栓", "垫片", "五金", "紧固"]):
        result["category"] = "五金紧固件"
    elif any(kw in text for kw in ["电机", "加热棒", "热敏", "电路板", "电容", "电阻", "电子"]):
        result["category"] = "电子元件"
    elif any(kw in text for kw in ["喷嘴", "热床", "铁氟龙", "喉管", "打印头", "备件", "工具"]):
        result["category"] = "工具备件"
    elif any(kw in text for kw in ["同步带", "轴承", "联轴器", "导轨", "弹簧", "传动"]):
        result["category"] = "机械传动件"
    elif any(kw in text for kw in ["胶水", "润滑油", "美纹纸", "清洗剂", "固化剂", "辅料", "耗材"]):
        result["category"] = "耗材辅料"

    spec_match = re.search(r'(?:规格|型号)?\s*(M\d+[xX×]\d+|\d+\.?\d*\s*mm|0\.\d+\s*mm)', text)
    if spec_match:
        result["spec"] = spec_match.group(1)
    qty_match = re.search(r'(\d+)\s*(?:卷|个|包|套|盒|袋|根|条|瓶)', text)
    if qty_match:
        result["quantity"] = int(qty_match.group(1))
    loc_match = re.search(r'(?:放在|在|位置|货架|收纳盒)\s*(\w+)', text)
    if loc_match:
        result["location"] = loc_match.group(1)
    brand_match = re.search(r'(?:品牌|厂家)\s*(\S+)', text)
    if brand_match:
        result["brand"] = brand_match.group(1)
    if result["category"] and not result["material_name"]:
        result["material_name"] = re.sub(r'\d+', '', text.split(result["category"])[0] if result["category"] in text else text[:20]).strip()[:50]
    result["_raw_text"] = text
    return result


def parse_voice_outbound(text):
    result = {
        "material_name": "", "category": "", "spec": "",
        "used_up": False, "consumed": 0, "remaining": None,
    }
    if any(kw in text for kw in ["用完", "没了", "耗尽", "空了"]):
        result["used_up"] = True
    if any(kw in text for kw in ["PLA", "PETG", "ABS", "ASA", "TPU", "尼龙", "线材", "耗材"]):
        result["category"] = "3D打印耗材"
    elif any(kw in text for kw in ["螺丝", "螺母", "螺栓", "垫片", "五金"]):
        result["category"] = "五金紧固件"
    elif any(kw in text for kw in ["电机", "电子", "电路", "电容", "电阻"]):
        result["category"] = "电子元件"
    elif any(kw in text for kw in ["喷嘴", "热床", "备件", "工具"]):
        result["category"] = "工具备件"
    elif any(kw in text for kw in ["同步带", "轴承", "联轴器", "传动"]):
        result["category"] = "机械传动件"
    elif any(kw in text for kw in ["胶水", "润滑油", "辅料"]):
        result["category"] = "耗材辅料"
    consume_match = re.search(r'(?:消耗|用了)\s*(\d+)\s*(?:个|卷|包|套)', text)
    if consume_match:
        result["consumed"] = int(consume_match.group(1))
    remain_match = re.search(r'(?:还剩|剩余|还有)\s*(\d+)\s*(?:个|卷|包|套)', text)
    if remain_match:
        result["remaining"] = int(remain_match.group(1))
    spec_match = re.search(r'(M\d+[xX×]\d+|\d+\.?\d*\s*mm)', text)
    if spec_match:
        result["spec"] = spec_match.group(1)
    result["_raw_text"] = text
    return result


# ==================== 库存聚合计算 ====================

def aggregate_inventory(records, category_key=None):
    aggregated = {}
    for rec in records:
        cat_name = rec.get("category", "")
        cat_key = get_category_key(cat_name)
        if category_key and cat_key != category_key:
            continue
        if rec.get("status") == "已用完":
            continue
        qty = rec.get("quantity", 0)
        threshold = rec.get("threshold", 0) or get_default_threshold(cat_name)
        if cat_key == "3d_printing_filament":
            mat = rec.get("filament_material", "") or rec.get("material_name", "")
            color = rec.get("filament_color", "") or rec.get("color", "")
            diam = str(rec.get("filament_diameter", "1.75"))
            key = f"{mat}|{color}|{diam}"
        else:
            name = rec.get("material_name", "")
            spec = rec.get("spec", "")
            key = f"{name}|{spec}"
        if key not in aggregated:
            aggregated[key] = {
                "key": key, "total_quantity": 0, "threshold": threshold,
                "records": [], "category": cat_name, "category_key": cat_key,
            }
        aggregated[key]["total_quantity"] += qty
        aggregated[key]["records"].append(rec)
        aggregated[key]["threshold"] = max(aggregated[key]["threshold"], threshold)
    return list(aggregated.values())

def get_low_stock_alerts(records):
    aggregated = aggregate_inventory(records)
    alerts = []
    for agg in aggregated:
        if agg["total_quantity"] <= agg["threshold"]:
            alerts.append({
                "key": agg["key"],
                "category": agg["category"],
                "total_quantity": agg["total_quantity"],
                "threshold": agg["threshold"],
                "shortage": agg["threshold"] - agg["total_quantity"],
            })
    alerts.sort(key=lambda x: x["shortage"], reverse=True)
    return alerts

def find_records_by_aggregation(category_name, match_fields):
    all_records = db_get_all()
    matched = []
    for rec in all_records:
        if rec.get("category") != category_name:
            continue
        if rec.get("status") == "已用完":
            continue
        match = True
        for k, v in match_fields.items():
            rec_val = str(rec.get(k, "")).strip()
            if str(v).strip() and rec_val != str(v).strip():
                match = False
                break
        if match:
            matched.append(rec)
    return matched


# ==================== API 路由 ====================

@app.route("/health")
def health():
    return jsonify({"status": "ok", "service": "filament-manager", "time": beijing_now()})

@app.route("/")
def index():
    return render_template("index.html")

@app.route("/manifest.json")
def manifest():
    domain = os.getenv("DOMAIN", "https://your-domain.com")
    return jsonify({
        "name": "物料库存管理", "short_name": "库存管理",
        "description": "个人3D打印全品类物料库存管理系统",
        "start_url": "/", "display": "standalone", "orientation": "portrait",
        "background_color": "#0f172a", "theme_color": "#3b82f6",
        "icons": [
            {"src": "/static/icon-192.png", "sizes": "192x192", "type": "image/png"},
            {"src": "/static/icon-512.png", "sizes": "512x512", "type": "image/png"},
        ],
        "scope": "/",
    })

@app.route("/sw.js")
def service_worker():
    sw_js = """const CACHE_NAME = 'filament-manager-v3';
const urlsToCache = ['/', '/static/app.css', '/static/app.js'];
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE_NAME).then(cache => cache.addAll(urlsToCache)));
});
self.addEventListener('fetch', event => {
  event.respondWith(caches.match(event.request).then(response => response || fetch(event.request)));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(
    keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
  )));
});"""
    return app.response_class(sw_js, mimetype="application/javascript")


# ---------- 库存查询 ----------

@app.route("/api/inventory/list", methods=["GET"])
def inventory_list():
    try:
        category = request.args.get("category", "")
        status_filter = request.args.get("status", "")
        page = int(request.args.get("page", 1))
        page_size = int(request.args.get("page_size", 50))

        # 构建 SQL WHERE
        conditions = []
        params = []
        if category:
            conditions.append("category = ?")
            params.append(category)
        if status_filter:
            conditions.append("status = ?")
            params.append(status_filter)

        if conditions:
            where = " AND ".join(conditions)
            all_records = db_get_all(where, tuple(params))
        else:
            all_records = db_get_all()

        total = len(all_records)
        start = (page - 1) * page_size
        end = start + page_size

        return jsonify({
            "success": True, "total": total, "page": page,
            "page_size": page_size, "items": all_records[start:end],
        })
    except Exception as e:
        logger.error(f"[API] 库存列表查询失败: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/inventory/aggregated", methods=["GET"])
def inventory_aggregated():
    try:
        category = request.args.get("category", "")
        all_records = db_get_all()
        if category:
            cat_key = get_category_key(category)
            aggregated = aggregate_inventory(all_records, cat_key)
        else:
            aggregated = aggregate_inventory(all_records)
        return jsonify({"success": True, "total_groups": len(aggregated), "items": aggregated})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/inventory/search", methods=["GET"])
def inventory_search():
    try:
        keyword = request.args.get("keyword", "").strip()
        if not keyword:
            return jsonify({"success": False, "error": "请提供搜索关键词"}), 400
        all_records = db_get_all()
        results = []
        for rec in all_records:
            search_text = f"{rec.get('material_name','')} {rec.get('category','')} {rec.get('spec','')} {rec.get('brand','')} {rec.get('remark','')}"
            if keyword.lower() in search_text.lower():
                results.append(rec)
        return jsonify({"success": True, "total": len(results), "items": results})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ---------- 库存预警 ----------

@app.route("/api/inventory/alerts", methods=["GET"])
def inventory_alerts():
    try:
        all_records = db_get_all()
        alerts = get_low_stock_alerts(all_records)
        return jsonify({"success": True, "total": len(alerts), "items": alerts})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ---------- 品类管理 ----------

@app.route("/api/categories", methods=["GET"])
def get_categories():
    return jsonify({
        "success": True,
        "items": [
            {
                "key": k, "name": v["name"], "icon": v["icon"],
                "default_threshold": v["default_threshold"],
                "extra_fields": v["extra_fields"],
                "aggregation_dims": v["aggregation_dims"],
            }
            for k, v in CATEGORIES.items()
        ],
    })


# ---------- 入库 API ----------

@app.route("/api/inbound/manual", methods=["POST"])
def inbound_manual():
    try:
        data = request.get_json()
        if not data:
            return jsonify({"success": False, "error": "请求数据为空"}), 400
        material_name = data.get("material_name", "").strip()
        category = data.get("category", "").strip()
        quantity = data.get("quantity", 1)
        if not material_name or not category:
            return jsonify({"success": False, "error": "物料名称和物料类别为必填"}), 400

        fields = {
            "material_name": material_name,
            "category": category,
            "spec": data.get("spec", ""),
            "quantity": float(quantity),
            "location": data.get("location", ""),
            "brand": data.get("brand", ""),
            "remark": data.get("remark", ""),
            "status": "在库",
            "inbound_date": beijing_date(),
            "last_operation_time": beijing_now(),
            "threshold": float(data.get("threshold", get_default_threshold(category))),
        }
        if is_filament_category(category):
            fields["filament_material"] = data.get("filament_material", "")
            fields["filament_color"] = data.get("filament_color", "")
            fields["filament_diameter"] = float(data.get("filament_diameter", 1.75))

        record_id = db_insert(fields)
        return jsonify({"success": True, "message": "入库成功", "record_id": record_id})
    except Exception as e:
        logger.error(f"[入库] 手动入库失败: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/inbound/photo", methods=["POST"])
def inbound_photo():
    try:
        if "image" not in request.files:
            return jsonify({"success": False, "error": "请上传图片"}), 400
        file = request.files["image"]
        filename = f"temp_{int(time.time())}_{file.filename}"
        temp_path = os.path.join("/app/logs", filename)
        file.save(temp_path)
        ai_result = recognize_image_with_ai(temp_path)
        try:
            os.remove(temp_path)
        except Exception:
            pass
        if "error" in ai_result:
            return jsonify({"success": False, "error": ai_result["error"]}), 500
        draft = {
            "material_name": ai_result.get("material_name", ""),
            "category": ai_result.get("category", ""),
            "spec": ai_result.get("spec", ""),
            "quantity": ai_result.get("quantity", 1),
            "color": ai_result.get("color", ""),
            "brand": ai_result.get("brand", ""),
            "diameter": ai_result.get("diameter", "1.75"),
        }
        return jsonify({
            "success": True, "message": "AI识别完成，请确认后提交",
            "draft": draft, "raw_response": ai_result.get("_raw_response", ""),
        })
    except Exception as e:
        logger.error(f"[入库] 图片AI入库失败: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/inbound/voice", methods=["POST"])
def inbound_voice():
    try:
        data = request.get_json()
        text = data.get("text", "")
        if not text:
            return jsonify({"success": False, "error": "请提供语音文本"}), 400
        draft = parse_voice_inbound(text)
        return jsonify({"success": True, "message": "AI解析完成，请确认后提交", "draft": draft})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/inbound/asr", methods=["POST"])
def inbound_asr():
    try:
        if "audio" not in request.files:
            return jsonify({"success": False, "error": "请上传音频文件"}), 400
        file = request.files["audio"]
        audio_data = file.read()
        asr_result = speech_to_text(audio_data)
        if "error" in asr_result:
            return jsonify({"success": False, "error": asr_result["error"]}), 500
        text = asr_result.get("text", "")
        if not text:
            return jsonify({"success": False, "error": "未识别到语音内容"}), 400
        draft = parse_voice_inbound(text)
        draft["transcribed_text"] = text
        return jsonify({"success": True, "message": "语音识别完成，请确认后提交", "draft": draft})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ---------- 出库 API ----------

@app.route("/api/outbound/exact", methods=["POST"])
def outbound_exact():
    try:
        data = request.get_json()
        record_id = data.get("record_id", "")
        consumed = float(data.get("consumed", 0))
        if not record_id or consumed <= 0:
            return jsonify({"success": False, "error": "请提供记录ID和有效消耗数量"}), 400

        rec = db_get_one(record_id)
        current_qty = rec["quantity"]
        new_qty = max(0, current_qty - consumed)
        new_status = "已用完" if new_qty <= 0 else "在库"

        db_update(record_id, {
            "quantity": new_qty,
            "status": new_status,
            "last_operation_time": beijing_now(),
        })
        return jsonify({
            "success": True,
            "message": f"出库成功，消耗 {consumed}，剩余 {new_qty}",
            "remaining": new_qty, "status": new_status,
        })
    except Exception as e:
        logger.error(f"[出库] 精确出库失败: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/outbound/estimate", methods=["POST"])
def outbound_estimate():
    try:
        data = request.get_json()
        record_id = data.get("record_id", "")
        remaining = float(data.get("remaining", 0))
        if not record_id:
            return jsonify({"success": False, "error": "请提供记录ID"}), 400
        if remaining < 0:
            return jsonify({"success": False, "error": "剩余数量不能为负数"}), 400

        rec = db_get_one(record_id)
        current_qty = rec["quantity"]
        consumed = current_qty - remaining
        new_status = "已用完" if remaining <= 0 else "在库"

        db_update(record_id, {
            "quantity": remaining,
            "status": new_status,
            "last_operation_time": beijing_now(),
        })
        return jsonify({
            "success": True,
            "message": f"出库成功，消耗 {consumed:.1f}，剩余 {remaining}",
            "consumed": round(consumed, 1), "remaining": remaining, "status": new_status,
        })
    except Exception as e:
        logger.error(f"[出库] 模糊出库失败: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/outbound/filament", methods=["POST"])
def outbound_filament():
    try:
        data = request.get_json()
        material = data.get("filament_material", "").strip()
        color = data.get("filament_color", "").strip()
        diameter = str(data.get("filament_diameter", "1.75")).strip()
        brand = data.get("brand", "").strip()
        if not material or not color:
            return jsonify({"success": False, "error": "请提供耗材材质和颜色"}), 400

        match_fields = {
            "filament_material": material,
            "filament_color": color,
            "filament_diameter": diameter,
        }
        if brand:
            match_fields["brand"] = brand
        matched = find_records_by_aggregation("3D打印耗材", match_fields)
        if not matched:
            brand_hint = f"（{brand}）" if brand else ""
            return jsonify({"success": False, "error": f"未找到 {material} {color} {diameter}mm{brand_hint} 的3D耗材记录"}), 404

        target = matched[0]
        db_update(target["record_id"], {
            "quantity": 0, "status": "已用完", "last_operation_time": beijing_now(),
        })
        return jsonify({
            "success": True,
            "message": f"3D耗材 {material} {color} {diameter}mm 已出库（标记为已用完）",
            "record_id": target["record_id"],
        })
    except Exception as e:
        logger.error(f"[出库] 3D耗材出库失败: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/outbound/voice", methods=["POST"])
def outbound_voice():
    try:
        data = request.get_json()
        text = data.get("text", "")
        if not text:
            return jsonify({"success": False, "error": "请提供语音文本"}), 400
        draft = parse_voice_outbound(text)
        return jsonify({"success": True, "message": "AI解析完成，请在PWA中确认出库", "draft": draft})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ---------- 记录操作 ----------

@app.route("/api/record/<record_id>", methods=["GET"])
def get_record(record_id):
    try:
        item = db_get_one(record_id)
        return jsonify({"success": True, "item": item})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/record/<record_id>", methods=["PUT"])
def update_record(record_id):
    try:
        data = request.get_json()
        fields = {
            "material_name": data.get("material_name", ""),
            "category": data.get("category", ""),
            "spec": data.get("spec", ""),
            "quantity": float(data.get("quantity", 0)),
            "location": data.get("location", ""),
            "brand": data.get("brand", ""),
            "remark": data.get("remark", ""),
            "last_operation_time": beijing_now(),
            "threshold": float(data.get("threshold", get_default_threshold(data.get("category", "")))),
        }
        if is_filament_category(data.get("category", "")):
            fields["filament_material"] = data.get("filament_material", "")
            fields["filament_color"] = data.get("filament_color", "")
            fields["filament_diameter"] = float(data.get("filament_diameter", 1.75))

        db_update(record_id, fields)
        return jsonify({"success": True, "message": "更新成功"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/record/<record_id>", methods=["DELETE"])
def delete_record(record_id):
    try:
        db_delete(record_id)
        return jsonify({"success": True, "message": "删除成功"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ---------- 品类阈值更新 ----------

@app.route("/api/categories/threshold", methods=["PUT"])
def update_category_threshold():
    try:
        data = request.get_json()
        category = data.get("category", "")
        threshold = int(data.get("threshold", 5))
        for key, info in CATEGORIES.items():
            if info["name"] == category:
                info["default_threshold"] = threshold
                return jsonify({"success": True, "message": f"{category} 阈值已更新为 {threshold}"})
        return jsonify({"success": False, "error": "未找到该品类"}), 404
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ---------- AI API 配置 ----------

@app.route("/api/config/ai", methods=["GET"])
def get_ai_config_api():
    try:
        config = get_ai_config()
        return jsonify({
            "success": True,
            "config": {
                "provider": config.get("provider", ""),
                "api_key": mask_api_key(config.get("api_key", "")),
                "has_api_key": bool(config.get("api_key")),
                "api_base_url": config.get("api_base_url", ""),
                "model": config.get("model", ""),
                "use_for_asr": config.get("use_for_asr", False),
            },
            "providers": {
                k: {
                    "name": v["name"], "base_url": v["base_url"],
                    "models": v["models"], "default_model": v["default_model"],
                    "supports_audio": v["supports_audio"],
                }
                for k, v in AI_PROVIDERS.items()
            },
            "volcano_asr_configured": bool(
                os.getenv("VOLCANO_ASR_APP_ID") or config.get("volcano_asr_app_id")
            ),
            "ai_configured": bool(config.get("provider") and config.get("api_key")),
        })
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/config/ai", methods=["PUT"])
def update_ai_config_api():
    try:
        data = request.get_json()
        new_config = {}
        provider = data.get("provider", "")
        if provider and provider not in AI_PROVIDERS:
            return jsonify({"success": False, "error": "不支持的AI提供商"}), 400
        new_config["provider"] = provider
        new_config["model"] = data.get("model", "")
        new_config["use_for_asr"] = bool(data.get("use_for_asr", False))
        api_base = data.get("api_base_url", "").strip()
        if not api_base and provider in AI_PROVIDERS:
            api_base = AI_PROVIDERS[provider]["base_url"]
        new_config["api_base_url"] = api_base
        api_key = data.get("api_key", "").strip()
        if api_key and "****" not in api_key:
            new_config["api_key"] = api_key
        else:
            existing = load_ai_config()
            new_config["api_key"] = existing.get("api_key", "")
        existing = load_ai_config()
        for k in ["volcano_asr_app_id", "volcano_asr_token", "volcano_asr_cluster"]:
            if existing.get(k):
                new_config[k] = existing[k]
        if os.getenv("VOLCANO_ASR_APP_ID"):
            new_config["volcano_asr_app_id"] = os.getenv("VOLCANO_ASR_APP_ID")
            new_config["volcano_asr_token"] = os.getenv("VOLCANO_ASR_TOKEN", "")
        save_ai_config(new_config)
        logger.info(f"[AI配置] 已更新: provider={provider}, model={new_config['model']}")
        return jsonify({"success": True, "message": "AI配置已保存"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/config/ai/test", methods=["POST"])
def test_ai_config():
    try:
        messages = [{"role": "user", "content": "回复一句话：连接测试成功"}]
        content = call_ai_chat(messages, max_tokens=50, temperature=0)
        return jsonify({"success": True, "message": "AI API 连接测试成功", "response": content})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


def mask_api_key(key):
    if not key:
        return ""
    if len(key) <= 8:
        return key[:4] + "****"
    return key[:8] + "****" + key[-4:]


# ---------- 颜色映射 ----------

@app.route("/api/config/colors", methods=["GET"])
def get_color_map():
    return jsonify({"success": True, "color_map": COLOR_MAP})


# ---------- 快速入库 ----------

@app.route("/api/inventory/restock", methods=["POST"])
def quick_restock():
    """快速入库：选择物料后仅输入数量，无需重新填写信息"""
    try:
        data = request.get_json()
        record_id = data.get("record_id", "")
        added = float(data.get("added", 0))
        if not record_id or added <= 0:
            return jsonify({"success": False, "error": "请提供记录ID和有效的入库数量"}), 400

        rec = db_get_one(record_id)
        if not rec:
            return jsonify({"success": False, "error": "记录不存在"}), 404

        new_qty = rec["quantity"] + added
        new_status = "在库"  # 入库后恢复为在库

        db_update(record_id, {
            "quantity": new_qty,
            "status": new_status,
            "last_operation_time": beijing_now(),
        })
        return jsonify({
            "success": True,
            "message": f"入库成功，新增 {added}，当前库存 {new_qty}",
            "quantity": new_qty, "status": new_status,
        })
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ---------- 二维码生成 ----------

@app.route("/api/qrcode/<record_id>", methods=["GET"])
def get_qrcode(record_id):
    """获取单个物料二维码图片（优先用已存储的 base64）"""
    try:
        item = db_get_one(record_id)
        qr_b64 = item.get("qrcode_base64", "")
        if not qr_b64:
            qr_b64 = generate_qrcode_base64(str(record_id))
        return send_file(io.BytesIO(base64.b64decode(qr_b64)), mimetype="image/png")
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/inventory/<record_id>/detail", methods=["GET"])
def inventory_detail(record_id):
    """获取库存记录详情（含完整信息 + 二维码 base64）"""
    try:
        item = db_get_one(str(record_id))
        if not item.get("qrcode_base64"):
            item["qrcode_base64"] = generate_qrcode_base64(str(record_id))
            db_update(record_id, {"qrcode_base64": item["qrcode_base64"]})
        return jsonify({"success": True, "item": item})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/qrcode/export", methods=["POST"])
def qrcode_export():
    """导出二维码为 Excel"""
    try:
        data = request.get_json()
        ids = data.get("ids", [])
        if not ids:
            return jsonify({"success": False, "error": "请提供物料ID列表"}), 400

        # 生成每个物料的 QR 图片 + 信息
        entries = []
        for rid in ids:
            item = db_get_one(str(rid))
            if not item:
                continue
            cat = item.get("category", "")
            label_lines = []
            if cat == "3D打印耗材":
                if item.get("brand"):
                    label_lines.append(item["brand"])
                label_lines.append(f"{item.get('filament_material','')} / {item.get('filament_color','')}")
                if item.get("filament_diameter"):
                    label_lines.append(f"{item['filament_diameter']}mm")
            else:
                if item.get("material_name"):
                    label_lines.append(item["material_name"])
                if item.get("spec"):
                    label_lines.append(item["spec"])
                if item.get("brand"):
                    label_lines.append(item["brand"])

            qr_data = str(rid)
            qr = qrcode.QRCode(box_size=5, border=2)
            qr.add_data(qr_data)
            qr.make(fit=True)
            qr_img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
            entries.append({"qr_img": qr_img, "label": "\n".join(label_lines), "id": rid})

        if not entries:
            return jsonify({"success": False, "error": "未找到有效物料"}), 404

        return _generate_qr_xlsx(entries)

    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


def _generate_qr_xlsx(entries):
    """生成二维码 Excel（每行：二维码图片 + 信息列）"""
    buf = io.BytesIO()
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "二维码清单"

    # 表头
    headers = ["二维码", "物料名称", "规格/信息", "品牌", "物料ID"]
    header_font = Font(bold=True, size=11)
    for ci, h in enumerate(headers, 1):
        cell = ws.cell(row=1, column=ci, value=h)
        cell.font = header_font
        cell.alignment = Alignment(horizontal="center", vertical="center")

    ws.column_dimensions["A"].width = 18
    ws.column_dimensions["B"].width = 18
    ws.column_dimensions["C"].width = 22
    ws.column_dimensions["D"].width = 16
    ws.column_dimensions["E"].width = 12

    for ri, entry in enumerate(entries, 2):
        # QR image embedded
        qr_stream = io.BytesIO()
        entry["qr_img"].save(qr_stream, format="PNG")
        qr_stream.seek(0)
        xl_img = XLImage(qr_stream)
        xl_img.width = 100
        xl_img.height = 100
        cell_ref = f"A{ri}"
        ws.add_image(xl_img, cell_ref)
        ws.row_dimensions[ri].height = 80

        # Info columns
        label_parts = entry["label"].split("\n")
        info_font = Font(size=10)
        ws.cell(row=ri, column=2, value=label_parts[0] if len(label_parts) > 0 else "").font = info_font
        ws.cell(row=ri, column=3, value=label_parts[1] if len(label_parts) > 1 else (
            label_parts[0] if len(label_parts) > 0 else "")).font = info_font
        ws.cell(row=ri, column=4, value=label_parts[2] if len(label_parts) > 2 else "").font = info_font
        ws.cell(row=ri, column=5, value=entry["id"]).font = info_font
        for ci in range(2, 6):
            ws.cell(row=ri, column=ci).alignment = Alignment(vertical="center")

    wb.save(buf)
    buf.seek(0)
    return send_file(buf, mimetype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                     as_attachment=True, download_name="qr_codes.xlsx")


# ---------- Excel 导出 ----------

@app.route("/api/inventory/export", methods=["GET"])
def export_inventory():
    """导出库存为 Excel"""
    try:
        category = request.args.get("category", "")
        status = request.args.get("status", "")
        brand = request.args.get("brand", "")
        keyword = request.args.get("keyword", "").strip()
        color = request.args.get("color", "")

        conditions = []
        params = []
        if category:
            conditions.append("category = ?")
            params.append(category)
        if status:
            conditions.append("status = ?")
            params.append(status)
        if brand:
            conditions.append("brand LIKE ?")
            params.append(f"%{brand}%")

        if conditions:
            records = db_get_all(" AND ".join(conditions), tuple(params))
        else:
            records = db_get_all()

        if keyword:
            records = [r for r in records if keyword.lower() in
                       f"{r.get('material_name','')} {r.get('spec','')} {r.get('brand','')} {r.get('remark','')}".lower()]
        if color:
            records = [r for r in records if
                       (r.get("filament_color", "") or "").lower() == color.lower()]

        wb = openpyxl.Workbook()
        ws = wb.active
        ws.title = "库存清单"

        header_font = Font(bold=True, color="FFFFFF", size=11)
        header_fill = PatternFill(start_color="3B82F6", end_color="3B82F6", fill_type="solid")
        thin_border = Border(
            left=Side(style="thin"), right=Side(style="thin"),
            top=Side(style="thin"), bottom=Side(style="thin"),
        )

        headers = ["ID", "物料名称", "品类", "规格", "库存数量", "存放位置",
                   "品牌/厂家", "备注", "状态", "入库日期", "最近操作", "预警阈值",
                   "耗材材质", "线材颜色", "线材直径(mm)"]
        for col, h in enumerate(headers, 1):
            cell = ws.cell(row=1, column=col, value=h)
            cell.font = header_font
            cell.fill = header_fill
            cell.alignment = Alignment(horizontal="center")
            cell.border = thin_border

        for row_idx, r in enumerate(records, 2):
            vals = [r.get("record_id", ""), r.get("material_name", ""), r.get("category", ""),
                    r.get("spec", ""), r.get("quantity", 0), r.get("location", ""),
                    r.get("brand", ""), r.get("remark", ""), r.get("status", ""),
                    r.get("inbound_date", ""), r.get("last_operation_time", ""),
                    r.get("threshold", 5), r.get("filament_material", ""),
                    r.get("filament_color", ""), r.get("filament_diameter", "")]
            for col, v in enumerate(vals, 1):
                cell = ws.cell(row=row_idx, column=col, value=v)
                cell.border = thin_border

        ws.column_dimensions["A"].width = 8
        ws.column_dimensions["B"].width = 20
        ws.column_dimensions["C"].width = 14
        ws.column_dimensions["D"].width = 16
        ws.column_dimensions["F"].width = 14
        ws.column_dimensions["G"].width = 16
        ws.column_dimensions["H"].width = 20
        for c in ["E", "I", "J", "K", "L", "M", "N", "O"]:
            ws.column_dimensions[c].width = 12

        ws.auto_filter.ref = ws.dimensions

        buf = io.BytesIO()
        wb.save(buf)
        buf.seek(0)
        return send_file(buf, mimetype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                         as_attachment=True, download_name=f"库存导出_{beijing_date()}.xlsx")
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/inventory/template", methods=["GET"])
def download_template():
    """下载 Excel 导入模板"""
    try:
        wb = openpyxl.Workbook()
        ws = wb.active
        ws.title = "批量导入模板"

        header_font = Font(bold=True, color="FFFFFF", size=11)
        header_fill = PatternFill(start_color="3B82F6", end_color="3B82F6", fill_type="solid")
        headers = ["物料名称*", "品类*", "规格", "库存数量*", "存放位置",
                   "品牌/厂家", "备注", "耗材材质", "线材颜色", "线材直径(mm)"]
        for col, h in enumerate(headers, 1):
            cell = ws.cell(row=1, column=col, value=h)
            cell.font = header_font
            cell.fill = header_fill
            cell.alignment = Alignment(horizontal="center")

        example = ["沉头螺丝", "五金紧固件", "M3x10", "100", "A3货架",
                   "某品牌", "银色", "", "", ""]
        for col, v in enumerate(example, 1):
            ws.cell(row=2, column=col, value=v)

        note = ws.cell(row=4, column=1, value="说明：")
        note.font = Font(bold=True)
        ws.cell(row=5, column=1, value="* 为必填字段。品类必须为：3D打印耗材 / 五金紧固件 / 电子元件 / 工具备件 / 机械传动件 / 耗材辅料")
        ws.cell(row=6, column=1, value="3D打印耗材需填写耗材材质、线材颜色、线材直径；其他品类留空即可")
        ws.cell(row=7, column=1, value="导入时将跳过与现有库存完全重复的记录（同名同品类同规格同品牌）")

        for c in "ABCDEFGHIJ":
            ws.column_dimensions[c].width = 16
        ws.column_dimensions["B"].width = 14
        ws.column_dimensions["C"].width = 14

        buf = io.BytesIO()
        wb.save(buf)
        buf.seek(0)
        return send_file(buf, mimetype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                         as_attachment=True, download_name="库存导入模板.xlsx")
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/api/inventory/import", methods=["POST"])
def import_inventory():
    """从 Excel 批量导入库存"""
    try:
        if "file" not in request.files:
            return jsonify({"success": False, "error": "请上传 Excel 文件"}), 400
        f = request.files["file"]
        if not f.filename.endswith((".xlsx", ".xls")):
            return jsonify({"success": False, "error": "仅支持 .xlsx / .xls 格式"}), 400

        wb = openpyxl.load_workbook(f, read_only=True)
        ws = wb.active
        rows = list(ws.iter_rows(min_row=2, values_only=True))

        valid_categories = [c["name"] for c in CATEGORIES.values()]
        imported, skipped, errors = 0, 0, []
        existing = db_get_all()

        for row_idx, row in enumerate(rows, 2):
            if not row or not row[0]:
                continue
            name = str(row[0]).strip() if row[0] else ""
            category = str(row[1]).strip() if len(row) > 1 and row[1] else ""
            spec = str(row[2]).strip() if len(row) > 2 and row[2] else ""
            try:
                quantity = float(row[3]) if len(row) > 3 and row[3] else 0
            except (ValueError, TypeError):
                quantity = 0
            location = str(row[4]).strip() if len(row) > 4 and row[4] else ""
            brand = str(row[5]).strip() if len(row) > 5 and row[5] else ""
            remark = str(row[6]).strip() if len(row) > 6 and row[6] else ""
            filament_material = str(row[7]).strip() if len(row) > 7 and row[7] else ""
            filament_color = str(row[8]).strip() if len(row) > 8 and row[8] else ""
            try:
                filament_diameter = float(row[9]) if len(row) > 9 and row[9] else 1.75
            except (ValueError, TypeError):
                filament_diameter = 1.75

            if not name or not category:
                errors.append(f"第{row_idx}行: 物料名称或品类为空，已跳过")
                skipped += 1
                continue
            if category not in valid_categories:
                errors.append(f"第{row_idx}行: 品类「{category}」无效，已跳过")
                skipped += 1
                continue

            dup = False
            for ex in existing:
                if (ex.get("material_name", "") == name and ex.get("category", "") == category
                        and ex.get("spec", "") == spec and ex.get("brand", "") == brand):
                    dup = True
                    break
            if dup:
                skipped += 1
                continue

            fields = {
                "material_name": name, "category": category, "spec": spec,
                "quantity": quantity, "location": location, "brand": brand,
                "remark": remark, "status": "在库", "inbound_date": beijing_date(),
                "last_operation_time": beijing_now(),
                "threshold": get_default_threshold(category),
            }
            if is_filament_category(category):
                fields["filament_material"] = filament_material
                fields["filament_color"] = filament_color
                fields["filament_diameter"] = filament_diameter

            rid = db_insert(fields)
            imported += 1
            existing.append({**fields, "record_id": rid})

        wb.close()
        return jsonify({
            "success": True,
            "imported": imported, "skipped": skipped,
            "errors": errors[:20],
            "message": f"导入完成：成功 {imported} 条，跳过 {skipped} 条",
        })
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ---------- 库存多维度筛选 ----------

@app.route("/api/inventory/filter", methods=["GET"])
def inventory_filter():
    """多维度筛选库存（品类/品牌/颜色/状态/关键词）"""
    try:
        category = request.args.get("category", "")
        brand = request.args.get("brand", "")
        color = request.args.get("color", "")
        status = request.args.get("status", "")
        keyword = request.args.get("keyword", "").strip()
        page = int(request.args.get("page", 1))
        page_size = int(request.args.get("page_size", 30))

        conditions = []
        params = []
        if category:
            conditions.append("category = ?")
            params.append(category)
        if status:
            conditions.append("status = ?")
            params.append(status)
        if brand:
            conditions.append("brand LIKE ?")
            params.append(f"%{brand}%")

        if conditions:
            records = db_get_all(" AND ".join(conditions), tuple(params))
        else:
            records = db_get_all()

        if keyword:
            records = [r for r in records if keyword.lower() in
                       f"{r.get('material_name','')} {r.get('spec','')} {r.get('brand','')} {r.get('remark','')}".lower()]
        if color:
            records = [r for r in records if (r.get("filament_color", "") or "").lower() == color.lower()]

        total = len(records)
        start = (page - 1) * page_size
        paged = records[start:start + page_size]

        brands = sorted(set(r.get("brand", "") for r in db_get_all() if r.get("brand", "").strip()))

        return jsonify({
            "success": True, "total": total, "page": page, "page_size": page_size,
            "items": paged, "brands": brands,
        })
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ---------- 应用配置 ----------

@app.route("/api/config", methods=["GET"])
def get_config():
    ai_config = get_ai_config()
    return jsonify({
        "success": True,
        "categories": [
            {"key": k, "name": v["name"], "icon": v["icon"], "default_threshold": v["default_threshold"]}
            for k, v in CATEGORIES.items()
        ],
        "filament_materials": FILAMENT_MATERIALS,
        "filament_colors": FILAMENT_COLORS,
        "color_map": COLOR_MAP,
        "status_options": STATUS_OPTIONS,
        "domain": os.getenv("DOMAIN", ""),
        "ai_configured": bool(ai_config.get("provider") and ai_config.get("api_key")),
        "ai_provider": ai_config.get("provider", ""),
    })


# ==================== 启动 ====================

if __name__ == "__main__":
    logger.info("=" * 60)
    logger.info("个人3D打印全品类物料库存系统 v3 启动中...")
    logger.info("数据存储: 本地 SQLite (/app/data/inventory.db)")
    ai_cfg = get_ai_config()
    if ai_cfg.get("provider"):
        logger.info(f"AI引擎: {ai_cfg['provider']} / {ai_cfg.get('model', 'N/A')}")
    else:
        logger.info("AI引擎: 未配置（请在设置页面配置）")
    logger.info("=" * 60)

    init_db()
    app.run(host="0.0.0.0", port=5088, debug=False)
