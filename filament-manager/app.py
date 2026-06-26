#!/usr/bin/env python3
"""
3D打印耗材/五金/备件/工具库存管理系统
Flask + SQLite 单文件部署，Docker 环境运行
"""

import os
import sys
import json
import sqlite3
import signal
import base64
import re
from datetime import datetime

from flask import Flask, request, jsonify, g, send_from_directory

# ─────────────────────────────────────────
# 应用初始化
# ─────────────────────────────────────────
app = Flask(__name__, template_folder="templates", static_folder="static")

DB_DIR = os.environ.get("DB_DIR", "/app/data")
DB_PATH = os.path.join(DB_DIR, "filament.db")
os.makedirs(DB_DIR, exist_ok=True)


# ─────────────────────────────────────────
# 数据库连接
# ─────────────────────────────────────────
def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA journal_mode=WAL")
        g.db.execute("PRAGMA foreign_keys=ON")
    return g.db


@app.teardown_appcontext
def close_db(exception):
    db = g.pop("db", None)
    if db is not None:
        db.close()


# ─────────────────────────────────────────
# 数据库初始化
# ─────────────────────────────────────────
def init_db():
    db = sqlite3.connect(DB_PATH)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA foreign_keys=ON")
    cursor = db.cursor()

    cursor.executescript("""
        CREATE TABLE IF NOT EXISTS materials (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            category TEXT NOT NULL DEFAULT '耗材',
            supplier TEXT DEFAULT '',
            material_type TEXT DEFAULT '',
            color TEXT DEFAULT '',
            spec TEXT DEFAULT '',
            name TEXT DEFAULT '',
            diameter TEXT DEFAULT '',
            roll_weight TEXT DEFAULT '',
            unit TEXT DEFAULT '',
            location TEXT DEFAULT '',
            unit_price REAL DEFAULT 0,
            safety_stock REAL DEFAULT 0,
            created_at TEXT DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS stock_in (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            material_id INTEGER NOT NULL,
            quantity REAL NOT NULL DEFAULT 0,
            unit_price REAL DEFAULT 0,
            unit TEXT DEFAULT '',
            note TEXT DEFAULT '',
            created_at TEXT DEFAULT '',
            FOREIGN KEY (material_id) REFERENCES materials(id)
        );

        CREATE TABLE IF NOT EXISTS stock_out (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            material_id INTEGER NOT NULL,
            quantity REAL NOT NULL DEFAULT 0,
            unit TEXT DEFAULT '',
            note TEXT DEFAULT '',
            created_at TEXT DEFAULT '',
            FOREIGN KEY (material_id) REFERENCES materials(id)
        );

        CREATE TABLE IF NOT EXISTS inventory_check (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            material_id INTEGER NOT NULL,
            system_qty REAL DEFAULT 0,
            actual_qty REAL DEFAULT 0,
            difference REAL DEFAULT 0,
            reason TEXT DEFAULT '',
            created_at TEXT DEFAULT '',
            FOREIGN KEY (material_id) REFERENCES materials(id)
        );

        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT DEFAULT ''
        );
    """)

    # 默认AI设置
    defaults = {
        "ai_enabled": "false",
        "ai_api_url": "https://api.openai.com/v1/chat/completions",
        "ai_api_key": "",
        "ai_model": "gpt-4o",
        "ai_prompt": "你是一个库存管理OCR助手。请识别图片中所有物品，返回JSON数组，每项包含：name(名称), supplier(品牌/供应商,可选), material_type(材质类型,可选), color(颜色,可选), spec(规格,可选), quantity(数量,默认1)。只返回JSON数组，不要其他内容。",
    }
    for k, v in defaults.items():
        cursor.execute(
            "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)", (k, v)
        )

    db.commit()
    db.close()


init_db()


# ─────────────────────────────────────────
# 工具函数
# ─────────────────────────────────────────
def now_str():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def get_stock_qty(db, material_id):
    row = db.execute(
        "SELECT COALESCE(SUM(quantity), 0) AS total FROM stock_in WHERE material_id = ?",
        (material_id,),
    ).fetchone()
    in_total = row["total"]
    row = db.execute(
        "SELECT COALESCE(SUM(quantity), 0) AS total FROM stock_out WHERE material_id = ?",
        (material_id,),
    ).fetchone()
    out_total = row["total"]
    return in_total - out_total


