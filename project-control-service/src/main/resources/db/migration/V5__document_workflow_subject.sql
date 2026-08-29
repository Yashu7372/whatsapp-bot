CREATE TABLE document_workflow_instances (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_document_workflow_document FOREIGN KEY (document_id) REFERENCES documents(id),
    CONSTRAINT fk_document_workflow_instance FOREIGN KEY (workflow_instance_id) REFERENCES workflow_instances(id),
    CONSTRAINT uk_document_workflow_instance UNIQUE (workflow_instance_id),
    CONSTRAINT uk_document_workflow_pair UNIQUE (document_id, workflow_instance_id)
);

CREATE INDEX idx_document_workflow_document ON document_workflow_instances (document_id, created_at);
