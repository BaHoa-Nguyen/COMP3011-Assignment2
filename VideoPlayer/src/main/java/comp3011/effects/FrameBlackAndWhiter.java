/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Ba Hoa Nguyen - a1938499, in collaboration with MiMo-V2.6-Flash Free
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011.effects;

import comp3011.dto.InfoFrame;

import java.nio.ByteBuffer;

import org.bytedeco.javacv.Frame;

/**
 * Converts colour frames to greyscale.
 *
 * <p>
 * {@link #process(Frame, InfoFrame)} calculates each pixel's luminance from
 * its BGR channels and writes that value back to every colour channel through
 * {@link #convertFrame(byte[])}.
 * </p>
 */
public class FrameBlackAndWhiter extends FrameProcessor {

  public FrameBlackAndWhiter() {
    super();
  }

  @Override
  public void process(Frame frame, InfoFrame info) throws Exception {
    if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
      return;
    }

    if (!(frame.image[0] instanceof ByteBuffer framePixels)) {
      throw new Exception("FrameBlackAndWhiter requires byte-backed image frames");
    }

    if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
      throw new Exception("FrameBlackAndWhiter requires 8-bit image frames");
    }

    if (videoInfo.imageChannels() < 3) {
      throw new Exception("FrameBlackAndWhiter requires at least three image channels");
    }

    byte[] pixels = copyPixelsFromFrame(framePixels);
    convertFrame(pixels);
    copyPixelsBackToFrame(framePixels);
  }

  private void convertFrame(byte[] pixels) {
    int height = videoInfo.imageHeight();
    int width = videoInfo.imageWidth();
    int channels = videoInfo.imageChannels();
    int stride = videoInfo.imageStride();

    for (int y = 0; y < height; y++) {
      int rowStart = y * stride;
      for (int x = 0; x < width; x++) {
        int base = rowStart + x * channels;
        // JavaCV supplies these decoded frames in BGR byte order.
        int blue = pixels[base] & 0xFF;
        int green = pixels[base + 1] & 0xFF;
        int red = pixels[base + 2] & 0xFF;
        byte luminance = (byte) ((54 * red + 183 * green + 19 * blue) / 256);

        for (int channel = 0; channel < channels; channel++) {
          pixels[base + channel] = luminance;
        }
      }
    }
  }
}
