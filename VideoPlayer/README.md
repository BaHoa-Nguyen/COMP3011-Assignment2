# COMP3011 Assignment 2 - Java Local Project

## Author: Ba Hoa Nguyen, a1938499, in collaboration with MiMo-V2.6-Flash Free

## Building and running

```sh
mvn javafx:run -Djavafx.args="myvideo.mp4 -a -nssn"   # play a file with effects
mvn test                                              # CLI regression tests
```

`-Djavafx.args` passes everything after `=` to the player as its command-line
arguments (the `javafx-maven-plugin` binds it to `commandlineArgs`).

## Command line

```
Usage: VideoPlayer [options] [video-file]
```

- Options: `-h/--help`, `-a/--audio`, `-x/--maximise`, `-1/--monitor-1`, `-2/--monitor-2`
- Frame processors: `-n -s -f -w -y -v -d -j -m -b -p` (see `--help` for long forms)
- Processors are applied **in command-line order** and **can be repeated**. For example, `-nn`
  applies the effect twice, as two independent instances with different random seeds.
- Bundled short flags such as `-nssnfwyvdjmbp` are split into individual processors.

### Adding a new frame processor

1. Create `comp3011.effects.<FrameName> extends FrameProcessor` and implement
   `process(Frame, InfoFrame)`. Keep it deterministic, meaning that the same media name and
   frame number must always produce the same output, and then use `saltedMediaSeed(...)`
   when you want it to _look_ random.
2. Register one `FrameEffectOption` in the `EFFECTS` list in
   `src/main/java/comp3011/app/CommandLineController.java`: short flag, long flag,
   a `MyThing::new` factory and the help text. That single line is the whole
   integration — help output, parsing, and pipeline wiring all read that list.
3. Nothing else to change. The pipeline builds one worker thread per processor.

## Design correctness (multi-threaded playback)

One `FramePipelineProcessor` is built per playback session from the parsed effect list, and there is a decode worker at the beginning of the pipeline. The number frame processors will be created based on the number of effects entered from the CLI. When all the effects have been applied to the frame, a final publish worker will convert those frame back into the image which would be then shown to the user.

Between each worker, including the decode one, there will be a waiting queue capped at 2 capacity. The reason I keep the queue capacity at 2 is that two is a small buffer that helps the video pipeline keep moving without using too much memory. Think of this like a warehouse, we can have a really big one but the workers are limited to the number of effects from the CLI, so no matter how big the warehouse is, the performance is still dependent on the number of frame processors.

For more information, see the table below:

| Stage             | Count                   | Job                                                                                             |
| ----------------- | ----------------------- | ----------------------------------------------------------------------------------------------- |
| decode            | 1                       | FFmpeg decode, tags every `FramePacket` with the current playback version, offers it to queue 0 |
| effect stage      | `k` (one per processor) | applies exactly one effect, forwards the packet                                                 |
| convert + publish | 1                       | converts to a JavaFX `Image` and publishes one `PreparedFrame`                                  |

The JavaFX thread only displays due frames and drains audio; it never blocks on
decode, effects or convert.

### Shared state and who protects it

| State                                                                                   | Written by               | Read by       | Protection                                                                                                                                                          |
| --------------------------------------------------------------------------------------- | ------------------------ | ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `preparedFrame` and the playback clock fields                                           | last stage               | JavaFX thread | `preparedFrameLock` (monitor, `notifyAll` on both the publish and consume side)                                                                                     |
| `pendingSeekUs`                                                                         | JavaFX (`seekTo`)        | decode stage  | `volatile`, written **before** the version bump so a packet can never carry the new version with the old position                                                   |
| `playbackVersion`                                                                       | JavaFX (`seekTo`, close) | all stages    | `volatile`; every packet is tagged and stale ones are dropped at the stage head **and** re-checked under the lock before publish                                    |
| `pendingAudio`                                                                          | decode stage             | JavaFX thread | `ConcurrentLinkedQueue` (multi-producer/consumer safe)                                                                                                              |
| `playbackOpen`, `pauseRequested`, `videoEnded`, `decodeDone`, `backgroundPlaybackError` | various                  | all stages    | `volatile` flags                                                                                                                                                    |
| `FFmpegFrameGrabber`                                                                    | decode stage only        | —             | stage threads never call into it; `pixelFormat`, `sampleRate` and `audioChannels` are snapshotted once in `openPlaybackResources()` on the thread that owns startup |
| one `FrameProcessor` instance                                                           | its own stage            | —             | never shared between threads, so each processor's `pixelData` scratch buffer is single-threaded by construction                                                     |

