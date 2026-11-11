package au.edu.adelaide.assignment1;

import au.edu.adelaide.assignment1.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration class for defining beans and their dependencies.
 */
@Configuration
public class SpringConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SpringConfiguration.class);

    /**
     * Creates a RestClient bean configured with the base URL and API key for the speech-to-text service.
     *
     * @param builder the RestClient.Builder used to create the RestClient
     * @param baseUrl the base URL of the speech-to-text service, injected from application properties
     * @param apiKey  the API key for authentication, injected from application properties
     * @return a configured RestClient instance
     */
    @Bean
    RestClient sttClient(
        RestClient.Builder builder,
        @Value("${transcription.service.provider.base-url}") String baseUrl,
        @Value("${transcription.service.provider.api-key}") String apiKey
    ) {
        log.info("Configuring STT RestClient; baseUrl={}", baseUrl);
        return builder
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .build();
    }

}
