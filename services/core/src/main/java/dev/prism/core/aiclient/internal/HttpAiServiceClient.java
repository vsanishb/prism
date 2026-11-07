package dev.prism.core.aiclient.internal;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.aiclient.DocumentExtraction;
import dev.prism.core.aiclient.DocumentExtraction.Failure;
import dev.prism.core.aiclient.DocumentExtraction.ModelRun;
import dev.prism.core.aiclient.PolicyExtraction;
import dev.prism.core.platform.config.PrismProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class HttpAiServiceClient implements AiServiceClient {

    private static final String PING_PATH = "/internal/v1/ping";
    private static final String EXTRACT_PATH = "/internal/v1/documents/extract";
    private static final String POLICY_PAGES_PATH = "/internal/v1/policies/extract-pages";

    private final RestClient signed;
    private final RestClient unsigned;
    private final RestClient extraction;

    HttpAiServiceClient(RestClient.Builder builder, ServiceTokenSigner signer, PrismProperties properties) {
        Duration timeout = properties.ai().timeout();
        RestClient.Builder base = builder.clone().baseUrl(properties.ai().baseUrl().toString());
        this.unsigned = base.clone().requestFactory(requestFactory(timeout, timeout)).build();
        RestClient.Builder authenticated = base.clone().requestInterceptor((request, body, execution) -> {
            String operation = request.getMethod() + " " + request.getURI().getPath();
            request.getHeaders().setBearerAuth(signer.sign(operation, body));
            return execution.execute(request, body);
        });
        this.signed = authenticated.clone().requestFactory(requestFactory(timeout, timeout)).build();
        this.extraction = authenticated.clone()
                .requestFactory(requestFactory(timeout, properties.ai().extractionTimeout()))
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration connect, Duration read) {
        // HTTP/1.1 is required: the default h2c upgrade attempt is not understood by Uvicorn, which
        // then loses the request body, and the signed body hash no longer matches.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connect)
                .build());
        factory.setReadTimeout(read);
        return factory;
    }

    @Override
    public DocumentExtraction.Result extractDocument(DocumentExtraction.Request request) {
        return post(EXTRACT_PATH, request, DocumentExtraction.Result.class);
    }

    @Override
    public PolicyExtraction.Result extractPolicyPages(PolicyExtraction.Request request) {
        return post(POLICY_PAGES_PATH, request, PolicyExtraction.Result.class);
    }

    private <T> T post(String path, Object request, Class<T> resultType) {
        try {
            return extraction.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return response.bodyTo(resultType);
                        }
                        throw failure(response.getStatusCode().value(), readFailure(response));
                    });
        } catch (ResourceAccessException exception) {
            throw new Failure("ai_service_unavailable", true, List.of());
        }
    }

    private static ProcessingFailure readFailure(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(ProcessingFailure.class);
        } catch (RestClientException exception) {
            return null;
        }
    }

    /** Anything else here is a defect or a broken deployment, which a retry would only repeat. */
    private static Failure failure(int status, ProcessingFailure body) {
        if (body != null) {
            return new Failure(body.code(), body.retryable(), body.runs());
        }
        boolean serverSide = status >= HttpStatus.INTERNAL_SERVER_ERROR.value();
        return new Failure(serverSide ? "ai_service_error" : "ai_request_refused", serverSide, List.of());
    }

    @Override
    public PingResult ping() {
        try {
            PingResponse response = signed.post()
                    .uri(PING_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .body(PingResponse.class);
            boolean database = response != null && response.knowledgeDatabaseConnected();
            return new PingResult(true, true, database, database ? "Connected" : "Knowledge database unavailable");
        } catch (RestClientResponseException exception) {
            boolean refused = exception.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED);
            return new PingResult(true, false, false, refused ? "Signed request was refused" : "AI service returned an error");
        } catch (ResourceAccessException exception) {
            return new PingResult(false, false, false, "AI service is unreachable");
        }
    }

    @Override
    public ProbeResult probeUnsignedRequest() {
        try {
            int status = unsigned.post()
                    .uri(PING_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .exchange((request, response) -> response.getStatusCode().value());
            return new ProbeResult(true, status == HttpStatus.UNAUTHORIZED.value(), status);
        } catch (ResourceAccessException exception) {
            return new ProbeResult(false, false, 0);
        }
    }

    private record PingResponse(String service, boolean knowledgeDatabaseConnected) {}

    private record ProcessingFailure(String code, boolean retryable, List<ModelRun> runs) {}
}
