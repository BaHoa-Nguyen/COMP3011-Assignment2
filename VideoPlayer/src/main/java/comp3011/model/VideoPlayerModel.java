/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Ba Hoa Nguyen - a1938499, in collaboration with MiMo-V2.6-Flash Free
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011.model;

import comp3011.effects.FrameProcessor;
import comp3011.dto.VideoMetadata;

import java.io.File;

import java.util.List;
import java.util.Queue;

// the pipeline runs its workers on their own threads, away from the
// GUI (JavaFX) thread
import java.util.concurrent.ConcurrentLinkedQueue;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.sound.sampled.LineUnavailableException;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;

import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic and audio scheduling.
 * Frame processing itself lives in {@link FramePipelineProcessor}, which this
 * class creates fresh for every playback session from the effect list on the
 * command line: decode -&gt; effect[0] -&gt ... -&gt; effect[n-1] -&gt; display.
 * The JavaFX thread only displays due frames and drains audio, so the GUI
 * never blocks on decode/effects/convert. This class keeps the frame lock and
 * the clock; the pipeline keeps the workers and the buckets between them.
 * </p>
 */
public class VideoPlayerModel {
  private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
  private static final long FIVE_SECONDS_US = 5_000_000L; // calculated in MicroSeconds
  private static final long AUDIO_LEAD_NS = 30_000_000L;

  // Protects access to the prepared frame shared between threads.
  private final Object preparedFrameLock = new Object();

  // This session's assembly line (workers + the buckets between them).
  // null means "nothing is playing" - that is how every reader tells.
  // Written only on the JavaFX thread; read by the FX heartbeat and the sink.
  private volatile FramePipelineProcessor pipeline;

  // Asks the decode worker whether sound is still wanted, on every frame.
  // Reads the volatile audioAvailable field instead of freezing a copy.
  private final BooleanSupplier wantsSamples = () -> this.audioAvailable;

