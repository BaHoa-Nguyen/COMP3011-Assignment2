package comp3011.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import comp3011.effects.FrameProcessor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the command-line contract: which flags exist, the order
 * effects come out in, repeats, and every error message. If any changes are
 * made through the CLI,
 * this class tells you what you broke.
 */
public class CommandLineControllerTest {
  // The controller prints help and errors straight to System.out during its
  // constructor, so we swap the stream out, run, and read back what was printed.
  private final PrintStream originalConsoleOutput = System.out;
  private final ByteArrayOutputStream capturedConsoleOutput = new ByteArrayOutputStream();

  // create a temporary directory
  @TempDir
  Path temporaryDirectory;

  // It redirects printed messages into capturedConsoleOutput instead of the
  // terminal.
  @BeforeEach
  void captureConsoleOutput() {
    System.setOut(new PrintStream(capturedConsoleOutput, true));
  }

  // other tests and the application aren't accidentally left printing into the
  // test's memory buffer.
  @AfterEach
  void restoreConsoleOutput() {
    System.setOut(originalConsoleOutput);
  }

  private String getCapturedConsoleOutput() {
    return capturedConsoleOutput.toString();
  }

  // retrieve and store the frame processors name in a list
  private static List<String> getFrameProcessorNames(CommandLineController controller) {
    return controller.getFrameProcessors().stream().map(processor -> processor.getClass().getSimpleName()).toList();
  }

  // This method creates a fake video file for testing how the program handles an
  // invalid video.
  private String createTempVideoFile() throws Exception {
    Path videoFilePath = temporaryDirectory.resolve("clip.mp4");
    Files.writeString(videoFilePath, "invalid video file");
    return videoFilePath.toString();
  }

  // Test commandline parsing
  @Test
  void bundledShortFlagsExpandInOrder() throws Exception {
    CommandLineController cli = new CommandLineController(
        new String[] { "-nssnfwyvdjmbp", createTempVideoFile() });

    assertEquals(
        List.of(
            "FrameNumberer", "FrameScratcher", "FrameScratcher", "FrameNumberer",
            "FrameFlickerer", "FrameBlackAndWhiter", "FrameYellower", "FrameVignetter",
            "FrameDuster", "FrameJitterer", "FrameMottler", "FrameBleeder",
            "FramePepperer"),
        getFrameProcessorNames(cli));
  }

  @Test
  void sameEffectsTwiceMeansTwoDifferentProcessors() throws Exception {
    CommandLineController cli = new CommandLineController(new String[] {
        "-nn", createTempVideoFile()
    });

    List<FrameProcessor> processors = cli.getFrameProcessors();
    assertEquals(2, processors.size());
    assertNotSame(processors.get(0), processors.get(1));
  }

  @Test
  void longFormsKepeTheirCommandLineOrder() throws Exception {
    CommandLineController cli = new CommandLineController(new String[] {
        "--number-frames", "--jitter-frames", createTempVideoFile()
    });

    assertEquals(List.of("FrameNumberer", "FrameJitterer"), getFrameProcessorNames(cli));
  }

  @Test
  void everyDocumentedFlagBuildsItsEffect() throws Exception {
    Map<String, String> shortToClass = Map.ofEntries(
        Map.entry("-n", "FrameNumberer"),
        Map.entry("-s", "FrameScratcher"),
        Map.entry("-f", "FrameFlickerer"),
        Map.entry("-w", "FrameBlackAndWhiter"),
        Map.entry("-y", "FrameYellower"),
        Map.entry("-v", "FrameVignetter"),
        Map.entry("-d", "FrameDuster"),
        Map.entry("-j", "FrameJitterer"),
        Map.entry("-m", "FrameMottler"),
        Map.entry("-b", "FrameBleeder"),
        Map.entry("-p", "FramePepperer"));

    for (Map.Entry<String, String> flag : shortToClass.entrySet()) {
      CommandLineController cli = new CommandLineController(
          new String[] { flag.getKey(), createTempVideoFile() });
      assertEquals(List.of(flag.getValue()), getFrameProcessorNames(cli),
          "flag " + flag.getKey() + " should build " + flag.getValue());
    }
  }

