package dev.prism.core.workflow.internal;

import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.platform.outbox.Outbox;
import dev.prism.core.platform.web.ApiException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The processing screen's view and actions. Reads Postgres only, so it survives a Temporal outage. */
@Service
class ProcessingStatusService {

    private final ClaimDirectory claims;
    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final Outbox outbox;

    ProcessingStatusService(ClaimDirectory claims, JdbcClient jdbc, AuditRecorder audit, Outbox outbox) {
        this.claims = claims;
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
    }

    record StageView(String stage, UUID documentId, String status, String detailCode, Instant occurredAt) {}

    record ProcessingView(String claimStatus, boolean retryAvailable, List<StageView> stages) {}

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public ProcessingView view(UUID claimId) {
        ClaimRecord claim = claims.visible(claimId);
        List<StageView> stages = currentStages(claimId);
        return new ProcessingView(claim.status(), !claim.closed() && anyFailed(stages), stages);
    }

    @PreAuthorize("hasAuthority('CLAIM_MANAGE')")
    @Transactional
    public void retry(UUID claimId) {
        ClaimRecord claim = claims.visible(claimId);
        if (claim.closed() || !anyFailed(currentStages(claimId))) {
            throw ApiException.conflict("nothing_to_retry", "No stage of this claim is waiting for a retry.");
        }
        audit.record(AuditEntry.of("claim:" + claimId, AuditAction.CLAIM_PROCESSING_RETRY_REQUESTED, AuditOutcome.SUCCESS)
                .target("CLAIM", claimId));
        outbox.append(Outbox.Event.CLAIM_RETRY_REQUESTED, claimId);
    }

    private static boolean anyFailed(List<StageView> stages) {
        return stages.stream().anyMatch(stage -> StageRecorder.Status.FAILED.name().equals(stage.status()));
    }

    /** The newest event for each stage and document. A replaced document no longer counts. */
    private List<StageView> currentStages(UUID claimId) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (e.stage, e.document_id)
                            e.stage, e.document_id, e.status, e.detail_code, e.occurred_at
                        FROM core.stage_events e
                        LEFT JOIN core.documents d ON d.id = e.document_id
                        WHERE e.claim_id = ? AND (e.document_id IS NULL OR d.status = 'ACTIVE')
                        ORDER BY e.stage, e.document_id, e.id DESC
                        """)
                .param(claimId)
                .query((row, index) -> new StageView(
                        row.getString("stage"),
                        row.getObject("document_id", UUID.class),
                        row.getString("status"),
                        row.getString("detail_code"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
