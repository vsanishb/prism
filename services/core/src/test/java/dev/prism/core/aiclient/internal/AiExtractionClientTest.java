package dev.prism.core.aiclient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import dev.prism.core.aiclient.DocumentExtraction;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.support.TestKeys;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Exercises real HTTP: signing and failure mapping depend on what actually crosses the wire. */
class AiExtractionClientTest {

    private static final String RESULT = """
            {"pipelineVersion":"doc-1","pageCount":1,
             "classification":{"documentType":"HOSPITAL_BILL","confidence":"HIGH","declaredType":"HOSPITAL_BILL"},
             "fields":[{"name":"total_amount","kind":"AMOUNT","critical":true,"state":"PRESENT","confidence":"HIGH",
                        "rawValue":"1,06,000.00","value":"106000.00","page":1,"quote":"Total 1,06,000.00",
                        "region":[0.1,0.2,0.3,0.4],"grounding":"TEXT_MATCH","reason":null}],
             "lineItems":[{"description":"Room","quantity":"1","unitPrice":null,"amount":"100.00","rawAmount":"100.00",
                           "state":"PRESENT","page":1,"quote":"Room 100.00","region":null,"grounding":"PAGE_ONLY"}],
             "runs":[{"task":"extraction","model":"m","provider":"Google","promptVersion":"extract.v1","attempt":1,
                      "outcome":"SUCCEEDED","inputTokens":10,"outputTokens":5,"costUsd":"0.0012","latencyMs":40}]}
            """;

    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private HttpServer server;
    private HttpAiServiceClient client;
    private int status = 200;
    private String response = RESULT;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/documents/extract", exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = clientFor(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static HttpAiServiceClient clientFor(URI target) throws Exception {
        Path key = TestKeys.writeSigningKey();
        PrismProperties properties = new PrismProperties(
                "test",
                URI.create("http://localhost"),
                "INR",
                new PrismProperties.Security("key", false, false, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1)),
                new PrismProperties.ServiceAuth(key.toString(), "core-test", Duration.ofSeconds(60)),
                new PrismProperties.Documents("store", 1000, 1000, 10, 10, 1000, 100),
                new PrismProperties.Ai(target, Duration.ofSeconds(2), Duration.ofSeconds(4)),
                new PrismProperties.Temporal(false, "localhost:1", "default", Duration.ofDays(1), 1, Duration.ofSeconds(1)),
                new PrismProperties.Bootstrap("", ""),
                new PrismProperties.Seed("", ""));
        return new HttpAiServiceClient(RestClient.builder(), new ServiceTokenSigner(properties), properties);
    }

    private static DocumentExtraction.Request request() {
        return new DocumentExtraction.Request(
                UUID.randomUUID(), "HOSPITAL_BILL", "application/pdf", "%PDF synthetic".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void aResultIsReadWithItsTypes() {
        DocumentExtraction.Result result = client.extractDocument(request());

        assertThat(result.fields().getFirst().value()).isEqualTo("106000.00");
        assertThat(result.fields().getFirst().region()).hasSize(4);
        assertThat(result.lineItems().getFirst().amount()).isEqualByComparingTo("100.00");
        assertThat(result.runs().getFirst().costUsd()).isEqualByComparingTo("0.0012");
    }

    @Test
    void theRequestIsSignedAndCarriesTheDocumentAsBase64() throws Exception {
        client.extractDocument(request());

        String text = new String(body.get(), StandardCharsets.UTF_8);
        assertThat(text).contains(Base64.getEncoder().encodeToString("%PDF synthetic".getBytes(StandardCharsets.US_ASCII)));
        assertThat(text).contains("\"declaredType\":\"HOSPITAL_BILL\"");
        SignedJWT token = SignedJWT.parse(authorization.get().substring("Bearer ".length()));
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo("POST /internal/v1/documents/extract");
    }

    @Test
    void aReportedFailureKeepsItsCodeRetryFlagAndUsage() {
        status = 503;
        response = """
                {"code":"model_unavailable","retryable":true,
                 "runs":[{"task":"extraction","model":"m","provider":null,"promptVersion":"extract.v1","attempt":1,
                          "outcome":"UNAVAILABLE","inputTokens":null,"outputTokens":null,"costUsd":null,"latencyMs":40}]}
                """;

        assertThatThrownBy(() -> client.extractDocument(request()))
                .isInstanceOfSatisfying(Failure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("model_unavailable");
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.runs()).hasSize(1);
                });
    }

    @Test
    void aPermanentFailureIsNotRetryable() {
        status = 422;
        response = "{\"code\":\"document_unreadable\",\"retryable\":false,\"runs\":[]}";

        assertThatThrownBy(() -> client.extractDocument(request()))
                .isInstanceOfSatisfying(Failure.class, failure -> assertThat(failure.retryable()).isFalse());
    }

    @Test
    void anUnexpectedServerErrorIsRetryable() {
        status = 500;
        response = "Internal Server Error";

        assertThatThrownBy(() -> client.extractDocument(request()))
                .isInstanceOfSatisfying(Failure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ai_service_error");
                    assertThat(failure.retryable()).isTrue();
                });
    }

    @Test
    void aRefusedRequestIsNotRetryable() {
        status = 401;
        response = "{\"detail\":\"Unauthorized\"}";

        assertThatThrownBy(() -> client.extractDocument(request()))
                .isInstanceOfSatisfying(Failure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ai_request_refused");
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void anUnreachableServiceIsRetryable() throws Exception {
        HttpAiServiceClient unreachable = clientFor(URI.create("http://127.0.0.1:9"));

        assertThatThrownBy(() -> unreachable.extractDocument(request()))
                .isInstanceOfSatisfying(Failure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ai_service_unavailable");
                    assertThat(failure.retryable()).isTrue();
                });
    }
}
