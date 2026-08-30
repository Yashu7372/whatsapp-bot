CREATE TABLE document_evidence_snapshots (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    extractor_code VARCHAR(100) NOT NULL,
    extractor_version VARCHAR(100) NOT NULL,
    input_content_sha256 VARCHAR(64),
    evidence_json TEXT NOT NULL,
    created_by_reference VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_document_evidence_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_document_evidence_document FOREIGN KEY (document_id) REFERENCES documents(id),
    CONSTRAINT fk_document_evidence_revision FOREIGN KEY (revision_id) REFERENCES document_revisions(id),
    CONSTRAINT ck_document_evidence_sha256 CHECK (
        input_content_sha256 IS NULL OR LENGTH(input_content_sha256) = 64
    )
);

CREATE INDEX idx_document_evidence_revision_created
    ON document_evidence_snapshots (revision_id, created_at DESC);
CREATE INDEX idx_document_evidence_document_created
    ON document_evidence_snapshots (document_id, created_at DESC);