  @Test
  void plainOptionStillParse() throws Exception {
    CommandLineController cli = new CommandLineController(new String[] {
        "-a", "-x", "-2", createTempVideoFile()
    });

    assertTrue(cli.isAudioRequested());
    assertTrue(cli.isMaximiseRequested());
    assertEquals(2, cli.getDisplayId());
    assertTrue(cli.shouldLaunchApplication());
    assertTrue(getFrameProcessorNames(cli).isEmpty(), "no -n style flag means no effects");
  }

  // Test print help message

  @Test
  void helpListyOptionAndEveryEffect() {
    CommandLineController cli = new CommandLineController(new String[] { "--help" });

    assertTrue(getCapturedConsoleOutput().contains("Usage: VideoPlayer [options] [video-file]"));
    assertTrue(getCapturedConsoleOutput().contains("-h,"), "missing -h row");
    assertTrue(getCapturedConsoleOutput().contains("--monitor-2"), "missing -2 row");
    assertTrue(getCapturedConsoleOutput().contains("Frame processors:"), "missing effects section");
    assertTrue(getCapturedConsoleOutput().contains("--pepper-frames"), "missing -p row");
    assertTrue(getCapturedConsoleOutput().contains("applied in command-line order and may be repeated"),
        "help must state ordering and repeat rules");

    assertEquals(0, cli.getExitCode());
    assertFalse(cli.shouldLaunchApplication(), "help alone must not launch the player");
  }

  // test exeception handling

  @Test
  void noArgumentsComplainsAboutTheMissingFile() {
    CommandLineController cli = new CommandLineController(new String[] {});

    assertEquals("No video file specified.", cli.getErrorMessage());
    assertEquals(1, cli.getExitCode());
    assertFalse(cli.shouldLaunchApplication());
  }

  @Test
  void unknownOptionIsReportedEvenWithoutAFile() {
    CommandLineController cli = new CommandLineController(new String[] { "-z" });

    assertEquals("Unknown option: -z", cli.getErrorMessage(),
        "the first, most specific error must win over the generic missing-file one");
    assertEquals(1, cli.getExitCode());
  }

  @Test
  void unknownOptionNextToAGoodFileIsStillRejected() throws Exception {
    CommandLineController cli = new CommandLineController(new String[] { "-q", createTempVideoFile() });

    assertEquals("Unknown option: -q", cli.getErrorMessage());
    assertFalse(cli.shouldLaunchApplication());
  }

  @Test
  void missingFileIsReported() {
    CommandLineController cli = new CommandLineController(
        new String[] { temporaryDirectory.resolve("nope.mp4").toString() });

    assertTrue(cli.getErrorMessage().startsWith("File not found:"));
    assertNull(cli.getVideoFile());
    assertEquals(1, cli.getExitCode());
  }

  @Test
  void twoVideoFilesShowUsage() throws Exception {
    String firstVideo = createTempVideoFile();
    String secondVideo = createTempVideoFile().replace("clip.mp4", "other.mp4");
    Files.writeString(temporaryDirectory.resolve("other.mp4"), "x");

    CommandLineController cli = new CommandLineController(new String[] { firstVideo, secondVideo });

    assertEquals("Usage: VideoPlayer [options] [video-file]", cli.getErrorMessage());
    assertFalse(cli.shouldLaunchApplication());
  }

  @Test
  void onlyOneMonitorFlagIsAllowed() throws Exception {
    CommandLineController conflicting = new CommandLineController(
        new String[] { "-1", "-2", createTempVideoFile() });
    assertEquals("Only one display option can be used", conflicting.getErrorMessage());

    CommandLineController single = new CommandLineController(
        new String[] { "-1", createTempVideoFile() });
    assertEquals(1, single.getDisplayId());
    assertTrue(single.shouldLaunchApplication());
  }
}
