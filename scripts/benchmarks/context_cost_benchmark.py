"""Multi-turn Context Engineering & Token Cost Benchmark (16 cases, stub + concurrent live Qwen)."""

from __future__ import annotations

import argparse
import asyncio
from collections.abc import Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import sys
import time
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
AI_SERVICE_ROOT = REPO_ROOT / "ai-service"
for candidate_path in (str(REPO_ROOT), str(AI_SERVICE_ROOT)):
    if candidate_path not in sys.path:
        sys.path.insert(0, candidate_path)

from ai.core.context_engine import (  # noqa: E402
    ContextEngine,
    SlidingWindowCompressor,
    TokenBudgetManager,
    ToolResultPruner,
)

# Realistic Qwen-Plus CNY pricing per 1,000 tokens for relative cost estimation
INPUT_PRICE_PER_1K_CNY = 0.0008
OUTPUT_PRICE_PER_1K_CNY = 0.0020

SYSTEM_POLICY = (
    "你是 SupportFlow 企业级智能客服助手。请严格依据上下文中的最新有效订单号、工单号、"
    "客户编号与工具核验结果回答用户问题；如用户在对话中途更正过订单号，必须以更正后的最新订单号为准。"
    "输出简洁准确的中文处理结论，并在末尾附上 JSON 摘要。"
)


@dataclass(frozen=True)
class BenchmarkCase:
    """Single multi-turn support session benchmark case."""

    case_id: str
    scenario: str
    history: list[dict[str, Any]]
    tool_results: list[dict[str, Any]]
    knowledge_items: list[dict[str, Any]]
    user_query: str
    expected_slots: dict[str, Any]
    expected_intent: str
    expected_action: str


def _make_verbose_order_tool(
    *,
    order_no: str,
    customer_no: str,
    status: str,
    pay_amount: float,
    refundable: bool,
    address_changeable: bool,
    version: str,
) -> dict[str, Any]:
    items = [
        {
            "sku_id": f"SKU-{1000 + idx}",
            "product_name": f"SupportFlow 智能无线降噪耳机 Pro 第{idx + 1}批次配件包（企业定制版含延保服务）",
            "quantity": 1,
            "unit_price": round(pay_amount / 5, 2),
            "warehouse_zone": f"华东智能云仓-A{idx + 1}区-恒温货架-{20 + idx}",
        }
        for idx in range(6)
    ]
    return {
        "tool_name": "query_order",
        "resource_version": version,
        "evidence_ref": f"sha256:ord_{order_no[-6:].lower()}_{version}",
        "output": {
            "order_no": order_no,
            "customer_no": customer_no,
            "status": status,
            "pay_amount": pay_amount,
            "currency": "CNY",
            "shipping_status": "IN_TRANSIT" if status == "SHIPPED" else status,
            "refundable": refundable,
            "address_changeable": address_changeable,
            "items": items,
            "resource_version": version,
            "evidence_ref": f"sha256:ord_{order_no[-6:].lower()}_{version}",
            "raw_sql": f"SELECT * FROM trade_order WHERE order_no = '{order_no}' FOR SHARE",
            "warehouse_telemetry": {
                "picker_node": "wh-east-04",
                "scan_timestamps": [f"2026-04-0{i}T09:12:00Z" for i in range(1, 7)],
                "packaging_spec": "防震气泡柱+双层瓦楞纸箱+防拆封签核验通过",
            },
        },
    }


def _make_verbose_logistics_tool(
    *,
    order_no: str,
    tracking_no: str,
    current_status: str,
    version: str,
) -> dict[str, Any]:
    tracks = [
        {
            "step": idx + 1,
            "time": f"2026-04-10 0{idx + 1}:15:00",
            "station": f"长三角自动化分拨中心-{idx + 1}号中转场",
            "detail": f"快件已完成自动化分拣扫描（设备编号 SRT-88{idx}），发往下一站配送网点途中",
        }
        for idx in range(7)
    ]
    return {
        "tool_name": "query_logistics",
        "resource_version": version,
        "evidence_ref": f"sha256:log_{tracking_no[-6:].lower()}_{version}",
        "output": {
            "order_no": order_no,
            "carrier": "顺丰速运",
            "tracking_no": tracking_no,
            "current_status": current_status,
            "estimated_delivery": "2026-04-12 18:00",
            "tracks": tracks,
            "resource_version": version,
            "evidence_ref": f"sha256:log_{tracking_no[-6:].lower()}_{version}",
            "replica_diagnostics": {"shard": "logistics-db-03", "lag_ms": 4.2},
        },
    }


def _make_verbose_ticket_tool(
    *,
    ticket_no: str,
    order_no: str,
    customer_no: str,
    status: str,
    pending_action: str,
    version: str,
) -> dict[str, Any]:
    history = [
        {
            "seq": idx + 1,
            "actor": "system_router" if idx % 2 == 0 else "tier1_bot",
            "note": f"工单流转节点记录 #{idx + 1}：已完成初步订单核验与客户等级权益匹配检查",
        }
        for idx in range(6)
    ]
    return {
        "tool_name": "query_ticket",
        "resource_version": version,
        "evidence_ref": f"sha256:tkt_{ticket_no[-5:].lower()}_{version}",
        "output": {
            "ticket_no": ticket_no,
            "order_no": order_no,
            "customer_no": customer_no,
            "status": status,
            "priority": "HIGH",
            "sla_due_at": "2026-04-11T16:00:00Z",
            "pending_action": pending_action,
            "history": history,
            "resource_version": version,
            "evidence_ref": f"sha256:tkt_{ticket_no[-5:].lower()}_{version}",
            "internal_logs": "routing_queue=vip_after_sales; escalation_score=0.82; audit=ok",
        },
    }


