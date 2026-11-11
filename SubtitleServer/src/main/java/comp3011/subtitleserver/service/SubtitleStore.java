/* Subtitle server prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. */
package comp3011.subtitleserver.service;

import comp3011.subtitleserver.dto.SubtitleResponse;
import comp3011.subtitleserver.exception.SubtitleException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns the bounded transcript cache and cumulative audio seconds. Its monitor protects all state
 * and disk replacement. Provider calls run outside this monitor, so cached reads remain responsive.
 * One server process must own a store file. Audio bytes and API keys are never persisted here.
 */
@Service
public class SubtitleStore {

    private final Path file;
    private final int maxEntries;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final LinkedHashMap<String, SubtitleResponse> entries = new LinkedHashMap<>();
    private double usage;
    private boolean dirty;

    /**
     * Loads durable state, failing startup rather than silently losing usage if it is corrupt.
     *
     * @param filename   server-local store path
     * @param maxEntries maximum retained chunks; oldest inserted chunks are evicted first
     * @throws IllegalStateException if existing state cannot be read
     */
    public SubtitleStore(
        @Value("${subtitles.store-file}") String filename,
        @Value("${subtitles.cache-max-entries}") int maxEntries
    ) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Cache size must be positive");
        }

        this.file = Path.of(filename).toAbsolutePath().normalize();
        this.maxEntries = maxEntries;

        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                State state = mapper.readValue(Files.readAllBytes(file), State.class);
                if (state.version() != 1 || !Double.isFinite(state.usage()) || state.usage() < 0 || state.entries() == null) {
                    throw new IllegalArgumentException("Invalid persisted state");
                }
                usage = state.usage();
                state.entries().forEach((key, value) -> {
                    if (!key.matches("[a-f0-9]{64}") || value == null)
                        throw new IllegalArgumentException("Invalid cache entry");
                    entries.put(key, value);
                });
                trim();
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot load subtitle store; preserve the file and check its contents and permissions", error);
        }
    }

    /**
     * @param key hash of audio content and transcription settings
     * @return cached result, or null on a miss
     * @throws SubtitleException if previously changed state still cannot be persisted
     */
    public synchronized SubtitleResponse find(String key) {
        flush();
        return entries.get(key);
    }

    /**
     * @return cumulative successful provider usage in seconds, including evicted chunks
     */
    public synchronized double getUsage() {
        flush();
        return usage;
    }

    /**
     * Counts one successful provider request and saves its result in one replacement file.
     * Memory retains the result on write failure, preventing a retry from making another paid request.
     *
     * @param key     hash for this chunk
     * @param result  filtered transcription
     * @param seconds provider usage, or audio duration when usage was omitted
     * @throws SubtitleException if state cannot be persisted
     */
    public synchronized void record(String key, SubtitleResponse result, double seconds) {
        flush();
        if (!Double.isFinite(seconds) || seconds < 0 || !Double.isFinite(usage + seconds)) {
            throw new IllegalArgumentException("Invalid usage seconds");
        }
        usage += seconds;
        entries.put(key, result);
        trim();
        dirty = true;
        flush();
    }

    private void trim() {
        while (entries.size() > maxEntries) entries.remove(entries.keySet().iterator().next());
    }

    private void flush() {
        if (!dirty) return;
        Path temporary = null;
        try {
            byte[] data = mapper.writeValueAsBytes(new State(1, usage, new LinkedHashMap<>(entries)));
            temporary = Files.createTempFile(file.getParent(), "subtitles-", ".tmp");
            Files.write(temporary, data);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException | RuntimeException error) {
            throw new SubtitleException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot save subtitle cache and usage; check server storage");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {}
            }
        }
    }

    private record State(int version, double usage, Map<String, SubtitleResponse> entries) {
    }
}
