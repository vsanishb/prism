package dev.prism.core.workflow.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Stage events are what the processing screen shows. Append-only: the newest per stage is current. */
@Component
class StageRecorder {

    enum Stage {
        DOCUMENT_EXTRACTION,
        CONSOLIDATION,
        VALIDATION,
        POLICY_ANALYSIS,
        RISK_SCREENING
    }

    enum Status {
        NOT_STARTED,
        PROCESSING,
        COMPLETED,
        REQUIRES_REVIEW,
        FAILED
    }

    private final JdbcClient jdbc;

    StageRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void record(UUID claimId, Stage stage, UUID documentId, Status status, String detailCode) {
        jdbc.sql("""
                        INSERT INTO core.stage_events (claim_id, stage, document_id, status, detail_code)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(claimId, stage.name(), documentId, status.name(), detailCode)
                .update();
    }
}
