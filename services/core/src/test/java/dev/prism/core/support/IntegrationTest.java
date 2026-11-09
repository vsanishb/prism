package dev.prism.core.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.identity.Role;
import dev.prism.core.identity.internal.IdentityFixtures;
import dev.prism.core.workflow.WorkflowHealth;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Boots the full application against a real database and drives it through the HTTP layer. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final String PASSWORD = "synthetic-Test-passphrase-41";
    private static final Path DOCUMENT_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "prism-test-documents");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected IdentityFixtures identity;

    @MockitoBean
    protected AiServiceClient aiService;

    @MockitoBean
    protected WorkflowHealth workflowHealth;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException, GeneralSecurityException {
        TestDatabase database = TestDatabase.instance();
        registry.add("PRISM_CORE_DB_URL", database::url);
        registry.add("PRISM_CORE_DB_USER", () -> TestDatabase.CORE_LOGIN);
        registry.add("PRISM_CORE_DB_PASSWORD", database::password);
        registry.add("PRISM_WEB_BASE_URL", () -> "http://localhost:3000");
        registry.add("PRISM_AI_BASE_URL", () -> "http://127.0.0.1:9");
        registry.add("PRISM_THROTTLE_HMAC_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("PRISM_COOKIE_SECURE", () -> "false");
        registry.add("PRISM_FORWARDED_HEADER_TRUSTED", () -> "false");
        registry.add("PRISM_BOOTSTRAP_ADMIN_EMAIL", () -> "");
        registry.add("PRISM_ENVIRONMENT", () -> "test");
        registry.add("PRISM_TEMPORAL_ENABLED", () -> "false");
        registry.add("PRISM_LOG_FORMAT", () -> "");
        registry.add("PRISM_DOCUMENT_STORE_PATH", () -> DOCUMENT_ROOT.toString());
        Path key = TestKeys.writeSigningKey();
        registry.add("PRISM_SERVICE_JWT_PRIVATE_KEY_PATH", key::toString);
    }

    protected ClaimFixtures claimFixtures() {
        return new ClaimFixtures(this);
    }

    protected Client anonymous() {
        return new Client();
    }

    /** Creates a user with the role and signs them in through the real endpoint. */
    public Client signedInAs(Role role) throws Exception {
        String email = uniqueEmail();
        identity.createUser(email, role, PASSWORD);
        Client client = new Client();
        MockHttpServletResponse response = client.signIn(email, PASSWORD);
        if (response.getStatus() != 200) {
            throw new IllegalStateException("Sign-in failed with status " + response.getStatus());
        }
        client.email = email;
        client.userId = UUID.fromString(json.readTree(response.getContentAsString()).get("id").asString());
        return client;
    }

    protected static String uniqueEmail() {
        return "user." + UUID.randomUUID() + "@prism.local";
    }

    /** A browser stand-in: keeps cookies and attaches the CSRF header, as the web client does. */
    public final class Client {

        private final List<Cookie> cookies = new ArrayList<>();
        private final String address = "10.%d.%d.%d".formatted(
                ThreadLocalRandom.current().nextInt(256),
                ThreadLocalRandom.current().nextInt(256),
                ThreadLocalRandom.current().nextInt(1, 255));
        public String email;
        public UUID userId;

        public MockHttpServletResponse signIn(String email, String password) throws Exception {
            return postJson("/api/v1/auth/sign-in", Map.of("email", email, "password", password));
        }

        public MockHttpServletResponse getPath(String path) throws Exception {
            return perform(get(path));
        }

        public MockHttpServletResponse postJson(String path, Object body) throws Exception {
            ensureCsrf();
            return perform(withCsrf(post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body))));
        }

        public MockHttpServletResponse postEmpty(String path) throws Exception {
            ensureCsrf();
            return perform(withCsrf(post(path)));
        }

        public MockHttpServletResponse postWithoutCsrf(String path, Object body) throws Exception {
            return perform(post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)));
        }

        public MockHttpServletResponse postJsonWithHeader(String path, Object body, String header, String value)
                throws Exception {
            ensureCsrf();
            return perform(withCsrf(post(path)
                    .header(header, value)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body))));
        }

        public MockHttpServletResponse upload(String path, String filename, byte[] content) throws Exception {
            ensureCsrf();
            return perform(withCsrf(multipart(path).file(new MockMultipartFile("file", filename, null, content))));
        }

        public MockHttpServletResponse uploadWithoutCsrf(String path, String filename, byte[] content) throws Exception {
            return perform(multipart(path).file(new MockMultipartFile("file", filename, null, content)));
        }

        public MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
            ensureCsrf();
            return perform(withCsrf(request));
        }

        public JsonNode bodyOf(MockHttpServletResponse response) throws Exception {
            return json.readTree(response.getContentAsString());
        }

        public String cookie(String name) {
            return cookies.stream()
                    .filter(cookie -> cookie.getName().equals(name))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        private void ensureCsrf() throws Exception {
            if (cookie("XSRF-TOKEN") == null) {
                perform(get("/api/v1/auth/csrf"));
            }
        }

        private <B extends AbstractMockHttpServletRequestBuilder<B>> B withCsrf(B request) {
            return request.header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"));
        }

        private MockHttpServletResponse perform(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
            request.with(servletRequest -> {
                servletRequest.setRemoteAddr(address);
                return servletRequest;
            });
            if (!cookies.isEmpty()) {
                request.cookie(cookies.toArray(Cookie[]::new));
            }
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            for (Cookie cookie : response.getCookies()) {
                cookies.removeIf(existing -> existing.getName().equals(cookie.getName()));
                if (cookie.getMaxAge() != 0 && !cookie.getValue().isEmpty()) {
                    cookies.add(cookie);
                }
            }
            return response;
        }
    }
}
