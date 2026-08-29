package com.yashu.projectcontrol.verification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class VerificationRepository {

    private final JdbcTemplate jdbc;

    VerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    PackageRow insertPackage(
            UUID projectId,
            UUID scopeId,
            String packageNumber,
            String subjectType,
            UUID submittingOrganizationId,
            UUID parentPackageId,
            UUID createdByUserId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        update("""
                insert into verification_packages(
                    id,project_id,scope_id,package_number,subject_type,submitting_organization_id,
                    created_by_user_id,status,parent_package_id,version,created_at,updated_at)
                values(?,?,?,?,?,?,?,'DRAFT',?,0,?,?)
                """, id, projectId, scopeId, packageNumber, subjectType, submittingOrganizationId,
                createdByUserId, parentPackageId, now, now);
        return requirePackage(id);
    }

    Optional<PackageRow> findPackage(UUID packageId) {
        return jdbc.query("""
                select id,project_id,scope_id,package_number,subject_type,submitting_organization_id,
                       created_by_user_id,submitted_by_user_id,status,submitted_at,completed_at,
                       parent_package_id,version,created_at,updated_at
                from verification_packages where id=?
                """, (rs, n) -> mapPackage(rs), packageId).stream().findFirst();
    }

    PackageRow requirePackage(UUID packageId) {
        return findPackage(packageId).orElseThrow();
    }

    List<PackageRow> listPackages(UUID projectId, UUID scopeId) {
        return jdbc.query("""
                select id,project_id,scope_id,package_number,subject_type,submitting_organization_id,
                       created_by_user_id,submitted_by_user_id,status,submitted_at,completed_at,
                       parent_package_id,version,created_at,updated_at
                from verification_packages
                where project_id=? and (? is null or scope_id=?)
                order by created_at,id
                """, (rs, n) -> mapPackage(rs), projectId, scopeId, scopeId);
    }

    int touchDraft(UUID packageId, long expectedVersion) {
        return update("""
                update verification_packages
                set version=version+1,updated_at=?
                where id=? and status='DRAFT' and version=?
                """, Instant.now(), packageId, expectedVersion);
    }

    ItemRow insertItem(
            UUID packageId,
            String subjectResourceReference,
            BigDecimal claimedProgress,
            BigDecimal claimedQuantity,
            String unit,
            String completionStatement) {
        UUID id = UUID.randomUUID();
        update("""
                insert into verification_items(
                    id,verification_package_id,subject_resource_reference,claimed_progress,
                    claimed_quantity,unit,completion_statement,created_at)
                values(?,?,?,?,?,?,?,?)
                """, id, packageId, subjectResourceReference, claimedProgress, claimedQuantity,
                unit, completionStatement, Instant.now());
        return requireItem(id);
    }

    Optional<ItemRow> findItem(UUID itemId) {
        return jdbc.query("""
                select id,verification_package_id,subject_resource_reference,claimed_progress,
                       claimed_quantity,unit,completion_statement,created_at
                from verification_items where id=?
                """, (rs, n) -> mapItem(rs), itemId).stream().findFirst();
    }

    ItemRow requireItem(UUID itemId) {
        return findItem(itemId).orElseThrow();
    }

    List<ItemRow> listItems(UUID packageId) {
        return jdbc.query("""
                select id,verification_package_id,subject_resource_reference,claimed_progress,
                       claimed_quantity,unit,completion_statement,created_at
                from verification_items where verification_package_id=? order by created_at,id
                """, (rs, n) -> mapItem(rs), packageId);
    }

    EvidenceRevisionRow findRevision(UUID projectId, UUID revisionId) {
        if (revisionId == null) return null;
        return jdbc.query("""
                select dr.id revision_id,dr.document_id,dr.project_id,dr.revision_code,dr.revision_status,
                       dr.content_sha256,d.document_number,d.title,d.primary_scope_id
                from document_revisions dr
                join documents d on d.id=dr.document_id
                where dr.id=? and dr.project_id=?
                """, (rs, n) -> new EvidenceRevisionRow(
                rs.getObject("revision_id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("primary_scope_id", UUID.class),
                rs.getString("document_number"), rs.getString("title"), rs.getString("revision_code"),
                rs.getString("revision_status"), rs.getString("content_sha256")), revisionId, projectId)
                .stream().findFirst().orElse(null);
    }

    EvidenceRow insertEvidence(
            UUID packageId,
            UUID documentRevisionId,
            String evidenceType,
            String visibilityScope,
            boolean required) {
        UUID id = UUID.randomUUID();
        update("""
                insert into verification_evidence(
                    id,verification_package_id,document_revision_id,evidence_type,visibility_scope,required_flag,created_at)
                values(?,?,?,?,?,?,?)
                """, id, packageId, documentRevisionId, evidenceType, visibilityScope, required, Instant.now());
        return requireEvidence(id);
    }

    EvidenceRow requireEvidence(UUID evidenceId) {
        return jdbc.query("""
                select ve.id,ve.verification_package_id,ve.document_revision_id,ve.evidence_type,
                       ve.visibility_scope,ve.required_flag,ve.created_at,
                       dr.document_id,dr.revision_code,dr.revision_status,dr.content_sha256,
                       d.document_number,d.title,d.primary_scope_id
                from verification_evidence ve
                join document_revisions dr on dr.id=ve.document_revision_id
                join documents d on d.id=dr.document_id
                where ve.id=?
                """, (rs, n) -> mapEvidence(rs), evidenceId).stream().findFirst().orElseThrow();
    }

    List<EvidenceRow> listEvidence(UUID packageId) {
        return jdbc.query("""
                select ve.id,ve.verification_package_id,ve.document_revision_id,ve.evidence_type,
                       ve.visibility_scope,ve.required_flag,ve.created_at,
                       dr.document_id,dr.revision_code,dr.revision_status,dr.content_sha256,
                       d.document_number,d.title,d.primary_scope_id
                from verification_evidence ve
                join document_revisions dr on dr.id=ve.document_revision_id
                join documents d on d.id=dr.document_id
                where ve.verification_package_id=? order by ve.created_at,ve.id
                """, (rs, n) -> mapEvidence(rs), packageId);
    }

    int submitPackage(UUID packageId, long expectedVersion, UUID actorUserId) {
        Instant now = Instant.now();
        return update("""
                update verification_packages
                set status='SUBMITTED',submitted_by_user_id=?,submitted_at=?,version=version+1,updated_at=?
                where id=? and status='DRAFT' and version=?
                """, actorUserId, now, now, packageId, expectedVersion);
    }

    void insertWorkflowLink(UUID packageId, UUID workflowInstanceId) {
        update("""
                insert into verification_workflow_instances(id,verification_package_id,workflow_instance_id,created_at)
                values(?,?,?,?)
                """, UUID.randomUUID(), packageId, workflowInstanceId, Instant.now());
    }

    UUID workflowInstanceId(UUID packageId) {
        return jdbc.query("""
                select workflow_instance_id from verification_workflow_instances
                where verification_package_id=? order by created_at desc
                """, (rs, n) -> rs.getObject("workflow_instance_id", UUID.class), packageId)
                .stream().findFirst().orElse(null);
    }

    DecisionRow insertDecision(
            UUID packageId,
            UUID itemId,
            UUID actorUserId,
            UUID actorOrganizationId,
            UUID workflowInstanceId,
            String decision,
            BigDecimal acceptedQuantity,
            BigDecimal rejectedQuantity,
            String unit,
            String comments,
            UUID priorDecisionId,
            long subjectVersion) {
        UUID id = UUID.randomUUID();
        update("""
                insert into verification_decisions(
                    id,verification_package_id,verification_item_id,actor_user_id,actor_organization_id,
                    workflow_instance_id,decision,accepted_quantity,rejected_quantity,unit,comments,
                    decided_at,prior_decision_id,subject_version)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, packageId, itemId, actorUserId, actorOrganizationId, workflowInstanceId,
                decision, acceptedQuantity, rejectedQuantity, unit, comments, Instant.now(), priorDecisionId,
                subjectVersion);
        return requireDecision(id);
    }

    Optional<DecisionRow> findDecision(UUID decisionId) {
        return jdbc.query("""
                select id,verification_package_id,verification_item_id,actor_user_id,actor_organization_id,
                       workflow_instance_id,decision,accepted_quantity,rejected_quantity,unit,comments,
                       decided_at,prior_decision_id,subject_version
                from verification_decisions where id=?
                """, (rs, n) -> mapDecision(rs), decisionId).stream().findFirst();
    }

    DecisionRow requireDecision(UUID decisionId) {
        return findDecision(decisionId).orElseThrow();
    }

    DecisionRow latestItemDecision(UUID packageId, UUID itemId) {
        return jdbc.query("""
                select id,verification_package_id,verification_item_id,actor_user_id,actor_organization_id,
                       workflow_instance_id,decision,accepted_quantity,rejected_quantity,unit,comments,
                       decided_at,prior_decision_id,subject_version
                from verification_decisions
                where verification_package_id=? and verification_item_id=?
                order by decided_at desc,id desc
                """, (rs, n) -> mapDecision(rs), packageId, itemId).stream().findFirst().orElse(null);
    }

    List<DecisionRow> listDecisions(UUID packageId) {
        return jdbc.query("""
                select id,verification_package_id,verification_item_id,actor_user_id,actor_organization_id,
                       workflow_instance_id,decision,accepted_quantity,rejected_quantity,unit,comments,
                       decided_at,prior_decision_id,subject_version
                from verification_decisions where verification_package_id=? order by decided_at,id
                """, (rs, n) -> mapDecision(rs), packageId);
    }

    int touchSubmittedPackage(UUID packageId, long expectedVersion) {
        return update("""
                update verification_packages set version=version+1,updated_at=?
                where id=? and status='SUBMITTED' and version=?
                """, Instant.now(), packageId, expectedVersion);
    }

    int completePackage(UUID packageId, long expectedVersion, String status) {
        Instant now = Instant.now();
        return update("""
                update verification_packages
                set status=?,completed_at=?,version=version+1,updated_at=?
                where id=? and status='SUBMITTED' and version=?
                """, status, now, now, packageId, expectedVersion);
    }

    MeasurementRow insertMeasurement(
            UUID projectId,
            UUID scopeId,
            String subjectResourceReference,
            UUID packageId,
            UUID itemId,
            UUID decisionId,
            String unit,
            LocalDate periodFrom,
            LocalDate periodTo,
            BigDecimal submittedQuantity,
            BigDecimal measuredQuantity,
            BigDecimal acceptedQuantity,
            BigDecimal rejectedQuantity,
            String status,
            UUID verifiedByUserId,
            Instant verifiedAt) {
        UUID id = UUID.randomUUID();
        update("""
                insert into measurements(
                    id,project_id,scope_id,subject_resource_reference,verification_package_id,
                    verification_item_id,verification_decision_id,unit,period_from,period_to,
                    submitted_quantity,measured_quantity,accepted_quantity,rejected_quantity,
                    status,verified_by_user_id,verified_at,version,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)
                """, id, projectId, scopeId, subjectResourceReference, packageId, itemId, decisionId,
                unit, periodFrom, periodTo, submittedQuantity, measuredQuantity, acceptedQuantity,
                rejectedQuantity, status, verifiedByUserId, verifiedAt, Instant.now());
        return requireMeasurement(id);
    }

    Optional<MeasurementRow> findMeasurement(UUID measurementId) {
        return jdbc.query("""
                select id,project_id,scope_id,subject_resource_reference,verification_package_id,
                       verification_item_id,verification_decision_id,unit,period_from,period_to,
                       submitted_quantity,measured_quantity,accepted_quantity,rejected_quantity,status,
                       verified_by_user_id,verified_at,version,created_at
                from measurements where id=?
                """, (rs, n) -> mapMeasurement(rs), measurementId).stream().findFirst();
    }

    MeasurementRow requireMeasurement(UUID measurementId) {
        return findMeasurement(measurementId).orElseThrow();
    }

    List<MeasurementRow> listMeasurements(UUID projectId, UUID scopeId) {
        return jdbc.query("""
                select id,project_id,scope_id,subject_resource_reference,verification_package_id,
                       verification_item_id,verification_decision_id,unit,period_from,period_to,
                       submitted_quantity,measured_quantity,accepted_quantity,rejected_quantity,status,
                       verified_by_user_id,verified_at,version,created_at
                from measurements where project_id=? and (? is null or scope_id=?)
                order by verified_at,id
                """, (rs, n) -> mapMeasurement(rs), projectId, scopeId, scopeId);
    }

    private int update(String sql, Object... args) {
        Object[] normalized = Arrays.stream(args)
                .map(value -> value instanceof Instant instant ? Timestamp.from(instant) : value)
                .toArray();
        return jdbc.update(sql, normalized);
    }

    private static PackageRow mapPackage(ResultSet rs) throws SQLException {
        Timestamp submitted = rs.getTimestamp("submitted_at");
        Timestamp completed = rs.getTimestamp("completed_at");
        return new PackageRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("scope_id", UUID.class), rs.getString("package_number"),
                rs.getString("subject_type"), rs.getObject("submitting_organization_id", UUID.class),
                rs.getObject("created_by_user_id", UUID.class), rs.getObject("submitted_by_user_id", UUID.class),
                rs.getString("status"), submitted == null ? null : submitted.toInstant(),
                completed == null ? null : completed.toInstant(), rs.getObject("parent_package_id", UUID.class),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ItemRow mapItem(ResultSet rs) throws SQLException {
        return new ItemRow(
                rs.getObject("id", UUID.class), rs.getObject("verification_package_id", UUID.class),
                rs.getString("subject_resource_reference"), rs.getBigDecimal("claimed_progress"),
                rs.getBigDecimal("claimed_quantity"), rs.getString("unit"),
                rs.getString("completion_statement"), rs.getTimestamp("created_at").toInstant());
    }

    private static EvidenceRow mapEvidence(ResultSet rs) throws SQLException {
        return new EvidenceRow(
                rs.getObject("id", UUID.class), rs.getObject("verification_package_id", UUID.class),
                rs.getObject("document_revision_id", UUID.class), rs.getString("evidence_type"),
                rs.getString("visibility_scope"), rs.getBoolean("required_flag"),
                rs.getObject("document_id", UUID.class), rs.getString("document_number"), rs.getString("title"),
                rs.getString("revision_code"), rs.getString("revision_status"), rs.getString("content_sha256"),
                rs.getObject("primary_scope_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    }

    private static DecisionRow mapDecision(ResultSet rs) throws SQLException {
        return new DecisionRow(
                rs.getObject("id", UUID.class), rs.getObject("verification_package_id", UUID.class),
                rs.getObject("verification_item_id", UUID.class), rs.getObject("actor_user_id", UUID.class),
                rs.getObject("actor_organization_id", UUID.class), rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("decision"), rs.getBigDecimal("accepted_quantity"),
                rs.getBigDecimal("rejected_quantity"), rs.getString("unit"), rs.getString("comments"),
                rs.getTimestamp("decided_at").toInstant(), rs.getObject("prior_decision_id", UUID.class),
                rs.getLong("subject_version"));
    }

    private static MeasurementRow mapMeasurement(ResultSet rs) throws SQLException {
        return new MeasurementRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("scope_id", UUID.class), rs.getString("subject_resource_reference"),
                rs.getObject("verification_package_id", UUID.class),
                rs.getObject("verification_item_id", UUID.class),
                rs.getObject("verification_decision_id", UUID.class), rs.getString("unit"),
                rs.getObject("period_from", LocalDate.class), rs.getObject("period_to", LocalDate.class),
                rs.getBigDecimal("submitted_quantity"), rs.getBigDecimal("measured_quantity"),
                rs.getBigDecimal("accepted_quantity"), rs.getBigDecimal("rejected_quantity"),
                rs.getString("status"), rs.getObject("verified_by_user_id", UUID.class),
                rs.getTimestamp("verified_at").toInstant(), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant());
    }

    record PackageRow(
            UUID id, UUID projectId, UUID scopeId, String packageNumber, String subjectType,
            UUID submittingOrganizationId, UUID createdByUserId, UUID submittedByUserId,
            String status, Instant submittedAt, Instant completedAt, UUID parentPackageId,
            long version, Instant createdAt, Instant updatedAt) {}

    record ItemRow(
            UUID id, UUID packageId, String subjectResourceReference, BigDecimal claimedProgress,
            BigDecimal claimedQuantity, String unit, String completionStatement, Instant createdAt) {}

    record EvidenceRevisionRow(
            UUID revisionId, UUID documentId, UUID projectId, UUID scopeId, String documentNumber,
            String title, String revisionCode, String revisionStatus, String contentSha256) {}

    record EvidenceRow(
            UUID id, UUID packageId, UUID documentRevisionId, String evidenceType,
            String visibilityScope, boolean required, UUID documentId, String documentNumber,
            String title, String revisionCode, String revisionStatus, String contentSha256,
            UUID scopeId, Instant createdAt) {}

    record DecisionRow(
            UUID id, UUID packageId, UUID itemId, UUID actorUserId, UUID actorOrganizationId,
            UUID workflowInstanceId, String decision, BigDecimal acceptedQuantity,
            BigDecimal rejectedQuantity, String unit, String comments, Instant decidedAt,
            UUID priorDecisionId, long subjectVersion) {}

    record MeasurementRow(
            UUID id, UUID projectId, UUID scopeId, String subjectResourceReference,
            UUID packageId, UUID itemId, UUID decisionId, String unit,
            LocalDate periodFrom, LocalDate periodTo, BigDecimal submittedQuantity,
            BigDecimal measuredQuantity, BigDecimal acceptedQuantity, BigDecimal rejectedQuantity,
            String status, UUID verifiedByUserId, Instant verifiedAt, long version, Instant createdAt) {}
}