### Invariants

1. **Back-pressure**: the waiting queues hold at most 2 frames each, so a slow stage stops the
   stage before it instead of buffering the whole video: flat memory, bounded GC.
2. **Frame accuracy**: a packet's `playbackVersion` must equal the live version at
   both the stage head and the publish point, so a seek or stop can never display a
   stale frame, and cancelled frames are discarded before any effect is paid for.
3. **Single-writer clock**: the seven playback-clock fields are only ever written
   under `preparedFrameLock`, so the JavaFX thread never reads a half-updated time.
4. **Order and repeats are the same data**: stage order is built from the
   command-line list, so effect order cannot diverge from what the user typed, and
   each occurrence is a fresh instance (own `instanceIndex`, own random seed).

### Shutdown

`closePlaybackResources()` detaches the pipeline (a null pipeline now means
"nothing is playing"), raises the playback version under the frame lock so no stale
frame can be shown, then calls `FramePipelineProcessor.stop()`. That interrupts the
workers out of their 50 ms poll/offer waits and clears every queue, so the calling
JavaFX thread only waits a 200 ms grace period - not the 2 seconds a plain
`shutdown()` could stall the window for - before the grabber, converter and audio
line are closed.

## Changes to the supplied starter code

| Starter file                                                                                               | Change                                                                                                                                                                          |
| ---------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CommandLineController`                                                                                    | effect parsing (bundled flags, order, repeats), generated help text, option tables moved into `CommandOption`/`FrameEffectOption` records, first-error-wins validation          |
| `VideoPlayerModel`                                                                                         | single worker thread replaced by the decode/effect/publish pipeline (now extracted into `FramePipelineProcessor`); versioned `FramePacket`; `preparedFrameLock` guarding the shared frame and clock; interrupt-based shutdown on close |
| `VideoPlayerApp`, `VideoPlayerController`                                                                  | hand the `FrameProcessor` list through to the model (ownership established in `VideoPlayerModel`)                                                                               |
| `FrameProcessor`                                                                                           | new `copyPixelsFromFrame`/`copyPixelsBackToFrame` helpers so effects walk a `byte[]` instead of per-pixel `ByteBuffer.get`                                                      |
| `FrameBlackAndWhiter`, `FrameBleeder`, `FrameFlickerer`, `FrameMottler`, `FrameVignetter`, `FrameYellower` | pixel loops rewritten onto the `byte[]` helpers                                                                                                                                 |
| `FrameDuster`, `FrameJitterer`, `FrameNumberer`, `FrameScratcher`, `FramePepperer`                         | package move + attribution only (logic unchanged)                                                                                                                               |
| `InfoFrame`, `InfoVideo`                                                                                   | moved to `comp3011.dto`, content unchanged                                                                                                                                      |
| `AudioPlayer`, `VideoPlayerView`, `VideoPlayerAppLauncher`                                                 | package move + attribution only (logic unchanged)                                                                                                                               |
| `pom.xml`                                                                                                  | JUnit 5 + Surefire for the test suite                                                                                                                                           |
| new files                                                                                                  | `FramePipelineProcessor` (the decode/effect/publish pipeline, extracted from the model), `VideoMetadata`, `FramePacket`, `PreparedFrame`, `PendingAudio`, `CommandOption`, `FrameEffectOption`, `src/test/java/comp3011/app/CommandLineControllerTest.java`                               |

## Testing

`mvn test` runs the `CommandLineController` regression suite (12 tests): bundled
flags and their ordering, repeated effects, every documented flag, help output
contents, and each error message with its exit code.

```sh
mvn test
```
