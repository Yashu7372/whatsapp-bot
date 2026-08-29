-- Verification submission creates the generic WorkflowInstance through JPA and the typed
-- verification link through JDBC in the same transaction. Keep the authoritative FK,
-- but validate it at commit after the JPA persistence context has flushed.
ALTER TABLE verification_workflow_instances
    DROP CONSTRAINT fk_verification_workflow_instance;

ALTER TABLE verification_workflow_instances
    ADD CONSTRAINT fk_verification_workflow_instance
    FOREIGN KEY (workflow_instance_id)
    REFERENCES workflow_instances(id)
    DEFERRABLE INITIALLY DEFERRED;