def _build_filler_dialogue(
    *,
    initial_order: str,
    corrected_order: str | None,
    ticket_no: str,
    customer_no: str,
    raw_phone: str,
    extra_pairs: int,
    topic_label: str,
    pending_prompt: str,
) -> list[dict[str, Any]]:
    turns: list[dict[str, Any]] = [
        {
            "role": "user",
            "content": (
                f"您好，我是企业会员 {customer_no}，联系电话是 {raw_phone}，"
                f"麻烦先帮我看一下订单 {initial_order} 的当前状态和物流进度。"
            ),
        },
        {
            "role": "assistant",
            "content": (
                f"您好！非常感谢您的耐心等待，已为您核实客户编号 {customer_no} 名下的订单 {initial_order}，"
                f"同时查到关联服务工单号为 {ticket_no}。请问您是需要查询配送详情还是办理其他售后服务？"
            ),
        },
        {
            "role": "user",
            "content": f"我想先了解下关于{topic_label}的具体办理时效和平台规则说明。",
        },
        {
            "role": "assistant",
            "content": (
                f"您好，关于{topic_label}规则：平台支持在线自助核验，审核通过后系统会自动同步最新进度到您的手机，"
                "请问还有什么细节需要我为您进一步确认？"
            ),
        },
    ]

    if corrected_order:
        turns.extend(
            [
                {
                    "role": "user",
                    "content": (
                        f"不好意思刚才看错采购台账了！订单号更正为 {corrected_order}，"
                        f"不是前面那个 {initial_order}，后续操作请全部按 {corrected_order} 处理。"
                    ),
                },
                {
                    "role": "assistant",
                    "content": (
                        f"收到，已为您将当前办理的订单号更新为 {corrected_order}，"
                        f"此前的 {initial_order} 不会做任何变更，请您放心。"
                    ),
                },
            ]
        )

    filler_templates = [
        (
            "另外请问处理完成后，系统会有短信或者站内通知回执吗？我们财务这边需要留档。",
            "您好，操作完成后系统会立即推送回执通知，并在电子工单详情页生成完整凭证供贵司财务归档，感谢您的理解。",
        ),
        (
            "如果遇到节假日或者周末，专员审核时效会不会顺延？我们这边比较着急结项。",
            "您好，企业客户工单享有 7x24 小时优先队列保障，节假日也有值班专员按 SLA 时效正常流转跟进。",
        ),
        (
            "之前提交的附件照片还需要重新上传一份吗，还是直接复用工单里的原有凭证就行？",
            "您好，工单系统里已归档的清晰凭证可以直接复用，无需重复上传，如有补充材料我也会第一时间提醒您。",
        ),
        (
            "好的，那费用退回或者单据更新是原路返回到对公付款账户还是企业余额里？",
            "您好，所有资金与单据均严格遵循原支付路径自动结算回退，账单明细可在企业采购中心实时导出。",
        ),
        (
            "那会员积分和当月采购返利额度会受到这次售后调整的影响吗？",
            "您好，系统会按照实际结算金额自动重算有效积分，已发放的企业专属优惠券在有效期内也会自动退回账户。",
        ),
        (
            "清楚了，那如果仓库那边已经有拦截回执，大概多久会在后台刷新最终状态？",
            "您好，仓库扫描回传通常在 15 至 30 分钟内同步至订单中心，届时工单状态也会一并更新。",
        ),
        (
            "明白了，那请帮我核对一下当前待执行的操作项是不是已经准备就绪了？",
            f"您好，当前参数均已核验无误，正在等待{pending_prompt}，您确认后即可立即流转执行。",
        ),
    ]

    for idx in range(extra_pairs):
        u_txt, a_txt = filler_templates[idx % len(filler_templates)]
        turns.append({"role": "user", "content": u_txt})
        turns.append({"role": "assistant", "content": a_txt})

    # Ensure last assistant prompt sets clear pending action context
    turns.append(
        {
            "role": "user",
            "content": f"好的，请帮我再次确认当前工单和订单是否满足办理{topic_label}的条件。",
        }
    )
    turns.append(
        {
            "role": "assistant",
            "content": f"经系统工具核验条件全部满足，当前进入待处理阶段：{pending_prompt}。",
        }
    )
    return turns


