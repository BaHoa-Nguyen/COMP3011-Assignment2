/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011.model;

import comp3011.effects.FrameProcessor;
import comp3011.media.InfoFrame;
import comp3011.media.InfoVideo;

import java.io.File;

import java.util.List;
import java.util.Queue;

// split the decode + effect into a separate worker thread
// from the GUI (JavaFX) thread
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
  private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
  private static final long FIVE_SECONDS_US = 5_000_000L; // calculated in MicroSeconds
  private static final long AUDIO_LEAD_NS = 30_000_000L;

  // decode + effect thread
  private final ExecutorService decodeEffectExecutor = Executors.newSingleThreadExecutor(runnable -> {
    Thread decodeEffectThread = new Thread(runnable, "decode-worker");
    decodeEffectThread.setDaemon(true); // allow JVM exiting normally
    return decodeEffectThread;
  });

  // Protects access to the prepared frame shared between threads.
  private final Object preparedFrameLock = new Object();

  // Version of the current playback session.
  // Incremented when seeking or closing so old frames can be discarded.
  private volatile long playbackVersion = 0;

  // True while a frame-preparation job is queued or being processed.
  private volatile boolean framePreparationInProgress = false;

  // True when the decoder has reached the end of the video.
  private volatile boolean videoEnded = false;

  // Stores an error produced during background playback processing.
  private volatile Exception backgroundPlaybackError = null;

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

  // since we split into 2 threads, the threads need to be kept updated with the
  // lastest value of most of these variables --> use the volatile keyword for
  // this purpose

  private volatile File videoFile; // Path to the media that comes from the command line
  private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame
                                      // objects.
  private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on
                                          // screen.
  private volatile AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
  private volatile PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
  private volatile boolean playbackOpen; // True when playback is happening.
  private volatile boolean pauseRequested; // request pause
  private volatile boolean audioOutputEnabled;
  private volatile boolean audioAvailable;
  private volatile boolean frameProcessorsInitialised;
  private volatile long currentTimestampUs; // where we are in the video (e.g. 4:00)
  private volatile long videoDurationUs = NO_SEEK_REQUEST;
  private volatile int videoFrameDurationUs;
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
    this.frameProcessors = frameProcessors;
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
    if (!playbackOpen) {
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
    decodeEffectExecutor.close(); // shutdown the worker as well
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

    if (!playbackOpen) {
      startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
      return;
    }

    // discard old frame
    synchronized (preparedFrameLock) {
      playbackVersion++;
      preparedFrame = null;
    }

    long currentFrameVersion = playbackVersion;

    flushAudioOutput();

    decodeEffectExecutor.execute(() -> {
      try {
        grabber.setTimestamp(grabTimestampUs);
        resetPlaybackClock(logicalTimestampUs);
        prepareNextFrame(currentFrameVersion);
      } catch (Exception e) {
        backgroundPlaybackError = e; // handled by the GUI
      }
    });

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
      playbackOpen = true;
      playbackTimer.start();
      submitPreparedNextFrameToWorker();
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
    audioPlayer = new AudioPlayer();
    if (audioAvailable) {
      audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
    }

    if (startTimestampUs > 0) {
      grabber.setTimestamp(startTimestampUs);
    }
  }

  private void resetPlaybackClock(long logicalTimestampUs) {
    pendingAudio.clear();
    currentTimestampUs = logicalTimestampUs;
    relativeSeekBaseUs = logicalTimestampUs;
    firstTimestampUs = NO_SEEK_REQUEST;
    logicalPlaybackBaseUs = logicalTimestampUs;
    playbackStartNs = 0;
    pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
  }

  private void resumePlaybackClock(long now) {
    if (pauseStartedNs > 0 && playbackStartNs > 0) {
      playbackStartNs += now - pauseStartedNs;
    }
    pauseStartedNs = 0;
  }

  private void submitPreparedNextFrameToWorker() {
    if (framePreparationInProgress) {
      return;
    }

    framePreparationInProgress = true;

    // remember the current frame before sending to the worker
    long currentFrameVersion = playbackVersion;

    // posting
    decodeEffectExecutor.execute(() -> {
      try {
        if (!pauseRequested) { // re-check if the user has clicked pause or not
          prepareNextFrame(currentFrameVersion);
        }
      } finally {
        framePreparationInProgress = false; // job done -> GUI may post the next one
      }
    });

  }

  private void prepareNextFrame(long frameGeneration) {
    if (!playbackOpen || preparedFrame != null) {
      return;
    }

    try {
      PreparedFrame nextFrame = readNextVideoFrame();

      if (nextFrame == null) {
        return;
      }

      // discard old frame
      synchronized (preparedFrameLock) {
        if (playbackVersion == frameGeneration) {
          preparedFrame = nextFrame;
        }
      }

    } catch (Exception e) {
      backgroundPlaybackError = e; // handled by the GUI
    }
  }

  private PreparedFrame readNextVideoFrame() throws Exception {
    while (playbackOpen) {
      Frame frame = grabFrame();
      if (frame == null) {
        videoEnded = true;
        return null;
      }

      long timestampUs = grabber.getTimestamp();
      if (audioAvailable && frame.samples != null) {
        queueAudio(timestampUs, frame);
      }

      if (frame.image == null) {
        continue;
      }

      if (!frameProcessorsInitialised) {
        initialiseFrameProcessors(new InfoVideo(
            mediaName(videoFile),
            totalVideoFrames,
            frame.imageWidth,
            frame.imageHeight,
            frame.imageDepth,
            frame.imageChannels,
            frame.imageStride,
            frameRate,
            intFrameRate,
            videoFrameDurationUs,
            grabber.getPixelFormat()));
        frameProcessorsInitialised = true;
      }

      if (firstTimestampUs == NO_SEEK_REQUEST) {
        firstTimestampUs = timestampUs;
        playbackStartNs = System.nanoTime();
        if (pauseRequested) {
          pauseStartedNs = playbackStartNs;
        }
      }

      int frameNumber = grabber.getFrameNumber();
      InfoFrame info = new InfoFrame(frameNumber, timestampUs);
      processFrame(frame, info);

      Image image = converter.convert(frame);
      long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
      long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
      long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

      return new PreparedFrame(
          image,
          frameNumber,
          timestampUs,
          logicalTimestampUs,
          targetTimeNs,
          System.nanoTime());
    }

    return null;
  }

  // This is called on a heart beat by the GUI thread. We want to be co-operative
  // here by (a) not blocking, and (b)
  // getting our required work out of the way quickly so that we can return
  // control flow to the JavaFX event loop for
  // handling user interaction with the GUI and rendering. We have three jobs: (1)
  // keep audio flowing if sound is on,
  // (2) display a frame if it is due and (3) prepare the next frame if we're in
  // the window after the previous frame
  // has gone to the display. We don't buffer frames here, just handling them one
  // at a time. Not a great architecture,
  // living on the edge a bit, but can't do much better on a single thread.
  private void pumpPlayback(long now) {
    if (!playbackOpen) {
      return;
    }

    // display the error if caught any
    if (backgroundPlaybackError != null) {
      Exception e = backgroundPlaybackError;
      backgroundPlaybackError = null;
      handlePlaybackError(e);
      return;
    }

    if (videoEnded) {
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

    // Task (3). We'll try and be nice to the GUI event loop here by queueing the
    // prepareNextFrame call rather than
    // hogging the thread and doing it here, hence the tricky callback and use of
    if (!pauseRequested && preparedFrame == null) {
      submitPreparedNextFrameToWorker(); // the job goes to the worker --> decoding + effects no longer eat the GUI
                                         // thread's time
    }
  }

  private void displayPreparedFrame(long now) {
    PreparedFrame frame;

    // take the frame out through the lock, so we can never grab it
    // at the same moment the worker is swapping in a new one.

    synchronized (preparedFrameLock) {
      frame = preparedFrame;
      preparedFrame = null;
    }
    currentTimestampUs = frame.logicalTimestampUs();
    relativeSeekBaseUs = NO_SEEK_REQUEST;

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

    byte[] samples = audioPlayer.copySamples(frame);
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
    if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
      return;
    }

    // Here is the real time dependent logic
    long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
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
    playbackOpen = false;
    framePreparationInProgress = false;

    synchronized (preparedFrameLock) {
      playbackVersion++;
      preparedFrame = null;
    }

    pendingAudio.clear();
    firstTimestampUs = NO_SEEK_REQUEST;
    playbackStartNs = 0;
    pauseStartedNs = 0;
    audioAvailable = false;
    frameProcessorsInitialised = false;

    if (audioPlayer != null) {
      audioPlayer.close();
      audioPlayer = null;
    }

    // (B4) Grabber + converter are worker-owned: the worker closes them,
    // after whatever it is doing right now (FIFO order), and we wait.
    try {
      decodeEffectExecutor.submit(() -> {
        closeGrabberNow();
        closeConverterNow();
      }).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      // Teardown is best-effort; the worker is a daemon thread, so a
      // failure here can never stop the application from exiting.
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

  // process all the available frame in the ArrayList
  private void processFrame(Frame frame, InfoFrame info) throws Exception {
    for (FrameProcessor processor : frameProcessors) {
      processor.process(frame, info);
    }
  }

  private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
    for (FrameProcessor processor : frameProcessors) {
      processor.initialise(videoInfo);
    }
  }

  private Frame grabFrame() throws Exception {
    if (audioAvailable) {
      return grabber.grab();
    }
    return grabber.grabImage();
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
    playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
  }

  private void notifyAudioOutputStateChanged() {
    audioOutputStateChangedHandler.accept(audioOutputEnabled);
  }

  private record PreparedFrame(
      Image image,
      int frameNumber,
      long mediaTimestampUs,
      long logicalTimestampUs,
      long targetTimeNs,
      long preparedAtNs) {
  }

  private static class PendingAudio {
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
}
