# schema（运行期副本）

这里的脚本是仓库根目录 `schema/` 里 customer_db 增量脚本的**副本**，
用途只有一个：跟 jar 一起打包，供**手工/CI 的一次性迁移**执行
（`bash scripts/migrate-customer-db.sh`，或独立租户库升级时的
`TenantSchemaCatalog` 清单）。

- 权威版本在根目录 `schema/`，改脚本请改那边，然后同步到这里；
- 全部脚本都是"可重复执行"的，跑多次不会有副作用；
- **应用启动不执行这里的脚本**：customer-service 原来有个
  `config/SchemaGuard` 自检会在启动时自动补结构，现在改成一次性迁移后已移除
  （见 `legacy/removed-schema-guard/README.md`），应用账号因此不需要 DDL 权限。

## 别忘了同步副本

同步前先跑一下检查（不一致会退出码 1，并提示要拷哪些）：

```bash
bash scripts/check-schema-copies.sh
cp schema/customer_db_*.sql supportflow-customer-service/src/main/resources/schema/
```

忘了同步的后果很坑：迁移执行器里列了这个脚本，运行期却找不到文件，升级到一半停下，
最后在业务查询里炸成 `column "xxx" does not exist`——报错点离真正的原因（少拷一个文件）
隔着好几层调用栈。各功能的 `verify-*.py` 会校验"权威脚本 + 运行期副本 + 迁移执行器登记"三处齐全。

## 什么时候跑迁移

- 拉到新代码后、启动服务前：`bash scripts/migrate-customer-db.sh`；
- 启动日志里出现 `column "xxx" does not exist` / `relation "xxx" does not exist`：说明这个环境的库落后了。

> tenant-service 侧还保留了一个**只读**自检（`TenantSchemaGuard`）：租户库是按租户动态创建的，
> 一次全局迁移覆盖不到它们，所以启动时比对一下表/列、缺了就打印该跑哪条命令（不改库）。