def material_to_dict(row):
    return {
        "id": row["id"],
        "category": row["category"],
        "supplier": row["supplier"],
        "material_type": row["material_type"],
        "color": row["color"],
        "spec": row["spec"],
        "name": row["name"],
        "diameter": row["diameter"],
        "roll_weight": row["roll_weight"],
        "unit": row["unit"],
        "location": row["location"],
        "unit_price": row["unit_price"],
        "safety_stock": row["safety_stock"],
        "created_at": row["created_at"],
    }


# ─────────────────────────────────────────
# 首页
# ─────────────────────────────────────────
@app.route("/")
def index():
    return send_from_directory("templates", "index.html")


# ─────────────────────────────────────────
# 仪表盘
# ─────────────────────────────────────────
@app.route("/api/dashboard")
def dashboard():
    db = get_db()
    category = request.args.get("category", "")

    where = ""
    params = []
    if category:
        where = "WHERE category = ?"
        params = [category]

    # 分类统计
    stats = db.execute(
        "SELECT category, COUNT(*) AS cnt FROM materials GROUP BY category"
    ).fetchall()
    cat_stats = {row["category"]: row["cnt"] for row in stats}

    total_materials = sum(cat_stats.values())

    row = db.execute(
        "SELECT COALESCE(SUM(quantity), 0) AS total FROM stock_in"
    ).fetchone()
    total_in = row["total"]
    row = db.execute(
        "SELECT COALESCE(SUM(quantity), 0) AS total FROM stock_out"
    ).fetchone()
    total_out = row["total"]

    # 预警
    materials = db.execute(f"SELECT * FROM materials {where}", params).fetchall()
    alerts = []
    for m in materials:
        qty = get_stock_qty(db, m["id"])
        if m["safety_stock"] > 0 and qty < m["safety_stock"]:
            name = (
                m["name"]
                or f'{m["material_type"]} {m["color"]} {m["diameter"]}'.strip()
            )
            alerts.append(
                {
                    "id": m["id"],
                    "name": name,
                    "category": m["category"],
                    "current_qty": round(qty, 4),
                    "safety_stock": m["safety_stock"],
                    "unit": m["unit"],
                }
            )

    return jsonify(
        {
            "ok": True,
            "data": {
                "category_stats": cat_stats,
                "total_materials": total_materials,
                "total_in": round(total_in, 4),
                "total_out": round(total_out, 4),
                "current_stock": round(total_in - total_out, 4),
                "alerts": alerts,
            },
        }
    )


# ─────────────────────────────────────────
# 产品档案
# ─────────────────────────────────────────
@app.route("/api/materials", methods=["GET"])
def list_materials():
    db = get_db()
    category = request.args.get("category", "")
    where = ""
    params = []
    if category:
        where = "WHERE category = ?"
        params = [category]
    rows = db.execute(
        f"SELECT * FROM materials {where} ORDER BY id DESC", params
    ).fetchall()
    result = []
    for r in rows:
        d = material_to_dict(r)
        d["stock_qty"] = round(get_stock_qty(db, r["id"]), 4)
        result.append(d)
    return jsonify({"ok": True, "data": result})


