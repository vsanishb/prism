package dev.prism.core.risk;

import java.util.UUID;

/**
 * What an indicator rests on: a document page, a fact, a line item, or another claim. Another claim
 * is referenced by ID only, so that none of its content is shown in this one.
 *
 * @param label where to look, such as "Hospital bill, page 1"
 * @param detail what was found there, or null
 */
public record Evidence(
        String label, String detail, UUID documentId, Integer page, UUID factId, UUID lineItemId, UUID matchedClaimId) {

    public static Evidence onPage(String label, String detail, UUID documentId, Integer page) {
        return new Evidence(label, detail, documentId, page, null, null, null);
    }

    public static Evidence ofFact(String label, String detail, UUID documentId, Integer page, UUID factId) {
        return new Evidence(label, detail, documentId, page, factId, null, null);
    }

    public static Evidence ofLineItem(String label, String detail, UUID documentId, Integer page, UUID lineItemId) {
        return new Evidence(label, detail, documentId, page, null, lineItemId, null);
    }

    public static Evidence otherClaim(String label, String detail, UUID matchedClaimId) {
        return new Evidence(label, detail, null, null, null, null, matchedClaimId);
    }
}