  // The two hand-off points of the pipeline, running on worker threads:
  // one collects a decoded frame's sound, the other converts a finished
  // frame to an image and publishes it under the frame lock.
  private final FramePipelineProcessor.FrameSink frameSink = new FramePipelineProcessor.FrameSink() {
    @Override
    public void onFrameDecoded(Frame frame, long timestampUs) {
      if (audioAvailable && frame.samples != null) {
        queueAudio(timestampUs, frame);
      }
    }

    @Override
    public void onFrameReadyForDisplay(FramePacket packet) {
      FramePipelineProcessor active = pipeline;

      // Convert off-lock: making the image can take a millisecond and must
      // never hold up the GUI thread.
      Image image = converter.convert(packet.frame());
      long readyNs = System.nanoTime();

      synchronized (preparedFrameLock) {
        // Wait until the screen has taken the previous frame (or until we are
        // paused, or until a seek/close wakes us). One frame in flight at a
        // time: that is what keeps memory and frame pacing steady.
        try {
          while (pipeline != null && (pauseRequested || preparedFrame != null)) {
            preparedFrameLock.wait(50);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return; // stop() interrupted us: drop this frame and stand down
        }

        if (pipeline == null) {
          return; // the player was closed while we waited
        }

        // Round check: a seek happened while we waited, so this frame is
        // stale. Drop it instead of flashing an old picture.
        if (packet.version() != active.currentFrameVersion()) {
          return;
        }

        // First frame of this playback: anchor the clock to it.
        if (firstTimestampUs == NO_SEEK_REQUEST) {
          firstTimestampUs = packet.timestampUs();
          playbackStartNs = System.nanoTime();
          if (pauseRequested) {
            pauseStartedNs = playbackStartNs;
          }
        }

        // Work out where this frame should appear on the timeline.
        long relativeTimestampUs = Math.max(0, packet.timestampUs() - firstTimestampUs);
        long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
        long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

        // Hand the frame over: the FX heartbeat displays it when its time comes.
        preparedFrame = new PreparedFrame(
            image,
            packet.frameNumber(),
            packet.timestampUs(),
            logicalTimestampUs,
            targetTimeNs,
            readyNs,
            packet.version());
      }

      // Opening the sound device can block for ~100ms, so do it once, only
      // after a frame is already on screen - never on the first-frame path.
      if (audioAvailable && audioPlayer == null) {
        audioPlayer = new AudioPlayer();
        try {
          audioPlayer.open(sampleRate, audioChannels);
        } catch (LineUnavailableException e) {
          audioAvailable = false; // no sound line --> video continues silently
        }
      }
    }
  };

  private final List<FrameProcessor> frameProcessors; // store frames
  private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>(); // ConcurrentLinkedQueue because two
                                                                                  // threads use it: the worker adds
                                                                                  // sound chunks, the GUI takes them
                                                                                  // out. ArrayDeque is not safe for
                                                                                  // that.

  private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
  private final Consumer<Image> frameReadyHandler;
  private final Consumer<String> statusChangedHandler;
  private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
  private final Consumer<Boolean> audioOutputStateChangedHandler;

  // This is the critical wiring that allows the framework (JavaFX) to call
  // into our model logic every time it goes around its event loop. Every GUI
  // system has an event loop so that button clicks, key presses and window
  // resizes can be responded to. Most offer ways of running additional logic
  // in either idle time (when there are no pending UI events to process) or
  // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
  // we just create a little anonymous local subclass and override the handle
  // method to call the method we want run every heart beat.
  private final AnimationTimer playbackTimer = new AnimationTimer() {
    @Override
    public void handle(long now) {
      pumpPlayback(now);
    }
  };

  // since we use multi-threading, all the threads need to be kept updated with
  // the
  // lastest value of most of these variables --> use the "volatile"" keyword for
  // this purpose

  private volatile File videoFile; // Path to the media that comes from the command line
  private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame
                                      // objects.
  private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on
                                          // screen.
  private volatile AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
  private volatile PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
  private volatile boolean pauseRequested; // request pause
  private volatile boolean audioOutputEnabled;
  private volatile boolean audioAvailable;

  private volatile long currentTimestampUs; // where we are in the video (e.g. 4:00)
  private volatile long videoDurationUs = NO_SEEK_REQUEST;
  private volatile int videoFrameDurationUs;
  // Grabber metadata captured once in openPlaybackResources(), on the thread that
  // owns startup. FFmpegFrameGrabber is not thread-safe, so stage threads must
  // never call back into it - they read these fields instead.
  private volatile int pixelFormat;
  private volatile int sampleRate;
  private volatile int audioChannels;
  private volatile double frameRate; // frame rate in double
  private volatile int intFrameRate; // frame rate in Integer
  private volatile int totalVideoFrames;
  private volatile long firstTimestampUs = NO_SEEK_REQUEST;
  private volatile long logicalPlaybackBaseUs;
  private volatile long playbackStartNs;
  private volatile long pauseStartedNs;
  private volatile long relativeSeekBaseUs = NO_SEEK_REQUEST;

  // constructor
  public VideoPlayerModel(
      boolean audioEnabled,
      List<FrameProcessor> frameProcessors,
      BiConsumer<Integer, Integer> videoSizeChangedHandler,
      Consumer<Image> frameReadyHandler,
      Consumer<String> statusChangedHandler,
      BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
      Consumer<Boolean> audioOutputStateChangedHandler) {
    audioOutputEnabled = audioEnabled;
    this.videoSizeChangedHandler = videoSizeChangedHandler;
    this.frameReadyHandler = frameReadyHandler;
    this.statusChangedHandler = statusChangedHandler;
    this.playbackStateChangedHandler = playbackStateChangedHandler;
    this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
    this.frameProcessors = frameProcessors; // the pipeline is built from this list, in this order
  }

  // play the video file
  public void play(File file) {
    videoFile = file;
    videoDurationUs = NO_SEEK_REQUEST;
    videoFrameDurationUs = 0;
    startPlayback(0, false, NO_SEEK_REQUEST);
  }

  // back to the start
  public void startOver() {
    if (videoFile == null) {
      return;
    }

    seekTo(0);
  }

  // back 5 seconds
  public void backFiveSeconds() {
    seekRelative(-FIVE_SECONDS_US);
  }

  // forward 5 secs
  public void forwardFiveSeconds() {
    seekRelative(FIVE_SECONDS_US);
  }

  // request pause (flip pauseRequested)
  public void togglePause() {
    if (pipeline == null) { // nothing is playing: start it up again from where it stopped
      if (videoFile != null) {
        startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
      }
      return;
    }

    pauseRequested = !pauseRequested;
    relativeSeekBaseUs = NO_SEEK_REQUEST;

    if (pauseRequested) {
      pauseStartedNs = System.nanoTime();
      flushAudioOutput();
    } else {
      resumePlaybackClock(System.nanoTime());
    }

    notifyPlaybackStateChanged();
  }

  // mute and unmute
  public void toggleAudioOutput() {
    audioOutputEnabled = !audioOutputEnabled;
    pendingAudio.clear();
    flushAudioOutput();
    notifyAudioOutputStateChanged();
  }

  public boolean isAudioOutputEnabled() {
    return audioOutputEnabled;
  }

  // close everything
  public void stopPlayback() {
    closePlaybackResources();
    currentTimestampUs = 0;
    pauseRequested = false;
    notifyStatusChanged("Stopped");
    notifyFrameReady(null);
    notifyPlaybackStateChanged();
  }

  public void shutdown() {
    closePlaybackResources();
  }

  // Build this session's assembly line from the command-line effect list
  // and set the workers going. Called once per playback session.
  private void startPipeline() {
    pipeline = new FramePipelineProcessor(
        frameProcessors,
        grabber,
        wantsSamples,
        new VideoMetadata(
            mediaName(videoFile),
            totalVideoFrames,
            frameRate,
            intFrameRate,
            videoFrameDurationUs,
            pixelFormat),
        frameSink);
    pipeline.start();
  }

  private void seekRelative(long offsetUs) {
    if (videoFile == null) {
      return;
    }

    long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
        ? relativeSeekBaseUs
        : currentTimestampUs;
    seekTo(baseTimestampUs + offsetUs);
  }

  private void seekTo(long timestampUs) {
    long logicalTimestampUs = clampSeekTimestamp(timestampUs);
    long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

    if (pipeline == null || pipeline.hasVideoEnded() || pipeline.hasDecodeFinished()) {
      // Nothing running (or it just finished): start a fresh session at the new spot.
      startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
      return;
    }

    // Tell the decode worker where to jump - BEFORE the round number is
    // raised, so no frame can carry the new round with the old position.
    pipeline.requestSeek(grabTimestampUs);

    // discard old frames: raise the round number and empty the buckets
    // while holding the frame lock, so nothing stale can still be shown
    synchronized (preparedFrameLock) {
      pipeline.discardCancelledFrames();
      preparedFrame = null;
      resetPlaybackClock(logicalTimestampUs);
      preparedFrameLock.notifyAll();
    }

    flushAudioOutput();
    notifyPlaybackStateChanged();
  }

  // GUI thread
  private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
    closePlaybackResources();

    pauseRequested = initiallyPaused;
    currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
        ? initialRelativeSeekBaseUs
        : startTimestampUs;
    relativeSeekBaseUs = initialRelativeSeekBaseUs;

    notifyStatusChanged(videoFile.getName());

    try {
      openPlaybackResources(startTimestampUs);
      resetPlaybackClock(currentTimestampUs);
      playbackTimer.start();
      startPipeline(); // a non-null pipeline from here on means "playback is open"
      notifyPlaybackStateChanged();
    } catch (Exception e) {
      handlePlaybackError(e);
    }
  }

