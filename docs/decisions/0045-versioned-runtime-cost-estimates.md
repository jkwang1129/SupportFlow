# ADR-0045 按显式价目表估算 Runtime 用量

运营可对单个任务的已知 Token 估算金额。复用同一受限查询和模型回执校验。调用方显式提供租户价目快照，按执行时已绑定的完整模型 Profile 摘要匹配费率。Chat 需要显式输入和输出费率，Embedding 使用输入费率。保持已有未加价目表的查询格式。

输入为至多 32 KiB 的 JSON，最多 64 项费率。费率以每百万 Token 的百万分之一货币单位表示，采用非负整数。结果以十进制定点字符串返回，包含价目版本、文件 SHA-256 和覆盖计数。分别保留已估算、已有用量但缺费率、缺少用量的调用次数。有明确零价时返回零，没有可计价样本时金额为 `null`。已知部分的估算与供应商账单仍需独立对账。

在已配置受限账号的 AI 服务目录执行：

```powershell
python -m ai.runtime.trace_export --tenant TENANT_A --run RUN_UUID --model-usage --price-book prices.json
```

合成价目示例。`rates` 的键应来自执行时的 `ChatProfile.definition_hash()` 或 `EmbeddingProfile.definition_hash()`，两者均排除 API 密钥。

```json
{
  "schema_version": "runtime-price-book-v1",
  "tenant": "TENANT_A",
  "version": "synthetic-price-v1",
  "currency": "CNY",
  "rates": {
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa": {
      "input_micros_per_million": 1000000,
      "output_micros_per_million": 2000000
    }
  }
}
```

该示例对应每百万输入 Token 1 元、每百万输出 Token 2 元的合成费率。运营提供与任务实际时段和合同相符的快照。当前入口适用于离线核查，折扣、缓存、分级费率和跨币种结算单独处理。回滚可停止使用 `--price-book` 或单独回退此批，无迁移。
