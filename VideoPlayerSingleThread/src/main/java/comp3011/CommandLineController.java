/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Ba Hoa Nguyen - a1938499
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import java.util.Map;
import java.util.function.Supplier;

import org.bytedeco.opencv.presets.opencv_core.Str;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video
 * player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to
 * the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
  private final String[] args;

  private boolean helpRequested;
  private boolean audioRequested;
  private boolean maximiseRequested;
  private Integer displayId;
  private File videoFile;
  private String errorMessage;

  // storing the effects
  private List<FrameProcessor> frameProcessors = new ArrayList<>();

  // use a Map + Supplier for faster lookup of the frame instead of a chaining
  // if-else
  private final Map<String, Supplier<FrameProcessor>> frameProcessorMap = Map.ofEntries(
      Map.entry("-n", FrameNumberer::new),
      Map.entry("--number-frames", FrameNumberer::new),

      Map.entry("-s", FrameScratcher::new),
      Map.entry("--scratch-frames", FrameScratcher::new),

      Map.entry("-f", FrameFlickerer::new),
      Map.entry("--flicker-frames", FrameFlickerer::new),

      Map.entry("-w", FrameBlackAndWhiter::new),
      Map.entry("--black-and-white", FrameBlackAndWhiter::new),

      Map.entry("-y", FrameYellower::new),
      Map.entry("--yellow-frames", FrameYellower::new),

      Map.entry("-v", FrameVignetter::new),
      Map.entry("--vignette-frame", FrameVignetter::new),

      Map.entry("-d", FrameDuster::new),
      Map.entry("--dust-frame", FrameDuster::new),

      Map.entry("-j", FrameJitterer::new),
      Map.entry("--jitter-frames", FrameJitterer::new),

      Map.entry("-m", FrameMottler::new),
      Map.entry("--mottle-frames", FrameMottler::new),

      Map.entry("-b", FrameBleeder::new),
      Map.entry("--bleed-frames", FrameBleeder::new),

      Map.entry("-p", FramePepperer::new),
      Map.entry("--pepper-frames", FramePepperer::new));

  // use Map to avoid long if-else chaining
  private final Map<String, Runnable> optionMap = Map.of(
      "-h", () -> helpRequested = true,
      "--help", () -> helpRequested = true,

      "-a", () -> audioRequested = true,
      "--audio", () -> audioRequested = true,

      "-x", () -> maximiseRequested = true,
      "--maximise", () -> maximiseRequested = true,

      "-1", () -> setDisplayId(1),
      "--monitor-1", () -> setDisplayId(1),

      "-2", () -> setDisplayId(2),
      "--monitor-2", () -> setDisplayId(2));

  public CommandLineController(String[] args) {
    this.args = args.clone();
    parse();
    if (errorMessage != null) {
      System.out.println(errorMessage);
    }
    if (helpRequested) {
      printHelp();
    }
  }

  public File getVideoFile() {
    return videoFile;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public int getExitCode() {
    return errorMessage == null ? 0 : 1;
  }

  public Integer getDisplayId() {
    return displayId;
  }

  public boolean isAudioRequested() {
    return audioRequested;
  }

  public boolean isMaximiseRequested() {
    return maximiseRequested;
  }

  public boolean shouldLaunchApplication() {
    return errorMessage == null && videoFile != null;
  }

  public List<FrameProcessor> getFrameProcessors() {
    return frameProcessors;
  }

  private void parse() {
    List<String> videoFiles = new ArrayList<>();

    List<String> stackedFrames = stackFrameProcessor();

    for (String arg : stackedFrames) {
      processArgument(arg, videoFiles);
    }

    validateVideoFile(videoFiles);

  }

  // handling frame stacking up
  private List<String> stackFrameProcessor() {
    List<String> stackedProcessors = new ArrayList<>();

    for (String arg : args) {
      if (arg.matches("^-[a-z]{2,}$")) {
        for (int argIndex = 1; argIndex < arg.length(); argIndex++) {
          stackedProcessors.add("-" + arg.charAt(argIndex));
        }
      } else {
        stackedProcessors.add(arg);
      }
    }
    return stackedProcessors;
  }

  private void validateVideoFile(List<String> videoFiles) {
    if (videoFiles.size() > 1) {
      errorMessage = "Usage: VideoPlayer [options] [video-file]";
    } else if (videoFiles.size() == 1) {
      videoFile = new File(videoFiles.get(0));
      if (!videoFile.isFile()) {
        errorMessage = "File not found: " + videoFile.getPath();
        videoFile = null;
      }
    } else {
      if (!helpRequested) {
        errorMessage = "No video file specified.";
      }
    }

  }

  // process the arguments
  private void processArgument(String arg, List<String> videoFiles) {
    Runnable option = optionMap.get(arg);

    // Reference:
    // https://medium.com/but-it-works-on-my-machine/supplier-t-what-is-it-and-how-to-use-it-in-java-846e8517374a?sk=5d48b62fad246a079716b5c791a55284
    Supplier<FrameProcessor> frameFactory = frameProcessorMap.get(arg);// use Map to avoid long if-else chaining

    // Map will return null when the key does not exist
    if (option != null) {
      option.run();
      return; // return immediately as option cannot stack as required in the description
    }

    // Map will return null when the key does not exist
    if (frameFactory != null) {
      frameProcessors.add(frameFactory.get());
    } else if (arg.startsWith("-")) {
      errorMessage = "Unknown option: " + arg;
    } else {
      videoFiles.add(arg);
    }

  }

  private void setDisplayId(int displayId) {
    if (this.displayId != null && this.displayId != displayId) {
      errorMessage = "Only one display option can be used";
      return;
    }
    this.displayId = displayId;
  }

  private void printHelp() {
    System.out.println("Usage: VideoPlayer [options] [video-file]");
    System.out.println();
    System.out.println("Options:");
    System.out.println("  -h, --help         Show this help message");
    System.out.println("  -a, --audio        Play audio");
    System.out.println("  -x, --maximise     Open the player maximised");
    System.out.println("  -1, --monitor-1    Open the player on display 1");
    System.out.println("  -2, --monitor-2    Open the player on display 2");
    System.out.println();
    System.out.println("Frame processors:");
    System.out.println("  -n, --number-frames    Render the frame number onto each frame");
    System.out.println("  -s, --scratch-frames   Render vertical film scratches");
    System.out.println("  -f, --flicker-frames   Randomly dim frames");
    System.out.println("  -w, --black-and-white  Convert frames to black and white");
    System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
    System.out.println("  -v, --vignette-frame   Darken the frame edges");
    System.out.println("  -d, --dust-frame       Render dust and hair marks");
    System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
    System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
    System.out.println("  -b, --bleed-frames     Bleed light into frames");
    System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
    System.out.println();
    System.out.println("Frame processors are applied in command-line order and may be repeated.");
    System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
    System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
  }
}