def build_benchmark_cases() -> list[BenchmarkCase]:
    """Construct 16 diverse multi-turn support sessions (12 to 24 turns each)."""
    specs: list[dict[str, Any]] = [
        {
            "case_id": "ctx-01",
            "scenario": "order_correction_refund",
            "initial_order": "ORD-20260401-1001",
            "corrected_order": "ORD-20260405-2011",
            "ticket_no": "TKT-202604-9001",
            "customer_no": "CUST-8001",
            "raw_phone": "13812340001",
            "masked_phone": "138****0001",
            "extra_pairs": 4,  # 6 + 8 + 2 = 16 turns
            "topic": "退货退款",
            "pending_prompt": "确认退款申请并锁定售后单",
            "pending_action": "confirm_refund",
            "expected_intent": "refund_return",
            "expected_action": "confirm_refund",
            "query": "没问题了，现在请直接帮我给这个更正后的订单提交退货退款申请！",
        },
        {
            "case_id": "ctx-02",
            "scenario": "multi_turn_address_change",
            "initial_order": "ORD-20260402-1022",
            "corrected_order": None,
            "ticket_no": "TKT-202604-9002",
            "customer_no": "CUST-8002",
            "raw_phone": "13923450002",
            "masked_phone": "139****0002",
            "extra_pairs": 3,  # 4 + 6 + 2 = 12 turns
            "topic": "修改收货地址",
            "pending_prompt": "确认修改地址为上海市浦东新区张江高科园区88号",
            "pending_action": "confirm_address_update",
            "expected_intent": "address_change",
            "expected_action": "confirm_address_update",
            "query": "好的，请把上面那个订单的收货地址正式改成张江高科园区88号吧。",
        },
        {
            "case_id": "ctx-03",
            "scenario": "logistics_urge_escalation",
            "initial_order": "ORD-20260402-1033",
            "corrected_order": "ORD-20260406-2033",
            "ticket_no": "TKT-202604-9003",
            "customer_no": "CUST-8003",
            "raw_phone": "13734560003",
            "masked_phone": "137****0003",
            "extra_pairs": 5,  # 6 + 10 + 2 = 18 turns
            "topic": "物流催单与异常加急",
            "pending_prompt": "提交加急工单催促分拨中心优先派送",
            "pending_action": "approve_ticket_escalation",
            "expected_intent": "order_logistics",
            "expected_action": "approve_ticket_escalation",
            "query": "这批设备明天开会急用，请帮我跟进这个订单的物流并催促发货派送！",
        },
        {
            "case_id": "ctx-04",
            "scenario": "vat_invoice_reissue",
            "initial_order": "ORD-20260403-1044",
            "corrected_order": None,
            "ticket_no": "TKT-202604-9004",
            "customer_no": "CUST-8004",
            "raw_phone": "13645670004",
            "masked_phone": "136****0004",
            "extra_pairs": 4,  # 14 turns
            "topic": "增值税专票重开",
            "pending_prompt": "确认开票信息并重开电子增值税专用发票",
            "pending_action": "issue_invoice",
            "expected_intent": "invoice_request",
            "expected_action": "issue_invoice",
            "query": "税号核对无误，请帮我为该订单重开增值税电子专票。",
        },
        {
            "case_id": "ctx-05",
            "scenario": "damaged_item_exchange",
            "initial_order": "ORD-20260403-1055",
            "corrected_order": "ORD-20260407-2055",
            "ticket_no": "TKT-202604-9005",
            "customer_no": "CUST-8005",
            "raw_phone": "13556780005",
            "masked_phone": "135****0005",
            "extra_pairs": 6,  # 20 turns
            "topic": "外包装破损换货",
            "pending_prompt": "确认换货并安排顺丰上门取件",
            "pending_action": "confirm_exchange",
            "expected_intent": "product_exchange",
            "expected_action": "confirm_exchange",
            "query": "外箱确实压坏了，请按刚才更正的订单帮我办理破损换货和上门取件。",
        },
        {
            "case_id": "ctx-06",
            "scenario": "vip_sla_ticket_escalation",
            "initial_order": "ORD-20260404-1066",
            "corrected_order": None,
            "ticket_no": "TKT-202604-9006",
            "customer_no": "CUST-8006",
            "raw_phone": "15867890006",
            "masked_phone": "158****0006",
            "extra_pairs": 7,  # 20 turns
            "topic": "工单超时升级专员",
            "pending_prompt": "升级工单确认并转交二线高级专员督办",
            "pending_action": "approve_ticket_escalation",
            "expected_intent": "ticket_escalation",
            "expected_action": "approve_ticket_escalation",
            "query": "麻烦立即把这个工单升级专员加急处理，并同步关联订单进度。",
        },
        {
            "case_id": "ctx-07",
            "scenario": "price_protection_refund",
            "initial_order": "ORD-20260404-1077",
            "corrected_order": "ORD-20260408-2077",
            "ticket_no": "TKT-202604-9007",
            "customer_no": "CUST-8007",
            "raw_phone": "15978900007",
            "masked_phone": "159****0007",
            "extra_pairs": 7,  # 22 turns
            "topic": "企业采购价保退差价",
            "pending_prompt": "确认退款退差价金额并提交原路退回",
            "pending_action": "confirm_refund",
            "expected_intent": "refund_return",
            "expected_action": "confirm_refund",
            "query": "价保金额我认可，请对这个最新订单执行退差价退款吧。",
        },
        {
            "case_id": "ctx-08",
            "scenario": "missing_accessory_reship",
            "initial_order": "ORD-20260405-1088",
            "corrected_order": None,
            "ticket_no": "TKT-202604-9008",
            "customer_no": "CUST-8008",
            "raw_phone": "18689010008",
            "masked_phone": "186****0008",
            "extra_pairs": 5,  # 16 turns
            "topic": "漏发配件补发",
            "pending_prompt": "安排补发Type-C转换器配件包",
            "pending_action": "confirm_exchange",
            "expected_intent": "product_exchange",
            "expected_action": "confirm_exchange",
            "query": "好的，请尽快为我的订单安排少发配件补发。",
        },
        {
            "case_id": "ctx-09",
            "scenario": "order_correction_address_ intercept",
            "initial_order": "ORD-20260405-1099",
            "corrected_order": "ORD-20260409-2099",
            "ticket_no": "TKT-202604-9009",
            "customer_no": "CUST-8009",
            "raw_phone": "18890120009",
            "masked_phone": "188****0009",
            "extra_pairs": 8,  # 24 turns
            "topic": "发货前修改收货地址",
            "pending_prompt": "确认修改地址至深圳市南山区科技园南区12栋",
            "pending_action": "confirm_address_update",
            "expected_intent": "address_change",
            "expected_action": "confirm_address_update",
            "query": "趁还没出库，请马上把这个更正订单的收货地址改到南山区科技园12栋。",
        },
        {
            "case_id": "ctx-10",
            "scenario": "billing_duplicate_charge_refund",
            "initial_order": "ORD-20260406-1100",
            "corrected_order": None,
            "ticket_no": "TKT-202604-9010",
            "customer_no": "CUST-8010",
            "raw_phone": "13301230010",
            "masked_phone": "133****0010",
            "extra_pairs": 3,  # 12 turns
            "topic": "账单重复扣费核销与退款",
            "pending_prompt": "确认退款核销重复扣款记录",
            "pending_action": "confirm_refund",
            "expected_intent": "refund_return",
            "expected_action": "confirm_refund",
            "query": "对账单我看过了，请帮我把这个订单重复扣费的那笔申请退款。",
        },
        {
            "case_id": "ctx-11",
            "scenario": "order_correction_invoice",
            "initial_order": "ORD-20260406-1111",
            "corrected_order": "ORD-20260410-2111",
            "ticket_no": "TKT-202604-9011",
            "customer_no": "CUST-8011",
            "raw_phone": "13112340011",
            "masked_phone": "131****0011",
            "extra_pairs": 4,  # 16 turns
            "topic": "抬头变更重开发票",
            "pending_prompt": "确认开票信息并开具新抬头电子发票",
            "pending_action": "issue_invoice",
            "expected_intent": "invoice_request",
            "expected_action": "issue_invoice",
            "query": "新公司抬头确认没问题，请给这个订单开具电子发票。",
        },
        {
            "case_id": "ctx-12",
            "scenario": "cold_chain_logistics_tracking",
            "initial_order": "ORD-20260407-1122",
            "corrected_order": None,
            "ticket_no": "TKT-202604-9012",
            "customer_no": "CUST-8012",
            "raw_phone": "13223450012",
            "masked_phone": "132****0012",
            "extra_pairs": 6,  # 18 turns
            "topic": "专线物流派送追踪",
            "pending_prompt": "提交加急工单协调末端网点今日派送",
            "pending_action": "approve_ticket_escalation",
            "expected_intent": "order_logistics",
            "expected_action": "approve_ticket_escalation",
            "query": "请帮我查清楚该订单最新物流节点并协调网点今天完成派送。",
        },
        {
            "case_id": "ctx-13",
            "scenario": "batch_defect_escalation",
            "initial_order": "ORD-20260407-1133",
            "corrected_order": "ORD-20260410-2133",
            "ticket_no": "TKT-202604-9013",
            "customer_no": "CUST-8013",
            "raw_phone": "15034560013",
            "masked_phone": "150****0013",
            "extra_pairs": 5,  # 18 turns
            "topic": "批次质量问题工单升级",
            "pending_prompt": "人工审批批次质检升级工单",
            "pending_action": "approve_ticket_escalation",
            "expected_intent": "ticket_escalation",
            "expected_action": "approve_ticket_escalation",
            "query": "请针对更正后的采购订单提交工单升级专员加急处理。",
        },
        {
            "case_id": "ctx-14",
            "scenario": "unshipped_cancel_refund",
            "initial_order": "ORD-20260408-1144",
            "corrected_order": None,
            "ticket_no": "TKT-202604-9014",
            "customer_no": "CUST-8014",
            "raw_phone": "15145670014",
            "masked_phone": "151****0014",
            "extra_pairs": 4,  # 14 turns
            "topic": "未发货整单仅退款",
            "pending_prompt": "确认退款并拦截仓库出库任务",
            "pending_action": "confirm_refund",
            "expected_intent": "refund_return",
            "expected_action": "confirm_refund",
            "query": "采购计划调整了，请直接帮我把这个订单申请仅退款。",
        },
        {
            "case_id": "ctx-15",
            "scenario": "warranty_repair_exchange",
            "initial_order": "ORD-20260408-1155",
            "corrected_order": "ORD-20260411-2155",
            "ticket_no": "TKT-202604-9015",
            "customer_no": "CUST-8015",
            "raw_phone": "15256780015",
            "masked_phone": "152****0015",
            "extra_pairs": 7,  # 22 turns
            "topic": "延保期内故障换新",
            "pending_prompt": "确认换货并生成双向免邮寄修单",
            "pending_action": "confirm_exchange",
            "expected_intent": "product_exchange",
            "expected_action": "confirm_exchange",
            "query": "故障视频已经在工单里了，请帮我给这个最新订单办理维修换货。",
        },
        {
            "case_id": "ctx-16",
            "scenario": "coupon_account_billing_check",
            "initial_order": "ORD-20260409-1166",
            "corrected_order": None,
            "ticket_no": "TKT-202604-9016",
            "customer_no": "CUST-8016",
            "raw_phone": "18967890016",
            "masked_phone": "189****0016",
            "extra_pairs": 4,  # 14 turns
            "topic": "企业账单与优惠券抵扣核对",
            "pending_prompt": "升级工单确认账单抵扣明细复核",
            "pending_action": "approve_ticket_escalation",
            "expected_intent": "account_billing",
            "expected_action": "approve_ticket_escalation",
            "query": "请帮我核查这个订单的企业账单扣费和优惠券权益明细。",
        },
    ]

    cases: list[BenchmarkCase] = []
    for idx, sp in enumerate(specs):
        active_order = sp["corrected_order"] or sp["initial_order"]
        history = _build_filler_dialogue(
            initial_order=sp["initial_order"],
            corrected_order=sp["corrected_order"],
            ticket_no=sp["ticket_no"],
            customer_no=sp["customer_no"],
            raw_phone=sp["raw_phone"],
            extra_pairs=sp["extra_pairs"],
            topic_label=sp["topic"],
            pending_prompt=sp["pending_prompt"],
        )
        tools = [
            _make_verbose_order_tool(
                order_no=active_order,
                customer_no=sp["customer_no"],
                status="SHIPPED" if "logistics" in sp["scenario"] else "PAID_CONFIRMED",
                pay_amount=2499.0 + idx * 120.0,
                refundable=True,
                address_changeable=True,
                version=f"v{idx + 3}",
            ),
            _make_verbose_logistics_tool(
                order_no=active_order,
                tracking_no=f"SF2026041099{idx:02d}",
                current_status="IN_TRANSIT_HUB",
                version=f"v{idx + 2}",
            ),
            _make_verbose_ticket_tool(
                ticket_no=sp["ticket_no"],
                order_no=active_order,
                customer_no=sp["customer_no"],
                status="OPEN_PENDING_CONFIRMATION",
                pending_action=sp["pending_action"],
                version=f"v{idx + 5}",
            ),
        ]
        kb_items = [
            {
                "id": f"kb-policy-{idx + 1}",
                "title": f"SupportFlow 企业客户{sp['topic']}服务规范（2026版）",
                "score": 0.91,
                "content": (
                    f"针对企业 VIP 客户办理{sp['topic']}业务：须核验最新有效订单号、客户编号及工单状态；"
                    "若客户在会话中更正过单号，一律以最新确认的订单号作为唯一执行标的，保留证据哈希备查。"
                ),
            }
        ]
        expected_slots = {
            "order_no": active_order,
            "ticket_no": sp["ticket_no"],
            "customer_no": sp["customer_no"],
            "phone_masked": sp["masked_phone"],
            "pending_action": sp["pending_action"],
        }
        cases.append(
            BenchmarkCase(
                case_id=sp["case_id"],
                scenario=sp["scenario"],
                history=history,
                tool_results=tools,
                knowledge_items=kb_items,
                user_query=sp["query"],
                expected_slots=expected_slots,
                expected_intent=sp["expected_intent"],
                expected_action=sp["expected_action"],
            )
        )
    return cases


