package dev.prism.core.aiclient;

/** The only way the core service talks to the AI service. */
public interface AiServiceClient {

    /** Makes a signed call, proving the AI service is reachable and accepts core's identity. */
    PingResult ping();

    /** Makes a deliberately unsigned call, proving the AI service refuses unauthenticated traffic. */
    ProbeResult probeUnsignedRequest();

    /**
     * Classifies and extracts one document.
     *
     * @throws DocumentExtraction.Failure with a stable code, and whether a retry could help
     */
    DocumentExtraction.Result extractDocument(DocumentExtraction.Request request);

    /**
     * Reads a batch of policy pages into provisions.
     *
     * @throws DocumentExtraction.Failure with a stable code, and whether a retry could help
     */
    PolicyExtraction.Result extractPolicyPages(PolicyExtraction.Request request);

    /**
     * Builds or replaces a published version's search index. Safe to repeat.
     *
     * @throws DocumentExtraction.Failure with a stable code, and whether a retry could help
     */
    PolicySearch.IndexResult indexPolicyVersion(PolicySearch.IndexRequest request);

    /**
     * Searches one version's index.
     *
     * @throws DocumentExtraction.Failure with a stable code, and whether a retry could help
     */
    PolicySearch.SearchResult searchPolicyVersion(PolicySearch.SearchRequest request);

    record PingResult(boolean reachable, boolean authenticated, boolean knowledgeDatabaseConnected, String detail) {}

    record ProbeResult(boolean reachable, boolean rejected, int statusCode) {}
}
