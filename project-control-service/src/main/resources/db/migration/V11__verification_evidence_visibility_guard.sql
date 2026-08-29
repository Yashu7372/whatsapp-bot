-- Verification packages can be consumed by project-scope reads and by cross-organization
-- commercial provenance. Until a typed audience/contract relation exists for verification
-- evidence, only project-shareable evidence is safe to expose through those paths.
-- Narrower visibility classes must fail closed instead of being accidentally widened.
ALTER TABLE verification_evidence
    ADD CONSTRAINT ck_verification_evidence_visibility_guard
    CHECK (visibility_scope IN ('PROJECT_SHARED', 'PUBLIC_WITHIN_PROJECT'));