def _classify_query_intent(text: str) -> str:
    rules = (
        ("refund_return", r"(退款|退货|退差价|仅退款)"),
        ("address_change", r"(改.*地址|修改.*地址|收货地址)"),
        ("invoice_request", r"(发票|专票|开票)"),
        ("product_exchange", r"(换货|补发|维修|取件)"),
        ("account_billing", r"(账单|扣费|优惠券|权益)"),
        ("order_logistics", r"(物流|派送|催促发货|运单)"),
        ("ticket_escalation", r"(升级专员|加急处理|工单)"),
    )
    for code, pat in rules:
        if re.search(pat, text):
            return code
    return "general_inquiry"


def deterministic_stub_responder(messages: Sequence[Mapping[str, str]]) -> dict[str, Any]:
    """Deterministic model simulator that resolves task strictly from provided context messages."""
    last_content = str(messages[-1].get("content") or "") if messages else ""
    parsed_frame: dict[str, Any] | None = None
    if last_content.startswith("{"):
        try:
            candidate = json.loads(last_content)
            if isinstance(candidate, dict) and candidate.get("kind") == "untrusted_context_frame":
                parsed_frame = candidate
        except json.JSONDecodeError:
            parsed_frame = None

    if parsed_frame is not None:
        slots = parsed_frame.get("sticky_slots") or {}
        query = str(parsed_frame.get("current_user_query") or "")
        order_no = slots.get("order_no")
        ticket_no = slots.get("ticket_no")
        customer_no = slots.get("customer_no")
        action = slots.get("pending_action") or ""
        intent = _classify_query_intent(query)
    else:
        # Baseline mode: scan raw messages chronologically
        order_no = None
        ticket_no = None
        customer_no = None
        action = ""
        for msg in messages:
            role = str(msg.get("role") or "")
            txt = str(msg.get("content") or "")
            if role == "user" and not txt.startswith("工具返回原始数据") and not txt.startswith("知识库检索片段"):
                for m in re.finditer(r"\bORD-[0-9]{8}-[0-9]{4}\b", txt):
                    order_no = m.group(0)
                for m in re.finditer(r"\bTKT-[0-9]{6}-[0-9]{4}\b", txt):
                    ticket_no = m.group(0)
                for m in re.finditer(r"\bCUST-[0-9]{4}\b", txt):
                    customer_no = m.group(0)
            elif txt.startswith("工具返回原始数据"):
                for m in re.finditer(r'"ticket_no"\s*:\s*"([^"]+)"', txt):
                    ticket_no = ticket_no or m.group(1)
                for m in re.finditer(r'"pending_action"\s*:\s*"([^"]+)"', txt):
                    action = m.group(1)
        intent = _classify_query_intent(last_content)

    decision = {
        "intent": intent,
        "order_no": order_no,
        "ticket_no": ticket_no,
        "customer_no": customer_no,
        "action": action,
    }
    reply_text = (
        f"已为您核实客户 {customer_no} 的有效订单 {order_no}（关联工单 {ticket_no}），"
        f"当前业务意图为 {intent}，正在为您执行动作 {action}。\n"
        f"```json\n{json.dumps(decision, ensure_ascii=False)}\n```"
    )
    return {"reply_text": reply_text, "decision": decision, "usage": None}


