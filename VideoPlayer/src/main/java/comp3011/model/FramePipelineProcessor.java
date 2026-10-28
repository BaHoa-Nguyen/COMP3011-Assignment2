package comp3011.model;

import comp3011.dto.InfoFrame;
import comp3011.dto.InfoVideo;
import comp3011.dto.VideoMetadata;
import comp3011.effects.FrameProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * Turns one video into a stream of finished pictures, one effect at a time.
 *
 * <p>The work is laid out like a factory assembly line. The first worker (the
 * decode stage) reads the video file frame by frame. Each following worker
 * applies exactly one effect and passes the frame along. The last worker hands
 * the finished frame to whoever created this object - the video player model -
 * so it can put the frame on screen.</p>
 *
 * <p>Three promises this class keeps for its owner:</p>
 * <ul>
 *   <li>Frames arrive in the order they were typed on the command line, and a
 *       repeated flag really is applied twice (each one gets its own effect
 *       object).</li>
 *   <li>Every frame carries a "round number" (its version). When the user seeks
 *       or stops, the round number goes up, and any frame from an older round is
 *       thrown away before an effect ever touches it. This is what stops stale
 *       pictures from flashing on screen.</li>
 *   <li>None of these workers ever talk to JavaFX or to the screen. Drawing
 *       belongs to the owner; this class only prepares frames.</li>
 * </ul>
 */
final class FramePipelineProcessor {

  // A special packet that means "the video file has ended". It is passed down
  // the line like a normal frame so every worker learns the video is over.
  private static final FramePacket END_PACKET = new FramePacket(null, 0, -1, 0);

  // How many finished frames may wait between two workers.
  // Each waiting frame is a full picture (about 4.6 MB), multiplied by every
  // pair of workers, so this number decides the memory the player uses.
  // The screen only needs a new picture every 41,667 microseconds, so bigger
  // queues never make playback faster - they only waste memory and electricity.
  private static final int QUEUE_CAPACITY = 2;

  // Means "no seek wanted". Kept at -1 because real timestamps start at 0.
  private static final long NO_SEEK_REQUEST = -1;

  /**
   * The two moments where a worker hands work back to the owner of this
   * pipeline. Both are called on worker threads, never on the JavaFX thread.
   */
  interface FrameSink {

    /** A raw frame was just read from the file: the owner can queue its sound now. */
    void onFrameDecoded(Frame frame, long timestampUs);

    /** A frame has passed through every effect: the owner can display it now. */
    void onFrameReadyForDisplay(FramePacket packet);
  }

  // The effects to apply, one per worker, in command-line order.
  private final List<FrameProcessor> frameProcessors;
  // The video file reader. Borrowed from the owner: only the decode stage touches it.
  private final FFmpegFrameGrabber grabber;
  // Asks "does this session still want sound?" for every frame: decides grab()
  // (video + sound) versus grabImage() (video only, faster).
  private final BooleanSupplier wantsSamples;
  // Video facts (name, length, frame rate...) read once at start-up.
  private final VideoMetadata metadata;
  // Where finished frames and decoded sound frames are handed back.
  private final FrameSink sink;

  // One bucket between each pair of workers. Bucket 0 holds freshly decoded
  // frames; the last bucket holds fully effected frames.
  private final List<ArrayBlockingQueue<FramePacket>> waitingQueues;

  // Workers = effects + 1 (the decode stage) + 1 (the display stage).
  private final int stageCount;

  // The thread pool that runs every worker. Null until start() is called.
  private ExecutorService workers;

  // True between start() and stop(). Every worker checks this so stop()
  // can end the whole assembly line without waking anyone twice.
  private volatile boolean running = false;

  // Where the decode stage should jump to next (microseconds into the video).
  // Written by the GUI thread BEFORE the round number is bumped, so a frame can
  // never be tagged with the new round but positioned at the old spot.
  private volatile long pendingSeekUs = NO_SEEK_REQUEST;

  // The current "round number" of playback. Goes up on every seek and stop so
  // leftover frames from the previous round become recognisable and droppable.
  // Written only by the GUI thread, always while holding the owner's frame lock.
  private volatile long version = 0;

  // Set when the last worker has seen the end-of-video packet.
  private volatile boolean videoEnded = false;
  // Set when the decode worker has stopped for any reason (end, stop, or error).
  private volatile boolean decodeFinished = false;
  // The first crash any worker had since the owner last collected it with
  // takeStageError(); null means every worker is healthy.
  private volatile Exception stageError = null;

