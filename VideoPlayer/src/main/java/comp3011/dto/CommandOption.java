package comp3011.dto;

/**
 * This is for the 5 command options, treated as an object
 */

public record CommandOption(
    String shortForm,
    String longForm,
    Runnable action,
    String optionUsageHelp) {
}
