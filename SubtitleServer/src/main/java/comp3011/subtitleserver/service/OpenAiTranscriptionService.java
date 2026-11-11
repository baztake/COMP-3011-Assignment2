/* Subtitle server prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. Adapted from Assignment 1. */
package comp3011.subtitleserver.service;

import comp3011.subtitleserver.dto.OpenAiTranscriptionResult;
import comp3011.subtitleserver.exception.SubtitleException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

/**
 * Performs one bounded Whisper request; failures are not automatically retried or billed locally.
 */
@Service
public class OpenAiTranscriptionService {

    private final RestClient client;
    private final String apiKey;

    /**
     * @param client configured transcription client
     * @param apiKey server-side API credential
     */
    public OpenAiTranscriptionService(RestClient client, @Value("${transcription.api-key}") String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    /**
     * @param audio validated encoded audio chunk
     * @return validated word and segment timing response
     * @throws SubtitleException if credentials, connectivity or the provider response prevent transcription
     */
    public OpenAiTranscriptionResult transcribe(MultipartFile audio) {
        if (apiKey.isBlank()) {
            throw new SubtitleException(HttpStatus.SERVICE_UNAVAILABLE, "Set OPENAI_API_KEY on the subtitle server");
        }

        // Do not retry, cloud may have processed audio before a connection failed.
        try {
            OpenAiTranscriptionResult result = client.post()
                .uri("/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(buildRequest(audio))
                .retrieve()
                .body(OpenAiTranscriptionResult.class);
            if (result == null) {
                throw new SubtitleException(HttpStatus.BAD_GATEWAY, "Transcription provider returned no data");
            }

            return result;
        } catch (RestClientResponseException error) {
            throw providerFailure(error.getStatusCode().value());
        } catch (RestClientException | IllegalArgumentException error) {
            if (isConnectionFailure(error)) {
                throw new SubtitleException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot reach transcription provider; retry later");
            }
            throw new SubtitleException(HttpStatus.BAD_GATEWAY, "Transcription provider returned invalid timing data");
        }
    }

    private MultiValueMap<String, Object> buildRequest(MultipartFile audio) {
        // Keep the real encoding extension, but do not forward a user's filename or local path.
        String filename = audio.getOriginalFilename();
        String extension = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentDispositionFormData("file", "audio" + extension);
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(audio.getResource(), fileHeaders));
        body.add("model", "whisper-1");
        body.add("response_format", "verbose_json");
        // Word timings support wrapping, while segments provide phrase boundaries and silence confidence.
        body.add("timestamp_granularities[]", "word");
        body.add("timestamp_granularities[]", "segment");
        return body;
    }

    private SubtitleException providerFailure(int status) {
        // Return our own messages because provider errors can include credentials or request details.
        return switch (status) {
            case 400, 413, 415, 422 -> new SubtitleException(HttpStatus.BAD_REQUEST,
                "Transcription provider rejected the audio chunk");
            case 401, 403, 429 -> new SubtitleException(HttpStatus.SERVICE_UNAVAILABLE,
                "Transcription provider is unavailable; check server credentials and quota");
            default -> new SubtitleException(HttpStatus.BAD_GATEWAY, "Transcription provider failed; retry later");
        };
    }

    private boolean isConnectionFailure(Throwable error) {
        // Spring may wrap a read timeout inside another exception when consuming the response body.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException || cause instanceof InterruptedIOException
                || cause instanceof SocketException || cause instanceof UnknownHostException
                || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
