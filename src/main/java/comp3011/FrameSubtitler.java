/* Subtitle support prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. */
package comp3011;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Objects;

/**
 * Draws one transparent caption layer for both native processing and JavaFX presentation.
 * Each owner must have its own instance because the last cue and image are cached here.
 */
public final class FrameSubtitler extends FrameProcessor {
    private final SubtitleTrack track;
    private SubtitleCue cachedCue;
    private BufferedImage cachedCaption;
    private Image cachedImage;

    /** Creates a processor with the temporary sample captions. */
    public FrameSubtitler() {
        this(SubtitleTrack.sample());
    }

    /** @param track immutable cues on the media timeline */
    public FrameSubtitler(SubtitleTrack track) {
        this.track = Objects.requireNonNull(track, "track");
    }

    /**
     * Draws onto a caller-owned native frame. Each worker must own its processor instance.
     *
     * @param frame writable video frame
     * @param info metadata with a timestamp relative to the beginning of the media
     */
    @Override
    public void process(Frame frame, InfoFrame info) {
        if (frame.image == null) {
            return;
        }
        BufferedImage caption = captionAt(frame.imageWidth, frame.imageHeight, info.timestampUs());
        if (caption == null) {
            return;
        }
        try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
            BufferedImage image = converter.convert(frame);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.drawImage(caption, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            Java2DFrameConverter.copy(image, frame);
        }
    }

    /**
     * Gets the caption layer for presentation on JavaFX. The video image remains untouched.
     *
     * @param width video width in pixels
     * @param height video height in pixels
     * @param timestampUs position of the displayed frame in microseconds
     * @return transparent caption image, or null during silence
     */
    public Image captionImage(int width, int height, long timestampUs) {
        BufferedImage caption = captionAt(width, height, timestampUs);
        if (caption == null) {
            return null;
        }
        if (cachedImage == null) {
            // Convert only when the cue or video size changes, rather than copying every video frame.
            int[] pixels = ((DataBufferInt) caption.getRaster().getDataBuffer()).getData();
            WritableImage image = new WritableImage(width, height);
            image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            cachedImage = image;
        }
        return cachedImage;
    }

    private BufferedImage captionAt(int width, int height, long timestampUs) {
        SubtitleCue cue = track.findCue(timestampUs);
        if (cue == null) {
            return null;
        }
        // Keep just one caption, so memory use does not grow with the length of the track.
        if (cachedCaption == null || !cue.equals(cachedCue)
            || cachedCaption.getWidth() != width || cachedCaption.getHeight() != height) {
            cachedCaption = drawCaption(cue, width, height);
            cachedCue = cue;
            cachedImage = null;
        }
        return cachedCaption;
    }

    private BufferedImage drawCaption(SubtitleCue cue, int width, int height) {
        BufferedImage caption = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = caption.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            String[] lines = cue.text().split("\n");
            float size = Math.max(1, height / 20.0f);
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(size);

            // Shrink both lines together if needed, leaving a margin at either side of the video.
            for (String line : lines) {
                double textWidth = font.getStringBounds(line, graphics.getFontRenderContext()).getWidth();
                if (textWidth > width * 0.9) {
                    size *= (float) (width * 0.9 / textWidth);
                    font = font.deriveFont(size);
                }
            }
            graphics.setStroke(new BasicStroke(Math.max(1, size / 10), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int index = 0; index < lines.length; index++) {
                GlyphVector glyphs = font.createGlyphVector(graphics.getFontRenderContext(), lines[index]);
                float x = (float) ((width - glyphs.getLogicalBounds().getWidth()) / 2);
                float y = height - size * (0.8f + (lines.length - index - 1) * 1.25f);
                Shape outline = glyphs.getOutline(x, y);
                // Draw the outline first so white text remains legible on bright and dark frames.
                graphics.setColor(Color.BLACK);
                graphics.draw(outline);
                graphics.setColor(Color.WHITE);
                graphics.fill(outline);
            }
        } finally {
            graphics.dispose();
        }
        return caption;
    }
}
