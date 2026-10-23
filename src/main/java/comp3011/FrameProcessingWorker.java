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
    public FrameProcessingWorker(
        List<FrameProcessor> processors
    ) {
        this.processors = List.copyOf(processors);
    }

    /**
     * Processes a frame using the worker's processors and returns a prepared frame.
     *
     * @param frame the frame to process
     * @param info the info frame associated with the frame
     * @param generation the generation number of the frame
     * @return a prepared frame containing the processed image, info, and generation
     * @throws Exception if any processor throws an exception during processing
     */
    public PreparedFrame process(
        Frame frame,
        InfoFrame info,
        long generation
    ) throws Exception {
        for (FrameProcessor processor : processors) {
            processor.process(frame, info);
        }

        Image image = converter.convert(frame);
        return new PreparedFrame(image, info, generation);
    }
}