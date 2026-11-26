"""机器人配置接口：意图管理、机器人设置、模型选择。"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
import random
import time
from typing import Any, Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..core.db import connect
from ..services import brain as brain_service

router = APIRouter(prefix="/ai/v1/bot", tags=["bot"])


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


DEFAULT_INTENTS = [
    ("查询物流", "我的快递到哪了\n帮我查一下订单物流\n订单什么时候发货"),
    ("退款售后", "我要退款\n怎么申请售后\n商品有问题怎么退"),
    ("价格优惠", "现在有什么优惠\n能便宜一点吗\n有没有优惠券"),
    ("人工客服", "转人工\n找人工客服\n我要投诉"),
    ("开票服务", "怎么开发票\n发票抬头怎么填\n需要开增值税发票"),
]


def ok(data: Any = None):
    return {"code": 0, "message": "成功", "data": data}


# 默认欢迎语用占位符而不是写死文案：这条会在"租户第一次进线"时落到 bot_setting，
# 写死就等于把平台品牌（以及固定的示例问题）塞进了租户对客户的对话里。
DEFAULT_WELCOME_TEMPLATE = "您好，我是{bot_name}，很高兴为您服务。您可以直接描述问题。"


def render_greeting(text: Optional[str], bot_name: Optional[str], brand_name: Optional[str]) -> Optional[str]:
    """替换话术里的 {bot_name} / {brand_name}。

    只有默认模板才含占位符；租户自己改过的文案是明文，原样返回。
    {brand_name} 没配时退化成不带品牌的说法，不留一个空洞的"的"。
    """
    if not text:
        return text
    name = (bot_name or "智能客服").strip()
    brand = (brand_name or "").strip()
    if brand:
        rendered = text.replace("{brand_name}", brand)
    else:
        # 没配品牌时，把它后面那个"的"一起去掉，别留下"我是的小云"这种残句
        rendered = text.replace("{brand_name}的", "").replace("{brand_name}", "")
    return rendered.replace("{bot_name}", name)


def clean(row: Optional[dict]) -> Optional[dict]:
    if row is None:
        return None
    result = {}
    for key, value in row.items():
        key = {
            "bot_name": "botName",
            "brand_name": "brandName",
            "avatar_url": "avatarUrl",
            "welcome_message": "welcomeMessage",
            "fallback_message": "fallbackMessage",
            "transfer_prompt": "transferPrompt",
            "transfer_message": "transferMessage",
            "reception_enabled": "receptionEnabled",
            "transfer_on_anger": "transferOnAnger",
            "transfer_after_unresolved": "transferAfterUnresolved",
            "transfer_keywords": "transferKeywords",
            "is_enabled": "isEnabled",
            "escalate": "escalate",
            "model_key": "modelKey",
            "model_name": "modelName",
            "model_type": "modelType",
            "intent_code": "intentCode",
            "hit_count": "hitCount",
            "tenant_code": "tenantCode",
            "session_no": "sessionNo",
            "turn_count": "turnCount",
            "unresolved_rounds": "unresolvedRounds",
            "last_intent": "lastIntent",
            "last_intent_code": "lastIntentCode",
            "last_confidence": "lastConfidence",
            "last_emotion": "lastEmotion",
            "emotion_score": "emotionScore",
            "transfer_reason": "transferReason",
            "last_message": "lastMessage",
            "create_time": "createTime",
            "update_time": "updateTime",
        }.get(key, key)
        if isinstance(value, Decimal):
            result[key] = float(value)
        elif isinstance(value, datetime):
            result[key] = value.strftime("%Y-%m-%d %H:%M:%S")
        else:
            result[key] = value
    return result


def require_tenant(tenant_code: Optional[str]) -> str:
    if not tenant_code or tenant_code == "PLATFORM":
        raise HTTPException(status_code=400, detail="缺少企业租户上下文")
    return tenant_code


def new_id() -> int:
    return int(time.time() * 1000) * 1024 + random.randint(0, 1023)


def infer_provider(model_key: str) -> str:
    """从模型标识推断厂商：模型名不会撒谎，而 provider 字段很容易被写脏。

    以前这里没传就用 "supportflow"（一个并不存在的厂商），于是"库里的 provider 是 supportflow、
    模型却是 deepseek-chat"，最后把 DeepSeek 的模型名发给了千问的接口。
    """
    name = (model_key or "").strip().lower()
    if "deepseek" in name:
        return "deepseek"
    if name.startswith("qwen") or name.startswith("qwq") or "dashscope" in name:
        return "qwen"
    return "supportflow"


class IntentCreate(ApiModel):
    name: str = Field(min_length=1, max_length=64)
    samples: Optional[str] = None
    escalate: bool = False


class IntentUpdate(ApiModel):
    name: Optional[str] = Field(default=None, max_length=64)
    samples: Optional[str] = None
    status: Optional[int] = Field(default=None, ge=1, le=2)
    escalate: Optional[bool] = None


class SettingUpdate(ApiModel):
    bot_name: str = Field(min_length=1, max_length=64)
    # 租户品牌名：自我介绍/默认欢迎语里用它，不再写死平台品牌（留空走中性说法）
    brand_name: Optional[str] = Field(default=None, max_length=64)
    # 机器人头像：访客端聊天里显示的那张图（留空用内置默认头像）
    avatar_url: Optional[str] = Field(default=None, max_length=255)
    welcome_message: Optional[str] = Field(default=None, max_length=255)
    fallback_message: Optional[str] = Field(default=None, max_length=255)
    transfer_prompt: Optional[str] = Field(default=None, max_length=255)
    # 转接中话术：和"转人工提示"分开——那个是教客户怎么找人工，这个是已经在转的确认
    transfer_message: Optional[str] = Field(default=None, max_length=255)
    is_enabled: bool = True
    # 接待策略：机器人先接待还是直接进人工、什么时候自动转人工
    reception_enabled: bool = True
    transfer_on_anger: bool = True
    transfer_after_unresolved: int = Field(default=2, ge=0, le=10)
    transfer_keywords: Optional[str] = Field(default=None, max_length=255)


class ModelUpdate(ApiModel):
    model_key: str = Field(min_length=1, max_length=64)
    model_name: Optional[str] = Field(default=None, max_length=64)
    provider: Optional[str] = Field(default=None, max_length=32)
    model_type: Optional[int] = Field(default=1, ge=1, le=3)
    temperature: float = Field(default=0.3, ge=0, le=2)


@router.get("/intents")
def list_intents(x_tenant_code: Optional[str] = Header(default=None)):
    tenant = require_tenant(x_tenant_code)
    with connect() as conn:
        count = conn.execute(
            "SELECT COUNT(1) FROM bot_intent WHERE tenant_code=%s AND is_deleted=FALSE", (tenant,)).fetchone()
        if count and count["count"] == 0:
            conn.cursor().executemany(
                """INSERT INTO bot_intent
                      (id, tenant_code, intent_code, name, status, confidence, hit_count, samples, escalate)
                   VALUES (%s, %s, %s, %s, 1, 0.00, 0, %s, %s)""",
                [(new_id(), tenant, f"IT000{i + 1}", name, samples, escalate)
                 for i, (name, samples, escalate) in enumerate(DEFAULT_INTENTS)],
            )
            conn.commit()
        rows = conn.execute(
            """SELECT id, tenant_code, intent_code, name, status, confidence, hit_count, samples,
                      escalate, update_time
                 FROM bot_intent
                WHERE tenant_code=%s AND is_deleted=FALSE
                ORDER BY create_time DESC""",
            (tenant,),
        ).fetchall()
    return ok([clean(r) for r in rows])


@router.post("/intents")
def create_intent(body: IntentCreate, x_tenant_code: Optional[str] = Header(default=None)):
    tenant = require_tenant(x_tenant_code)
    intent_code = "IT" + datetime.now().strftime("%Y%m%d%H%M%S")
    with connect() as conn, conn.cursor() as cur:
        row = cur.execute(
            """INSERT INTO bot_intent
                      (id, tenant_code, intent_code, name, status, confidence, hit_count, samples, escalate)
               VALUES (%s, %s, %s, %s, 1, NULL, 0, %s, %s)
               RETURNING id, tenant_code, intent_code, name, status, confidence, hit_count, samples,
                         escalate, update_time""",
            (new_id(), tenant, intent_code, body.name.strip(), body.samples, body.escalate),
        ).fetchone()
        conn.commit()
    return ok(clean(row))


@router.put("/intents/{intent_id}")
def update_intent(intent_id: int, body: IntentUpdate, x_tenant_code: Optional[str] = Header(default=None)):
    tenant = require_tenant(x_tenant_code)
    sets = []
    params = []
    if body.name is not None:
        sets.append("name=%s")
        params.append(body.name.strip())
    if body.samples is not None:
        sets.append("samples=%s")
        params.append(body.samples)
    if body.status is not None:
        sets.append("status=%s")
        params.append(body.status)
    if body.escalate is not None:
        sets.append("escalate=%s")
        params.append(body.escalate)
    if not sets:
        return ok()
    sets.append("update_time=CURRENT_TIMESTAMP")
    params += [intent_id, tenant]
    with connect() as conn, conn.cursor() as cur:
        cur.execute(
            f"UPDATE bot_intent SET {', '.join(sets)} WHERE id=%s AND tenant_code=%s AND is_deleted=FALSE",
            params)
        conn.commit()
    return ok()


@router.get("/settings")
def get_setting(x_tenant_code: Optional[str] = Header(default=None)):
    tenant = require_tenant(x_tenant_code)
    with connect() as conn:
        row = conn.execute(
            """SELECT id, tenant_code, bot_name, welcome_message, fallback_message, transfer_prompt,
                      transfer_message, is_enabled, model_key, temperature,
                      reception_enabled, transfer_on_anger,
                      transfer_after_unresolved, transfer_keywords, brand_name, avatar_url,
                      create_time, update_time
                 FROM bot_setting
                WHERE tenant_code=%s AND is_deleted=FALSE""",
            (tenant,),
        ).fetchone()
        if row is None:
            row = conn.execute(
                """INSERT INTO bot_setting
                      (id, tenant_code, bot_name, welcome_message, fallback_message,
                       transfer_prompt, transfer_message, is_enabled, model_key, temperature,
                       reception_enabled, transfer_on_anger, transfer_after_unresolved, transfer_keywords)
                   VALUES (%s, %s, '小云', %s, %s, %s, %s, TRUE, 'qwen-plus', 0.35, TRUE, TRUE, 2, %s)
                   RETURNING id, tenant_code, bot_name, welcome_message, fallback_message,
                             transfer_prompt, transfer_message, is_enabled, model_key, temperature,
                             reception_enabled, transfer_on_anger, transfer_after_unresolved,
                             transfer_keywords, brand_name, avatar_url, create_time, update_time""",
                (
                    new_id(),
                    tenant,
                    DEFAULT_WELCOME_TEMPLATE,
                    "抱歉，我暂时无法准确回答您的问题，已为您记录并建议转人工客服进一步处理。",
                    "如需人工客服，请回复“转人工”，或通过右上角电话联系我们。",
                    "好的，正在为您转接人工客服，请稍候。",
                    "转人工,人工客服,找人工,要人工,人工",
                ),
            ).fetchone()
            conn.commit()
    data = clean(row)
    # 欢迎语可能还是默认模板：返回前把占位符换成这台租户的名字/品牌。
    # Java 侧拿到的就是一句可以直接塞进会话的成品文案。
    data["welcomeMessage"] = render_greeting(data.get("welcomeMessage"),
                                             data.get("botName"), data.get("brandName"))
    # "配置过没有"：这一行是 GET 自动建的（create_time == update_time），还是管理员显式保存过
    # （PUT 会把 update_time 顶上去）。前端菜单上的「待配置」角标就靠这个判断，
    # 不能再靠浏览器里的本地开关——保存了却还显示"待配置"就是这么来的。
    data["configured"] = bool(
        data.get("updateTime") and data.get("createTime") and data["updateTime"] > data["createTime"])
    return ok(data)


@router.put("/settings")
def update_setting(body: SettingUpdate, x_tenant_code: Optional[str] = Header(default=None)):
    tenant = require_tenant(x_tenant_code)
    get_setting(x_tenant_code)
    with connect() as conn, conn.cursor() as cur:
        cur.execute(
            """UPDATE bot_setting
                  SET bot_name=%s, brand_name=%s, avatar_url=%s, welcome_message=%s, fallback_message=%s,
                      transfer_prompt=%s,
                      transfer_message=%s, is_enabled=%s, reception_enabled=%s, transfer_on_anger=%s,
                      transfer_after_unresolved=%s, transfer_keywords=%s, update_time=CURRENT_TIMESTAMP
                WHERE tenant_code=%s AND is_deleted=FALSE""",
            (body.bot_name.strip(),
             (body.brand_name or "").strip() or None,
             (body.avatar_url or "").strip() or None,
             body.welcome_message, body.fallback_message, body.transfer_prompt,
             body.transfer_message, body.is_enabled, body.reception_enabled, body.transfer_on_anger,
             body.transfer_after_unresolved, body.transfer_keywords, tenant),
        )
        conn.commit()
    return ok(get_setting(x_tenant_code)["data"])


@router.get("/models")
def list_models(x_tenant_code: Optional[str] = Header(default=None)):
    tenant = require_tenant(x_tenant_code)
    defaults = [
        ("qwen-plus", "千问 Plus", "qwen", 1, 0.35),
        ("deepseek-chat", "DeepSeek Chat", "deepseek", 1, 0.30),
        ("qwen-vl-plus", "千问 VL", "qwen", 2, 0.20),
    ]
    with connect() as conn, conn.cursor() as cur:
        cur.executemany(
            """INSERT INTO bot_model (id, tenant_code, model_key, model_name, provider, model_type, temperature, is_enabled)
               VALUES (%s, %s, %s, %s, %s, %s, %s, TRUE)
               ON CONFLICT (tenant_code, model_key) DO NOTHING""",
            [(new_id(), tenant, k, n, p, t, temp) for k, n, p, t, temp in defaults],
        )
        conn.commit()
        rows = conn.execute(
            """SELECT id, tenant_code, model_key, model_name, provider, model_type, temperature, is_enabled
                 FROM bot_model
                WHERE tenant_code=%s AND is_deleted=FALSE
                ORDER BY model_type, id""",
            (tenant,),
        ).fetchall()
    return ok([clean(r) for r in rows])


@router.put("/model")
def update_model(body: ModelUpdate, x_tenant_code: Optional[str] = Header(default=None)):
    tenant = require_tenant(x_tenant_code)
    with connect() as conn, conn.cursor() as cur:
        # 厂商优先取"当前这条模型记录里已有的值"，其次按模型名推断；
        # 只有两者都拿不到才落到默认值——不再无条件写 "supportflow"
        existing = cur.execute(
            """SELECT provider FROM bot_model
                WHERE tenant_code=%s AND model_key=%s AND is_deleted=FALSE
                LIMIT 1""",
            (tenant, body.model_key),
        ).fetchone()
        provider = body.provider or (existing or {}).get("provider") or infer_provider(body.model_key)
        cur.execute(
            """INSERT INTO bot_model (id, tenant_code, model_key, model_name, provider, model_type, temperature, is_enabled)
               VALUES (%s, %s, %s, %s, %s, %s, %s, TRUE)
               ON CONFLICT (tenant_code, model_key)
               DO UPDATE SET model_name=EXCLUDED.model_name, provider=EXCLUDED.provider,
                             model_type=EXCLUDED.model_type, temperature=%s,
                             is_enabled=TRUE, update_time=CURRENT_TIMESTAMP
               RETURNING id, tenant_code, model_key, model_name, provider, model_type, temperature, is_enabled""",
            (new_id(), tenant, body.model_key, body.model_name or body.model_key,
             provider, body.model_type, body.temperature, body.temperature),
        ).fetchone()
        cur.execute(
            """UPDATE bot_setting SET model_key=%s, temperature=%s, update_time=CURRENT_TIMESTAMP
              WHERE tenant_code=%s AND is_deleted=FALSE""",
            (body.model_key, body.temperature, tenant),
        )
        conn.commit()
    return ok()


@router.get("/dialogues")
def list_dialogues(limit: int = 50, x_tenant_code: Optional[str] = Header(default=None)):
    """机器人接待记录：机器人接待了多少轮、识别成什么意图/情绪、为什么转了人工。

    数据来自 bot_dialogue（多轮状态表），页面拿它做"机器人到底干了什么"的审计视图。
    """
    tenant = require_tenant(x_tenant_code)
    return ok([clean(item) for item in brain_service.list_dialogues(tenant, limit)])


@router.get("/intent-stats")
def intent_stats(x_tenant_code: Optional[str] = Header(default=None)):
    """意图识别统计：总接纳轮次、转人工数、按意图的命中分布。"""
    tenant = require_tenant(x_tenant_code)
    with connect() as conn:
        total = conn.execute(
            """SELECT COALESCE(SUM(turn_count), 0) AS turns,
                      COUNT(1) AS sessions,
                      COALESCE(SUM(CASE WHEN transferred THEN 1 ELSE 0 END), 0) AS transferred
                 FROM bot_dialogue
                WHERE tenant_code=%s AND is_deleted=FALSE""",
            (tenant,),
        ).fetchone()
        by_intent = conn.execute(
            """SELECT COALESCE(last_intent, '其他') AS intent,
                      COUNT(1) AS sessions,
                      COALESCE(SUM(CASE WHEN transferred THEN 1 ELSE 0 END), 0) AS transferred
                 FROM bot_dialogue
                WHERE tenant_code=%s AND is_deleted=FALSE
                GROUP BY COALESCE(last_intent, '其他')
                ORDER BY sessions DESC""",
            (tenant,),
        ).fetchall()
        by_emotion = conn.execute(
            """SELECT COALESCE(last_emotion, '中性') AS emotion, COUNT(1) AS sessions
                 FROM bot_dialogue
                WHERE tenant_code=%s AND is_deleted=FALSE
                GROUP BY COALESCE(last_emotion, '中性')
                ORDER BY sessions DESC""",
            (tenant,),
        ).fetchall()
    return ok({
        "totalTurns": int(total["turns"] or 0) if total else 0,
        "totalSessions": int(total["sessions"] or 0) if total else 0,
        "transferredSessions": int(total["transferred"] or 0) if total else 0,
        "byIntent": [{"intent": row["intent"], "sessions": int(row["sessions"]),
                      "transferred": int(row["transferred"])} for row in by_intent],
        "byEmotion": [{"emotion": row["emotion"], "sessions": int(row["sessions"])}
                      for row in by_emotion],
    })