@app.route("/api/materials", methods=["POST"])
def create_material():
    db = get_db()
    data = request.get_json(force=True)
    required = ["category"]
    for f in required:
        if f not in data:
            return jsonify({"ok": False, "error": f"缺少必填字段: {f}"}), 400

    now = now_str()
    cursor = db.execute(
        """INSERT INTO materials
        (category, supplier, material_type, color, spec, name, diameter,
         roll_weight, unit, location, unit_price, safety_stock, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (
            data["category"],
            data.get("supplier", ""),
            data.get("material_type", ""),
            data.get("color", ""),
            data.get("spec", ""),
            data.get("name", ""),
            data.get("diameter", ""),
            data.get("roll_weight", ""),
            data.get("unit", ""),
            data.get("location", ""),
            data.get("unit_price", 0),
            data.get("safety_stock", 0),
            now,
        ),
    )
    db.commit()
    new_id = cursor.lastrowid
    row = db.execute("SELECT * FROM materials WHERE id = ?", (new_id,)).fetchone()
    return jsonify({"ok": True, "data": material_to_dict(row)}), 201


@app.route("/api/materials/<int:mid>", methods=["PUT"])
def update_material(mid):
    db = get_db()
    row = db.execute("SELECT * FROM materials WHERE id = ?", (mid,)).fetchone()
    if not row:
        return jsonify({"ok": False, "error": "产品不存在"}), 404

    data = request.get_json(force=True)
    db.execute(
        """UPDATE materials SET
        category=?, supplier=?, material_type=?, color=?, spec=?, name=?,
        diameter=?, roll_weight=?, unit=?, location=?, unit_price=?, safety_stock=?
        WHERE id=?""",
        (
            data.get("category", row["category"]),
            data.get("supplier", row["supplier"]),
            data.get("material_type", row["material_type"]),
            data.get("color", row["color"]),
            data.get("spec", row["spec"]),
            data.get("name", row["name"]),
            data.get("diameter", row["diameter"]),
            data.get("roll_weight", row["roll_weight"]),
            data.get("unit", row["unit"]),
            data.get("location", row["location"]),
            data.get("unit_price", row["unit_price"]),
            data.get("safety_stock", row["safety_stock"]),
            mid,
        ),
    )
    db.commit()
    row = db.execute("SELECT * FROM materials WHERE id = ?", (mid,)).fetchone()
    return jsonify({"ok": True, "data": material_to_dict(row)})


@app.route("/api/materials/search")
def search_materials():
    db = get_db()
    q = request.args.get("q", "").strip()
    category = request.args.get("category", "")
    if not q:
        return jsonify({"ok": True, "data": []})

    where = (
        "WHERE (name LIKE ? OR material_type LIKE ? OR color LIKE ? "
        "OR spec LIKE ? OR supplier LIKE ?)"
    )
    params = [f"%{q}%"] * 5
    if category:
        where += " AND category = ?"
        params.append(category)

    rows = db.execute(
        f"SELECT * FROM materials {where} ORDER BY id DESC LIMIT 50", params
    ).fetchall()
    result = []
    for r in rows:
        d = material_to_dict(r)
        d["stock_qty"] = round(get_stock_qty(db, r["id"]), 4)
        result.append(d)
    return jsonify({"ok": True, "data": result})


@app.route("/api/materials/match-or-create", methods=["POST"])
def match_or_create():
    """OCR 匹配或创建产品"""
    db = get_db()
    data = request.get_json(force=True)
    name = data.get("name", "").strip()
    supplier = data.get("supplier", "").strip()
    material_type = data.get("material_type", "").strip()
    color = data.get("color", "").strip()
    spec = data.get("spec", "").strip()
    category = data.get("category", "耗材")

    conditions = []
    params = []
    if name:
        conditions.append("name = ?")
        params.append(name)
    if material_type:
        conditions.append("material_type = ?")
        params.append(material_type)
    if color:
        conditions.append("color = ?")
        params.append(color)
    if spec:
        conditions.append("spec = ?")
        params.append(spec)
    if supplier:
        conditions.append("supplier = ?")
        params.append(supplier)

    if conditions:
        where = " AND ".join(conditions)
        row = db.execute(
            f"SELECT * FROM materials WHERE {where} LIMIT 1", params
        ).fetchone()
        if row:
            return jsonify(
                {"ok": True, "data": material_to_dict(row), "matched": True}
            )

    # 创建新产品
    now = now_str()
    cursor = db.execute(
        """INSERT INTO materials
        (category, supplier, material_type, color, spec, name, diameter,
         roll_weight, unit, location, unit_price, safety_stock, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (
            category,
            supplier,
            material_type,
            color,
            spec,
            name,
            "",
            "",
            "",
            "",
            0,
            0,
            now,
        ),
    )
    db.commit()
    new_id = cursor.lastrowid
    row = db.execute("SELECT * FROM materials WHERE id = ?", (new_id,)).fetchone()
    return jsonify({"ok": True, "data": material_to_dict(row), "matched": False}), 201


# ─────────────────────────────────────────
# 库存筛选器
# ─────────────────────────────────────────
@app.route("/api/inventory/filters")
def inventory_filters():
    db = get_db()
    categories = [
        r["category"]
        for r in db.execute(
            "SELECT DISTINCT category FROM materials"
        ).fetchall()
    ]
    locations = [
        r["location"]
        for r in db.execute(
            "SELECT DISTINCT location FROM materials WHERE location != ''"
        ).fetchall()
    ]
    suppliers = [
        r["supplier"]
        for r in db.execute(
            "SELECT DISTINCT supplier FROM materials WHERE supplier != ''"
        ).fetchall()
    ]
    return jsonify(
        {
            "ok": True,
            "data": {
                "categories": categories,
                "locations": locations,
                "suppliers": suppliers,
            },
        }
    )


# ─────────────────────────────────────────
# 入库
# ─────────────────────────────────────────
@app.route("/api/stock-in", methods=["GET"])
def list_stock_in():
    db = get_db()
    category = request.args.get("category", "")
    where = ""
    params = []
    if category:
        where = "WHERE m.category = ?"
        params = [category]
    rows = db.execute(
        f"""SELECT si.*, m.name, m.material_type, m.color, m.spec,
                   m.diameter, m.category, m.unit AS m_unit
        FROM stock_in si
        LEFT JOIN materials m ON si.material_id = m.id
        {where} ORDER BY si.id DESC LIMIT 200""",
        params,
    ).fetchall()
    result = []
    for r in rows:
        name = (
            r["name"]
            or f'{r["material_type"]} {r["color"]} {r["diameter"]}'.strip()
        )
        result.append(
            {
                "id": r["id"],
                "material_id": r["material_id"],
                "quantity": r["quantity"],
                "unit_price": r["unit_price"],
                "unit": r["unit"] or r["m_unit"],
                "note": r["note"],
                "created_at": r["created_at"],
                "material_name": name,
                "category": r["category"],
            }
        )
    return jsonify({"ok": True, "data": result})


@app.route("/api/stock-in", methods=["POST"])
def create_stock_in():
    db = get_db()
    data = request.get_json(force=True)
    mid = data.get("material_id")
    quantity = data.get("quantity", 0)
    if not mid or quantity <= 0:
        return jsonify({"ok": False, "error": "缺少产品ID或数量无效"}), 400

    now = now_str()
    db.execute(
        "INSERT INTO stock_in (material_id, quantity, unit_price, unit, note, created_at) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (
            mid,
            quantity,
            data.get("unit_price", 0),
            data.get("unit", ""),
            data.get("note", ""),
            now,
        ),
    )
    db.commit()
    rid = db.execute("SELECT last_insert_rowid()").fetchone()[0]
    return jsonify({"ok": True, "data": {"id": rid}}), 201


