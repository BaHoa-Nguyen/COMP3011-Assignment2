package comp3011.model;

import comp3011.dto.InfoFrame;
import comp3011.dto.InfoVideo;
import comp3011.dto.VideoMetadata;
import comp3011.effects.FrameProcessor;
import comp3011.effects.FrameYellower;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance evidence: the multi-threaded pipeline versus the original
 * single-threaded player, measured on the same clip and the same effects.
 *
 * <p>Plain-language version of what happens here: the test first films a tiny
 * synthetic video of its own (a bright square sweeping over a shifting
 * gradient), then watches that video twice. First the "serial" run plays it
 * the old way - one thread reads a frame and applies every effect by itself,
 * exactly like the starter code did. Then the "pipelined" run plays it
 * through the real {@link FramePipelineProcessor}, whose final hand-off only
 * counts frames instead of drawing them, so no screen or GUI is needed.</p>
 *
 * <p>How to read the printed table: at k=11 effects (the full 11-effect
 * command line) the pipelined column must be at least as fast as the serial
 * one - that is the one assertion. At k=0 there are no effects at all, and
 * the pipeline is expected to LOSE there: its fixed per-frame costs (the
 * defensive copy of each frame plus the hand-offs between worker threads)
 * are all that is left to see. The rows k=5 and k=11 show where the overlap
 * pays: once applying effects costs more than that overhead, the pipeline
 * pulls ahead. The test runs by itself with {@code mvn verify}.</p>
 */
class FramePipelineProcessorLoadTest {

  // The synthetic clip: 640x480, 30 frames per second, 100 frames. Small
  // enough that the whole test stays in a few seconds, big enough that 11
  // effect passes per frame are real CPU work - which is what makes thread
  // overlap measurable at all.
  private static final int WIDTH = 640;
  private static final int HEIGHT = 480;
  private static final int FPS = 30;
  private static final int FRAME_COUNT = 100;

  // The effect counts to measure: none (decode only), about half, and all 11
  // the assignment's command line allows. Each run uses k fresh copies of the
  // same optimised effect, mirroring a command line such as "-y -y -y".
  private static final int[] EFFECT_COUNTS = {0, 5, 11};
  private static final int FULL_LOAD = EFFECT_COUNTS[EFFECT_COUNTS.length - 1];

  // Hard deadline: a wedged pipeline must fail the build, not hang it. Only
  // ever reached when something is genuinely broken - a healthy run of 100
  // frames finishes in around a second.
  private static final long RUN_TIMEOUT_SECONDS = 60;

  @TempDir
  File temporaryFolder;

  @Test
  void pipelinedPlayerIsNotSlowerThanTheOriginalSingleThreadedPlayer() throws Exception {
    File clip = new File(temporaryFolder, "synthetic-clip.mp4");
    writeSyntheticClip(clip);

    // Warm-up pass, untimed: the first run of any Java code compiles it.
    // Timing that first run would measure the compiler, not the player.
    runSerialBaseline(clip, buildEffectList(1));
    runPipelinedPlayer(clip, buildEffectList(1));

    System.out.println();
    System.out.println("Throughput on a synthetic " + WIDTH + "x" + HEIGHT + " @ " + FPS
        + "fps / " + FRAME_COUNT + "-frame clip (frames per second, higher is better):");
    System.out.println("| effects (k) | serial fps | pipelined fps | pipelined speedup |");
    System.out.println("|------------:|-----------:|--------------:|------------------:|");

    long serialNanosAtFullLoad = -1;
    long pipelinedNanosAtFullLoad = -1;

    for (int effectCount : EFFECT_COUNTS) {
      // Fresh effect objects per run on purpose: effects keep state (instance
      // index, random seeds), and reusing them would leak state between runs.
      ThroughputMeasurement serial = runSerialBaseline(clip, buildEffectList(effectCount));
      ThroughputMeasurement pipelined = runPipelinedPlayer(clip, buildEffectList(effectCount));

      // Both runs must have processed the whole clip. A short count means the
      // run silently broke, and any timing printed after that would be a lie.
      assertEquals(FRAME_COUNT, serial.framesProcessed,
          "serial run with " + effectCount + " effects did not process the whole clip");
      assertEquals(FRAME_COUNT, pipelined.framesProcessed,
          "pipelined run with " + effectCount + " effects did not process the whole clip");

      if (effectCount == FULL_LOAD) {
        serialNanosAtFullLoad = serial.elapsedNanos;
        pipelinedNanosAtFullLoad = pipelined.elapsedNanos;
      }

      System.out.printf("| %11d | %10.1f | %13.1f | %16.2fx |%n",
          effectCount,
          serial.framesPerSecond(),
          pipelined.framesPerSecond(),
          (double) serial.elapsedNanos / pipelined.elapsedNanos);
    }
    System.out.println();

    // The one real assertion: with the full 11-effect load the pipeline must
    // not lose to the single-threaded player. Deliberately only "at least as
    // fast" - never a required speedup factor - so a slow or busy machine
    // cannot fail the build over timing noise.
    assertTrue(pipelinedNanosAtFullLoad <= serialNanosAtFullLoad,
        "pipelined (" + nanosToMillis(pipelinedNanosAtFullLoad) + " ms) was slower than serial ("
            + nanosToMillis(serialNanosAtFullLoad) + " ms) at " + FULL_LOAD + " effects");
  }

