package dev.prism.core.intelligence.internal;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.PolicyAnalysis;
import dev.prism.core.audit.AuditAction;
import dev.prism.core.audit.AuditDetail;
import dev.prism.core.audit.AuditEntry;
import dev.prism.core.audit.AuditOutcome;
import dev.prism.core.audit.AuditRecorder;
import dev.prism.core.claim.ClaimDirectory;
import dev.prism.core.claim.ClaimDirectory.ClaimRecord;
import dev.prism.core.intelligence.ClaimIntelligence.Analysis;
import dev.prism.core.intelligence.internal.AssessmentCalculator.Inputs;
import dev.prism.core.intelligence.internal.AssessmentStore.CalculatedValue;
import dev.prism.core.intelligence.internal.AssessmentStore.CheckedFinding;
import dev.prism.core.intelligence.internal.AssessmentStore.Fact;
import dev.prism.core.intelligence.internal.AssessmentStore.LatestFacts;
import dev.prism.core.policy.PolicyDirectory;
import dev.prism.core.policy.PolicyDirectory.VersionRef;
import dev.prism.core.policyknowledge.PublishedKnowledge;
import dev.prism.core.policyknowledge.PublishedKnowledge.Provision;
import dev.prism.core.policyknowledge.PublishedKnowledge.Snapshot;
import dev.prism.core.policyknowledge.PublishedKnowledge.Value;
import dev.prism.core.retrieval.SearchIndexes;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the Policy Agent for a claim's latest facts (D-079). Sends only de-identified facts, checks
 * every citation and value against the published version itself, and computes with confirmed
 * values. No transaction is held during the model calls.
 */
@Component
class PolicyAnalysisStep {

    /** What a policy can be read against. Never names, hospitals, doctors, or policy numbers. */
    static final Set<String> SHARED_FACTS = Set.of(
            "admission_date", "discharge_date", "claimed_amount", "diagnosis", "procedure", "test_name", "findings", "medicines");

    private static final Set<String> EVIDENCED = Set.of("SUPPORTED", "LIMITED", "POTENTIALLY_EXCLUDED");

    private final ClaimDirectory claims;
    private final PolicyDirectory policies;
    private final PublishedKnowledge knowledge;
    private final SearchIndexes indexes;
    private final AiServiceClient ai;
    private final AssessmentStore store;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;

    PolicyAnalysisStep(
            ClaimDirectory claims,
            PolicyDirectory policies,
            PublishedKnowledge knowledge,
            SearchIndexes indexes,
            AiServiceClient ai,
            AssessmentStore store,
            AuditRecorder audit,
            TransactionTemplate transactions) {
        this.claims = claims;
        this.policies = policies;
        this.knowledge = knowledge;
        this.indexes = indexes;
        this.ai = ai;
        this.store = store;
        this.audit = audit;
        this.transactions = transactions;
    }

    Analysis analyze(UUID claimId) {
        ClaimRecord claim = claims.get(claimId);
        LatestFacts latest = store.latestFacts(claimId)
                .orElseThrow(() -> new Failure("no_consolidated_facts", false, List.of()));
        if (claim.closed() || store.analyzed(latest.consolidationId())) {
            return new Analysis(false);
        }
        UUID versionId = claim.policyVersionId();
        // Retried by a person once the version is published and indexed: the claim waits, visibly.
        Snapshot snapshot = knowledge.snapshot(versionId)
                .orElseThrow(() -> new Failure("policy_not_published", false, List.of()));
        if (!indexes.ready(versionId)) {
            throw new Failure("policy_not_searchable", false, List.of());
        }
        VersionRef version = policies.findVersion(versionId).orElseThrow();
        LocalDate coverStart = policies.coverStart(versionId).orElseThrow();

        PolicyAnalysis.Request request = request(claim, version, coverStart, latest, snapshot);
        String stream = "claim:" + claimId;
        audit.recordIndependently(AuditEntry.of(stream, AuditAction.CLAIM_SENT_FOR_POLICY_ANALYSIS, AuditOutcome.SUCCESS)
                .target("CLAIM", claimId)
                .detail(AuditDetail.POLICY_VERSION_ID, versionId));
        PolicyAnalysis.Result result;
        try {
            result = ai.analyzeClaim(request);
        } catch (Failure failure) {
            transactions.executeWithoutResult(status -> store.saveRuns(claimId, failure.runs()));
            throw failure;
        }

        Inputs inputs = inputs(latest.facts(), version, coverStart);
        Set<UUID> sentFacts = request.facts().stream().map(PolicyAnalysis.Fact::id).collect(Collectors.toSet());
        List<CheckedFinding> findings = result.findings().stream()
                .map(finding -> check(finding, snapshot, sentFacts, inputs))
                .toList();
        transactions.executeWithoutResult(status -> {
            store.save(claimId, latest.consolidationId(), versionId, coverStart, result, findings);
            store.saveRuns(claimId, result.runs());
            audit.record(AuditEntry.of(stream, AuditAction.CLAIM_POLICY_ANALYZED, AuditOutcome.SUCCESS)
                    .target("CLAIM", claimId)
                    .detail(AuditDetail.PIPELINE_VERSION, result.pipelineVersion()));
        });
        return new Analysis(findings.stream().anyMatch(finding -> !"SUPPORTED".equals(finding.status())));
    }

