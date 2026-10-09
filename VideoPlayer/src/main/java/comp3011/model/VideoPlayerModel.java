/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Ba Hoa Nguyen - a1938499, in collaboration with Muse Spark 1.3 Free
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011.model;

import comp3011.effects.FrameProcessor;
import comp3011.dto.InfoFrame;
import comp3011.dto.InfoVideo;

import java.io.File;

import java.util.List;
import java.util.ArrayList;
import java.util.Queue;

// split the decode + effect into a separate worker thread
// from the GUI (JavaFX) thread
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

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
 * and frame processing through an ordered multi-threading pipeline:
 * decode -&gt; effect[0] -&gt ... -&gt; effect[n-1] -&gt; convert then publish
 * in the final thread
 * Each worker owns one effect only, and once it has finished applying the
 * frame, it will forward the frame to the next worker thread over a bounded
 * queue. The JavaFX thread only displays due frames and drains audio, so the
 * GUI never blocks on decode/effects/convert. Order and repeats come from the
 * CLI list; see EFFECTS in {@link CommandLineController}.
 * </p>
 */
public class VideoPlayerModel {
  private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
  private static final long FIVE_SECONDS_US = 5_000_000L; // calculated in MicroSeconds
  private static final long AUDIO_LEAD_NS = 30_000_000L;
  private static final int QUEUE_CAPACITY = 4; // Cap the size of the waiting queue to 4 frames max
  private static final FramePacket END_PACKET = new FramePacket(null, 0, -1, 0); // mark the end of the video

  // Protects access to the prepared frame shared between threads.
  private final Object preparedFrameLock = new Object();

  // each worker will have its own effect, and between the workers there would be
  // a waiting queue
  // the decoded frame will be in the waitingQueues 0
  private final List<ArrayBlockingQueue<FramePacket>> waitingQueues;

  // the number of stages the frame will undergo
  private final int stageCount;
  private ExecutorService pipeline;

  // GUI -> decode worker: where to seek next. Written BEFORE the version
  // bump in seekTo(); the decode loop reads playbackVersion first, then
  // this, so a packet can never carry the new version with the old position.
  private volatile long pendingSeekUs = NO_SEEK_REQUEST;

