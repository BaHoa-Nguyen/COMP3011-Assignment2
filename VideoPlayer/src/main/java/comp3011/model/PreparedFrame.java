package comp3011.model;

import javafx.scene.image.Image;

public record PreparedFrame(
    Image image,
    int frameNumber,
    long mediaTimestampUs,
    long logicalTimestampUs,
    long targetTimeNs,
    long preparedAtNs,
    long version) { // playbackVersion captured by the publisher, so display can drop a frame that
                    // seek/shutdown already invalidated
}
