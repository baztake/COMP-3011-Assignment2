/* Adapted from Assignment 1 server with assistance from OpenAI Codex. */
package comp3011.subtitleserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configures bounded provider waits; credentials remain on the server.
 */
@Configuration
public class SpringConfiguration {

    /**
     * @param builder        auto-configured HTTP client builder
     * @param baseUrl        transcription provider base URL
     * @param apiKey         server-side credential, empty when only cached results are available
     * @param connectSeconds connection timeout
     * @param readSeconds    response timeout
     * @return transcription HTTP client
     */
    @Bean
    RestClient transcriptionClient(
        RestClient.Builder builder,
        @Value("${transcription.base-url}") String baseUrl,
        @Value("${transcription.api-key}") String apiKey,
        @Value("${transcription.connect-timeout-seconds}") int connectSeconds,
        @Value("${transcription.read-timeout-seconds}") int readSeconds
    ) {
        if (connectSeconds < 1 || readSeconds < 1) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readSeconds));

        return builder.baseUrl(baseUrl).requestFactory(factory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).build();
    }
}
