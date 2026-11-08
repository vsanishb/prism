package dev.prism.core.review.internal;

import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.intelligence.ReviewSupport;
import dev.prism.core.intelligence.ReviewSupport.Evidence;
import dev.prism.core.review.internal.ReviewRecordStore.FindingRecord;
import dev.prism.core.review.internal.ReviewRecordStore.Outcome;
import dev.prism.core.review.internal.ReviewRecordStore.Request;
import dev.prism.core.review.internal.ReviewTaskStore.Task;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The evidence chain (plan: human review): from the authorized outcome back through the people who
 * worked the claim, the analysis and screening it was decided with, the facts, their source pages,
 * the AI runs, and the documents themselves.
 */
@Service
class EvidenceChainReader {

    private final ClaimDirectory claims;
    private final ReviewTaskStore tasks;
    private final ReviewRecordStore records;
    private final ReviewSupport intelligence;

    EvidenceChainReader(ClaimDirectory claims, ReviewTaskStore tasks, ReviewRecordStore records, ReviewSupport intelligence) {
        this.claims = claims;
        this.tasks = tasks;
        this.records = records;
        this.intelligence = intelligence;
    }

    /** @param evidence what the current outcome was decided with, or the latest facts if there is no outcome */
    record Chain(
            String claimNumber,
            String claimStatus,
            String currency,
            List<Outcome> outcomes,
            List<Task> tasks,
            List<FindingRecord> findings,
            List<Request> requests,
            int notes,
            Evidence evidence) {}

    @PreAuthorize("hasAuthority('CLAIM_READ')")
    @Transactional(readOnly = true)
    public Chain read(UUID claimId) {
        ClaimRecord claim = claims.visible(claimId);
        List<Outcome> outcomes = records.outcomes(claimId);
        UUID consolidation = outcomes.stream()
                .filter(Outcome::current)
                .map(Outcome::consolidationId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .or(() -> Optional.ofNullable(intelligence.current(claimId).consolidationId()))
                .orElse(null);
        return new Chain(claim.claimNumber(), claim.status(), claim.currency(), outcomes, tasks.history(claimId), records.findings(claimId),
                records.requests(claimId), records.notes(claimId).size(),
                consolidation == null ? null : intelligence.evidence(claimId, consolidation));
    }
}