@app.route("/api/stock-in/batch", methods=["POST"])
def batch_stock_in():
    db = get_db()
    data = request.get_json(force=True)
    items = data.get("items", [])
    if not items:
        return jsonify({"ok": False, "error": "items不能为空"}), 400

    now = now_str()
    ids = []
    for item in items:
        mid = item.get("material_id")
        qty = item.get("quantity", 0)
        if not mid or qty <= 0:
            continue
        db.execute(
            "INSERT INTO stock_in (material_id, quantity, unit_price, unit, note, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (
                mid,
                qty,
                item.get("unit_price", 0),
                item.get("unit", ""),
                item.get("note", ""),
                now,
            ),
        )
        ids.append(db.execute("SELECT last_insert_rowid()").fetchone()[0])
    db.commit()
    return jsonify({"ok": True, "data": {"count": len(ids), "ids": ids}}), 201


# ─────────────────────────────────────────
# 出库
# ─────────────────────────────────────────
@app.route("/api/stock-out", methods=["GET"])
def list_stock_out():
    db = get_db()
    category = request.args.get("category", "")
    where = ""
    params = []
    if category:
        where = "WHERE m.category = ?"
        params = [category]
    rows = db.execute(
        f"""SELECT so.*, m.name, m.material_type, m.color, m.spec,
                   m.diameter, m.category, m.unit AS m_unit
        FROM stock_out so
        LEFT JOIN materials m ON so.material_id = m.id
        {where} ORDER BY so.id DESC LIMIT 200""",
        params,
    ).fetchall()
    result = []
    for r in rows:
        name = (
            r["name"]
            or f'{r["material_type"]} {r["color"]} {r["diameter"]}'.strip()
        )
        result.append(
            {
                "id": r["id"],
                "material_id": r["material_id"],
                "quantity": r["quantity"],
                "unit": r["unit"] or r["m_unit"],
                "note": r["note"],
                "created_at": r["created_at"],
                "material_name": name,
                "category": r["category"],
            }
        )
    return jsonify({"ok": True, "data": result})


