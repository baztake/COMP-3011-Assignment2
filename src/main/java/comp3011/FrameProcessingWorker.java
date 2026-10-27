package comp3011;

import javafx.scene.image.Image;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;

import java.util.List;

/**
 * A worker that processes frames using a list of {@link FrameProcessor}s.
 * {@link #process(Frame, InfoFrame, long)} applies each processor in order to
 * the given frame and returns a {@link PreparedFrame} containing the processed
 * image, the original info frame, and the generation number.
 */
public class FrameProcessingWorker {

    private final List<FrameProcessor> processors;
    private final JavaFXFrameConverter converter = new JavaFXFrameConverter();

    /**
     * Constructs a worker with an unmodifiable snapshot of the provided processor list.
     * The processor instances themselves are not copied.
     * <p>
     * Each worker must receive its own processor instances to avoid sharing
     * mutable processor state between threads.
     *
     * @param processors the processors to apply to each frame, in order
     */
    public FrameProcessingWorker(List<FrameProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

    /**
     * Applies effects in order and converts the processed frame.
     * Checks for cancellation before each effect and before conversion.
     *
     * @param frame      input frame owned by the calling task
     * @param info       frame number and media timestamp
     * @param generation playback generation associated with the frame
     * @return processed image with its metadata and generation
     * @throws InterruptedException if cancellation is detected
     * @throws Exception            if processing or conversion fails
     */
    public PreparedFrame process(Frame frame, InfoFrame info, long generation) throws Exception {
        for (FrameProcessor processor : processors) {
            throwIfInterrupted();
            processor.process(frame, info);
        }

        // Covers interruption during the final effect and an empty effect chain.
        throwIfInterrupted();

        Image image = converter.convert(frame);
        return new PreparedFrame(image, info, generation);
    }

    /**
     * Checks if the current thread has been interrupted.
     *
     * @throws InterruptedException if the thread has been interrupted
     */
    private void throwIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Frame processing cancelled");
        }
    }
}