def _build_live_qwen_messages(messages: Sequence[Mapping[str, str]]) -> list[dict[str, str]]:
    augmented = [dict(m) for m in messages]
    if augmented:
        augmented[0] = {
            "role": "system",
            "content": (
                augmented[0]["content"]
                + "\n请在中文回复最后一行输出严格 JSON 对象，包含字段："
                '{"intent":"refund_return|address_change|order_logistics|invoice_request|'
                'product_exchange|ticket_escalation|account_billing","order_no":"...","ticket_no":"...","customer_no":"...","action":"..."}'
            ),
        }
    return augmented


def _parse_live_decision(reply_text: str) -> dict[str, Any]:
    fenced = re.findall(r"\{[^{}]*\"order_no\"[^{}]*\}", reply_text, flags=re.DOTALL)
    if fenced:
        try:
            return json.loads(fenced[-1])
        except json.JSONDecodeError:
            pass
    order_m = re.search(r"\bORD-[0-9]{8}-[0-9]{4}\b", reply_text)
    ticket_m = re.search(r"\bTKT-[0-9]{6}-[0-9]{4}\b", reply_text)
    cust_m = re.search(r"\bCUST-[0-9]{4}\b", reply_text)
    return {
        "intent": _classify_query_intent(reply_text),
        "order_no": order_m.group(0) if order_m else None,
        "ticket_no": ticket_m.group(0) if ticket_m else None,
        "customer_no": cust_m.group(0) if cust_m else None,
        "action": "",
    }


