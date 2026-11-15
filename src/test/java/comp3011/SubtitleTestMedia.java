/* Subtitle fixtures prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.File;
import java.nio.ShortBuffer;
import java.nio.file.Path;

/** Small generated files keep subtitle tests independent of siege.mp4 and real speech services. */
final class SubtitleTestMedia {

    static final String TRANSCRIPT = """
        {
          "duration": 4,
          "words": [{"word": "Current.", "start": 0, "end": 3.5}],
          "segments": [{"text": "Current.", "start": 0, "end": 4}]
        }
        """;

    private SubtitleTestMedia() {
    }

    static File video(Path directory, int seconds, boolean withAudio) throws Exception {
        File file = directory.resolve("subtitles.mp4").toFile();
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file, 64, 48, withAudio ? 1 : 0);
             Frame frame = new Frame(64, 48, Frame.DEPTH_UBYTE, 3)) {
            recorder.setFormat("mp4");
            recorder.setFrameRate(2);
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4);
            if (withAudio) {
                recorder.setAudioCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC);
                recorder.setSampleRate(48_000);
            }

            // Half a second of tone per frame also exercises resampling to the player's 16 kHz upload format.
            short[] tone = new short[24_000];
            for (int index = 0; index < tone.length; index++) {
                tone[index] = (short) (12_000 * Math.sin(index * 2 * Math.PI * 440 / 48_000));
            }

            recorder.start();
            for (int index = 0; index < seconds * 2; index++) {
                recorder.record(frame);
                if (withAudio) {
                    recorder.recordSamples(48_000, 1, ShortBuffer.wrap(tone));
                }
            }
            recorder.stop();
        }
        return file;
    }
}