    private static PolicyAnalysis.Request request(
            ClaimRecord claim, VersionRef version, LocalDate coverStart, LatestFacts latest, Snapshot snapshot) {
        return new PolicyAnalysis.Request(
                claim.id(),
                version.id(),
                claim.type(),
                version.currency(),
                version.sumInsured(),
                coverStart,
                latest.facts().stream()
                        .filter(fact -> SHARED_FACTS.contains(fact.name()))
                        .map(fact -> new PolicyAnalysis.Fact(fact.id(), fact.name(), fact.kind(), fact.state(), fact.value()))
                        .toList(),
                latest.lineItems().stream()
                        .map(item -> new PolicyAnalysis.LineItem(item.id(), item.description(), item.amount()))
                        .toList(),
                snapshot.values().stream()
                        .map(value -> new PolicyAnalysis.PolicyValue(
                                value.id(), value.provisionId(), value.name(), value.kind(), value.value(), value.unit(),
                                value.qualifier()))
                        .toList());
    }

    /**
     * The AI service already validated this finding. Core checks again against the published version
     * it holds, as it does for search (D-075): a citation must quote a provision of this version, and
     * a value must belong to a cited provision.
     */
    static CheckedFinding check(PolicyAnalysis.Finding finding, Snapshot snapshot, Set<UUID> sentFacts, Inputs inputs) {
        Map<UUID, Provision> provisions = snapshot.provisions().stream()
                .collect(Collectors.toMap(Provision::id, Function.identity()));
        Map<UUID, Value> values = snapshot.values().stream().collect(Collectors.toMap(Value::id, Function.identity()));
        List<String> problems = new ArrayList<>(finding.problems());
        List<String> unresolved = new ArrayList<>(finding.unresolved());
        String status = finding.status();

        List<PolicyAnalysis.Citation> citations = new ArrayList<>();
        boolean foreign = false;
        for (PolicyAnalysis.Citation citation : finding.citations()) {
            Provision provision = provisions.get(citation.provisionId());
            if (provision == null || !quoted(citation.quote(), provision.text())) {
                foreign = true;
            } else {
                citations.add(citation);
            }
        }
        if (foreign || !sentFacts.containsAll(finding.factsUsed())) {
            problems.add("NOT_IN_THIS_CLAIM_OR_VERSION");
            if (EVIDENCED.contains(status)) {
                status = "INSUFFICIENT_EVIDENCE";
                unresolved.add("Part of this finding did not match this claim or its policy version, so it needs checking by a person.");
            }
        }

        Set<UUID> cited = citations.stream().map(PolicyAnalysis.Citation::provisionId).collect(Collectors.toSet());
        List<CalculatedValue> calculations = new ArrayList<>();
        if (EVIDENCED.contains(status)) {
            for (PolicyAnalysis.AppliedValue applied : finding.appliedValues()) {
                Value value = values.get(applied.valueId());
                if (value == null || !cited.contains(value.provisionId())) {
                    problems.add("VALUE_REJECTED");
                    continue;
                }
                calculations.add(new CalculatedValue(value.id(), AssessmentCalculator.calculate(applied.role(), value, inputs)));
            }
        }
        return new CheckedFinding(
                finding, status, problems.stream().distinct().toList(), unresolved.stream().distinct().toList(),
                citations, calculations);
    }

    static boolean quoted(String quote, String source) {
        String wanted = normalized(quote);
        return wanted.length() >= 8 && normalized(source).contains(wanted);
    }

    private static String normalized(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('–', '-')
                .replace('—', '-')
                .strip();
        plain = plain.replaceAll("^[\"']+|[\"']+$", "").replaceAll("(\\.\\.\\.|…)$", "").strip();
        return plain.replaceAll("\\s+", " ");
    }

    private static Inputs inputs(List<Fact> facts, VersionRef version, LocalDate coverStart) {
        Map<String, Fact> byName = facts.stream().collect(Collectors.toMap(Fact::name, Function.identity(), (first, second) -> first));
        return new Inputs(
                date(byName.get("admission_date")),
                date(byName.get("discharge_date")),
                Optional.ofNullable(byName.get("claimed_amount"))
                        .filter(fact -> "PRESENT".equals(fact.state()))
                        .map(Fact::amount),
                version.sumInsured(),
                version.currency(),
                coverStart);
    }

    private static Optional<LocalDate> date(Fact fact) {
        return Optional.ofNullable(fact)
                .filter(found -> "PRESENT".equals(found.state()) && found.value() != null)
                .map(found -> LocalDate.parse(found.value()));
    }
}
