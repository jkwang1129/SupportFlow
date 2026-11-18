-- 企业知识库：文档解析、切块与向量索引（可重复执行）
--   1. 打开 pgvector 扩展；
--   2. 新增 kb_chunk：一个文档切出来的每一块，带向量；
--   3. 补两个索引：按文档取切片、按向量做相似度检索（HNSW 余弦）。
--
-- 为什么切片单独一张表：文档（kb_document）是"人看的"，切片（kb_chunk）是"检索用的"。
-- 一份文档切几十上百块很正常，放一张表里查询会互相拖累；分开之后，
-- 文档列表只查文档表，检索只打切片表 + 向量索引。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS "kb_chunk" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "chunk_no" INT NOT NULL,
  "content" TEXT NOT NULL,
  "char_count" INT NOT NULL DEFAULT 0,
  "token_count" INT NOT NULL DEFAULT 0,
  "embedding" vector(1024),
  "embedding_model" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "kb_chunk" IS '知识文档切片表（检索的最小单位）';
COMMENT ON COLUMN "kb_chunk"."doc_id" IS '所属文档ID';
COMMENT ON COLUMN "kb_chunk"."chunk_no" IS '文档内切片序号（从 1 开始）';
COMMENT ON COLUMN "kb_chunk"."content" IS '切片正文';
COMMENT ON COLUMN "kb_chunk"."char_count" IS '字符数（切块时统计，用于展示）';
COMMENT ON COLUMN "kb_chunk"."token_count" IS '估算 token 数（按 1 汉字≈1 token 粗估）';
COMMENT ON COLUMN "kb_chunk"."embedding" IS '向量（1024 维，千问 text-embedding-v3 默认维度）';
COMMENT ON COLUMN "kb_chunk"."embedding_model" IS '生成这个向量的模型（换模型要重建索引）';

-- 按文档取切片：切片预览、重建索引都要用
CREATE INDEX IF NOT EXISTS "idx_kb_chunk_tenant_doc"
    ON "kb_chunk" ("tenant_code", "doc_id", "chunk_no");

-- 向量检索：HNSW + 余弦距离（pgvector 0.5+ 才支持 HNSW）
CREATE INDEX IF NOT EXISTS "idx_kb_chunk_embedding"
    ON "kb_chunk" USING hnsw ("embedding" vector_cosine_ops)
    WHERE "embedding" IS NOT NULL;

-- 文档表补两列：切了多少块、最后索引进度（前端列表直接展示，不用再 count）
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "chunk_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "index_status" SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "index_message" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "file_name" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "file_size" BIGINT DEFAULT NULL;

COMMENT ON COLUMN "kb_document"."chunk_count" IS '切片数（向量化完成后回填）';
COMMENT ON COLUMN "kb_document"."index_status" IS '索引状态：1-未索引、2-索引中、3-已索引、4-索引失败';
COMMENT ON COLUMN "kb_document"."index_message" IS '索引失败原因 / 最近一次索引说明';
COMMENT ON COLUMN "kb_document"."file_name" IS '原始文件名（导入的文档）';
COMMENT ON COLUMN "kb_document"."file_size" IS '文件大小（字节）';

-- 知识库上传暴露的老问题：file_meta.mime_type 只有 varchar(64)，
-- 而 Office 文档的标准 MIME 比它长（docx 71、xlsx 65、pptx 73），
-- 一上传 Word 就报 "value too long for type character varying(64)"。
ALTER TABLE "file_meta" ALTER COLUMN "mime_type" TYPE VARCHAR(128);
COMMENT ON COLUMN "file_meta"."mime_type" IS '文件 MIME 类型（Office 文档的 MIME 比较长，留到 128）';