def _resolve_qwen_factory(local_config: Path | None):
    from scripts.benchmarks.rag_quality_provider import QwenGeneration

    env_key = (
        os.getenv("QWEN_API_KEY")
        or os.getenv("SUPPORTFLOW_AI_QWEN_API_KEY")
        or os.getenv("DASHSCOPE_API_KEY")
        or ""
    ).strip()
    default_cfg = local_config or (REPO_ROOT / ".local" / "config" / "local-services.env")

    def make_client():
        if env_key:
            return QwenGeneration(env_key)
        return QwenGeneration.from_local_config(default_cfg)

    return make_client


def _estimate_cost_cny(input_tokens: float, output_tokens: float) -> float:
    return (input_tokens / 1000.0) * INPUT_PRICE_PER_1K_CNY + (output_tokens / 1000.0) * OUTPUT_PRICE_PER_1K_CNY


def _evaluate_sticky_slots(actual_slots: Mapping[str, Any], expected_slots: Mapping[str, Any]) -> tuple[int, int]:
    matched = 0
    total = len(expected_slots)
    for key, expected_val in expected_slots.items():
        if actual_slots.get(key) == expected_val:
            matched += 1
    return matched, total


def _evaluate_task_resolution(decision: Mapping[str, Any], case: BenchmarkCase, reply_text: str) -> bool:
    order_ok = decision.get("order_no") == case.expected_slots["order_no"] or (
        case.expected_slots["order_no"] in reply_text
    )
    ticket_ok = decision.get("ticket_no") == case.expected_slots["ticket_no"] or (
        case.expected_slots["ticket_no"] in reply_text
    )
    intent_ok = decision.get("intent") == case.expected_intent
    return bool(order_ok and ticket_ok and intent_ok)


def create_benchmark_engine(*, max_input_tokens: int = 1650) -> ContextEngine:
    """Create benchmark-configured ContextEngine tuned for realistic multi-turn support sessions."""
    return ContextEngine(
        compressor=SlidingWindowCompressor(
            recent_turns=4,
            max_digest_items=6,
            max_digest_chars=96,
            max_resolved_facts=6,
        ),
        tool_pruner=ToolResultPruner(
            max_items=3,
            max_str_chars=130,
            max_bytes=920,
        ),
        budget_manager=TokenBudgetManager(
            max_input_tokens=max_input_tokens,
            reserved_output_tokens=320,
            model_context_window=4096,
        ),
    )


