package comp3011.dto;

import java.util.function.Supplier;

import comp3011.effects.FrameProcessor;

// This is
public record FrameEffectOption(
    String shortForm,
    String longForm,
    Supplier<FrameProcessor> factory,
    String commandUsageHelp) {
}
