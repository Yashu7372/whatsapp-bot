CREATE TABLE document_intelligence (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    version_num INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    result_json TEXT,
    error_message TEXT,
    analyzed_by UUID REFERENCES tenant_users(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT uq_document_intelligence_version UNIQUE (tenant_id, document_id, version_num)
);

CREATE INDEX idx_document_intelligence_document
    ON document_intelligence (tenant_id, document_id, version_num DESC);
