package comp3011.dto;

public record CommandOption(
    String shortForm,
    String longForm,
    Runnable action,
    String optionUsageHelp) {
}
