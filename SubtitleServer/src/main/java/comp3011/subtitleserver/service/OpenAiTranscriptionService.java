/* Adapted from Assignment 1 server with assistance from OpenAI Codex. */
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

        // Build request body
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        String filename = audio.getOriginalFilename();
        int separator = filename.lastIndexOf('.');
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentDispositionFormData("file", "audio" + filename.substring(separator).toLowerCase(java.util.Locale.ROOT));
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        body.add("file", new HttpEntity<>(audio.getResource(), fileHeaders));
        body.add("model", "whisper-1");
        body.add("response_format", "verbose_json");
        body.add("timestamp_granularities[]", "word");
        body.add("timestamp_granularities[]", "segment");

        try {
            OpenAiTranscriptionResult result = client.post().uri("/audio/transcriptions").contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(OpenAiTranscriptionResult.class);
            if (result == null) {
                throw new SubtitleException(HttpStatus.BAD_GATEWAY, "Transcription provider returned no data");
            }

            return result;
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status == 400 || status == 413 || status == 415 || status == 422) {
                throw new SubtitleException(HttpStatus.BAD_REQUEST, "Transcription provider rejected the audio chunk");
            }
            if (status == 429 || status == 401 || status == 403) {
                throw new SubtitleException(HttpStatus.SERVICE_UNAVAILABLE, "Transcription provider is unavailable; check server credentials and quota");
            }
            throw new SubtitleException(HttpStatus.BAD_GATEWAY, "Transcription provider failed; retry later");
        } catch (ResourceAccessException error) {
            throw new SubtitleException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot reach transcription provider; retry later");
        } catch (RestClientException error) {
            // A timeout while consuming the response can be wrapped instead of exposed as ResourceAccessException.
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.io.InterruptedIOException || cause instanceof java.net.SocketException || cause instanceof java.net.UnknownHostException || cause instanceof java.net.http.HttpTimeoutException) {
                    throw new SubtitleException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot reach transcription provider; retry later");
                }
            }
            throw new SubtitleException(HttpStatus.BAD_GATEWAY, "Transcription provider returned invalid timing data");
        } catch (IllegalArgumentException error) {
            throw new SubtitleException(HttpStatus.BAD_GATEWAY, "Transcription provider returned invalid timing data");
        }
    }
}
