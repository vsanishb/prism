package dev.prism.core.aiclient.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.prism.core.aiclient.AiServiceClient.PingResult;
import dev.prism.core.aiclient.AiServiceClient.ProbeResult;
import dev.prism.core.platform.config.PrismProperties;
import dev.prism.core.support.TestKeys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Exercises real HTTP, because signing depends on what actually crosses the wire. */
class HttpAiServiceClientTest {

    private record Received(String authorization, String upgrade, byte[] body) {}

    private final AtomicReference<Received> received = new AtomicReference<>();
    private HttpServer server;
    private HttpAiServiceClient client;
    private int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/ping", this::handle);
        server.start();
        Path key = TestKeys.writeSigningKey();
        PrismProperties properties = new PrismProperties(
                "test",
                URI.create("http://localhost"),
                new PrismProperties.Security("key", false, false, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1)),
                new PrismProperties.ServiceAuth(key.toString(), "core-test", Duration.ofSeconds(60)),
                new PrismProperties.Ai(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(2)),
                new PrismProperties.Bootstrap("", ""),
                new PrismProperties.Seed(""));
        client = new HttpAiServiceClient(RestClient.builder(), new ServiceTokenSigner(properties), properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.set(new Received(
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Upgrade"),
                body));
        byte[] response = "{\"service\":\"prism-ai\",\"knowledgeDatabaseConnected\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @Test
    void signedTokenMatchesTheBytesSentOverHttp() throws Exception {
        PingResult result = client.ping();

        assertThat(result.authenticated()).isTrue();
        assertThat(result.knowledgeDatabaseConnected()).isTrue();
        Received request = received.get();
        assertThat(request.upgrade()).as("HTTP/2 upgrade breaks Uvicorn").isNull();
        SignedJWT token = SignedJWT.parse(request.authorization().substring("Bearer ".length()));
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo("POST /internal/v1/ping");
        assertThat(token.getJWTClaimsSet().getStringClaim("body_sha256"))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.body())));
    }

    @Test
    void reportsARefusedToken() {
        status = 401;

        PingResult result = client.ping();

        assertThat(result.reachable()).isTrue();
        assertThat(result.authenticated()).isFalse();
    }

    @Test
    void probeSendsNoCredentials() {
        status = 401;

        ProbeResult result = client.probeUnsignedRequest();

        assertThat(result.rejected()).isTrue();
        assertThat(received.get().authorization()).isNull();
    }

    @Test
    void reportsAnUnreachableService() {
        server.stop(0);

        assertThat(client.ping().reachable()).isFalse();
        assertThat(client.probeUnsignedRequest().reachable()).isFalse();
    }
}