  private void openPlaybackResources(long startTimestampUs) throws Exception {
    converter = new JavaFXFrameConverter();
    grabber = new FFmpegFrameGrabber(videoFile);
    grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
    grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
    grabber.start();

    // read the grabber's metadata here, on the one thread that owns startup;
    // the stage threads must never call back into FFmpegFrameGrabber
    pixelFormat = grabber.getPixelFormat();
    sampleRate = grabber.getSampleRate();
    audioChannels = grabber.getAudioChannels();

    notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

    frameRate = grabber.getFrameRate();
    intFrameRate = (int) Math.round(frameRate);
    videoFrameDurationUs = frameRate > 0
        ? (int) Math.round(1_000_000.0 / frameRate)
        : 0;
    totalVideoFrames = grabber.getLengthInVideoFrames();

    long durationUs = grabber.getLengthInTime();
    videoDurationUs = durationUs > 0
        ? durationUs
        : NO_SEEK_REQUEST;

    audioAvailable = grabber.hasAudio();

    if (startTimestampUs > 0) {
      grabber.setTimestamp(startTimestampUs);
    }
  }

  private void resetPlaybackClock(long logicalTimestampUs) {
    // this writes 7 clock fields so synchronized here prevent the reader from
    // reading mid-write of the writer
    synchronized (preparedFrameLock) {
      pendingAudio.clear();
      currentTimestampUs = logicalTimestampUs;
      relativeSeekBaseUs = logicalTimestampUs;
      firstTimestampUs = NO_SEEK_REQUEST;
      logicalPlaybackBaseUs = logicalTimestampUs;
      playbackStartNs = 0;
      pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

  }

  // moves the start anchor forward by the pause seconds, so the video time
  // continues where it froze instead of jumping ahead
  private void resumePlaybackClock(long now) {
    synchronized (preparedFrameLock) {
      if (pauseStartedNs > 0 && playbackStartNs > 0) {
        playbackStartNs += now - pauseStartedNs;
      }
      pauseStartedNs = 0;
      preparedFrameLock.notifyAll();
    }
  }

  // FX heartbeat only. The heavy work has been deligated to the workers.
  // Workers do decode/effects/convert off-thread and publish a single
  // preparedFrame under preparedFrameLock. We consume it here
  private void pumpPlayback(long now) {
    FramePipelineProcessor active = pipeline;
    if (active == null) {
      return; // nothing is playing
    }

    // display the error if any worker had one since we last looked
    Exception error = active.takeStageError();
    if (error != null) {
      handlePlaybackError(error);
      return;
    }

    // the end marker reached the screen and no frame is waiting: wrap up
    if (active.hasVideoEnded() && preparedFrame == null) {
      finishPlayback();
      return;
    }

    // Task (1)
    writeDueAudio(now);

    // Task (2)
    if (preparedFrame != null && preparedFrame.targetTimeNs() <= now) {
      // We have a prepared frame ready to go and it is due (or just past due!) so get
      // it up on screen ASAP!
      displayPreparedFrame(now);
    }

  }

  private void displayPreparedFrame(long now) {
    PreparedFrame frame;

    // take the frame out through the lock, so we can never grab it
    // at the same moment the worker is swapping in a new one.

    synchronized (preparedFrameLock) {
      frame = preparedFrame;
      preparedFrame = null;

      preparedFrameLock.notifyAll();

      // a seek or shutdown can clear or re-version the frame between the
      // due-check in pumpPlayback and taking this lock: bail instead of
      // showing a torn stale frame or NPE-ing on the FX thread
      if (frame == null || pipeline == null || frame.version() != pipeline.currentFrameVersion()) {
        return;
      }
      currentTimestampUs = frame.logicalTimestampUs();
      relativeSeekBaseUs = NO_SEEK_REQUEST;
    }

    // Dump some logging to the console once per second so that real time
    // performance can be monitored.
    if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
      long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
      if (remainingUs < 0) {
        System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
            remainingUs,
            videoFrameDurationUs);
      } else {
        System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
            remainingUs,
            videoFrameDurationUs);
      }
    }

    notifyFrameReady(frame.image());
  }

  private void queueAudio(long timestampUs, Frame frame) {

    AudioPlayer player = audioPlayer;

    if (!audioOutputEnabled || player == null) {
      return;
    }

    byte[] samples = player.copySamples(frame);
    if (samples.length > 0) {
      pendingAudio.add(new PendingAudio(timestampUs, samples));
    }
  }

  private void writeDueAudio(long now) {
    // Handle some obvious early exits
    if (!audioOutputEnabled || audioPlayer == null) {
      pendingAudio.clear();
      return;
    }

    long snapFirst, snapStart;

    synchronized (preparedFrameLock) {
      snapFirst = firstTimestampUs;
      snapStart = playbackStartNs;
    }

    if (pauseRequested || snapFirst == NO_SEEK_REQUEST || snapStart <= 0) {
      return;
    }

    // Here is the real time dependent logic
    long dueTimestampUs = snapFirst + (now + AUDIO_LEAD_NS - snapStart) / 1_000L;
    while (!pendingAudio.isEmpty()) {
      PendingAudio audio = pendingAudio.peek();
      if (audio.timestampUs() > dueTimestampUs) {
        return;
      }

      int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
      if (written <= 0) {
        return;
      }

      audio.advance(written);
      if (!audio.finished()) {
        return;
      }
      pendingAudio.remove();
    }
  }

  private void finishPlayback() {
    long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
        ? videoDurationUs
        : currentTimestampUs;

    closePlaybackResources();
    pauseRequested = true;
    currentTimestampUs = endTimestampUs;
    relativeSeekBaseUs = endTimestampUs;
    notifyStatusChanged("Playback finished");
    notifyPlaybackStateChanged();
  }

  private void handlePlaybackError(Exception e) {
    e.printStackTrace();
    closePlaybackResources();
    pauseRequested = false;
    notifyStatusChanged("Error: " + e.getMessage());
    notifyPlaybackStateChanged();
  }

  private void closePlaybackResources() {
    playbackTimer.stop();

    // Detach first: from here on every reader sees "nothing is playing".
    FramePipelineProcessor closing = pipeline;
    pipeline = null;

    // Throw away in-flight frames under the frame lock, so nothing stale
    // can still reach the screen, and wake any worker waiting on the lock.
    synchronized (preparedFrameLock) {
      if (closing != null) {
        closing.discardCancelledFrames();
      }
      preparedFrame = null;
      preparedFrameLock.notifyAll();
    }

    // Now stop the workers: interrupt, drain the buckets, 200 ms grace.
    if (closing != null) {
      closing.stop();
    }

    pendingAudio.clear();
    firstTimestampUs = NO_SEEK_REQUEST;
    playbackStartNs = 0;
    pauseStartedNs = 0;
    audioAvailable = false;
    pixelFormat = 0;
    sampleRate = 0;
    audioChannels = 0;

    closeGrabberNow();
    closeConverterNow();

    if (audioPlayer != null) {
      audioPlayer.close();
      audioPlayer = null;
    }
  }

  private void closeConverterNow() {
    if (converter != null) {
      converter.close();
      converter = null;
    }

  }

  private void closeGrabberNow() {
    if (grabber != null) {
      try {
        grabber.stop();
      } catch (Exception e) {
        // The resource is being closed; there is no useful recovery action.
      }
      try {
        grabber.close();
      } catch (Exception e) {
        // The resource is being closed; there is no useful recovery action.
      }
      grabber = null;
    }
  }

  private String mediaName(File file) {
    String fileName = file.getName();
    int extensionStart = fileName.lastIndexOf('.');
    if (extensionStart <= 0) {
      return fileName;
    }
    return fileName.substring(0, extensionStart);
  }

  private long clampSeekTimestamp(long timestampUs) {
    long clampedTimestampUs = Math.max(0, timestampUs);
    long durationUs = videoDurationUs;
    if (durationUs != NO_SEEK_REQUEST) {
      clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
    }
    return clampedTimestampUs;
  }

  private long displayableSeekTimestamp(long logicalTimestampUs) {
    long durationUs = videoDurationUs;
    if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
      return logicalTimestampUs;
    }

    int frameDurationUs = videoFrameDurationUs;
    if (frameDurationUs <= 0) {
      return Math.max(0, durationUs - 1);
    }

    return Math.max(0, durationUs - frameDurationUs);
  }

  private void flushAudioOutput() {
    if (audioPlayer != null) {
      audioPlayer.flush();
    }
  }

  private void notifyVideoSizeChanged(int width, int height) {
    videoSizeChangedHandler.accept(width, height);
  }

  private void notifyFrameReady(Image image) {
    frameReadyHandler.accept(image);
  }

  private void notifyStatusChanged(String status) {
    statusChangedHandler.accept(status);
  }

  private void notifyPlaybackStateChanged() {
    playbackStateChangedHandler.accept(pipeline != null, pauseRequested);
  }

  private void notifyAudioOutputStateChanged() {
    audioOutputStateChangedHandler.accept(audioOutputEnabled);
  }

}