  // ------------------------------------------------------------------
  // The ORIGINAL single-threaded player, copied faithfully. One thread reads
  // a frame, applies every effect in a for-loop, counts the frame, repeats.
  // This is the "before" picture the performance criterion asks to compare.
  // ------------------------------------------------------------------
  private ThroughputMeasurement runSerialBaseline(File clip, List<FrameProcessor> effects)
      throws Exception {
    ThroughputMeasurement measurement = new ThroughputMeasurement();
    boolean[] effectReady = new boolean[effects.size()];

    try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(clip)) {
      grabber.start();
      InfoVideo videoSeenByEffects = null; // built once, from the first frame

      long startedAt = System.nanoTime();
      while (measurement.framesProcessed < FRAME_COUNT) {
        Frame frame = grabber.grabImage();
        if (frame == null || frame.image == null) {
          break; // end of the clip
        }

        if (videoSeenByEffects == null) {
          videoSeenByEffects = describeVideoForEffects(grabber, clip, frame);
        }

        for (int i = 0; i < effects.size(); i++) {
          if (!effectReady[i]) {
            effects.get(i).initialise(videoSeenByEffects); // once per effect, on its first frame
            effectReady[i] = true;
          }
          effects.get(i).process(frame,
              new InfoFrame(measurement.framesProcessed, grabber.getTimestamp()));
        }
        measurement.framesProcessed++;
      }
      measurement.elapsedNanos = System.nanoTime() - startedAt;
    }
    return measurement;
  }

  // ------------------------------------------------------------------
  // The REAL FramePipelineProcessor, driven headlessly. The sink only counts
  // finished frames - no screen, no audio, no JavaFX. Elapsed time runs from
  // start() until the last frame reaches the sink, so it covers the whole
  // pipeline: decode, every effect, and the final hand-off.
  // ------------------------------------------------------------------
  private ThroughputMeasurement runPipelinedPlayer(File clip, List<FrameProcessor> effects)
      throws Exception {
    ThroughputMeasurement measurement = new ThroughputMeasurement();
    CountDownLatch wholeClipProcessed = new CountDownLatch(1);

    FramePipelineProcessor.FrameSink countingSink = new FramePipelineProcessor.FrameSink() {
      @Override
      public void onFrameDecoded(Frame frame, long timestampUs) {
        // The synthetic clip has no sound, so there is nothing to collect here.
      }

      @Override
      public void onFrameReadyForDisplay(FramePacket packet) {
        measurement.framesProcessed++;
        if (measurement.framesProcessed >= FRAME_COUNT) {
          wholeClipProcessed.countDown(); // every frame of the clip is done
        }
      }
    };

    FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(clip);
    grabber.start();

    FramePipelineProcessor pipeline = new FramePipelineProcessor(
        effects,
        grabber,
        () -> false, // video only: match the serial baseline's grabImage()
        describeVideoForPipeline(grabber, clip),
        countingSink);

    long startedAt = System.nanoTime();
    pipeline.start();

    // Wait for the last frame. If the pipeline runs out of video first (a
    // clip shorter than expected), stop waiting immediately so the frame-count
    // assertion below reports the real problem instead of a 60-second timeout.
    boolean finished = false;
    long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(RUN_TIMEOUT_SECONDS);
    while (!finished && System.nanoTime() < deadlineNs) {
      finished = wholeClipProcessed.await(200, TimeUnit.MILLISECONDS);
      if (!finished && pipeline.hasVideoEnded()) {
        // The end marker reached the screen, so nothing more will arrive.
        finished = wholeClipProcessed.await(0, TimeUnit.MILLISECONDS);
        break;
      }
    }
    measurement.elapsedNanos = System.nanoTime() - startedAt;

    pipeline.stop(); // interrupt workers first, then let go of the file reader
    grabber.close();

    assertEquals(FRAME_COUNT, measurement.framesProcessed,
        "pipeline ended early or dropped frames (only " + measurement.framesProcessed + " of "
            + FRAME_COUNT + ")");
    assertTrue(finished, "pipeline did not finish " + FRAME_COUNT + " frames within "
        + RUN_TIMEOUT_SECONDS + " s - it is wedged");
    return measurement;
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  // k fresh copies of the same byte[]-optimised effect. Repeating one class
  // mirrors a command line like "-y -y -y": every occurrence is its own
  // effect object, exactly like the command-line controller builds them.
  private static List<FrameProcessor> buildEffectList(int effectCount) {
    List<FrameProcessor> effects = new ArrayList<>();
    for (int i = 0; i < effectCount; i++) {
      effects.add(new FrameYellower());
    }
    return effects;
  }

  // What the serial run's effects are told about the video. The depth, channel
  // and stride facts must come from a decoded frame itself - the grabber has
  // no getters for them - which is why the first frame is passed in. The
  // pipelined run gets the same facts inside FramePipelineProcessor.
  private static InfoVideo describeVideoForEffects(FFmpegFrameGrabber grabber, File clip, Frame firstFrame) {
    double frameRate = grabber.getFrameRate();
    return new InfoVideo(
        clip.getName(),
        grabber.getLengthInVideoFrames(),
        firstFrame.imageWidth,
        firstFrame.imageHeight,
        firstFrame.imageDepth,
        firstFrame.imageChannels,
        firstFrame.imageStride,
        frameRate,
        (int) Math.round(frameRate),
        frameRate > 0 ? (int) Math.round(1_000_000.0 / frameRate) : 0,
        grabber.getPixelFormat());
  }

  // Same video facts, in the shape FramePipelineProcessor wants them. Picture
  // width/height are deliberately missing: the pipeline reads those from each
  // frame as it travels down the line.
  private static VideoMetadata describeVideoForPipeline(FFmpegFrameGrabber grabber, File clip) {
    double frameRate = grabber.getFrameRate();
    return new VideoMetadata(
        clip.getName(),
        grabber.getLengthInVideoFrames(),
        frameRate,
        (int) Math.round(frameRate),
        frameRate > 0 ? (int) Math.round(1_000_000.0 / frameRate) : 0,
        grabber.getPixelFormat());
  }

  // Film the synthetic clip: a real (tiny) MP4 written through the real
  // FFmpeg encoder, so both runs below decode genuine video bytes - no
  // shortcuts, which is what makes the comparison honest.
  private static void writeSyntheticClip(File clipFile) throws Exception {
    try (FFmpegFrameRecorder recorder = FFmpegFrameRecorder.createDefault(clipFile, WIDTH, HEIGHT)) {
      recorder.setFrameRate(FPS);
      recorder.setVideoBitrate(2_000_000);
      recorder.start();

      Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
      for (int frameIndex = 0; frameIndex < FRAME_COUNT; frameIndex++) {
        paintFrame(frame, frameIndex);
        recorder.record(frame);
      }
      recorder.stop();
    }
  }

  // Paint one frame of the moving picture: a diagonal gradient that shifts
  // with time, plus a bright square sweeping left to right so every effect
  // has something to chew on. Pixels live in one interleaved buffer (red,
  // green, blue, red, green, blue, ...) and each row may start with padding,
  // which is why the row length comes from the frame's own stride.
  private static void paintFrame(Frame frame, int frameIndex) {
    ByteBuffer pixels = (ByteBuffer) frame.image[0];
    int stride = frame.imageStride;
    int squareLeft = (frameIndex * 4) % WIDTH;

    for (int y = 0; y < HEIGHT; y++) {
      int rowStart = y * stride;
      for (int x = 0; x < WIDTH; x++) {
        byte brightness = (byte) (x / 2 + y / 2 + frameIndex * 3); // shifting diagonal
        if (x >= squareLeft && x < squareLeft + 80 && y >= 200 && y < 280) {
          brightness = (byte) 255; // the sweeping square
        }
        int offset = rowStart + x * 3;
        pixels.put(offset, brightness);
        pixels.put(offset + 1, brightness);
        pixels.put(offset + 2, brightness);
      }
    }
    pixels.position(0); // the encoder reads the buffer from the start
  }

  private static long nanosToMillis(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }

  // One measurement: how many frames a run processed and how long it took.
  // framesProcessed is volatile because the pipeline's display worker writes
  // it while this test thread reads it.
  private static final class ThroughputMeasurement {
    volatile int framesProcessed;
    long elapsedNanos;

    double framesPerSecond() {
      return framesProcessed * 1_000_000_000.0 / elapsedNanos;
    }
  }
}