@app.route("/api/stock-out", methods=["POST"])
def create_stock_out():
    db = get_db()
    data = request.get_json(force=True)
    mid = data.get("material_id")
    quantity = data.get("quantity", 0)
    if not mid or quantity <= 0:
        return jsonify({"ok": False, "error": "缺少产品ID或数量无效"}), 400

    current = get_stock_qty(db, mid)
    mat = db.execute("SELECT * FROM materials WHERE id = ?", (mid,)).fetchone()
    warning = None
    if mat and mat["safety_stock"] > 0:
        after = current - quantity
        if after < mat["safety_stock"]:
            name = (
                mat["name"]
                or f'{mat["material_type"]} {mat["color"]} {mat["diameter"]}'.strip()
            )
            warning = {
                "current": round(current, 4),
                "after": round(after, 4),
                "safety_stock": mat["safety_stock"],
                "name": name,
            }

    now = now_str()
    db.execute(
        "INSERT INTO stock_out (material_id, quantity, unit, note, created_at) "
        "VALUES (?, ?, ?, ?, ?)",
        (mid, quantity, data.get("unit", ""), data.get("note", ""), now),
    )
    db.commit()
    rid = db.execute("SELECT last_insert_rowid()").fetchone()[0]
    return jsonify(
        {"ok": True, "data": {"id": rid}, "warning": warning}
    ), 201


# ─────────────────────────────────────────
# 库存台账
# ─────────────────────────────────────────
@app.route("/api/inventory")
def inventory():
    db = get_db()
    category = request.args.get("category", "")
    where = ""
    params = []
    if category:
        where = "WHERE category = ?"
        params = [category]
    rows = db.execute(
        f"SELECT * FROM materials {where} ORDER BY id DESC", params
    ).fetchall()
    result = []
    for r in rows:
        d = material_to_dict(r)
        d["stock_qty"] = round(get_stock_qty(db, r["id"]), 4)
        result.append(d)
    return jsonify({"ok": True, "data": result})


# ─────────────────────────────────────────
# 盘点
# ─────────────────────────────────────────
@app.route("/api/inventory/check", methods=["POST"])
def inventory_check():
    db = get_db()
    data = request.get_json(force=True)
    items = data.get("items", [])
    if not items:
        return jsonify({"ok": False, "error": "items不能为空"}), 400

    now = now_str()
    for item in items:
        mid = item.get("material_id")
        actual = item.get("actual_qty", 0)
        system_qty = get_stock_qty(db, mid)
        diff = actual - system_qty
        db.execute(
            "INSERT INTO inventory_check "
            "(material_id, system_qty, actual_qty, difference, reason, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (mid, system_qty, actual, round(diff, 4), item.get("reason", ""), now),
        )
    db.commit()
    return jsonify({"ok": True, "data": {"count": len(items)}})


