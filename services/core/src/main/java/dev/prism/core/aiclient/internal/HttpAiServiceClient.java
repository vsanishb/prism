package dev.prism.core.aiclient.internal;

import dev.prism.core.aiclient.AiServiceClient;
import dev.prism.core.platform.config.PrismProperties;
import java.net.http.HttpClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class HttpAiServiceClient implements AiServiceClient {

    private static final String PING_PATH = "/internal/v1/ping";

    private final RestClient signed;
    private final RestClient unsigned;

    HttpAiServiceClient(RestClient.Builder builder, ServiceTokenSigner signer, PrismProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.ai().timeout()).build());
        factory.setReadTimeout(properties.ai().timeout());
        RestClient.Builder base = builder.clone()
                .baseUrl(properties.ai().baseUrl().toString())
                .requestFactory(factory);
        this.unsigned = base.clone().build();
        this.signed = base.clone()
                .requestInterceptor((request, body, execution) -> {
                    String operation = request.getMethod() + " " + request.getURI().getPath();
                    request.getHeaders().setBearerAuth(signer.sign(operation, body));
                    return execution.execute(request, body);
                })
                .build();
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
}
