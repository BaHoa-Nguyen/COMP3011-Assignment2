package comp3011.dto;

/**
 * Everything an effect needs to know about the video before it runs once.
 *
 * <p>All of these are properties of the media file, so they are read once when
 * playback opens (on the thread that owns startup) and then handed to the
 * effects. The picture width and height are missing on purpose: they come from
 * each frame itself, because that is where they belong.</p>
 */
public record VideoMetadata(
    String mediaName,
    int totalFrames,
    double frameRate,
    int intFrameRate,
    int frameDurationUs,
    int pixelFormat) {
}