  FramePipelineProcessor(
      List<FrameProcessor> frameProcessors,
      FFmpegFrameGrabber grabber,
      BooleanSupplier wantsSamples,
      VideoMetadata metadata,
      FrameSink sink) {
    this.frameProcessors = frameProcessors;
    this.grabber = grabber;
    this.wantsSamples = wantsSamples;
    this.metadata = metadata;
    this.sink = sink;
    this.stageCount = frameProcessors.size() + 1; // +1 = the final display stage
    this.waitingQueues = new ArrayList<>(stageCount);

    // Build the assembly line: one empty bucket per gap between workers.
    for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
      waitingQueues.add(new ArrayBlockingQueue<>(QUEUE_CAPACITY));
    }
  }

  /** Hires the workers and sets them going: 1 decode + one per effect + 1 display. */
  void start() {
    running = true;

    workers = Executors.newFixedThreadPool(stageCount + 1, runnable -> {
      Thread thread = new Thread(runnable);
      thread.setDaemon(true); // never block the program from exiting
      // stay behind the GUI so the window remains smooth while effects run
      thread.setPriority(Thread.MIN_PRIORITY);
      return thread;
    });

    workers.execute(this::runDecodeStage);

    for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
      final int stageIndexCopy = stageIndex;
      workers.execute(() -> runEffectStage(stageIndexCopy));
    }
  }

  /**
   * Seek: remember where to jump to next. Must be called BEFORE
   * {@link #discardCancelledFrames()}, because the decode worker reads the
   * round number first and the jump position second - the other way around
   * would let a frame sneak through with the new round but the old position.
   */
  void requestSeek(long timestampUs) {
    pendingSeekUs = timestampUs;
  }

  /**
   * Throw away everything still in flight: raise the round number so old
   * frames become recognisable as stale, and empty every bucket. The owner
   * calls this while holding its frame lock, so a frame can never be shown
   * with a round number it was not made for.
   */
  void discardCancelledFrames() {
    version++;

    for (ArrayBlockingQueue<FramePacket> waitingQueue : waitingQueues) {
      waitingQueue.clear();
    }
  }

  /** The round number new frames are tagged with; the owner checks it before showing a frame. */
  long currentFrameVersion() {
    return version;
  }

  /** True once the last worker has seen the end of the video. */
  boolean hasVideoEnded() {
    return videoEnded;
  }

  /** True once the decode worker has stopped (finished, stopped by user, or failed). */
  boolean hasDecodeFinished() {
    return decodeFinished;
  }

  /**
   * Collect the first worker crash since the last call, or null if none.
   * Taking it clears it, so the owner reports each failure exactly once.
   */
  Exception takeStageError() {
    Exception error = stageError;
    stageError = null;
    return error;
  }

  /**
   * Shut the whole assembly line down. Interrupt first - every worker is
   * sitting in a 50 ms wait and wakes up immediately - then give them 200 ms
   * to finish the frame in their hands. Short enough that closing the player
   * never freezes the window; long enough that nobody is cut off mid-frame.
   * Safe to call more than once.
   */
  void stop() {
    running = false;
    pendingSeekUs = NO_SEEK_REQUEST;
    videoEnded = false;
    decodeFinished = false;

    for (ArrayBlockingQueue<FramePacket> waitingQueue : waitingQueues) {
      waitingQueue.clear();
    }

    if (workers == null) {
      return; // already stopped
    }
    ExecutorService stopping = workers;
    workers = null;

    // "Now" = interrupt the waiting workers instead of asking politely; a plain
    // shutdown() could keep the window frozen for 2 full seconds on stop/restart.
    stopping.shutdownNow();

    try {
      if (!stopping.awaitTermination(200, TimeUnit.MILLISECONDS)) {
        System.out.println("Playback workers did not stop within 200ms");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Hand one packet to the next worker, waiting up to 50 ms per try.
   * Returns false when the pipeline was shut down, so the caller can exit.
   * The waiting is what keeps memory flat: a fast worker cannot run ahead
   * and pile up frames the screen will never show.
   */
  private boolean passToNextStage(ArrayBlockingQueue<FramePacket> nextQueue, FramePacket framePacket)
      throws InterruptedException {
    while (running) {
      if (nextQueue.offer(framePacket, 50, TimeUnit.MILLISECONDS)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Worker 0: the decode stage. Reads the video file, tells the owner about
   * any sound on the frame, and puts a private copy of the picture into the
   * first bucket. The copy matters: the file reader reuses its buffer for the
   * next frame, so without it the picture would change while effects run.
   */
  private void runDecodeStage() {
    try {
      while (running) {

        // Read the current round and any requested jump before decoding,
        // in that order - see requestSeek().
        long packetVersion = version;
        long seekUs = pendingSeekUs;

        if (seekUs != NO_SEEK_REQUEST && pendingSeekUs == seekUs) {
          pendingSeekUs = NO_SEEK_REQUEST; // the jump is handled; clear it
          grabber.setTimestamp(seekUs); // tell the decoder to resume at that position
        }

        // grab() also reads sound (slower); grabImage() reads pictures only.
        Frame frame = wantsSamples.getAsBoolean() ? grabber.grab() : grabber.grabImage();

        if (!running) {
          return; // the player was closed while we were reading
        }

        if (frame == null) {
          // No picture came back: the video ended. Send the end marker onward.
          passToNextStage(waitingQueues.get(0), END_PACKET);
          return;
        }

        long timestampUs = grabber.getTimestamp();

        // Let the owner queue this frame's sound (it may hold no sound at all).
        sink.onFrameDecoded(frame, timestampUs);

        if (frame.image == null) {
          continue; // sound-only frame: nothing to draw, read the next one
        }

        // Copy the picture so the decoder cannot overwrite it while effects run.
        // Reference:
        // https://boofcv.org/javadoc/org/bytedeco/copiedstuff/FFmpegFrameGrabber.html#grab()
        FramePacket packetWithCopy = new FramePacket(
            frame.clone(),
            timestampUs,
            grabber.getFrameNumber(),
            packetVersion);

        if (!passToNextStage(waitingQueues.get(0), packetWithCopy)) {
          return; // pipeline shut down while waiting
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      if (running) {
        stageError = e; // remember the crash; the owner will collect it
      }
    } finally {
      decodeFinished = true; // any exit (end, stop, or error) means no more decoding
    }
  }

  /**
   * One effect worker: takes a frame from its own bucket, throws it away if a
   * seek already cancelled it, applies its single effect, and passes it on.
   * The very last worker has no effect - it just hands the frame to the owner
   * to convert and display.
   */
  private void runEffectStage(int stageIndex) {
    ArrayBlockingQueue<FramePacket> ownQueue = waitingQueues.get(stageIndex);

    boolean isDisplayStage = stageIndex == stageCount - 1;
    ArrayBlockingQueue<FramePacket> nextQueue = isDisplayStage ? null : waitingQueues.get(stageIndex + 1);

    // Each worker owns exactly one effect object - it is never shared with
    // another worker, so no locking is needed inside the effects.
    FrameProcessor frameProcessor = stageIndex < frameProcessors.size()
        ? frameProcessors.get(stageIndex)
        : null;

    boolean effectIsReady = false;

    try {
      while (running) {
        // Wait up to 50 ms for work; empty bucket means "nothing to do yet".
        FramePacket framePacket = ownQueue.poll(50, TimeUnit.MILLISECONDS);

        if (framePacket == null) {
          continue;
        }

        if (framePacket == END_PACKET) {
          if (isDisplayStage) {
            videoEnded = true; // the end marker reached the bottom: the video is over
            return;
          }
          // Pass the end marker down so the workers after us also stop.
          passToNextStage(nextQueue, END_PACKET);
          return;
        }

        // Round check: a seek or stop happened while this frame travelled here,
        // so it belongs to an older round and will never be shown. Dropping it
        // now, before any effect runs, saves doing the effect's work for free.
        if (framePacket.version() != version) {
          continue;
        }

        if (frameProcessor != null) {
          if (!effectIsReady) {
            // First frame of this session: tell the effect about the video
            // (its name, length, resolution...) exactly once.
            frameProcessor.initialise(new InfoVideo(
                metadata.mediaName(),
                metadata.totalFrames(),
                framePacket.frame().imageWidth,
                framePacket.frame().imageHeight,
                framePacket.frame().imageDepth,
                framePacket.frame().imageChannels,
                framePacket.frame().imageStride,
                metadata.frameRate(),
                metadata.intFrameRate(),
                metadata.frameDurationUs(),
                metadata.pixelFormat()));
            effectIsReady = true;
          }

          // Apply the effect: it changes the pixels of this frame in place.
          frameProcessor.process(framePacket.frame(),
              new InfoFrame(framePacket.frameNumber(), framePacket.timestampUs()));
        }

        if (!isDisplayStage) {
          // Hand the frame to the next worker in the line.
          if (!passToNextStage(nextQueue, framePacket)) {
            return; // pipeline shut down while waiting
          }
          continue;
        }

        // Last stage: every effect has run, so give the frame to the owner,
        // which converts it to an image and puts it on screen.
        sink.onFrameReadyForDisplay(framePacket);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      if (running) {
        stageError = e; // remember the crash; the owner will collect it
      }
    }
  }
}
