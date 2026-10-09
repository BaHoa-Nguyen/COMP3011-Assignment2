package comp3011.model;

import org.bytedeco.javacv.Frame;

/**
 * This is the containter for the frame travelling among the workers
 */
public record FramePacket(Frame frame, long timestampUs, int frameNumber, long version) {

}
