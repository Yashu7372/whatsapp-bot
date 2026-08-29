CREATE TABLE document_number_series (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    document_type VARCHAR(100) NOT NULL,
    prefix VARCHAR(120) NOT NULL,
    separator VARCHAR(5) NOT NULL,
    next_number INTEGER NOT NULL,
    padding INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_document_number_series_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT uk_document_number_series_project_type UNIQUE (project_id, document_type),
    CONSTRAINT ck_document_number_series_next CHECK (next_number >= 1),
    CONSTRAINT ck_document_number_series_padding CHECK (padding BETWEEN 1 AND 12)
);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    primary_scope_id UUID,
    originator_organization_id UUID,
    document_number VARCHAR(180) NOT NULL,
    number_source VARCHAR(32) NOT NULL,
    document_type VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    discipline VARCHAR(80),
    package_code VARCHAR(80),
    location_code VARCHAR(80),
    issue_purpose VARCHAR(80),
    classification_code VARCHAR(80),
    status VARCHAR(32) NOT NULL,
    current_revision_sequence INTEGER NOT NULL,
    current_revision_code VARCHAR(40),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_documents_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_documents_primary_scope FOREIGN KEY (primary_scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_documents_originator_org FOREIGN KEY (originator_organization_id) REFERENCES organizations(id),
    CONSTRAINT uk_documents_project_number UNIQUE (project_id, document_number),
    CONSTRAINT ck_documents_revision_sequence CHECK (current_revision_sequence >= 0)
);

CREATE TABLE document_revisions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    project_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    revision_code VARCHAR(40) NOT NULL,
    revision_status VARCHAR(32) NOT NULL,
    change_notes TEXT,
    content_uri VARCHAR(1000),
    content_sha256 VARCHAR(64),
    original_filename VARCHAR(500),
    media_type VARCHAR(160),
    size_bytes BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_document_revisions_document FOREIGN KEY (document_id) REFERENCES documents(id),
    CONSTRAINT fk_document_revisions_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT uk_document_revisions_sequence UNIQUE (document_id, sequence_number),
    CONSTRAINT uk_document_revisions_code UNIQUE (document_id, revision_code),
    CONSTRAINT ck_document_revisions_sequence CHECK (sequence_number >= 1),
    CONSTRAINT ck_document_revisions_size CHECK (size_bytes IS NULL OR size_bytes >= 0)
);

CREATE TABLE document_links (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID,
    relationship_type VARCHAR(80) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id UUID NOT NULL,
    target_reference VARCHAR(240),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_document_links_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_document_links_document FOREIGN KEY (document_id) REFERENCES documents(id),
    CONSTRAINT fk_document_links_revision FOREIGN KEY (revision_id) REFERENCES document_revisions(id)
);

CREATE INDEX idx_documents_project_type ON documents (project_id, document_type);
CREATE INDEX idx_documents_scope ON documents (primary_scope_id);
CREATE INDEX idx_documents_originator_org ON documents (originator_organization_id);
CREATE INDEX idx_document_revisions_document ON document_revisions (document_id, sequence_number);
CREATE INDEX idx_document_links_document ON document_links (document_id);
CREATE INDEX idx_document_links_target ON document_links (target_type, target_id);
