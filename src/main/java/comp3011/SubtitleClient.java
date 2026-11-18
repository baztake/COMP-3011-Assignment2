/* Subtitle client prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends audio only to the subtitle server. No OpenAI credential belongs in the player.
 */
public final class SubtitleClient implements AutoCloseable {

    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(3);
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * @param endpoint subtitle server URI
     */
    public SubtitleClient(URI endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Call on the subtitle worker, never JavaFX. Retries are limited to connection failures
     * and unavailable responses explicitly marked retryable by the server.
     *
     * @param wav bounded WAV audio bytes
     * @return validated server timing data
     * @throws IOException          if the server is unavailable or returns invalid data
     * @throws InterruptedException if loading was cancelled
     */
    public SubtitleTranscript transcribe(byte[] wav) throws IOException, InterruptedException {
        // Build request
        HttpEntity multipart = MultipartEntityBuilder.create()
            .addBinaryBody("file", wav, ContentType.create("audio/wav"), "audio.wav")
            .build();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        multipart.writeTo(body);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(135))
            .header("Content-Type", multipart.getContentType())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();

        HttpResponse<String> response = sendWithRetries(request);

        if (response.statusCode() != 200) {
            // Prevent leaking sensitive info from server
            throw new IOException("Subtitle server returned HTTP " + response.statusCode());
        }

        try {
            SubtitleTranscript transcript = mapper.readValue(response.body(), SubtitleTranscript.class);
            if (transcript == null) {
                throw new IllegalArgumentException("Missing transcript");
            }

            return transcript;
        } catch (RuntimeException invalid) {
            throw new IOException("Subtitle server returned invalid timing data", invalid);
        }
    }

    private HttpResponse<String> sendWithRetries(HttpRequest request) throws IOException, InterruptedException {
        for (int retries = 0; ; retries++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 1_048_576));
                if (retries == MAX_RETRIES || response.statusCode() != 503
                    || response.headers().firstValue("Retry-After").isEmpty()) {
                    return response;
                }
            } catch (ConnectException | HttpConnectTimeoutException | UnknownHostException unavailable) {
                if (retries == MAX_RETRIES) {
                    throw unavailable;
                }
            }

            // The worker can be interrupted during the delay when a seek replaces this request.
            Thread.sleep(RETRY_DELAY);
        }
    }

    /**
     * Cancels outstanding HTTP activity; called on the owning worker.
     */
    @Override
    public void close() {
        client.shutdownNow();
    }
}
