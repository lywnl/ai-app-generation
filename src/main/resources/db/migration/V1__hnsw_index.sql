-- HNSW 向量索引(cosine 距离)
-- 说明:
--   LangChain4j PgVectorEmbeddingStore 会自动建表(包含 embedding 字段),
--   但默认不建或建 IVFFlat 索引。HNSW 在数据量 < 100 万时性能显著更优,
--   因此我们在 RagConfig 中设 useIndex=false,改由本脚本手动建 HNSW。
--
-- 执行时机:完成首次摄取(rag.ingest.enabled=true 启动一次)后,用 psql / DBeaver / Navicat 执行本脚本即可。
-- 注意:空表也可以建索引,后续摄取会自动维护。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE INDEX IF NOT EXISTS idx_templates_html_hnsw
  ON templates_html USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_templates_multi_hnsw
  ON templates_multi USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_templates_vue_hnsw
  ON templates_vue USING hnsw (embedding vector_cosine_ops);

-- 可选:数据量大时可调索引参数(默认 m=16, ef_construction=64 已够用)
-- CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)
--   WITH (m = 16, ef_construction = 64);

-- 验证索引是否建成
-- SELECT indexname, tablename FROM pg_indexes
--   WHERE tablename IN ('templates_html', 'templates_multi', 'templates_vue');
