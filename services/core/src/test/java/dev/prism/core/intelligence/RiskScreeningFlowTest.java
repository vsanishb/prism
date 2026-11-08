package dev.prism.core.intelligence;

import static dev.prism.core.intelligence.internal.ExtractionFixtures.amount;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.date;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.item;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.result;
import static dev.prism.core.intelligence.internal.ExtractionFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import dev.prism.core.aiclient.DocumentExtraction;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.RiskScreening;
import dev.prism.core.identity.Role;
import dev.prism.core.support.ClaimFixtures;
import dev.prism.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Risk screening on the real database, with claim history, storage, audit, and the API. Only the AI service is mocked. */
class RiskScreeningFlowTest extends IntegrationTest {


    @Autowired
    private ClaimIntelligence intelligence;

    @Autowired
    private JdbcClient jdbc;

    private ClaimFixtures fixtures;
    private Client processor;
    private String policy;
    /** Unique per test: the database is shared, and other tests' bills at one hospital would match. */
    private String hospital;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = claimFixtures();
        processor = fixtures.processor();
        policy = fixtures.createPolicy();
        fixtures.addVersion(policy, "2026-01-01", "2026-12-31");
        hospital = "Synthetic Hospital " + UUID.randomUUID().toString().substring(0, 8);
        doAnswer(call -> new RiskScreening.SummaryResult(call.<RiskScreening.SummaryRequest>getArgument(0).claimId(), "UNAVAILABLE",
                null, List.of(), "model_unavailable", "risk_summary.v1", List.of())).when(aiService).summarizeRisk(any());
        doAnswer(call -> anomaly(call.getArgument(0), false)).when(aiService).scoreRiskAnomaly(any());
    }

    /** A PDF that no other test uploads, so that duplicate checks see only what a test sets up. */
    private static byte[] unique(String path) throws Exception {
        byte[] original = ClaimFixtures.read(path);
        byte[] suffix = ("\n%" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] content = new byte[original.length + suffix.length];
        System.arraycopy(original, 0, content, 0, original.length);
        System.arraycopy(suffix, 0, content, original.length, suffix.length);
        return content;
    }

    private DocumentExtraction.Result form() {
        return result("CLAIM_FORM", List.of(text("patient_name", "Synthetic Patient"), text("hospital_name", hospital),
                date("admission_date", "2026-03-14"), date("discharge_date", "2026-03-17"), amount("claimed_amount", "106000.00"),
                text("policy_number", "SYN-TEST")), List.of());
    }

    private DocumentExtraction.Result bill(String number) {
        return result("HOSPITAL_BILL", List.of(text("bill_number", number), date("bill_date", "2026-03-17"),
                text("patient_name", "Synthetic Patient"), text("hospital_name", hospital), date("admission_date", "2026-03-14"),
                date("discharge_date", "2026-03-17"), amount("total_amount", "106000.00")),
                List.of(item("Room rent", "19500.00"), item("Surgeon fee", "42000.00"), item("Anaesthetist fee", "12000.00"),
                        item("Operation theatre", "18500.00"), item("Pharmacy", "9250.00"), item("Laboratory", "4750.00")));
    }

    private DocumentExtraction.Result discharge() {
        return result("DISCHARGE_SUMMARY", List.of(text("patient_name", "Synthetic Patient"), text("hospital_name", hospital),
                date("admission_date", "2026-03-14"), date("discharge_date", "2026-03-17"),
                text("diagnosis", "Acute appendicitis")), List.of());
    }

    /** A submitted claim, extracted and consolidated as the workflow would. */
    private UUID consolidatedClaim(byte[] billFile, String billNumber) throws Exception {
        return consolidatedClaim(billFile, billNumber, "Synthetic Patient");
    }

    private UUID consolidatedClaim(byte[] billFile, String billNumber, String patient) throws Exception {
        Map<String, Object> body = fixtures.claimBody(policy);
        body.put("hospitalName", hospital);
        body.put("patientName", patient);
        String claim = processor.bodyOf(fixtures.postClaim(processor, body, UUID.randomUUID().toString()))
                .get("summary").get("id").asString();
        fixtures.upload(processor, claim, "CLAIM_FORM", "form.pdf", unique("claims/appendectomy/claim-form.pdf"));
        fixtures.upload(processor, claim, "HOSPITAL_BILL", "bill.pdf", billFile);
        fixtures.upload(processor, claim, "DISCHARGE_SUMMARY", "discharge.pdf", unique("claims/appendectomy/discharge-summary.pdf"));
        processor.postEmpty("/api/v1/claims/" + claim + "/submission");
        doAnswer(call -> switch (call.<DocumentExtraction.Request>getArgument(0).declaredType()) {
            case "CLAIM_FORM" -> form();
            case "HOSPITAL_BILL" -> bill(billNumber);
            default -> discharge();
        }).when(aiService).extractDocument(any());
        UUID id = UUID.fromString(claim);
        for (UUID document : intelligence.documentsToExtract(id)) {
            intelligence.extract(id, document);
        }
        intelligence.consolidate(id);
        return id;
    }

    private static RiskScreening.AnomalyResult anomaly(RiskScreening.AnomalyRequest request, boolean flagged) {
        return new RiskScreening.AnomalyResult(request.claimId(), "anomaly-1", "risk-features-1", "synthetic-claims-1", "f".repeat(64),
                5000,
                List.of(new RiskScreening.DetectorResult("ISOLATION_FOREST", flagged ? 0.7 : 0.4, 0.6, flagged ? 99.8 : 40, flagged),
                        new RiskScreening.DetectorResult("LOCAL_OUTLIER_FACTOR", flagged ? 2.1 : 1.0, 1.4, flagged ? 99.9 : 30, flagged)),
                request.features().stream()
                        .map(feature -> new RiskScreening.FeatureResult(feature.name(), feature.value() == null ? 0 : feature.value(),
                                feature.value() == null, 1, 50))
                        .toList());
    }

    private JsonNode screening(UUID claim) throws Exception {
        return processor.bodyOf(processor.getPath("/api/v1/claims/" + claim + "/risk")).get("screening");
    }

    private static List<String> ruleIds(JsonNode screening) {
        List<String> ids = new ArrayList<>();
        screening.get("indicators").forEach(indicator -> ids.add(indicator.get("ruleId").asString()));
        return ids;
    }

    @Test
    void aConsistentClaimIsLowRiskWithNothingRaisedAndNothingToSummarize() throws Exception {
        UUID claim = consolidatedClaim(unique(ClaimFixtures.BILL), "INV-" + UUID.randomUUID());
        assertThat(intelligence.screenRisk(claim).band()).isEqualTo("LOW");

        JsonNode view = screening(claim);
        assertThat(view.get("band").asString()).isEqualTo("LOW");
        assertThat(view.get("route").asString()).isEqualTo("STANDARD_REVIEW");
        assertThat(view.get("indicators")).isEmpty();
        assertThat(view.get("rules")).hasSize(22);
        assertThat(view.get("detectors")).hasSize(2);
        assertThat(view.get("summary").get("status").asString()).isEqualTo("NOT_NEEDED");
        assertThat(view.get("versions").get("ruleset").asString()).isEqualTo("risk-rules-2");
        verify(aiService, never()).summarizeRisk(any());
    }

    @Test
    void theDetectorsReceiveNumbersOnlyInTheirOrder() throws Exception {
        UUID claim = consolidatedClaim(unique(ClaimFixtures.BILL), "INV-" + UUID.randomUUID());
        intelligence.screenRisk(claim);
        ArgumentCaptor<RiskScreening.AnomalyRequest> sent = ArgumentCaptor.forClass(RiskScreening.AnomalyRequest.class);
        verify(aiService).scoreRiskAnomaly(sent.capture());
        assertThat(sent.getValue().featureVersion()).isEqualTo("risk-features-1");
        assertThat(sent.getValue().features()).extracting(RiskScreening.FeatureValue::name).containsExactly(
                "claimedAmount", "sumInsuredRatio", "lengthOfStayDays", "amountPerDay", "lineItemCount", "largestItemShare",
                "billDeviation", "priorClaims12m", "daysSinceCoverStart");
        List<Double> expected = List.of(106000.0, 0.212, 3.0, 106000.0 / 3, 6.0, 42000.0 / 106000, 0.0, 0.0, 72.0);
        for (int index = 0; index < expected.size(); index++) {
            assertThat(sent.getValue().features().get(index).value()).isCloseTo(expected.get(index), within(1e-9));
        }
    }

    @Test
    void aCopiedBillIsAFraudIndicatorThatNamesTheOtherClaimOnly() throws Exception {
        byte[] copied = unique(ClaimFixtures.BILL);
        // Another patient, so that only the copied file links the two claims.
        UUID original = consolidatedClaim(copied, "INV-" + UUID.randomUUID(), "Synthetic Other Patient");
        UUID copy = consolidatedClaim(copied, "INV-" + UUID.randomUUID());
        doAnswer(call -> anomaly(call.getArgument(0), true)).when(aiService).scoreRiskAnomaly(any());
        doAnswer(call -> {
            RiskScreening.SummaryRequest request = call.getArgument(0);
            return new RiskScreening.SummaryResult(request.claimId(), "AVAILABLE", "The bill is the same file as one in another claim [S1].",
                    List.of("S1"), null, "risk_summary.v1", List.of());
        }).when(aiService).summarizeRisk(any());

        assertThat(intelligence.screenRisk(copy).band()).isEqualTo("HIGH");
        JsonNode view = screening(copy);
        assertThat(ruleIds(view)).containsExactly("DUP-01");
        JsonNode indicator = view.get("indicators").get(0);
        assertThat(indicator.get("kind").asString()).isEqualTo("FRAUD_INDICATOR");
        assertThat(indicator.get("signalId").asString()).isEqualTo("S1");
        String originalNumber = jdbc.sql("SELECT claim_number FROM core.claims WHERE id = ?").param(original).query(String.class).single();
        assertThat(indicator.get("evidence").get(1).get("matchedClaim").get("claimNumber").asString()).isEqualTo(originalNumber);
        // 25 for the indicator, which alone is medium, and 20 for both detectors, which makes it high.
        assertThat(view.get("score").asInt()).isEqualTo(45);
        assertThat(view.get("rulesOnlyScore").asInt()).isEqualTo(25);
        assertThat(view.get("route").asString()).isEqualTo("SENIOR_REVIEW");
        assertThat(view.get("summary").get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(view.get("summary").get("signals").get("S1").asString()).isEqualTo("indicator:1");

        // The model saw de-identified signals only.
        ArgumentCaptor<RiskScreening.SummaryRequest> sent = ArgumentCaptor.forClass(RiskScreening.SummaryRequest.class);
        verify(aiService).summarizeRisk(sent.capture());
        assertThat(json.writeValueAsString(sent.getValue())).doesNotContain("Synthetic Patient", hospital, originalNumber);
        assertThat(sent.getValue().signals()).extracting(RiskScreening.Signal::id).containsExactly("S1", "S2", "S3");

        assertThat(jdbc.sql("SELECT action FROM core.audit_events WHERE stream = ?").param("claim:" + copy).query(String.class).list())
                .contains("CLAIM_RISK_SCREENED", "CLAIM_SENT_FOR_RISK_SUMMARY");
        String body = processor.getPath("/api/v1/claims/" + copy + "/risk").getContentAsString().toLowerCase();
        assertThat(body).doesNotContain("fraudulent", "confirmed fraud", "is fraud", "rejected");
    }

    @Test
    void anUnavailableSummaryLeavesTheScreeningComplete() throws Exception {
        byte[] copied = unique(ClaimFixtures.BILL);
        consolidatedClaim(copied, "INV-" + UUID.randomUUID(), "Synthetic Other Patient");
        UUID copy = consolidatedClaim(copied, "INV-" + UUID.randomUUID());
        doThrow(new Failure("ai_service_unavailable", true, List.of())).when(aiService).summarizeRisk(any());

        assertThat(intelligence.screenRisk(copy).band()).isEqualTo("MEDIUM");
        JsonNode view = screening(copy);
        assertThat(view.get("summary").get("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(view.get("summary").get("problem").asString()).isEqualTo("ai_service_unavailable");
        assertThat(ruleIds(view)).containsExactly("DUP-01");
    }

    @Test
    void theSummaryCannotChangeTheScreening() throws Exception {
        byte[] copied = unique(ClaimFixtures.BILL);
        consolidatedClaim(copied, "INV-" + UUID.randomUUID(), "Synthetic Other Patient");
        UUID copy = consolidatedClaim(copied, "INV-" + UUID.randomUUID());
        // A summary citing a signal that was never sent is refused, and nothing about the screening moves.
        doAnswer(call -> new RiskScreening.SummaryResult(
                call.<RiskScreening.SummaryRequest>getArgument(0).claimId(), "AVAILABLE", "Everything is fine [S9].", List.of("S9"), null,
                "risk_summary.v1", List.of())).when(aiService).summarizeRisk(any());

        intelligence.screenRisk(copy);
        JsonNode view = screening(copy);
        assertThat(view.get("summary").get("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(view.get("summary").get("problem").asString()).isEqualTo("summary_rejected");
        assertThat(view.get("band").asString()).isEqualTo("MEDIUM");
        assertThat(view.get("score").asInt()).isEqualTo(25);

        // Stored screenings are insert-only for the service's own database role.
        assertThatThrownBy(() -> jdbc.sql("UPDATE core.risk_assessments SET band = 'LOW' WHERE claim_id = ?").param(copy).update())
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void screeningIsIdempotentPerConsolidation() throws Exception {
        UUID claim = consolidatedClaim(unique(ClaimFixtures.BILL), "INV-" + UUID.randomUUID());
        intelligence.screenRisk(claim);
        intelligence.screenRisk(claim);
        verify(aiService, times(1)).scoreRiskAnomaly(any());
        assertThat(jdbc.sql("SELECT count(*) FROM core.risk_assessments WHERE claim_id = ?").param(claim).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void anAnomalyResultForAnotherClaimIsRefusedAndNothingIsStored() throws Exception {
        UUID claim = consolidatedClaim(unique(ClaimFixtures.BILL), "INV-" + UUID.randomUUID());
        doAnswer(call -> {
            RiskScreening.AnomalyRequest request = call.getArgument(0);
            return anomaly(new RiskScreening.AnomalyRequest(UUID.randomUUID(), request.featureVersion(), request.features()), true);
        }).when(aiService).scoreRiskAnomaly(any());
        assertThatThrownBy(() -> intelligence.screenRisk(claim)).isInstanceOf(Failure.class).hasMessageContaining("anomaly_result_mismatch");
        assertThat(screening(claim).isNull()).isTrue();
    }

    @Test
    void reviewersReadTheScreeningAndAdministratorsCannot() throws Exception {
        UUID claim = consolidatedClaim(unique(ClaimFixtures.BILL), "INV-" + UUID.randomUUID());
        intelligence.screenRisk(claim);
        assertThat(signedInAs(Role.REVIEWER).getPath("/api/v1/claims/" + claim + "/risk").getStatus()).isEqualTo(200);
        assertThat(signedInAs(Role.ADMIN).getPath("/api/v1/claims/" + claim + "/risk").getStatus()).isEqualTo(403);
    }

    @Test
    void demonstrationsExistOnlyInTheLocalEnvironment() throws Exception {
        assertThat(processor.getPath("/api/v1/demonstrations/risk").getStatus()).isEqualTo(404);
    }
}
