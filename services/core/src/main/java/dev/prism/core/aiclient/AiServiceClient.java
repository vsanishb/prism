package dev.prism.core.aiclient;

/** The only way the core service talks to the AI service. */
public interface AiServiceClient {

    /** Makes a signed call, proving the AI service is reachable and accepts core's identity. */
    PingResult ping();

    /** Makes a deliberately unsigned call, proving the AI service refuses unauthenticated traffic. */
    ProbeResult probeUnsignedRequest();

    record PingResult(boolean reachable, boolean authenticated, boolean knowledgeDatabaseConnected, String detail) {}

    record ProbeResult(boolean reachable, boolean rejected, int statusCode) {}
}
