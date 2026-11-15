/* Subtitle formatting prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

/**
 * Turns chunk-relative word timings into readable, non-overlapping media cues.
 * Most of the decisions regarding how and when to split phrases were developed with OpenAI Codex (GPT-6.1 Sol),
 * with manual review from myself.
 */
public final class SubtitleFormatter {

    private static final int MAX_LINE_LENGTH = 42;
    private static final long MIN_DURATION_US = 1_000_000;
    private static final long MAX_DURATION_US = 7_000_000;
    private static final long PHRASE_PAUSE_US = 600_000;

    /**
     * Prefers segment boundaries, punctuation and pauses. Extremely dense speech may need later cue starts
     * to preserve all text while respecting the minimum display duration.
     *
     * @param transcript filtered server response
     * @param offsetUs   original media offset of the uploaded chunk
     * @return formatted cues on the media timeline
     * @throws CancellationException if the owning worker was interrupted
     */
    public SubtitleTrack format(SubtitleTranscript transcript, long offsetUs) {
        if (offsetUs < 0) {
            throw new IllegalArgumentException("Negative chunk offset");
        }

        // Work in timestamp order even if the server returned its arrays out of order.
        List<SubtitleCue> cues = new ArrayList<>();
        List<SubtitleTranscript.Word> words = transcript.words().stream()
            .sorted(Comparator.comparingDouble(SubtitleTranscript.Word::start))
            .toList();
        boolean[] usedWords = new boolean[words.size()];

        List<SubtitleTranscript.Segment> orderedSegments = transcript.segments().stream()
            .sorted(Comparator.comparingDouble(SubtitleTranscript.Segment::start))
            .toList();
        for (SubtitleTranscript.Segment segment : orderedSegments) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Subtitle formatting cancelled");
            }

            List<TimedWord> phrase = new ArrayList<>();
            for (SubtitleTranscript.Word word : wordsForSegment(segment, words, usedWords)) {
                appendText(phrase, word.word(), mediaTime(word.start(), offsetUs), mediaTime(word.end(), offsetUs), cues);
            }

