CREATE TABLE project_intelligence_collector_jobs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID,
    collector_code VARCHAR(120) NOT NULL,
    trigger_type VARCHAR(120) NOT NULL,
    subject_type VARCHAR(120) NOT NULL,
    subject_id UUID,
    trigger_key VARCHAR(240) NOT NULL,
    payload_json TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    claimed_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_intelligence_jobs_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_intelligence_jobs_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT uk_intelligence_job_trigger UNIQUE (project_id, collector_code, trigger_key),
    CONSTRAINT ck_intelligence_job_status CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'FAILED_FINAL')),
    CONSTRAINT ck_intelligence_job_attempt CHECK (attempt_count >= 0)
);

CREATE TABLE project_intelligence_feature_snapshots (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID,
    subject_type VARCHAR(120) NOT NULL,
    subject_id UUID,
    feature_code VARCHAR(160) NOT NULL,
    feature_version VARCHAR(80) NOT NULL,
    value_json TEXT NOT NULL,
    confidence NUMERIC(5,4) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_trigger_type VARCHAR(120) NOT NULL,
    source_job_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_intelligence_features_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_intelligence_features_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_intelligence_features_job FOREIGN KEY (source_job_id) REFERENCES project_intelligence_collector_jobs(id),
    CONSTRAINT uk_intelligence_feature_job_code UNIQUE (source_job_id, feature_code),
    CONSTRAINT ck_intelligence_feature_confidence CHECK (confidence >= 0 AND confidence <= 1)
);

CREATE TABLE project_intelligence_findings (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID,
    subject_type VARCHAR(120) NOT NULL,
    subject_id UUID,
    finding_code VARCHAR(160) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    finding_json TEXT NOT NULL,
    method_code VARCHAR(160) NOT NULL,
    method_version VARCHAR(80) NOT NULL,
    confidence NUMERIC(5,4) NOT NULL,
    source_job_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_intelligence_findings_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_intelligence_findings_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_intelligence_findings_job FOREIGN KEY (source_job_id) REFERENCES project_intelligence_collector_jobs(id),
    CONSTRAINT uk_intelligence_finding_job_code UNIQUE (source_job_id, finding_code),
    CONSTRAINT ck_intelligence_finding_severity CHECK (severity IN ('INFO', 'ATTENTION', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_intelligence_finding_confidence CHECK (confidence >= 0 AND confidence <= 1)
);

CREATE INDEX idx_intelligence_jobs_recovery
    ON project_intelligence_collector_jobs (status, next_attempt_at, claimed_at);
CREATE INDEX idx_intelligence_jobs_project_subject
    ON project_intelligence_collector_jobs (project_id, subject_type, subject_id, created_at);
CREATE INDEX idx_intelligence_features_project_scope_code
    ON project_intelligence_feature_snapshots (project_id, scope_id, feature_code, observed_at);
CREATE INDEX idx_intelligence_findings_project_scope_severity
    ON project_intelligence_findings (project_id, scope_id, severity, created_at);
