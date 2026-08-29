package com.yashu.projectcontrol.commercial;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class CommercialRepository {

    private final JdbcTemplate jdbc;

    CommercialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ParticipantRow findParticipant(UUID projectId, UUID participantId) {
        return jdbc.query("""
                select pp.id,pp.project_id,pp.organization_id,pp.party_role,pp.status
                from project_participants pp
                where pp.id=? and pp.project_id=?
                """, (rs, n) -> new ParticipantRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("party_role"), rs.getString("status")),
                participantId, projectId).stream().findFirst().orElse(null);
    }

    ContractRow insertContract(
            UUID projectId,
            UUID payerParticipantId,
            UUID payeeParticipantId,
            String contractNumber,
            String contractType,
            String currency,
            BigDecimal originalValue,
            String visibilityPolicy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        update("""
                insert into contracts(
                    id,project_id,payer_participant_id,payee_participant_id,contract_number,contract_type,
                    currency,original_value,visibility_policy,status,version,created_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,'ACTIVE',0,?,?)
                """, id, projectId, payerParticipantId, payeeParticipantId, contractNumber, contractType,
                currency, originalValue, visibilityPolicy, now, now);
        return requireContract(id);
    }

    Optional<ContractRow> findContract(UUID contractId) {
        return jdbc.query("""
                select c.id,c.project_id,c.payer_participant_id,c.payee_participant_id,
                       payer.organization_id payer_organization_id,payee.organization_id payee_organization_id,
                       c.contract_number,c.contract_type,c.currency,c.original_value,c.visibility_policy,c.status,c.version
                from contracts c
                join project_participants payer on payer.id=c.payer_participant_id
                join project_participants payee on payee.id=c.payee_participant_id
                where c.id=?
                """, (rs, n) -> mapContract(rs), contractId).stream().findFirst();
    }

    ContractRow requireContract(UUID contractId) {
        return findContract(contractId).orElseThrow();
    }

    List<ContractRow> listContracts(UUID projectId) {
        return jdbc.query("""
                select c.id,c.project_id,c.payer_participant_id,c.payee_participant_id,
                       payer.organization_id payer_organization_id,payee.organization_id payee_organization_id,
                       c.contract_number,c.contract_type,c.currency,c.original_value,c.visibility_policy,c.status,c.version
                from contracts c
                join project_participants payer on payer.id=c.payer_participant_id
                join project_participants payee on payee.id=c.payee_participant_id
                where c.project_id=?
                order by c.contract_number
                """, (rs, n) -> mapContract(rs), projectId);
    }

    ItemRow insertItem(
            UUID contractId,
            UUID scopeId,
            String itemCode,
            String description,
            String valuationMethod,
            String unit,
            BigDecimal plannedQuantity,
            BigDecimal rate,
            BigDecimal contractValue,
            LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        update("""
                insert into contract_items(
                    id,contract_id,scope_id,item_code,description,valuation_method,unit,planned_quantity,rate,
                    contract_value,due_date,status,version,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',0,?)
                """, id, contractId, scopeId, itemCode, description, valuationMethod, unit,
                plannedQuantity, rate, contractValue, dueDate, Instant.now());
        return requireItem(id);
    }

    Optional<ItemRow> findItem(UUID itemId) {
        return jdbc.query("""
                select ci.id,ci.contract_id,c.project_id,ci.scope_id,ci.item_code,ci.description,ci.valuation_method,
                       ci.unit,ci.planned_quantity,ci.rate,ci.contract_value,ci.due_date,ci.status,ci.version
                from contract_items ci join contracts c on c.id=ci.contract_id where ci.id=?
                """, (rs, n) -> mapItem(rs), itemId).stream().findFirst();
    }

    ItemRow requireItem(UUID itemId) {
        return findItem(itemId).orElseThrow();
    }

    List<ItemRow> listItems(UUID contractId) {
        return jdbc.query("""
                select ci.id,ci.contract_id,c.project_id,ci.scope_id,ci.item_code,ci.description,ci.valuation_method,
                       ci.unit,ci.planned_quantity,ci.rate,ci.contract_value,ci.due_date,ci.status,ci.version
                from contract_items ci join contracts c on c.id=ci.contract_id
                where ci.contract_id=? order by ci.item_code
                """, (rs, n) -> mapItem(rs), contractId);
    }

    ValuationRow insertValuation(
            UUID projectId,
            UUID contractId,
            UUID scopeId,
            UUID itemId,
            String valuationNumber,
            String sourceType,
            String sourceReference,
            UUID sourceRevisionId,
            String unit,
            BigDecimal acceptedQuantity,
            BigDecimal rate,
            BigDecimal grossValue,
            BigDecimal priorValue,
            BigDecimal currentValue,
            BigDecimal cumulativeValue,
            BigDecimal retention,
            BigDecimal deductions,
            BigDecimal eligibleValue,
            UUID createdBy) {
        UUID id = UUID.randomUUID();
        update("""
                insert into valuation_lines(
                    id,project_id,contract_id,scope_id,contract_item_id,valuation_number,source_type,source_reference,
                    source_document_revision_id,unit,accepted_quantity,rate,gross_value,prior_value,current_value,
                    cumulative_value,retention,other_deductions,eligible_value,status,version,created_by_user_id,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'CLAIMABLE',0,?,?)
                """, id, projectId, contractId, scopeId, itemId, valuationNumber, sourceType, sourceReference,
                sourceRevisionId, unit, acceptedQuantity, rate, grossValue, priorValue, currentValue,
                cumulativeValue, retention, deductions, eligibleValue, createdBy, Instant.now());
        return requireValuation(id);
    }

    Optional<ValuationRow> findValuation(UUID valuationId) {
        return jdbc.query("""
                select id,project_id,contract_id,scope_id,contract_item_id,valuation_number,source_type,source_reference,
                       source_document_revision_id,unit,accepted_quantity,rate,gross_value,prior_value,current_value,
                       cumulative_value,retention,other_deductions,eligible_value,status,version
                from valuation_lines where id=?
                """, (rs, n) -> mapValuation(rs), valuationId).stream().findFirst();
    }

    ValuationRow requireValuation(UUID valuationId) {
        return findValuation(valuationId).orElseThrow();
    }

    List<ValuationRow> listValuations(UUID contractId) {
        return jdbc.query("""
                select id,project_id,contract_id,scope_id,contract_item_id,valuation_number,source_type,source_reference,
                       source_document_revision_id,unit,accepted_quantity,rate,gross_value,prior_value,current_value,
                       cumulative_value,retention,other_deductions,eligible_value,status,version
                from valuation_lines where contract_id=? order by created_at,id
                """, (rs, n) -> mapValuation(rs), contractId);
    }

    BigDecimal previouslyClaimedValue(UUID valuationId) {
        return amount("""
                select coalesce(sum(pal.claimed_value),0)
                from payment_application_lines pal
                join payment_applications pa on pa.id=pal.payment_application_id
                where pal.valuation_line_id=? and pa.status<>'VOID'
                """, valuationId);
    }

    PaymentApplicationRow insertApplication(
            UUID projectId,
            UUID contractId,
            String number,
            LocalDate periodFrom,
            LocalDate periodTo,
            LocalDate dueDate,
            UUID sourceRevisionId,
            UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        update("""
                insert into payment_applications(
                    id,project_id,contract_id,application_number,period_from,period_to,due_date,claimed_amount,
                    certified_amount,status,source_document_revision_id,version,created_by_user_id,created_at,updated_at)
                values(?,?,?,?,?,?,?,0,null,'DRAFT',?,0,?,?,?)
                """, id, projectId, contractId, number, periodFrom, periodTo, dueDate,
                sourceRevisionId, createdBy, now, now);
        return requireApplication(id);
    }

    Optional<PaymentApplicationRow> findApplication(UUID applicationId) {
        return jdbc.query("""
                select id,project_id,contract_id,application_number,period_from,period_to,due_date,claimed_amount,
                       certified_amount,status,submitted_by_user_id,certified_by_user_id,submitted_at,certified_at,
                       source_document_revision_id,version
                from payment_applications where id=?
                """, (rs, n) -> mapApplication(rs), applicationId).stream().findFirst();
    }

    PaymentApplicationRow requireApplication(UUID applicationId) {
        return findApplication(applicationId).orElseThrow();
    }

    ApplicationLineRow insertApplicationLine(
            UUID applicationId,
            UUID valuationId,
            BigDecimal claimedValue) {
        UUID id = UUID.randomUUID();
        update("""
                insert into payment_application_lines(id,payment_application_id,valuation_line_id,claimed_value)
                values(?,?,?,?)
                """, id, applicationId, valuationId, claimedValue);
        recalculateClaimed(applicationId);
        return new ApplicationLineRow(id, applicationId, valuationId, claimedValue, null, null);
    }

    List<ApplicationLineRow> listApplicationLines(UUID applicationId) {
        return jdbc.query("""
                select id,payment_application_id,valuation_line_id,claimed_value,certified_value,certification_reason
                from payment_application_lines where payment_application_id=? order by id
                """, (rs, n) -> new ApplicationLineRow(
                rs.getObject("id", UUID.class), rs.getObject("payment_application_id", UUID.class),
                rs.getObject("valuation_line_id", UUID.class), rs.getBigDecimal("claimed_value"),
                rs.getBigDecimal("certified_value"), rs.getString("certification_reason")), applicationId);
    }

    void recalculateClaimed(UUID applicationId) {
        update("""
                update payment_applications
                set claimed_amount=(select coalesce(sum(claimed_value),0) from payment_application_lines where payment_application_id=?),
                    updated_at=?
                where id=?
                """, applicationId, Instant.now(), applicationId);
    }

    int submitApplication(UUID applicationId, long expectedVersion, UUID actor) {
        Instant now = Instant.now();
        return update("""
                update payment_applications
                set status='SUBMITTED',submitted_by_user_id=?,submitted_at=?,version=version+1,updated_at=?
                where id=? and status='DRAFT' and version=?
                """, actor, now, now, applicationId, expectedVersion);
    }

    int certifyApplication(UUID applicationId, long expectedVersion, UUID actor) {
        Instant now = Instant.now();
        return update("""
                update payment_applications
                set status='CERTIFIED',
                    certified_amount=(select coalesce(sum(certified_value),0) from payment_application_lines where payment_application_id=?),
                    certified_by_user_id=?,certified_at=?,version=version+1,updated_at=?
                where id=? and status='SUBMITTED' and version=?
                """, applicationId, actor, now, now, applicationId, expectedVersion);
    }

    int setLineCertification(UUID applicationId, UUID valuationId, BigDecimal certifiedValue, String reason) {
        return update("""
                update payment_application_lines
                set certified_value=?,certification_reason=?
                where payment_application_id=? and valuation_line_id=? and certified_value is null
                """, certifiedValue, reason, applicationId, valuationId);
    }

    BigDecimal paidForApplication(UUID applicationId) {
        return amount("select coalesce(sum(amount),0) from payments where payment_application_id=? and status='PAID'", applicationId);
    }

    PaymentRow insertPayment(
            UUID projectId,
            UUID contractId,
            UUID applicationId,
            String reference,
            BigDecimal amount,
            String currency,
            Instant paidAt,
            UUID payerOrganizationId,
            UUID payeeOrganizationId,
            UUID sourceRevisionId,
            UUID actor) {
        UUID id = UUID.randomUUID();
        update("""
                insert into payments(
                    id,project_id,contract_id,payment_application_id,payment_reference,amount,currency,paid_at,
                    payer_organization_id,payee_organization_id,status,source_document_revision_id,version,
                    recorded_by_user_id,created_at)
                values(?,?,?,?,?,?,?,?,?,?,'PAID',?,0,?,?)
                """, id, projectId, contractId, applicationId, reference, amount, currency,
                paidAt, payerOrganizationId, payeeOrganizationId, sourceRevisionId, actor, Instant.now());
        return requirePayment(id);
    }

    Optional<PaymentRow> findPayment(UUID paymentId) {
        return jdbc.query("""
                select id,project_id,contract_id,payment_application_id,payment_reference,amount,currency,paid_at,
                       payer_organization_id,payee_organization_id,status,source_document_revision_id,version
                from payments where id=?
                """, (rs, n) -> mapPayment(rs), paymentId).stream().findFirst();
    }

    PaymentRow requirePayment(UUID paymentId) {
        return findPayment(paymentId).orElseThrow();
    }

    List<PaymentRow> listPayments(UUID contractId) {
        return jdbc.query("""
                select id,project_id,contract_id,payment_application_id,payment_reference,amount,currency,paid_at,
                       payer_organization_id,payee_organization_id,status,source_document_revision_id,version
                from payments where contract_id=? order by paid_at,id
                """, (rs, n) -> mapPayment(rs), contractId);
    }

    DocumentEvidenceRow findRevisionEvidence(UUID revisionId, UUID projectId) {
        if (revisionId == null) return null;
        return jdbc.query("""
                select dr.id revision_id,dr.document_id,dr.revision_code,dr.revision_status,dr.content_sha256,
                       d.document_number,d.title,d.primary_scope_id
                from document_revisions dr join documents d on d.id=dr.document_id
                where dr.id=? and dr.project_id=?
                """, (rs, n) -> new DocumentEvidenceRow(
                rs.getObject("revision_id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getString("document_number"), rs.getString("title"), rs.getString("revision_code"),
                rs.getString("revision_status"), rs.getString("content_sha256"),
                rs.getObject("primary_scope_id", UUID.class)), revisionId, projectId).stream().findFirst().orElse(null);
    }

    ContractSummaryRow contractSummary(UUID contractId) {
        return jdbc.query("""
                select c.id contract_id,c.original_value,
                       coalesce((select sum(vl.current_value) from valuation_lines vl where vl.contract_id=c.id and vl.status='CLAIMABLE'),0) valued_to_date,
                       coalesce((select sum(pa.claimed_amount) from payment_applications pa where pa.contract_id=c.id and pa.status in ('SUBMITTED','CERTIFIED')),0) claimed_to_date,
                       coalesce((select sum(pa.certified_amount) from payment_applications pa where pa.contract_id=c.id and pa.status='CERTIFIED'),0) certified_to_date,
                       coalesce((select sum(p.amount) from payments p where p.contract_id=c.id and p.status='PAID'),0) paid_to_date,
                       coalesce((select sum(vl.retention) from valuation_lines vl where vl.contract_id=c.id and vl.status='CLAIMABLE'),0) retention_to_date
                from contracts c where c.id=?
                """, (rs, n) -> {
            BigDecimal certified = rs.getBigDecimal("certified_to_date");
            BigDecimal paid = rs.getBigDecimal("paid_to_date");
            return new ContractSummaryRow(
                    rs.getObject("contract_id", UUID.class), rs.getBigDecimal("original_value"),
                    rs.getBigDecimal("valued_to_date"), rs.getBigDecimal("claimed_to_date"), certified, paid,
                    rs.getBigDecimal("retention_to_date"), certified.subtract(paid));
        }, contractId).stream().findFirst().orElse(null);
    }

    private int update(String sql, Object... args) {
        Object[] normalized = Arrays.stream(args)
                .map(arg -> arg instanceof Instant instant ? Timestamp.from(instant) : arg)
                .toArray();
        return jdbc.update(sql, normalized);
    }

    private BigDecimal amount(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static ContractRow mapContract(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ContractRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("payer_participant_id", UUID.class), rs.getObject("payee_participant_id", UUID.class),
                rs.getObject("payer_organization_id", UUID.class), rs.getObject("payee_organization_id", UUID.class),
                rs.getString("contract_number"), rs.getString("contract_type"), rs.getString("currency"),
                rs.getBigDecimal("original_value"), rs.getString("visibility_policy"),
                rs.getString("status"), rs.getLong("version"));
    }

    private static ItemRow mapItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemRow(
                rs.getObject("id", UUID.class), rs.getObject("contract_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("scope_id", UUID.class),
                rs.getString("item_code"), rs.getString("description"), rs.getString("valuation_method"),
                rs.getString("unit"), rs.getBigDecimal("planned_quantity"), rs.getBigDecimal("rate"),
                rs.getBigDecimal("contract_value"), date(rs, "due_date"), rs.getString("status"), rs.getLong("version"));
    }

    private static ValuationRow mapValuation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ValuationRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("contract_id", UUID.class), rs.getObject("scope_id", UUID.class),
                rs.getObject("contract_item_id", UUID.class), rs.getString("valuation_number"),
                rs.getString("source_type"), rs.getString("source_reference"),
                rs.getObject("source_document_revision_id", UUID.class), rs.getString("unit"),
                rs.getBigDecimal("accepted_quantity"), rs.getBigDecimal("rate"), rs.getBigDecimal("gross_value"),
                rs.getBigDecimal("prior_value"), rs.getBigDecimal("current_value"), rs.getBigDecimal("cumulative_value"),
                rs.getBigDecimal("retention"), rs.getBigDecimal("other_deductions"), rs.getBigDecimal("eligible_value"),
                rs.getString("status"), rs.getLong("version"));
    }

    private static PaymentApplicationRow mapApplication(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentApplicationRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("contract_id", UUID.class), rs.getString("application_number"),
                date(rs, "period_from"), date(rs, "period_to"), date(rs, "due_date"),
                rs.getBigDecimal("claimed_amount"), rs.getBigDecimal("certified_amount"), rs.getString("status"),
                rs.getObject("submitted_by_user_id", UUID.class), rs.getObject("certified_by_user_id", UUID.class),
                instant(rs, "submitted_at"), instant(rs, "certified_at"),
                rs.getObject("source_document_revision_id", UUID.class), rs.getLong("version"));
    }

    private static PaymentRow mapPayment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("contract_id", UUID.class), rs.getObject("payment_application_id", UUID.class),
                rs.getString("payment_reference"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getTimestamp("paid_at").toInstant(), rs.getObject("payer_organization_id", UUID.class),
                rs.getObject("payee_organization_id", UUID.class), rs.getString("status"),
                rs.getObject("source_document_revision_id", UUID.class), rs.getLong("version"));
    }

    private static LocalDate date(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record ParticipantRow(UUID id, UUID projectId, UUID organizationId, String partyRole, String status) {}
    record ContractRow(UUID id, UUID projectId, UUID payerParticipantId, UUID payeeParticipantId,
                       UUID payerOrganizationId, UUID payeeOrganizationId, String contractNumber,
                       String contractType, String currency, BigDecimal originalValue,
                       String visibilityPolicy, String status, long version) {}
    record ItemRow(UUID id, UUID contractId, UUID projectId, UUID scopeId, String itemCode,
                   String description, String valuationMethod, String unit, BigDecimal plannedQuantity,
                   BigDecimal rate, BigDecimal contractValue, LocalDate dueDate, String status, long version) {}
    record ValuationRow(UUID id, UUID projectId, UUID contractId, UUID scopeId, UUID contractItemId,
                        String valuationNumber, String sourceType, String sourceReference,
                        UUID sourceDocumentRevisionId, String unit, BigDecimal acceptedQuantity, BigDecimal rate,
                        BigDecimal grossValue, BigDecimal priorValue, BigDecimal currentValue,
                        BigDecimal cumulativeValue, BigDecimal retention, BigDecimal otherDeductions,
                        BigDecimal eligibleValue, String status, long version) {}
    record PaymentApplicationRow(UUID id, UUID projectId, UUID contractId, String applicationNumber,
                                 LocalDate periodFrom, LocalDate periodTo, LocalDate dueDate,
                                 BigDecimal claimedAmount, BigDecimal certifiedAmount, String status,
                                 UUID submittedBy, UUID certifiedBy, Instant submittedAt, Instant certifiedAt,
                                 UUID sourceDocumentRevisionId, long version) {}
    record ApplicationLineRow(UUID id, UUID paymentApplicationId, UUID valuationLineId,
                              BigDecimal claimedValue, BigDecimal certifiedValue, String certificationReason) {}
    record PaymentRow(UUID id, UUID projectId, UUID contractId, UUID paymentApplicationId,
                      String paymentReference, BigDecimal amount, String currency, Instant paidAt,
                      UUID payerOrganizationId, UUID payeeOrganizationId, String status,
                      UUID sourceDocumentRevisionId, long version) {}
    record DocumentEvidenceRow(UUID revisionId, UUID documentId, String documentNumber, String title,
                               String revisionCode, String revisionStatus, String contentSha256, UUID scopeId) {}
    record ContractSummaryRow(UUID contractId, BigDecimal originalValue, BigDecimal valuedToDate,
                              BigDecimal claimedToDate, BigDecimal certifiedToDate, BigDecimal paidToDate,
                              BigDecimal retentionToDate, BigDecimal outstandingCertified) {}
}
