/* Adapted from Assignment 1 server with assistance from OpenAI Codex. */
package comp3011.subtitleserver.service;

import comp3011.subtitleserver.dto.OpenAiTranscriptionResult;
import comp3011.subtitleserver.dto.OpenAiTranscriptionResult.Segment;
import comp3011.subtitleserver.dto.OpenAiTranscriptionResult.Word;
import comp3011.subtitleserver.dto.SubtitleResponse;
import comp3011.subtitleserver.exception.SubtitleException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates uploads, reuses transcriptions and filters probable non-speech.
 */
@Service
public class SubtitleService {

    private static final long MAX_BYTES = 24L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("wav", "mp3", "mp4", "m4a", "webm", "mpeg", "mpga");
    private final OpenAiTranscriptionService provider;
    private final SubtitleStore store;
    // Fixed locks bound coordination memory. Identical hashes always share a lock and recheck the cache.
    // Collisions can serialize unrelated misses, but cache hits never wait for provider requests.
    private final Object[] requestLocks = new Object[32];

    /**
     * @param provider Whisper HTTP integration
     * @param store    durable transcript cache and usage total
     */
    public SubtitleService(OpenAiTranscriptionService provider, SubtitleStore store) {
        this.provider = provider;
        this.store = store;
        for (int index = 0; index < requestLocks.length; index++) requestLocks[index] = new Object();
    }

    /**
     * Runs on a servlet request thread; the player must call this API off JavaFX.
     *
     * @param audio original encoded audio chunk with its real filename extension
     * @return filtered word and segment timings relative to the chunk
     * @throws SubtitleException if the upload, storage or transcription cannot be processed
     */
    public SubtitleResponse transcribe(MultipartFile audio) {
        validate(audio);

        String key = contentKey(audio);
        SubtitleResponse cached = store.find(key);
        if (cached != null) {
            return cached;
        }

        Object lock = requestLocks[(key.hashCode() & Integer.MAX_VALUE) % requestLocks.length];
        synchronized (lock) {
            cached = store.find(key);
            if (cached != null) {
                return cached;
            }

            OpenAiTranscriptionResult result = provider.transcribe(audio);

            List<Segment> segments = result.segments().stream().filter(segment -> !segment.isSuppressed()).sorted(Comparator.comparingDouble(Segment::start)).toList();
            // Filtering words as well prevents discarded phrases reappearing during client-side wrapping.
            List<Word> words = result.words().stream().filter(word -> segments.stream().anyMatch(segment -> {
                double midpoint = word.start() + (word.end() - word.start()) / 2;
                return midpoint >= segment.start() && midpoint < segment.end();
            })).sorted(Comparator.comparingDouble(Word::start)).toList();
            String text = segments.stream().map(segment -> segment.text().strip()).filter(phrase -> !phrase.isEmpty()).collect(Collectors.joining(" "));
            SubtitleResponse response = new SubtitleResponse(result.duration(), result.language(), text, words, segments);
            // Count original input, even if every segment was suppressed. Hits never reach this call.
            store.record(key, response, result.usageSeconds());

            return response;
        }
    }

    private void validate(MultipartFile audio) {
        if (audio == null || audio.isEmpty())
            throw new SubtitleException(HttpStatus.BAD_REQUEST, "Upload a nonempty audio file as multipart field 'file'");
        if (audio.getSize() > MAX_BYTES)
            throw new SubtitleException(HttpStatus.PAYLOAD_TOO_LARGE, "Audio chunks must not exceed 24 MiB");
        String filename = audio.getOriginalFilename();
        if (filename == null || !filename.contains(".") || !EXTENSIONS.contains(filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))) {
            throw new SubtitleException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Use a WAV, MP3, MP4, M4A, WebM, MPEG or MPGA audio chunk");
        }
    }

    private String contentKey(MultipartFile audio) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Include output settings and filtering policy, so a future policy change can invalidate old results.
            digest.update("whisper-1|verbose_json|word,segment|silence-v1|".getBytes(StandardCharsets.UTF_8));
            try (InputStream input = new DigestInputStream(audio.getInputStream(), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new SubtitleException(HttpStatus.BAD_REQUEST, "Cannot read the uploaded audio chunk");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
