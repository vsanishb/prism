package dev.prism.core.risk.internal;

import dev.prism.core.risk.Evidence;
import dev.prism.core.risk.RiskInput;
import dev.prism.core.risk.RiskInput.Document;
import dev.prism.core.risk.RiskInput.Fact;
import dev.prism.core.risk.RiskInput.LineItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Lookups over one claim's facts, shared by rules and features. Only PRESENT values are ever used as values. */
final class Facts {

    static final Set<String> BILLS = Set.of("HOSPITAL_BILL", "ITEMIZED_INVOICE");
    static final String PRESENT = "PRESENT";

    private final RiskInput input;
    private final Map<UUID, Document> documents;

    Facts(RiskInput input) {
        this.input = input;
        this.documents = input.documents().stream().collect(Collectors.toMap(Document::id, Function.identity()));
    }

    Optional<Fact> claimFact(String name) {
        return input.facts().stream()
                .filter(fact -> fact.documentId() == null && name.equals(fact.name()))
                .findFirst();
    }

    Optional<Fact> documentFact(UUID documentId, String name) {
        return input.facts().stream()
                .filter(fact -> documentId.equals(fact.documentId()) && name.equals(fact.name()))
                .findFirst();
    }

    Optional<String> documentValue(UUID documentId, String name) {
        return documentFact(documentId, name).filter(fact -> PRESENT.equals(fact.state())).map(Fact::value);
    }

    Optional<LocalDate> documentDate(UUID documentId, String name) {
        return documentValue(documentId, name).map(LocalDate::parse);
    }

    Optional<BigDecimal> documentAmount(UUID documentId, String name) {
        return documentFact(documentId, name)
                .filter(fact -> PRESENT.equals(fact.state()))
                .map(Fact::amount)
                .filter(Objects::nonNull);
    }

    List<Document> documentsOf(Set<String> types) {
        return input.documents().stream().filter(document -> types.contains(document.type())).toList();
    }

    List<LineItem> items(UUID documentId) {
        return input.lineItems().stream().filter(item -> documentId.equals(item.documentId())).toList();
    }

    List<LineItem> presentItems(UUID documentId) {
        return items(documentId).stream().filter(item -> PRESENT.equals(item.state()) && item.amount() != null).toList();
    }

    /** The sum of every bill's stated total, or empty unless every bill states one: a partial sum misleads. */
    Optional<BigDecimal> billsTotal() {
        List<Document> bills = documentsOf(BILLS);
        if (bills.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Document bill : bills) {
            Optional<BigDecimal> stated = documentAmount(bill.id(), "total_amount");
            if (stated.isEmpty()) {
                return Optional.empty();
            }
            total = total.add(stated.get());
        }
        return Optional.of(total);
    }

    String typeOf(UUID documentId) {
        Document document = documents.get(documentId);
        return document == null ? "OTHER" : document.type();
    }

    /** One line of evidence per document that stated the fact, with what it said. */
    List<Evidence> sourceEvidence(Fact fact) {
        return fact.sources().stream()
                .map(source -> Evidence.ofFact(
                        Wording.page(typeOf(source.documentId()), source.page()),
                        source.value() == null ? null : "Reads: " + source.value(),
                        source.documentId(),
                        source.page(),
                        fact.id()))
                .toList();
    }

    List<Evidence> documentFactEvidence(Document document, String name, String detail) {
        Optional<Fact> fact = documentFact(document.id(), name);
        Integer page = fact.flatMap(found -> found.sources().stream().map(RiskInput.Source::page).filter(Objects::nonNull).findFirst())
                .orElse(null);
        return List.of(Evidence.ofFact(
                Wording.page(document.type(), page), detail, document.id(), page, fact.map(Fact::id).orElse(null)));
    }

    /** Validation findings with this code, for this field, or for any field when it is null. */
    static List<RiskInput.Finding> findings(RiskInput input, String code, String field) {
        return input.findings().stream()
                .filter(finding -> code.equals(finding.code()) && (field == null || field.equals(finding.field())))
                .toList();
    }
}
