-- runs automatically on first Postgres container boot

-- 1. Enable pgvector
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Runbook embeddings (RAG source)
--    1536 dims = OpenAI ada-002 / Cohere; adjust if you switch models
--    Using 1024 here for Voyage AI or Cohere embed-english-v3 (common picks)
CREATE TABLE IF NOT EXISTS runbook_chunks (
    id          BIGSERIAL PRIMARY KEY,
    source      TEXT        NOT NULL,          -- filename / doc title
    chunk_index INT         NOT NULL,          -- position within the doc
    content     TEXT        NOT NULL,          -- raw text sent to the LLM
    embedding   vector(1024),                  -- pgvector column
    created_at  TIMESTAMPTZ DEFAULT now()
);

-- IVFFlat index: fast ANN search; lists=100 is a rule-of-thumb for <1M rows
-- IMPORTANT: index must be created AFTER data is loaded (Week 2 task)
-- CREATE INDEX ON runbook_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 3. RCA sessions
CREATE TABLE IF NOT EXISTS rca_sessions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_payload   JSONB       NOT NULL,      -- raw incoming alert
    rca_output      JSONB,                     -- structured LLM response
    status          TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING | COMPLETE | REVIEWED
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

-- 4. Human feedback (the feedback loop)
CREATE TABLE IF NOT EXISTS rca_feedback (
    id          BIGSERIAL   PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES rca_sessions(id) ON DELETE CASCADE,
    rating      SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     TEXT,
    created_at  TIMESTAMPTZ DEFAULT now()
);

-- index for fast lookup of feedback by session
CREATE INDEX IF NOT EXISTS idx_feedback_session ON rca_feedback(session_id);
