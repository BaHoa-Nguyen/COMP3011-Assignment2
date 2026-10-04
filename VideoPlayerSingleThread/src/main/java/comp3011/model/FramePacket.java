package comp3011.model;

import org.bytedeco.javacv.Frame;

// This is the container of the frame undergoing each stage in the pipeline
public record FramePacket(Frame frame, long timestampUs, int frameNumber, long version) {

}
