package dev.prism.core.intelligence.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Fact corrections. Insert-only: a newer correction of the same fact supersedes the older one. */
@Repository
class FactCorrectionStore {

    private final JdbcClient jdbc;

    FactCorrectionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record StoredCorrection(UUID id, String fieldName, UUID documentId, String value, BigDecimal amount) {}

    /** The newest correction for each fact of the claim. */
    List<StoredCorrection> latest(UUID claimId) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (field_name, document_id) id, field_name, document_id, value, amount
                        FROM core.fact_corrections WHERE claim_id = ?
                        ORDER BY field_name, document_id, id DESC
                        """)
                .param(claimId)
                .query((row, index) -> new StoredCorrection(row.getObject("id", UUID.class), row.getString("field_name"),
                        row.getObject("document_id", UUID.class), row.getString("value"), row.getBigDecimal("amount")))
                .list();
    }

    UUID insert(UUID claimId, String fieldName, UUID documentId, String kind, String value, BigDecimal amount, String reason,
            UUID basedOnFact, UUID correctedBy) {
        return jdbc.sql("""
                        INSERT INTO core.fact_corrections (claim_id, field_name, document_id, kind, value, amount, reason,
                            based_on_fact, corrected_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """)
                .params(claimId, fieldName, documentId, kind, value, amount, reason, basedOnFact, correctedBy)
                .query(UUID.class)
                .single();
    }
}
