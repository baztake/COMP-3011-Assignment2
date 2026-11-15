/* Background subtitle loading prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One background owner for extraction and HTTP. Public methods and callbacks belong to JavaFX.
 */
@Slf4j
public final class SubtitleLoader implements AutoCloseable {

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), task -> {
        Thread thread = new Thread(task, "subtitle-loader");
        // Native file opening may take time to return after cancellation, shouldn't keep alive.
        thread.setDaemon(true);
        return thread;
    });

    private Future<?> request;
    private long generation;
    private boolean closed;

    /**
     * @param file   current video
     * @param startUs chunk start on the video timeline in microseconds
     * @param ready  receives the chunk's immutable track and end-of-media flag on JavaFX
     * @param failed receives a safe failure message on JavaFX
     */
    public void load(File file, long startUs, Consumer<LoadedChunk> ready, Consumer<String> failed) {
        if (closed) {
            return;
        }

        cancel();
        long id = generation;

        request = worker.submit(() -> {
            String stage = "audio extraction";
            try {
                // Both native decoding and HTTP stay on this worker, no-audio files skip the upload.
                log.info("Extracting subtitle audio at {} seconds", startUs / 1_000_000);
                SubtitleAudioExtractor.AudioChunk audio = new SubtitleAudioExtractor().extract(file, startUs);
                SubtitleTrack track = new SubtitleTrack(List.of());
                if (audio.wav().length > 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }

                    String address = System.getProperty("comp3011.subtitles.url",
                        System.getenv().getOrDefault("COMP3011_SUBTITLE_URL", "http://localhost:8080/api/v1/subtitles"));

                    stage = "server request";
                    try (SubtitleClient client = new SubtitleClient(URI.create(address))) {
                        log.info("Uploading {} bytes of subtitle audio to {}", audio.wav().length, address);
                        SubtitleTranscript transcript = client.transcribe(audio.wav());
                        stage = "caption formatting";
                        track = new SubtitleFormatter().format(transcript, audio.offsetUs());
                    }
                } else {
                    log.info("No audio in subtitle chunk at {} seconds, skipping upload", startUs / 1_000_000);
                }

                log.info("Subtitle chunk complete at {} seconds, last chunk: {}", startUs / 1_000_000, audio.last());
                LoadedChunk result = new LoadedChunk(track, audio.last());
                // Check at delivery time too: a callback may already be queued when another file is opened.
                Platform.runLater(() -> {
                    if (!closed && id == generation) {
                        ready.accept(result);
                    }
                });
            } catch (InterruptedException cancelled) {
                log.debug("Subtitle loading cancelled");
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                log.warn("Subtitle loading failed during {}", stage, error);
                Platform.runLater(() -> {
                    if (!closed && id == generation) {
                        failed.accept("Subtitles unavailable, toggle off and on to retry");
                    }
                });
            }
        });
    }

    /**
     * Cancels obsolete work without waiting on JavaFX, only the newest queued chunk is retained.
     */
    public void cancel() {
        generation++;
        if (request != null) {
            request.cancel(true);
        }

        // Remove cancelled queued tasks so rapid file changes and seeks retain only the newest replacement.
        worker.getQueue().clear();
    }

    /**
     * Requests termination; native owners close their resources when their current read returns.
     */
    @Override
    public void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
    }

    /**
     * @param track captions already placed on the video's timeline
     * @param last whether loading beyond this chunk is unnecessary
     */
    public record LoadedChunk(SubtitleTrack track, boolean last) {
    }
}
