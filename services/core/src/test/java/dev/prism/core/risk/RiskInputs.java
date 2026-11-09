package dev.prism.core.risk;

import dev.prism.core.risk.RiskInput.ClaimDetails;
import dev.prism.core.risk.RiskInput.Document;
import dev.prism.core.risk.RiskInput.Fact;
import dev.prism.core.risk.RiskInput.Finding;
import dev.prism.core.risk.RiskInput.History;
import dev.prism.core.risk.RiskInput.ItemKey;
import dev.prism.core.risk.RiskInput.LineItem;
import dev.prism.core.risk.RiskInput.OtherBill;
import dev.prism.core.risk.RiskInput.OtherClaim;
import dev.prism.core.risk.RiskInput.PolicyDetails;
import dev.prism.core.risk.RiskInput.SharedFile;
import dev.prism.core.risk.RiskInput.Source;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds risk inputs for tests. {@link #clean()} is a consistent claim on which no rule should fire:
 * each test changes one thing, so a rule's positive and negative cases differ only in that.
 */
final class RiskInputs {

    static final String HOSPITAL = "Nilaya Riverside Hospital, Kochi";
    static final String PATIENT = "Vikram Nair";

    final UUID claimId = UUID.randomUUID();
    final UUID form = UUID.randomUUID();
    final UUID bill = UUID.randomUUID();
    final UUID discharge = UUID.randomUUID();

    String type = "REIMBURSEMENT";
    String patient = PATIENT;
    String hospital = HOSPITAL;
    LocalDate admission = LocalDate.parse("2026-07-07");
    LocalDate dischargeDate = LocalDate.parse("2026-07-10");
    BigDecimal claimed = new BigDecimal("98400.00");
    String currency = "INR";
    BigDecimal sumInsured = new BigDecimal("500000.00");
    String policyCurrency = "INR";
    LocalDate coverStart = LocalDate.parse("2026-01-01");

    final List<Document> documents = new ArrayList<>();
    final List<Fact> facts = new ArrayList<>();
    final List<LineItem> items = new ArrayList<>();
    final List<Finding> findings = new ArrayList<>();
    final List<SharedFile> sharedFiles = new ArrayList<>();
    final List<OtherBill> otherBills = new ArrayList<>();
    final List<OtherClaim> otherClaims = new ArrayList<>();

    static RiskInputs clean() {
        RiskInputs inputs = new RiskInputs();
        inputs.documents.add(new Document(inputs.form, "CLAIM_FORM"));
        inputs.documents.add(new Document(inputs.bill, "HOSPITAL_BILL"));
        inputs.documents.add(new Document(inputs.discharge, "DISCHARGE_SUMMARY"));
        inputs.claimFact("policy_number", "NGI-RSK-2026-0003");
        inputs.claimFact("patient_name", PATIENT);
        inputs.claimFact("hospital_name", HOSPITAL);
        inputs.claimFact("admission_date", "2026-07-07");
        inputs.claimFact("discharge_date", "2026-07-10");
        inputs.claimFact("claimed_amount", "98400.00");
        inputs.claimFact("diagnosis", "Acute appendicitis");
        inputs.documentFact(inputs.bill, "bill_number", "GF-2026-05521");
        inputs.documentFact(inputs.bill, "bill_date", "2026-07-10");
        inputs.documentAmount(inputs.bill, "total_amount", "98400.00");
        inputs.item(inputs.bill, "Room rent (single private)", "15600.00");
        inputs.item(inputs.bill, "Surgeon fee: laparoscopic appendectomy", "42000.00");
        inputs.item(inputs.bill, "Anaesthetist fee", "11000.00");
        inputs.item(inputs.bill, "Operation theatre charges", "16500.00");
        inputs.item(inputs.bill, "Pharmacy and consumables", "7600.00");
        inputs.item(inputs.bill, "Laboratory investigations", "3900.00");
        inputs.item(inputs.bill, "Nursing charges", "1800.00");
        return inputs;
    }

    RiskInputs claimFact(String name, String value) {
        facts.removeIf(fact -> fact.documentId() == null && fact.name().equals(name));
        facts.add(new Fact(UUID.randomUUID(), name, "PRESENT", value, amountOf(name, value), null,
                List.of(new Source(form, 1, value))));
        return this;
    }

    RiskInputs claimFactState(String name, String state, String... sourceValues) {
        facts.removeIf(fact -> fact.documentId() == null && fact.name().equals(name));
        List<Source> sources = new ArrayList<>();
        List<UUID> from = List.of(form, bill, discharge);
        for (int index = 0; index < sourceValues.length; index++) {
            sources.add(new Source(from.get(index % from.size()), 1, sourceValues[index]));
        }
        facts.add(new Fact(UUID.randomUUID(), name, state, null, null, null, sources));
        return this;
    }

    RiskInputs documentFact(UUID document, String name, String value) {
        facts.removeIf(fact -> document.equals(fact.documentId()) && fact.name().equals(name));
        facts.add(new Fact(UUID.randomUUID(), name, "PRESENT", value, null, document, List.of(new Source(document, 1, value))));
        return this;
    }

    RiskInputs documentAmount(UUID document, String name, String value) {
        facts.removeIf(fact -> document.equals(fact.documentId()) && fact.name().equals(name));
        facts.add(new Fact(UUID.randomUUID(), name, "PRESENT", value, new BigDecimal(value), document,
                List.of(new Source(document, 1, value))));
        return this;
    }

    RiskInputs documentMissing(UUID document, String name) {
        facts.removeIf(fact -> document.equals(fact.documentId()) && fact.name().equals(name));
        facts.add(new Fact(UUID.randomUUID(), name, "MISSING", null, null, document, List.of()));
        return this;
    }

    RiskInputs item(UUID document, String description, String amount) {
        items.add(new LineItem(UUID.randomUUID(), document, description, new BigDecimal(amount), "PRESENT", 1));
        return this;
    }

    RiskInputs finding(String code, String field, UUID document, String expected, String actual) {
        findings.add(new Finding(code, field, document, expected, actual));
        return this;
    }

    UUID addDocument(String documentType) {
        UUID id = UUID.randomUUID();
        documents.add(new Document(id, documentType));
        return id;
    }

    List<ItemKey> billItemKeys() {
        return items.stream()
                .filter(item -> item.documentId().equals(bill))
                .map(item -> new ItemKey(item.description(), item.amount()))
                .toList();
    }

    OtherClaim otherClaim(String patientName, String hospitalName, String from, String to, String amount) {
        OtherClaim other = new OtherClaim(UUID.randomUUID(), "CLM-2026-0000" + (otherClaims.size() + 1), patientName, hospitalName,
                LocalDate.parse(from), LocalDate.parse(to), new BigDecimal(amount));
        otherClaims.add(other);
        return other;
    }

    RiskInput build() {
        return new RiskInput(
                new ClaimDetails(claimId, type, patient, hospital, admission, dischargeDate, claimed, currency),
                new PolicyDetails(sumInsured, policyCurrency, coverStart),
                List.copyOf(documents),
                List.copyOf(facts),
                List.copyOf(items),
                List.copyOf(findings),
                new History(List.copyOf(sharedFiles), List.copyOf(otherBills), List.copyOf(otherClaims)));
    }

    private static BigDecimal amountOf(String name, String value) {
        return name.endsWith("amount") ? new BigDecimal(value) : null;
    }
}
