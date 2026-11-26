"""SupportFlow Durable Agent Runtime package.

Submodules are organized across five logical layers (see README.md):
1. Execution Kernel: types, store, payloads, payload_retention, worker
2. Controlled Tooling & MCP: tools, source, customer_tools, knowledge, knowledge_tools, tickets, mcp_ticket, ticket_creation, ticket_write_tool
3. HITL & Reconciliation: authority, approvals, reply, result, publication, reconciliation
4. Bounded Agent Loop: ticket_prompts, ticket_loop, ticket_worker
5. Observability & LLMOps: models, embeddings, model_usage, usage_pricing, trace_export, trace_timeline, trace_replay, ticket_replay, metrics
"""