# ─────────────────────────────────────────
# 设置
# ─────────────────────────────────────────
@app.route("/api/settings", methods=["GET"])
def get_settings():
    db = get_db()
    rows = db.execute("SELECT key, value FROM settings").fetchall()
    cfg = {r["key"]: r["value"] for r in rows}
    return jsonify({"ok": True, "data": cfg})


@app.route("/api/settings", methods=["PUT"])
def update_settings():
    db = get_db()
    data = request.get_json(force=True)
    for k, v in data.items():
        db.execute(
            "INSERT INTO settings (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = ?",
            (k, v, v),
        )
    db.commit()
    rows = db.execute("SELECT key, value FROM settings").fetchall()
    cfg = {r["key"]: r["value"] for r in rows}
    return jsonify({"ok": True, "data": cfg})


# ─────────────────────────────────────────
# OCR
# ─────────────────────────────────────────
import urllib.request  # noqa: E402


@app.route("/api/ocr", methods=["POST"])
def ocr():
    db = get_db()
    rows = db.execute("SELECT key, value FROM settings").fetchall()
    cfg = {r["key"]: r["value"] for r in rows}

    if "file" in request.files:
        file = request.files["file"]
        img_bytes = file.read()
    else:
        data = request.get_json(force=True) or {}
        img_b64 = data.get("image", "")
        if img_b64.startswith("data:"):
            img_b64 = img_b64.split(",", 1)[1]
        img_bytes = base64.b64decode(img_b64)

    ai_enabled = cfg.get("ai_enabled", "false") == "true"

    if ai_enabled:
        result = ocr_via_ai(img_bytes, cfg)
        if result is not None:
            return jsonify({"ok": True, "data": {"items": result, "method": "ai"}})

    result = ocr_local(img_bytes)
    return jsonify({"ok": True, "data": {"items": result, "method": "local"}})


def ocr_via_ai(img_bytes, cfg):
    api_url = cfg.get("ai_api_url", "").strip()
    api_key = cfg.get("ai_api_key", "").strip()
    model = cfg.get("ai_model", "gpt-4o").strip()
    prompt = cfg.get("ai_prompt", "").strip()

    if not api_url or not api_key:
        return None

    img_b64 = base64.b64encode(img_bytes).decode("utf-8")

    payload = {
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{img_b64}"},
                    },
                ],
            }
        ],
        "max_tokens": 2000,
    }

    try:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            api_url,
            data=data,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            if resp.status != 200:
                return None
            resp_data = json.loads(resp.read().decode("utf-8"))
        content = resp_data["choices"][0]["message"]["content"]
        json_match = re.search(r"\[.*\]", content, re.DOTALL)
        if json_match:
            return json.loads(json_match.group())
        return None
    except Exception:
        return None


def ocr_local(img_bytes):
    try:
        import pytesseract
        from PIL import Image
        from io import BytesIO

        image = Image.open(BytesIO(img_bytes))
        text = pytesseract.image_to_string(image, lang="chi_sim+eng")
        lines = [l.strip() for l in text.split("\n") if l.strip()]
        items = []
        for line in lines[:20]:
            items.append(
                {
                    "name": line,
                    "supplier": "",
                    "material_type": "",
                    "color": "",
                    "spec": "",
                    "quantity": 1,
                }
            )
        return items
    except ImportError:
        return [
            {
                "name": "OCR未安装",
                "supplier": "",
                "material_type": "",
                "color": "",
                "spec": "",
                "quantity": 1,
            }
        ]


# ─────────────────────────────────────────
# 信号处理
# ─────────────────────────────────────────
def graceful_shutdown(signum, frame):
    print(f"[INFO] 收到信号 {signum}，正在优雅关闭...")
    try:
        conn = sqlite3.connect(DB_PATH)
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.close()
        print("[INFO] WAL checkpoint 完成")
    except Exception as e:
        print(f"[WARN] WAL checkpoint 失败: {e}")
    sys.exit(0)


signal.signal(signal.SIGTERM, graceful_shutdown)
signal.signal(signal.SIGINT, graceful_shutdown)


# ─────────────────────────────────────────
# 启动
# ─────────────────────────────────────────
if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5088))
    app.run(host="0.0.0.0", port=port, debug=False)
