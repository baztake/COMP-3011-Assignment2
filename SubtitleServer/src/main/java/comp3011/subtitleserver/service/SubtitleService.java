/* Subtitle server prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. Adapted from Assignment 1. */
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
    // One lock keeps duplicate uploads from making separate requests.
    // New transcriptions run one at a time, cache hits do not need this lock.
    private final Object transcriptionLock = new Object();

    /**
     * @param provider Whisper HTTP integration
     * @param store    durable transcript cache and usage total
     */
    public SubtitleService(OpenAiTranscriptionService provider, SubtitleStore store) {
        this.provider = provider;
        this.store = store;
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

        synchronized (transcriptionLock) {
            // Another request may have saved this chunk while we were waiting for the lock.
            cached = store.find(key);
            if (cached != null) {
                return cached;
            }

            OpenAiTranscriptionResult result = provider.transcribe(audio);

            SubtitleResponse response = filterSpeech(result);
            // Count original input, even if every segment was suppressed. Hits never reach this call.
            store.record(key, response, result.usageSeconds());

            return response;
        }
    }

    private SubtitleResponse filterSpeech(OpenAiTranscriptionResult result) {
        List<Segment> segments = result.segments().stream()
            .filter(segment -> !segment.isSuppressed())
            .sorted(Comparator.comparingDouble(Segment::start))
            .toList();

        // Remove words from rejected segments too, otherwise the player could display them again.
        List<Word> words = result.words().stream()
            .filter(word -> belongsToSpeech(word, segments))
            .sorted(Comparator.comparingDouble(Word::start))
            .toList();
        String text = segments.stream()
            .map(segment -> segment.text().strip())
            .filter(phrase -> !phrase.isEmpty())
            .collect(Collectors.joining(" "));

        return new SubtitleResponse(result.duration(), result.language(), text, words, segments);
    }

    private boolean belongsToSpeech(Word word, List<Segment> segments) {
        // Word and segment boundaries can differ slightly, so use the word's midpoint.
        double midpoint = word.start() + (word.end() - word.start()) / 2;
        for (Segment segment : segments) {
            if (midpoint >= segment.start() && midpoint < segment.end()) {
                return true;
            }
        }
        return false;
    }

    private void validate(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new SubtitleException(HttpStatus.BAD_REQUEST, "Upload a nonempty audio file as multipart field 'file'");
        }
        if (audio.getSize() > MAX_BYTES) {
            throw new SubtitleException(HttpStatus.CONTENT_TOO_LARGE, "Audio chunks must not exceed 24 MiB");
        }
        String filename = audio.getOriginalFilename();
        if (filename == null || !filename.contains(".")) {
            throw new SubtitleException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Audio filename must include its encoding extension");
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) {
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