  // Version of the current playback session.
  // Incremented when seeking or closing so old frames can be discarded.
  private volatile long playbackVersion = 0;

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
  private volatile boolean playbackOpen; // True when playback is happening.
  private volatile boolean pauseRequested; // request pause
  private volatile boolean audioOutputEnabled;
  private volatile boolean audioAvailable;

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
    this.stageCount = frameProcessors.size() + 1; // each worker has its own frame + the final worker (the +1 part)
                                                  // doing the convert
                                                  // and publish.
    this.waitingQueues = new ArrayList<>(stageCount); // create the container
    // build up the assembly line
    for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
      waitingQueues.add(new ArrayBlockingQueue<>(QUEUE_CAPACITY));
    }
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
  }

  private boolean offerToQueue(ArrayBlockingQueue<FramePacket> waitingQueue, FramePacket framePacket)
      throws InterruptedException {
    while (playbackOpen) {
      if (waitingQueue.offer(framePacket, 50, TimeUnit.MILLISECONDS)) {
        return true;
      }
    }
    return false;

  }

  // The decode worker decode the video in Frames and place them into waiting
  // queue 0, where the effect worker will grab those frames and apply effect to
  // them
  private void decodeFrame() {
    try {
      while (playbackOpen) {

        // check if the user wants to seek or not
        long packetVersion = playbackVersion;
        long seekUs = pendingSeekUs;

        // seek requested
        if (seekUs != NO_SEEK_REQUEST) {
          pendingSeekUs = NO_SEEK_REQUEST; // mark the request handled
          grabber.setTimestamp(seekUs);
        }

        Frame frame = grabFrame();

        // video not playing
        if (!playbackOpen) {
          return;
        }

        // reach the end of the video
        if (frame == null) {
          offerToQueue(waitingQueues.get(0), END_PACKET);
          return;
        }

        long timestampUs = grabber.getTimestamp();

        // if there is audio and the frame contains the audio data
        if (audioAvailable && frame.samples != null) {
          queueAudio(timestampUs, frame);
        }

        // skip frames that does not contain images
        if (frame.image == null) {
          continue;
        }

        // grab() override the existing frame data, which could corrupt the pipeline
        // we need to use .clone() here to avoid that
        // Reference:
        // https://boofcv.org/javadoc/org/bytedeco/copiedstuff/FFmpegFrameGrabber.html#grab()
        FramePacket clonedPacket = new FramePacket(
            frame.clone(),
            timestampUs,
            grabber.getFrameNumber(),
            packetVersion);

        if (!offerToQueue(waitingQueues.get(0), clonedPacket)) {
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      if (playbackOpen) {
        backgroundPlaybackError = e;
      }
    }
  }

  // This method will concurrently take the frame from the waiting queue and apply
  // the effect to them.
  // The number of workers are based on the number of effects coming from the CLI
  // plus the final worker to convert and publish the processed frame to the GUI
  // thread one-by-one.
  private void processDecodedFrame(int stageIndex) {
    ArrayBlockingQueue<FramePacket> currentWaitingQueue = waitingQueues.get(stageIndex);

    boolean isLastStage = stageIndex == stageCount - 1;

    ArrayBlockingQueue<FramePacket> nextWaitingQueue = isLastStage ? null : waitingQueues.get(stageIndex + 1);

    FrameProcessor frameProcessor = stageIndex < frameProcessors.size() ? frameProcessors.get(stageIndex) : null;

    boolean isProcessorInitialised = false;

    try {
      while (playbackOpen) {
        FramePacket framePacket = currentWaitingQueue.poll(50, TimeUnit.MILLISECONDS);

        if (framePacket == null) {
          continue;
        }

        if (framePacket == END_PACKET) {
          if (isLastStage) {
            videoEnded = true;
            return;
          }

          offerToQueue(nextWaitingQueue, END_PACKET);
          return;

        }

        if (frameProcessor != null) {
          if (!isProcessorInitialised) {
            frameProcessor.initialise(new InfoVideo(
                mediaName(videoFile),
                totalVideoFrames,
                framePacket.frame().imageWidth,
                framePacket.frame().imageHeight,
                framePacket.frame().imageDepth,
                framePacket.frame().imageChannels,
                framePacket.frame().imageStride,
                frameRate,
                intFrameRate,
                videoFrameDurationUs,
                grabber.getPixelFormat()));

            isProcessorInitialised = true;
          }

          frameProcessor.process(framePacket.frame(),
              new InfoFrame(framePacket.frameNumber(), framePacket.timestampUs()));
        }

        if (!isLastStage) {
          if (!offerToQueue(nextWaitingQueue, framePacket)) {
            return;
          }
          continue;
        }

        // convert and publish the processed frame one by one
        Image image = converter.convert(framePacket.frame());
        long readyNs = System.nanoTime();

        synchronized (preparedFrameLock) {
          // if the the playback is open but
          // (a) the video is pause or (b) there's a frame in the queue
          // then we wait for 50ms
          while (playbackOpen && (pauseRequested || preparedFrame != null)) {
            preparedFrameLock.wait(50);
          }

          if (!playbackOpen) {
            return;
          }

          if (framePacket.version() != playbackVersion) {
            continue;
          }

          // first frame of current playback
          if (firstTimestampUs == NO_SEEK_REQUEST) {
            firstTimestampUs = framePacket.timestampUs();
            playbackStartNs = System.nanoTime();
            if (pauseRequested) {
              pauseStartedNs = playbackStartNs;
            }
          }

          long relativeTimestampUs = Math.max(0, framePacket.timestampUs() - firstTimestampUs);
          long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
          long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

          preparedFrame = new PreparedFrame(
              image,
              framePacket.frameNumber(),
              framePacket.timestampUs(),
              logicalTimestampUs,
              targetTimeNs,
              readyNs);

        }

      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      if (playbackOpen) {
        backgroundPlaybackError = e;
      }
    }

  }

  // start our pipeline
  private void startPipeline() {
    pipeline = Executors.newFixedThreadPool(stageCount + 1, runnable -> {
      Thread thread = new Thread(runnable);
      thread.setDaemon(true);
      return thread;
    });

    pipeline.execute(this::decodeFrame);

    for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
      final int lambdaIndex = stageIndex;
      pipeline.execute(() -> processDecodedFrame(lambdaIndex));
    }

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

    if (!playbackOpen || videoEnded) {
      startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
      return;
    }

    pendingSeekUs = grabTimestampUs;

    // discard old frame
    synchronized (preparedFrameLock) {
      playbackVersion++;
      preparedFrame = null;
      resetPlaybackClock(logicalTimestampUs);
      preparedFrameLock.notifyAll();
    }

    // when the user seek to another timestamp, the current frames in the queue are
    // old so we discard them in the queue
    for (ArrayBlockingQueue<FramePacket> waitingQueue : waitingQueues) {
      waitingQueue.clear();
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
      playbackOpen = true;
      playbackTimer.start();
      startPipeline();
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
    }
  }

  // FX heartbeat only. The heavy work has been deligated to the workers.
  // Workers do decode/effects/convert off-thread and publish a single
  // preparedFrame under preparedFrameLock. We consume it here
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

    if (videoEnded && preparedFrame == null) {
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
    playbackOpen = false;
    pendingSeekUs = NO_SEEK_REQUEST;
    videoEnded = false;

    synchronized (preparedFrameLock) {
      playbackVersion++;
      preparedFrame = null;
      preparedFrameLock.notifyAll();
    }

    for (ArrayBlockingQueue<FramePacket> waitingQueue : waitingQueues) {
      waitingQueue.clear();
    }

    pendingAudio.clear();
    firstTimestampUs = NO_SEEK_REQUEST;
    playbackStartNs = 0;
    pauseStartedNs = 0;
    audioAvailable = false;

    if (pipeline != null) {
      pipeline.shutdown();

      try {
        pipeline.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      pipeline = null;
    }

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

}