            finish(phrase, cues);
        }

        return new SubtitleTrack(cues);
    }

    /**
     * Selects unused words whose midpoints fall inside the segment. Missing timings are estimated,
     * except for overlapping segments where doing so could repeat words.
     *
     * @param segment   phrase being formatted
     * @param words     all words in timestamp order
     * @param usedWords flags updated as words are assigned to segments
     * @return selected words or estimated words for the full phrase
     */
    private List<SubtitleTranscript.Word> wordsForSegment(SubtitleTranscript.Segment segment,
                                                          List<SubtitleTranscript.Word> words, boolean[] usedWords) {
        List<SubtitleTranscript.Word> selected = new ArrayList<>();
        boolean overlapsPrevious = false;

        for (int index = 0; index < words.size(); index++) {
            SubtitleTranscript.Word word = words.get(index);
            double midpoint = word.start() + (word.end() - word.start()) / 2;
            if (word.word().isBlank() || midpoint < segment.start() || midpoint >= segment.end()) {
                continue;
            }

            // Assign each word once when segment boundaries overlap.
            if (usedWords[index]) {
                overlapsPrevious = true;
            } else {
                usedWords[index] = true;
                selected.add(word);
            }
        }

        String timedText = selected.stream().map(SubtitleTranscript.Word::word).collect(Collectors.joining());
        boolean complete = timedText.replaceAll("\\s+", "").equals(segment.text().replaceAll("\\s+", ""));
        if (complete || overlapsPrevious) {
            return selected;
        }

        // Keep the full phrase if timings are missing, but don't reintroduce words from overlapping segments.
        return estimateWordTimings(segment);
    }

    /**
     * Splits phrase text at whitespace and spreads its words evenly across the segment's duration.
     *
     * @param segment phrase with incomplete word timings
     * @return words with estimated start and end times in seconds
     */
    private List<SubtitleTranscript.Word> estimateWordTimings(SubtitleTranscript.Segment segment) {
        String[] tokens = segment.text().strip().split("\\s+");
        double duration = segment.end() - segment.start();
        List<SubtitleTranscript.Word> words = new ArrayList<>();

        for (int index = 0; index < tokens.length; index++) {
            double start = segment.start() + duration * index / tokens.length;
            double end = segment.start() + duration * (index + 1) / tokens.length;
            words.add(new SubtitleTranscript.Word(tokens[index], start, end));
        }

        return words;
    }

    /**
     * Converts chunk-relative seconds to a position on the video's timeline.
     *
     * @param seconds  time within the audio chunk
     * @param offsetUs chunk's starting position in microseconds
     * @return media position in microseconds
     * @throws ArithmeticException if adding the offset overflows
     */
    private long mediaTime(double seconds, long offsetUs) {
        return Math.addExact(offsetUs, Math.round(seconds * 1_000_000));
    }

    /**
     * Splits text into words, breaking words longer than 42 Unicode code points into smaller pieces.
     * Each piece keeps the supplied timing and is added to the current caption.
     *
     * @param phrase words in the current caption
     * @param text   text to split
     * @param start  media start time in microseconds
     * @param end    media end time in microseconds
     * @param cues   completed captions to append to
     */
    private void appendText(List<TimedWord> phrase, String text, long start, long end, List<SubtitleCue> cues) {
        for (String part : text.strip().split("\\s+")) {
            // Split an unusually long word without discarding its characters.
            while (!part.isEmpty()) {
                int length = Math.min(MAX_LINE_LENGTH, part.codePointCount(0, part.length()));
                int split = part.offsetByCodePoints(0, length);
                addWord(phrase, new TimedWord(part.substring(0, split), start, end), cues);
                part = part.substring(split);
            }
        }
    }

    /**
     * Starts a new caption at a 0.6-second pause, a sentence break after at least one second,
     * or the seven-second limit. Words that exceed two lines move to the next caption.
     *
     * @param phrase words in the current caption
     * @param next   word to add
     * @param cues   completed captions to append to
     */
    private void addWord(List<TimedWord> phrase, TimedWord next, List<SubtitleCue> cues) {
        if (!phrase.isEmpty()) {
            TimedWord first = phrase.getFirst();
            TimedWord last = phrase.getLast();
            boolean pause = next.start() - last.end() >= PHRASE_PAUSE_US;
            boolean sentence = last.text().matches(".*[.!?][\"')]*$") && next.start() - first.start() >= MIN_DURATION_US;

            // Start a new cue at a natural break or before exceeding seven seconds of dialogue.
            if (pause || sentence || next.end() - first.start() > MAX_DURATION_US) {
                finish(phrase, cues);
            }
        }

        phrase.add(next);

        if (lines(phrase) == null) {
            // Keep the overflowing word for the next cue instead of truncating the current text.
            phrase.removeLast();
            finish(phrase, cues);
            phrase.add(next);
        }
    }

    /**
     * Saves the current caption for one to seven seconds, delaying its start if needed to avoid overlap.
     * Clears the phrase ready for the next caption, doing nothing if it is empty.
     *
     * @param phrase words to turn into a caption
     * @param cues   completed captions to append to
     */
    private void finish(List<TimedWord> phrase, List<SubtitleCue> cues) {
        if (phrase.isEmpty()) {
            return;
        }

        // Dense speech may need a later start to keep the minimum duration without overlapping cues.
        long previousEnd = cues.isEmpty() ? 0 : cues.getLast().endUs();
        long start = Math.max(previousEnd, phrase.getFirst().start());
        long end = Math.max(start + MIN_DURATION_US, phrase.getLast().end());
        end = Math.min(start + MAX_DURATION_US, end);

        cues.add(new SubtitleCue(start, end, lines(phrase)));
        phrase.clear();
    }

    /**
     * Uses one line when it fits, otherwise chooses a space that keeps both lines within 42 code points.
     * Prefers breaks after punctuation, then roughly balanced line lengths.
     *
     * @param phrase words to lay out
     * @return formatted text, or null if it cannot fit in two lines
     */
    private String lines(List<TimedWord> phrase) {
        String text = phrase.stream().map(TimedWord::text).collect(Collectors.joining(" "));
        if (text.codePointCount(0, text.length()) <= MAX_LINE_LENGTH) {
            return text;
        }

        String best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int index = text.indexOf(' '); index >= 0; index = text.indexOf(' ', index + 1)) {
            String first = text.substring(0, index);
            String second = text.substring(index + 1);
            int firstLength = first.codePointCount(0, first.length());
            int secondLength = second.codePointCount(0, second.length());
            if (firstLength > MAX_LINE_LENGTH || secondLength > MAX_LINE_LENGTH) {
                continue;
            }

            // Prefer punctuation when it fits, otherwise choose roughly balanced lines.
            int score = (first.matches(".*[,.;:!?][\"')]*$") ? 100 : 0) - Math.abs(firstLength - secondLength);
            if (score > bestScore) {
                best = first + "\n" + second;
                bestScore = score;
            }
        }

        return best;
    }

    private record TimedWord(String text, long start, long end) {
    }
}
