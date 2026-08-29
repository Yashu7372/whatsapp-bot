package com.yashu.projectcontrol.financialreporting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class FinancialReadRepository {

    private final JdbcTemplate jdbc;

    FinancialReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<CashFact> cashFacts(UUID projectId, UUID organizationId, LocalDate from, LocalDate to) {
        List<CashFact> facts = new ArrayList<>();

        facts.addAll(jdbc.query("""
                select accounting_date fact_date,'POSTED_INTERNAL_COST' category,amount
                from actual_cost_entries
                where project_id=? and owning_organization_id=? and status='POSTED'
                  and accounting_date between ? and ?
                """, (rs, n) -> new CashFact(
                rs.getDate("fact_date").toLocalDate(), rs.getString("category"), rs.getBigDecimal("amount")),
                projectId, organizationId, from, to));

        facts.addAll(jdbc.query("""
                select forecast_period fact_date,'REMAINING_COST_FORECAST' category,remaining_forecast_amount amount
                from forecast_entries
                where project_id=? and owning_organization_id=? and status='ACTIVE'
                  and forecast_period between ? and ?
                """, (rs, n) -> new CashFact(
                rs.getDate("fact_date").toLocalDate(), rs.getString("category"), rs.getBigDecimal("amount")),
                projectId, organizationId, from, to));

        facts.addAll(jdbc.query("""
                select cast(paid_at as date) fact_date,
                       case when payee_organization_id=? then 'ACTUAL_CASH_IN' else 'ACTUAL_CASH_OUT' end category,
                       amount
                from payments
                where project_id=? and status='PAID'
                  and (payer_organization_id=? or payee_organization_id=?)
                  and cast(paid_at as date) between ? and ?
                """, (rs, n) -> new CashFact(
                rs.getDate("fact_date").toLocalDate(), rs.getString("category"), rs.getBigDecimal("amount")),
                organizationId, projectId, organizationId, organizationId, from, to));

        facts.addAll(jdbc.query("""
                select coalesce(pa.due_date,cast(pa.certified_at as date)) fact_date,
                       case when payee.organization_id=? then 'CERTIFIED_RECEIVABLE' else 'CERTIFIED_PAYABLE' end category,
                       case when pa.certified_amount-coalesce(paid.paid,0)>0
                            then pa.certified_amount-coalesce(paid.paid,0) else 0 end amount
                from payment_applications pa
                join contracts c on c.id=pa.contract_id
                join project_participants payer on payer.id=c.payer_participant_id
                join project_participants payee on payee.id=c.payee_participant_id
                left join (
                    select payment_application_id,sum(amount) paid
                    from payments where status='PAID' group by payment_application_id
                ) paid on paid.payment_application_id=pa.id
                where pa.project_id=? and pa.status='CERTIFIED'
                  and (payer.organization_id=? or payee.organization_id=?)
                  and coalesce(pa.due_date,cast(pa.certified_at as date)) between ? and ?
                """, (rs, n) -> new CashFact(
                rs.getDate("fact_date").toLocalDate(), rs.getString("category"), rs.getBigDecimal("amount")),
                organizationId, projectId, organizationId, organizationId, from, to));

        return facts;
    }

    record CashFact(LocalDate date, String category, BigDecimal amount) {}
}
