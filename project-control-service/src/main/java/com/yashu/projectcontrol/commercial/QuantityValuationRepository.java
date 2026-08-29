package com.yashu.projectcontrol.commercial;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
class QuantityValuationRepository {

    private final JdbcTemplate jdbc;

    QuantityValuationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void linkMeasurement(UUID valuationId, UUID measurementId) {
        int changed = jdbc.update(
                "update valuation_lines set measurement_id=? where id=? and measurement_id is null",
                measurementId, valuationId);
        if (changed != 1) {
            throw new IllegalStateException("Valuation measurement link could not be established");
        }
    }

    UUID measurementId(UUID valuationId) {
        return jdbc.query(
                "select measurement_id from valuation_lines where id=?",
                rs -> rs.next() ? rs.getObject("measurement_id", UUID.class) : null,
                valuationId);
    }

    BigDecimal acceptedQuantityAlreadyValued(UUID contractItemId) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(accepted_quantity),0)
                from valuation_lines
                where contract_item_id=? and status<>'VOID' and measurement_id is not null
                """, BigDecimal.class, contractItemId);
        return value == null ? BigDecimal.ZERO : value;
    }
}
