package comp3011.dto;

import java.util.function.Supplier;

import comp3011.effects.FrameProcessor;

/**
 * This is for the 11 frame effects, treated as an object
 *
 */
public record FrameEffectOption(
    String shortForm,
    String longForm,
    Supplier<FrameProcessor> factory,
    String commandUsageHelp) {
}
