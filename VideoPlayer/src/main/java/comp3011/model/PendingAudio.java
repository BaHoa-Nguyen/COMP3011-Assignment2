package comp3011.model;

public class PendingAudio {
  private final long timestampUs;
  private final byte[] samples;
  private int offset;

  PendingAudio(long timestampUs, byte[] samples) {
    this.timestampUs = timestampUs;
    this.samples = samples;
  }

  long timestampUs() {
    return timestampUs;
  }

  byte[] samples() {
    return samples;
  }

  int offset() {
    return offset;
  }

  int remaining() {
    return samples.length - offset;
  }

  void advance(int byteCount) {
    offset += byteCount;
  }

  boolean finished() {
    return offset >= samples.length;
  }
}