def run_benchmark(
    *,
    live_qwen: bool = False,
    max_workers: int = 4,
    local_config: Path | None = None,
    qwen_factory: Any = None,
    max_input_tokens: int = 1650,
) -> dict[str, Any]:
    """Run 16-case multi-turn benchmark comparing uncompressed_baseline vs compressed_budgeted."""
    cases = build_benchmark_cases()
    engine = create_benchmark_engine(max_input_tokens=max_input_tokens)
    budget_estimator = TokenBudgetManager(max_input_tokens=8192)

    prepared_jobs: list[dict[str, Any]] = []
    for case in cases:
        base_msgs, base_est_in = engine.build_uncompressed_baseline(
            system_policy=SYSTEM_POLICY,
            user_query=case.user_query,
            history=case.history,
            tool_results=case.tool_results,
            knowledge_items=case.knowledge_items,
        )
        comp_built = engine.build(
            system_policy=SYSTEM_POLICY,
            user_query=case.user_query,
            history=case.history,
            tool_results=case.tool_results,
            knowledge_items=case.knowledge_items,
        )
        prepared_jobs.append(
            {
                "case": case,
                "baseline_messages": base_msgs,
                "baseline_est_input_tokens": base_est_in,
                "compressed_built": comp_built,
            }
        )

    active_qwen_factory = None
    if live_qwen:
        active_qwen_factory = qwen_factory or _resolve_qwen_factory(local_config)

    def execute_model_call(messages: list[dict[str, str]], scene_label: str) -> dict[str, Any]:
        if not live_qwen:
            res = deterministic_stub_responder(messages)
            out_tokens = budget_estimator.estimate_text_tokens(res["reply_text"])
            return {
                "reply_text": res["reply_text"],
                "decision": res["decision"],
                "prompt_tokens": None,
                "completion_tokens": out_tokens,
                "latency_ms": 0.1,
            }

        client = active_qwen_factory()
        cfg = client.configuration()
        live_msgs = _build_live_qwen_messages(messages)
        payload = {
            "model": cfg["model"],
            "messages": live_msgs,
            "temperature": 0.0,
        }
        receipt: dict[str, Any] = {}
        asyncio.run(client.generate(payload, receipt, scene=scene_label))
        reply_text = str(receipt.get("response_text") or "")
        usage = receipt.get("usage") if isinstance(receipt.get("usage"), Mapping) else {}
        return {
            "reply_text": reply_text,
            "decision": _parse_live_decision(reply_text),
            "prompt_tokens": usage.get("prompt_tokens"),
            "completion_tokens": usage.get("completion_tokens")
            or budget_estimator.estimate_text_tokens(reply_text),
            "latency_ms": float(receipt.get("latency_ms") or 0.0),
        }

    started = time.perf_counter()
    call_specs: list[tuple[int, str, list[dict[str, str]]]] = []
    for idx, job in enumerate(prepared_jobs):
        call_specs.append((idx, "baseline", job["baseline_messages"]))
        call_specs.append((idx, "compressed", job["compressed_built"].messages))

    call_results: dict[tuple[int, str], dict[str, Any]] = {}
    if live_qwen:
        with ThreadPoolExecutor(max_workers=max(1, int(max_workers))) as executor:
            future_map = {
                executor.submit(execute_model_call, msgs, f"ctx_bench_{mode}"): (idx, mode)
                for idx, mode, msgs in call_specs
            }
            for future, key in future_map.items():
                call_results[key] = future.result()
    else:
        for idx, mode, msgs in call_specs:
            call_results[(idx, mode)] = execute_model_call(msgs, f"ctx_bench_{mode}")

    elapsed_ms = round((time.perf_counter() - started) * 1000, 2)

    case_reports: list[dict[str, Any]] = []
    base_in_sum = 0
    base_total_sum = 0
    base_cost_sum = 0.0
    base_resolved_hits = 0

    comp_in_sum = 0
    comp_total_sum = 0
    comp_cost_sum = 0.0
    comp_resolved_hits = 0
    slot_matched_sum = 0
    slot_total_sum = 0

    for idx, job in enumerate(prepared_jobs):
        case = job["case"]
        comp_built = job["compressed_built"]
        base_res = call_results[(idx, "baseline")]
        comp_res = call_results[(idx, "compressed")]

        base_in = int(base_res["prompt_tokens"] or job["baseline_est_input_tokens"])
        base_out = int(base_res["completion_tokens"])
        base_tot = base_in + base_out
        base_cost = _estimate_cost_cny(base_in, base_out)
        base_ok = _evaluate_task_resolution(base_res["decision"], case, base_res["reply_text"])

        comp_in = int(comp_res["prompt_tokens"] or comp_built.estimated_input_tokens)
        comp_out = int(comp_res["completion_tokens"])
        comp_tot = comp_in + comp_out
        comp_cost = _estimate_cost_cny(comp_in, comp_out)
        comp_ok = _evaluate_task_resolution(comp_res["decision"], case, comp_res["reply_text"])

        if comp_res["prompt_tokens"] is not None:
            engine.budget_manager.reconcile_provider_usage(
                comp_built.estimated_input_tokens,
                {
                    "prompt_tokens": comp_in,
                    "completion_tokens": comp_out,
                    "total_tokens": comp_tot,
                },
            )

        matched_slots, total_slots = _evaluate_sticky_slots(
            comp_built.sticky_slots,
            case.expected_slots,
        )

        base_in_sum += base_in
        base_total_sum += base_tot
        base_cost_sum += base_cost
        base_resolved_hits += int(base_ok)

        comp_in_sum += comp_in
        comp_total_sum += comp_tot
        comp_cost_sum += comp_cost
        comp_resolved_hits += int(comp_ok)
        slot_matched_sum += matched_slots
        slot_total_sum += total_slots

        case_reports.append(
            {
                "case_id": case.case_id,
                "scenario": case.scenario,
                "turn_count": len(case.history),
                "baseline_input_tokens": base_in,
                "baseline_total_tokens": base_tot,
                "compressed_input_tokens": comp_in,
                "compressed_total_tokens": comp_tot,
                "input_token_reduction_pct": round((base_in - comp_in) / base_in * 100.0, 2),
                "sticky_slots_matched": matched_slots,
                "sticky_slots_total": total_slots,
                "baseline_resolved": base_ok,
                "compressed_resolved": comp_ok,
                "budget_respected": comp_built.estimated_input_tokens <= comp_built.max_input_tokens,
            }
        )

    n = len(cases)
    input_reduction_pct = round((base_in_sum - comp_in_sum) / base_in_sum * 100.0, 2)
    total_reduction_pct = round((base_total_sum - comp_total_sum) / base_total_sum * 100.0, 2)
    cost_reduction_pct = round((base_cost_sum - comp_cost_sum) / base_cost_sum * 100.0, 2)
    sticky_retention_rate = round(slot_matched_sum / slot_total_sum, 4) if slot_total_sum else 1.0

    return {
        "benchmark": "supportflow_context_cost_benchmark",
        "mode": "live_qwen" if live_qwen else "deterministic_stub",
        "case_count": n,
        "turn_range": [
            min(len(c.history) for c in cases),
            max(len(c.history) for c in cases),
        ],
        "elapsed_ms": elapsed_ms,
        "uncompressed_baseline": {
            "avg_input_tokens": round(base_in_sum / n, 2),
            "avg_total_tokens": round(base_total_sum / n, 2),
            "total_estimated_cost_cny": round(base_cost_sum, 6),
            "task_resolution_accuracy": round(base_resolved_hits / n, 4),
        },
        "compressed_budgeted": {
            "max_input_tokens": max_input_tokens,
            "avg_input_tokens": round(comp_in_sum / n, 2),
            "avg_total_tokens": round(comp_total_sum / n, 2),
            "total_estimated_cost_cny": round(comp_cost_sum, 6),
            "sticky_slot_retention_rate": sticky_retention_rate,
            "sticky_slot_retention_pct": round(sticky_retention_rate * 100.0, 2),
            "task_resolution_accuracy": round(comp_resolved_hits / n, 4),
            "all_cases_within_budget": all(r["budget_respected"] for r in case_reports),
        },
        "comparison": {
            "input_token_reduction_pct": input_reduction_pct,
            "total_token_reduction_pct": total_reduction_pct,
            "estimated_cost_reduction_pct": cost_reduction_pct,
            "sticky_slot_retention_rate": sticky_retention_rate,
            "accuracy_delta": round((comp_resolved_hits - base_resolved_hits) / n, 4),
        },
        "cases": case_reports,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run SupportFlow multi-turn Context Cost Benchmark.")
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Optional path to write JSON benchmark report.",
    )
    parser.add_argument(
        "--live-qwen",
        action="store_true",
        help="Execute live Qwen evaluations via concurrent ThreadPoolExecutor.",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=4,
        help="Worker concurrency when --live-qwen is enabled (default: 4).",
    )
    parser.add_argument(
        "--local-config",
        type=Path,
        default=None,
        help="Path to local-services.env when --live-qwen is enabled.",
    )
    parser.add_argument(
        "--max-input-tokens",
        type=int,
        default=1650,
        help="ContextEngine max_input_tokens budget ceiling.",
    )
    args = parser.parse_args(argv)

    report = run_benchmark(
        live_qwen=args.live_qwen,
        max_workers=args.max_workers,
        local_config=args.local_config,
        max_input_tokens=args.max_input_tokens,
    )

    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    summary = {
        "mode": report["mode"],
        "case_count": report["case_count"],
        "baseline_avg_input_tokens": report["uncompressed_baseline"]["avg_input_tokens"],
        "compressed_avg_input_tokens": report["compressed_budgeted"]["avg_input_tokens"],
        "input_token_reduction_pct": report["comparison"]["input_token_reduction_pct"],
        "total_token_reduction_pct": report["comparison"]["total_token_reduction_pct"],
        "estimated_cost_reduction_pct": report["comparison"]["estimated_cost_reduction_pct"],
        "sticky_slot_retention_rate": report["compressed_budgeted"]["sticky_slot_retention_rate"],
        "task_resolution_accuracy": report["compressed_budgeted"]["task_resolution_accuracy"],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
