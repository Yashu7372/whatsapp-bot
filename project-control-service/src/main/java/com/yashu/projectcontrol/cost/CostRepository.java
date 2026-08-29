package com.yashu.projectcontrol.cost;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class CostRepository {

    private final JdbcTemplate jdbc;

    CostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    StructureRow insertStructure(
            UUID projectId,
            UUID owningOrganizationId,
            UUID contractId,
            String code,
            String name,
            String structureType) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into cost_structures(
                    id,project_id,owning_organization_id,contract_id,code,name,structure_type,
                    status,version,created_at,updated_at)
                values(?,?,?,?,?,?,?,'ACTIVE',0,?,?)
                """, id, projectId, owningOrganizationId, contractId, code, name, structureType, now, now);
        return requireStructure(id);
    }

    Optional<StructureRow> findStructure(UUID id) {
        return jdbc.query("""
                select id,project_id,owning_organization_id,contract_id,code,name,structure_type,status,version
                from cost_structures where id=?
                """, (rs, n) -> new StructureRow(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("owning_organization_id", UUID.class),
                rs.getObject("contract_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("structure_type"),
                rs.getString("status"), rs.getLong("version")), id).stream().findFirst();
    }

    StructureRow requireStructure(UUID id) {
        return findStructure(id).orElseThrow();
    }

    List<StructureRow> listStructures(UUID projectId, UUID owningOrganizationId) {
        if (owningOrganizationId == null) {
            return jdbc.query("""
                    select id,project_id,owning_organization_id,contract_id,code,name,structure_type,status,version
                    from cost_structures
                    where project_id=? and owning_organization_id is null
                    order by code
                    """, (rs, n) -> mapStructure(rs), projectId);
        }
        return jdbc.query("""
                select id,project_id,owning_organization_id,contract_id,code,name,structure_type,status,version
                from cost_structures
                where project_id=? and owning_organization_id=?
                order by code
                """, (rs, n) -> mapStructure(rs), projectId, owningOrganizationId);
    }

    NodeRow insertNode(
            UUID structureId,
            UUID parentNodeId,
            String code,
            String name,
            String category,
            int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cost_nodes(
                    id,cost_structure_id,parent_cost_node_id,code,name,category,sort_order,status,created_at)
                values(?,?,?,?,?,?,?,'ACTIVE',?)
                """, id, structureId, parentNodeId, code, name, category, sortOrder, Instant.now());
        return requireNode(id);
    }

    Optional<NodeRow> findNode(UUID nodeId) {
        return jdbc.query("""
                select n.id,n.cost_structure_id,n.parent_cost_node_id,n.code,n.name,n.category,n.sort_order,n.status,
                       s.project_id,s.owning_organization_id
                from cost_nodes n
                join cost_structures s on s.id=n.cost_structure_id
                where n.id=?
                """, (rs, n) -> new NodeRow(
                rs.getObject("id", UUID.class), rs.getObject("cost_structure_id", UUID.class),
                rs.getObject("parent_cost_node_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("category"), rs.getInt("sort_order"), rs.getString("status"),
                rs.getObject("project_id", UUID.class), rs.getObject("owning_organization_id", UUID.class)), nodeId)
                .stream().findFirst();
    }

    NodeRow requireNode(UUID nodeId) {
        return findNode(nodeId).orElseThrow();
    }

    List<NodeRow> listNodes(UUID structureId) {
        return jdbc.query("""
                select n.id,n.cost_structure_id,n.parent_cost_node_id,n.code,n.name,n.category,n.sort_order,n.status,
                       s.project_id,s.owning_organization_id
                from cost_nodes n
                join cost_structures s on s.id=n.cost_structure_id
                where n.cost_structure_id=?
                order by n.sort_order,n.code
                """, (rs, n) -> new NodeRow(
                rs.getObject("id", UUID.class), rs.getObject("cost_structure_id", UUID.class),
                rs.getObject("parent_cost_node_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("category"), rs.getInt("sort_order"), rs.getString("status"),
                rs.getObject("project_id", UUID.class), rs.getObject("owning_organization_id", UUID.class)), structureId);
    }

    ScopeLinkRow insertScopeLink(UUID nodeId, UUID scopeId, BigDecimal allocationPercent, String relationshipType) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cost_node_scope_links(id,cost_node_id,scope_id,allocation_percent,relationship_type,created_at)
                values(?,?,?,?,?,?)
                """, id, nodeId, scopeId, allocationPercent, relationshipType, Instant.now());
        return new ScopeLinkRow(id, nodeId, scopeId, allocationPercent, relationshipType);
    }

    List<ScopeLinkRow> listScopeLinks(UUID nodeId) {
        return jdbc.query("""
                select id,cost_node_id,scope_id,allocation_percent,relationship_type
                from cost_node_scope_links where cost_node_id=? order by relationship_type,scope_id
                """, (rs, n) -> new ScopeLinkRow(
                rs.getObject("id", UUID.class), rs.getObject("cost_node_id", UUID.class),
                rs.getObject("scope_id", UUID.class), rs.getBigDecimal("allocation_percent"),
                rs.getString("relationship_type")), nodeId);
    }

    BigDecimal allocatedPercent(UUID nodeId) {
        return amount("""
                select coalesce(sum(allocation_percent),0)
                from cost_node_scope_links
                where cost_node_id=? and relationship_type='ALLOCATION'
                """, nodeId);
    }

    int nextBudgetVersion(UUID structureId) {
        Integer value = jdbc.queryForObject(
                "select coalesce(max(version_number),0)+1 from budget_versions where cost_structure_id=?",
                Integer.class, structureId);
        return value == null ? 1 : value;
    }

    BudgetVersionRow insertBudgetVersion(
            UUID projectId,
            UUID owningOrganizationId,
            UUID structureId,
            int versionNumber,
            String baselineType,
            String currency,
            UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into budget_versions(
                    id,project_id,owning_organization_id,cost_structure_id,version_number,status,baseline_type,
                    currency,created_by_user_id,version,created_at,updated_at)
                values(?,?,?,?,?,'DRAFT',?,?,?,0,?,?)
                """, id, projectId, owningOrganizationId, structureId, versionNumber,
                baselineType, currency, createdBy, now, now);
        return requireBudget(id);
    }

    Optional<BudgetVersionRow> findBudget(UUID id) {
        return jdbc.query("""
                select id,project_id,owning_organization_id,cost_structure_id,version_number,status,baseline_type,
                       currency,created_by_user_id,submitted_by_user_id,approved_by_user_id,submitted_at,approved_at,version
                from budget_versions where id=?
                """, (rs, n) -> mapBudget(rs), id).stream().findFirst();
    }

    BudgetVersionRow requireBudget(UUID id) {
        return findBudget(id).orElseThrow();
    }

    BudgetLineRow insertBudgetLine(UUID budgetId, UUID nodeId, UUID scopeId, BigDecimal amount, String notes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into budget_lines(id,budget_version_id,cost_node_id,scope_id,amount,notes,created_at)
                values(?,?,?,?,?,?,?)
                """, id, budgetId, nodeId, scopeId, amount, notes, Instant.now());
        return new BudgetLineRow(id, budgetId, nodeId, scopeId, amount, notes);
    }

    List<BudgetLineRow> listBudgetLines(UUID budgetId) {
        return jdbc.query("""
                select id,budget_version_id,cost_node_id,scope_id,amount,notes
                from budget_lines where budget_version_id=? order by id
                """, (rs, n) -> new BudgetLineRow(
                rs.getObject("id", UUID.class), rs.getObject("budget_version_id", UUID.class),
                rs.getObject("cost_node_id", UUID.class), rs.getObject("scope_id", UUID.class),
                rs.getBigDecimal("amount"), rs.getString("notes")), budgetId);
    }

    int transitionBudget(UUID budgetId, long expectedVersion, String fromStatus, String toStatus, UUID actor) {
        Instant now = Instant.now();
        if (toStatus.equals("SUBMITTED")) {
            return jdbc.update("""
                    update budget_versions
                    set status='SUBMITTED',submitted_by_user_id=?,submitted_at=?,version=version+1,updated_at=?
                    where id=? and status=? and version=?
                    """, actor, now, now, budgetId, fromStatus, expectedVersion);
        }
        if (toStatus.equals("APPROVED")) {
            return jdbc.update("""
                    update budget_versions
                    set status='APPROVED',approved_by_user_id=?,approved_at=?,version=version+1,updated_at=?
                    where id=? and status=? and version=?
                    """, actor, now, now, budgetId, fromStatus, expectedVersion);
        }
        throw new IllegalArgumentException("Unsupported budget transition: " + toStatus);
    }

    void supersedePreviousApprovedBudget(UUID structureId, UUID exceptBudgetId) {
        jdbc.update("""
                update budget_versions
                set status='SUPERSEDED',version=version+1,updated_at=?
                where cost_structure_id=? and id<>? and status='APPROVED' and baseline_type<>'FORECAST'
                """, Instant.now(), structureId, exceptBudgetId);
    }

    BigDecimal originalBudget(UUID structureId, UUID nodeId) {
        List<BigDecimal> rows = jdbc.query("""
                select coalesce(sum(bl.amount),0) amount
                from budget_lines bl
                join budget_versions bv on bv.id=bl.budget_version_id
                where bl.cost_node_id=?
                  and bv.cost_structure_id=?
                  and bv.baseline_type='ORIGINAL'
                  and bv.status in ('APPROVED','SUPERSEDED')
                  and bv.version_number=(
                      select min(bv2.version_number) from budget_versions bv2
                      where bv2.cost_structure_id=? and bv2.baseline_type='ORIGINAL'
                        and bv2.status in ('APPROVED','SUPERSEDED'))
                """, (rs, n) -> rs.getBigDecimal("amount"), nodeId, structureId, structureId);
        return rows.isEmpty() || rows.getFirst() == null ? BigDecimal.ZERO : rows.getFirst();
    }

    BigDecimal currentBudget(UUID structureId, UUID nodeId) {
        List<BigDecimal> rows = jdbc.query("""
                select coalesce(sum(bl.amount),0) amount
                from budget_lines bl
                join budget_versions bv on bv.id=bl.budget_version_id
                where bl.cost_node_id=?
                  and bv.cost_structure_id=?
                  and bv.status='APPROVED'
                  and bv.baseline_type<>'FORECAST'
                  and bv.version_number=(
                      select max(bv2.version_number) from budget_versions bv2
                      where bv2.cost_structure_id=? and bv2.status='APPROVED' and bv2.baseline_type<>'FORECAST')
                """, (rs, n) -> rs.getBigDecimal("amount"), nodeId, structureId, structureId);
        return rows.isEmpty() || rows.getFirst() == null ? BigDecimal.ZERO : rows.getFirst();
    }

    BigDecimal actual(UUID nodeId) {
        return amount("select coalesce(sum(amount),0) from actual_cost_entries where cost_node_id=? and status='POSTED'", nodeId);
    }

    BigDecimal directScopeActual(UUID projectId, UUID organizationId, UUID scopeId) {
        return amount("""
                select coalesce(sum(amount),0) from actual_cost_entries
                where project_id=? and owning_organization_id=? and scope_id=? and status='POSTED'
                """, projectId, organizationId, scopeId);
    }

    BigDecimal committed(UUID nodeId) {
        return amount("select coalesce(sum(committed_amount),0) from commitments where cost_node_id=? and status='ACTIVE'", nodeId);
    }

    BigDecimal openCommitment(UUID nodeId) {
        List<BigDecimal> rows = jdbc.query("""
                select coalesce(sum(
                    case when c.committed_amount-coalesce(a.recognized,0)>0
                         then c.committed_amount-coalesce(a.recognized,0) else 0 end),0) amount
                from commitments c
                left join (
                    select commitment_id,sum(amount) recognized
                    from actual_cost_entries
                    where status='POSTED' and commitment_id is not null
                    group by commitment_id
                ) a on a.commitment_id=c.id
                where c.cost_node_id=? and c.status='ACTIVE'
                """, (rs, n) -> rs.getBigDecimal("amount"), nodeId);
        return rows.isEmpty() || rows.getFirst() == null ? BigDecimal.ZERO : rows.getFirst();
    }

    BigDecimal directScopeOpenCommitment(UUID projectId, UUID organizationId, UUID scopeId) {
        List<BigDecimal> rows = jdbc.query("""
                select coalesce(sum(
                    case when c.committed_amount-coalesce(a.recognized,0)>0
                         then c.committed_amount-coalesce(a.recognized,0) else 0 end),0) amount
                from commitments c
                left join (
                    select commitment_id,sum(amount) recognized
                    from actual_cost_entries
                    where status='POSTED' and commitment_id is not null
                    group by commitment_id
                ) a on a.commitment_id=c.id
                where c.project_id=? and c.owning_organization_id=? and c.scope_id=? and c.status='ACTIVE'
                """, (rs, n) -> rs.getBigDecimal("amount"), projectId, organizationId, scopeId);
        return rows.isEmpty() || rows.getFirst() == null ? BigDecimal.ZERO : rows.getFirst();
    }

    BigDecimal remainingForecast(UUID nodeId) {
        return amount("select coalesce(sum(remaining_forecast_amount),0) from forecast_entries where cost_node_id=? and status='ACTIVE'", nodeId);
    }

    BigDecimal directScopeForecast(UUID projectId, UUID organizationId, UUID scopeId) {
        return amount("""
                select coalesce(sum(remaining_forecast_amount),0) from forecast_entries
                where project_id=? and owning_organization_id=? and scope_id=? and status='ACTIVE'
                """, projectId, organizationId, scopeId);
    }

    void lockNode(UUID nodeId) {
        List<UUID> rows = jdbc.query("select id from cost_nodes where id=? for update",
                (rs, n) -> rs.getObject("id", UUID.class), nodeId);
        if (rows.isEmpty()) throw new IllegalStateException("Cost node missing while locking: " + nodeId);
    }

    CommitmentRow insertCommitment(
            UUID projectId,
            UUID owningOrganizationId,
            UUID counterpartyOrganizationId,
            UUID contractId,
            UUID scopeId,
            UUID nodeId,
            String reference,
            BigDecimal amount,
            String currency,
            Instant committedAt,
            UUID sourceRevisionId,
            UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into commitments(
                    id,project_id,owning_organization_id,counterparty_organization_id,contract_id,scope_id,cost_node_id,
                    commitment_reference,committed_amount,currency,status,committed_at,source_document_revision_id,
                    version,created_by_user_id,created_at)
                values(?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,0,?,?)
                """, id, projectId, owningOrganizationId, counterpartyOrganizationId, contractId, scopeId, nodeId,
                reference, amount, currency, committedAt, sourceRevisionId, createdBy, Instant.now());
        return new CommitmentRow(id, projectId, owningOrganizationId, counterpartyOrganizationId,
                contractId, scopeId, nodeId, reference, amount, currency, "ACTIVE", committedAt, sourceRevisionId, 0);
    }

    Optional<CommitmentRow> findCommitment(UUID id) {
        return jdbc.query("""
                select id,project_id,owning_organization_id,counterparty_organization_id,contract_id,scope_id,cost_node_id,
                       commitment_reference,committed_amount,currency,status,committed_at,source_document_revision_id,version
                from commitments where id=?
                """, (rs, n) -> new CommitmentRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("owning_organization_id", UUID.class), rs.getObject("counterparty_organization_id", UUID.class),
                rs.getObject("contract_id", UUID.class), rs.getObject("scope_id", UUID.class),
                rs.getObject("cost_node_id", UUID.class), rs.getString("commitment_reference"),
                rs.getBigDecimal("committed_amount"), rs.getString("currency"), rs.getString("status"),
                rs.getTimestamp("committed_at").toInstant(), rs.getObject("source_document_revision_id", UUID.class),
                rs.getLong("version")), id).stream().findFirst();
    }

    ActualRow insertActual(
            UUID projectId,
            UUID owningOrganizationId,
            UUID scopeId,
            UUID nodeId,
            UUID commitmentId,
            String sourceType,
            String sourceReference,
            UUID counterpartyOrganizationId,
            BigDecimal amount,
            String currency,
            LocalDate accountingDate,
            UUID sourceRevisionId,
            UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into actual_cost_entries(
                    id,project_id,owning_organization_id,scope_id,cost_node_id,commitment_id,source_type,source_reference,
                    counterparty_organization_id,amount,currency,accounting_date,status,posted_at,source_document_revision_id,
                    version,created_by_user_id,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,'POSTED',?,?,0,?,?)
                """, id, projectId, owningOrganizationId, scopeId, nodeId, commitmentId, sourceType, sourceReference,
                counterpartyOrganizationId, amount, currency, accountingDate, now, sourceRevisionId, createdBy, now);
        return new ActualRow(id, projectId, owningOrganizationId, scopeId, nodeId, commitmentId,
                sourceType, sourceReference, amount, currency, accountingDate, "POSTED", sourceRevisionId);
    }

    void supersedeForecast(UUID projectId, UUID owningOrganizationId, UUID nodeId, LocalDate period) {
        jdbc.update("""
                update forecast_entries set status='SUPERSEDED',version=version+1,updated_at=?
                where project_id=? and owning_organization_id=? and cost_node_id=? and forecast_period=? and status='ACTIVE'
                """, Instant.now(), projectId, owningOrganizationId, nodeId, period);
    }

    ForecastRow insertForecast(
            UUID projectId,
            UUID owningOrganizationId,
            UUID scopeId,
            UUID nodeId,
            LocalDate period,
            BigDecimal amount,
            String currency,
            String basis,
            UUID sourceRevisionId,
            UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into forecast_entries(
                    id,project_id,owning_organization_id,scope_id,cost_node_id,forecast_period,remaining_forecast_amount,
                    currency,basis,status,source_document_revision_id,version,created_by_user_id,created_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,'ACTIVE',?,0,?,?,?)
                """, id, projectId, owningOrganizationId, scopeId, nodeId, period, amount,
                currency, basis, sourceRevisionId, createdBy, now, now);
        return new ForecastRow(id, projectId, owningOrganizationId, scopeId, nodeId, period,
                amount, currency, basis, "ACTIVE", sourceRevisionId, 0);
    }

    UUID insertBudgetDecision(
            UUID projectId,
            UUID scopeId,
            UUID owningOrganizationId,
            UUID nodeId,
            String requestReference,
            BigDecimal currentBudget,
            BigDecimal actual,
            BigDecimal openCommitment,
            BigDecimal remainingForecast,
            BigDecimal proposedExposure,
            BigDecimal availableBefore,
            BigDecimal availableAfter,
            String decision,
            String reason,
            UUID actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into budget_control_decisions(
                    id,project_id,scope_id,owning_organization_id,cost_node_id,request_resource_reference,
                    current_budget,actual,open_commitment,remaining_forecast,proposed_exposure,available_before,
                    available_after,decision,policy_version,reason,actor_user_id,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'STRICT_AVAILABLE_BUDGET_V1',?,?,?)
                """, id, projectId, scopeId, owningOrganizationId, nodeId, requestReference,
                currentBudget, actual, openCommitment, remainingForecast, proposedExposure,
                availableBefore, availableAfter, decision, reason, actor, Instant.now());
        return id;
    }

    boolean revisionBelongsToProject(UUID revisionId, UUID projectId) {
        if (revisionId == null) return true;
        Integer count = jdbc.queryForObject(
                "select count(*) from document_revisions where id=? and project_id=?",
                Integer.class, revisionId, projectId);
        return count != null && count > 0;
    }

    boolean contractBelongsToProject(UUID contractId, UUID projectId) {
        if (contractId == null) return true;
        Integer count = jdbc.queryForObject(
                "select count(*) from contracts where id=? and project_id=?",
                Integer.class, contractId, projectId);
        return count != null && count > 0;
    }

    private BigDecimal amount(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static StructureRow mapStructure(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StructureRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("owning_organization_id", UUID.class), rs.getObject("contract_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("structure_type"),
                rs.getString("status"), rs.getLong("version"));
    }

    private static BudgetVersionRow mapBudget(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BudgetVersionRow(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("owning_organization_id", UUID.class), rs.getObject("cost_structure_id", UUID.class),
                rs.getInt("version_number"), rs.getString("status"), rs.getString("baseline_type"),
                rs.getString("currency"), rs.getObject("created_by_user_id", UUID.class),
                rs.getObject("submitted_by_user_id", UUID.class), rs.getObject("approved_by_user_id", UUID.class),
                instant(rs, "submitted_at"), instant(rs, "approved_at"), rs.getLong("version"));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record StructureRow(
            UUID id, UUID projectId, UUID owningOrganizationId, UUID contractId,
            String code, String name, String structureType, String status, long version) {}

    record NodeRow(
            UUID id, UUID structureId, UUID parentNodeId, String code, String name,
            String category, int sortOrder, String status, UUID projectId, UUID owningOrganizationId) {}

    record ScopeLinkRow(
            UUID id, UUID costNodeId, UUID scopeId, BigDecimal allocationPercent, String relationshipType) {}

    record BudgetVersionRow(
            UUID id, UUID projectId, UUID owningOrganizationId, UUID structureId,
            int versionNumber, String status, String baselineType, String currency,
            UUID createdBy, UUID submittedBy, UUID approvedBy, Instant submittedAt, Instant approvedAt, long version) {}

    record BudgetLineRow(
            UUID id, UUID budgetVersionId, UUID costNodeId, UUID scopeId, BigDecimal amount, String notes) {}

    record CommitmentRow(
            UUID id, UUID projectId, UUID owningOrganizationId, UUID counterpartyOrganizationId,
            UUID contractId, UUID scopeId, UUID costNodeId, String reference, BigDecimal amount,
            String currency, String status, Instant committedAt, UUID sourceDocumentRevisionId, long version) {}

    record ActualRow(
            UUID id, UUID projectId, UUID owningOrganizationId, UUID scopeId, UUID costNodeId,
            UUID commitmentId, String sourceType, String sourceReference, BigDecimal amount,
            String currency, LocalDate accountingDate, String status, UUID sourceDocumentRevisionId) {}

    record ForecastRow(
            UUID id, UUID projectId, UUID owningOrganizationId, UUID scopeId, UUID costNodeId,
            LocalDate forecastPeriod, BigDecimal remainingForecastAmount, String currency, String basis,
            String status, UUID sourceDocumentRevisionId, long version) {}
}
