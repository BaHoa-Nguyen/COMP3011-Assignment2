/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Ba Hoa Nguyen - a1938499, in collaboration with Muse Spark 1.3 free
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011.app;

import comp3011.effects.FrameProcessor;
import comp3011.effects.FrameNumberer;
import comp3011.effects.FrameScratcher;
import comp3011.effects.FrameFlickerer;
import comp3011.effects.FrameBlackAndWhiter;
import comp3011.effects.FrameYellower;
import comp3011.effects.FrameVignetter;
import comp3011.effects.FrameDuster;
import comp3011.effects.FrameJitterer;
import comp3011.effects.FrameMottler;
import comp3011.effects.FrameBleeder;
import comp3011.effects.FramePepperer;

import comp3011.dto.CommandOption;
import comp3011.dto.FrameEffectOption;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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

  // Since we only have 5 options and 11 effects for now, using a List would
  // iterate at most 11
  // times effects and at most 5 for options, which is O(n) in both case, and is
  // negligible in terms of performance. If in the future, more options and/or
  // more frames are added, then we can consider using a Map for faster lookup
  private final List<CommandOption> OPTIONS = List.of(
      new CommandOption("-h", "--help",
          () -> helpRequested = true,
          "Show this help message"),

      new CommandOption("-a", "--audio",
          () -> audioRequested = true,
          "Play audio"),

      new CommandOption("-x", "--maximise",
          () -> maximiseRequested = true,
          "Open the player maximised"),

      new CommandOption("-1", "--monitor-1",
          () -> setDisplayId(1),
          "Open the player on display 1"),

      new CommandOption("-2", "--monitor-2",
          () -> setDisplayId(2),
          "Open the player on display 2"));

  private final List<FrameEffectOption> EFFECTS = List.of(
      new FrameEffectOption(
          "-n",
          "--number-frames",
          FrameNumberer::new,
          "Render the frame number onto each frame"),

      new FrameEffectOption(
          "-s",
          "--scratch-frames",
          FrameScratcher::new,
          "Render vertical film scratches"),

      new FrameEffectOption(
          "-f",
          "--flicker-frames",
          FrameFlickerer::new,
          "Randomly dim frames"),

      new FrameEffectOption(
          "-w",
          "--black-and-white",
          FrameBlackAndWhiter::new,
          "Convert frames to black and white"),

      new FrameEffectOption(
          "-y",
          "--yellow-frames",
          FrameYellower::new,
          "Apply a warmer colour temperature"),

      new FrameEffectOption(
          "-v",
          "--vignette-frames",
          FrameVignetter::new,
          "Darken the frame edges"),

      new FrameEffectOption(
          "-d",
          "--dust-frames",
          FrameDuster::new,
          "Render dust and hair marks"),

      new FrameEffectOption(
          "-j",
          "--jitter-frames",
          FrameJitterer::new,
          "Randomly displace frames by a few pixels"),

      new FrameEffectOption(
          "-m",
          "--mottle-frames",
          FrameMottler::new,
          "Add cloudy emulsion mottling"),

      new FrameEffectOption(
          "-b",
          "--bleed-frames",
          FrameBleeder::new,
          "Bleed light into frames"),

      new FrameEffectOption(
          "-p",
          "--pepper-frames",
          FramePepperer::new,
          "Pepper frames with dark spots/blotches"));

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

    // the CLI command looks like: -njpbw, --jitter-frames --number-frames, etc
    // so the regex for the stacked frames would be: the string must start with a
    // hyphen (-), followed by
    // one or more lowercase English letters, and nothing else.
    for (String arg : args) {
      if (arg.matches("^-[a-z]{1,}$")) {
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
    for (CommandOption commandOption : OPTIONS) {
      if (arg.equals(commandOption.shortForm()) || arg.equals(commandOption.longForm())) {
        commandOption.action().run();
        return;
      }
    }

    for (FrameEffectOption frameEffectOption : EFFECTS) {
      if (arg.equals(frameEffectOption.shortForm()) || arg.equals(frameEffectOption.longForm())) {
        frameProcessors.add(frameEffectOption.factory().get());
        return;
      }
    }

    if (arg.startsWith("-")) {
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
    System.out.println();
    System.out.println("Usage: VideoPlayer [options] [video-file]");
    System.out.println();
    System.out.println("Options:");

    for (CommandOption commandOption : OPTIONS) {
      System.out.printf(
          "  %-4s %-20s %s%n",
          commandOption
              .shortForm() + ",",
          commandOption
              .longForm(),
          commandOption
              .optionUsageHelp());
    }
    System.out.println();
    System.out.println("Frame processors:");

    for (FrameEffectOption frameEffectOption : EFFECTS) {
      System.out.printf(
          "  %-4s %-20s %s%n",
          frameEffectOption
              .shortForm() + ",",
          frameEffectOption
              .longForm(),
          frameEffectOption
              .commandUsageHelp());
    }

    System.out.println();
    System.out.println("Frame processors are applied in command-line order and may be repeated.");
    System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
    System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");

    System.out.println();
  }
}
