-- ---------------------------------------------------------------------------
-- customer_db 增量脚本 · 快捷话术（工作台 Alt+1~9）
--
-- quick_reply 这张表在基线 customer_db.sql 里就有（表结构一直没人用），
-- 这里只补一列"用过多少次"：话术按使用频率排序，坐席最常发的自然浮到 Alt+1。
-- ---------------------------------------------------------------------------

ALTER TABLE "quick_reply" ADD COLUMN IF NOT EXISTS "use_count" INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN "quick_reply"."use_count" IS '被坐席使用次数（发送时累加，列表按它排序）';

-- 工作台每次打开都要查"这个租户启用中的话术"，索引按这个查询走
CREATE INDEX IF NOT EXISTS "idx_quick_reply_tenant_enabled"
    ON "quick_reply" ("tenant_code", "is_enabled", "sort_no");
