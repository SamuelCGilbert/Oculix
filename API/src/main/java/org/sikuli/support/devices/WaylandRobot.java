/*
 * Copyright (c) 2010-2026, sikuli.org, sikulix.com, oculix-org - MIT license
 */
package org.sikuli.support.devices;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.GraphicsDevice;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * A java.awt.Robot that works on KDE Plasma Wayland too (see {@link KWinWayland}).
 * Everywhere else it is a plain java.awt.Robot.
 */
public class WaylandRobot extends Robot {

  public WaylandRobot() throws AWTException {
    super();
  }

  public WaylandRobot(GraphicsDevice screen) throws AWTException {
    super(screen);
  }

  /** Replacement for MouseInfo.getPointerInfo().getLocation(), null if unknown. */
  public static Point pointerLocation() {
    if (KWinWayland.isActive()) {
      return KWinWayland.pointer();
    }
    PointerInfo info = MouseInfo.getPointerInfo();
    return info == null ? null : info.getLocation();
  }

  @Override
  public synchronized void mouseMove(int x, int y) {
    if (KWinWayland.isActive()) {
      KWinWayland.mouseMove(x, y);
      afterEvent();
    } else {
      super.mouseMove(x, y);
    }
  }

  @Override
  public synchronized void mousePress(int buttons) {
    if (KWinWayland.isActive()) {
      KWinWayland.mousePress(buttons);
      afterEvent();
    } else {
      super.mousePress(buttons);
    }
  }

  @Override
  public synchronized void mouseRelease(int buttons) {
    if (KWinWayland.isActive()) {
      KWinWayland.mouseRelease(buttons);
      afterEvent();
    } else {
      super.mouseRelease(buttons);
    }
  }

  @Override
  public synchronized void mouseWheel(int wheelAmt) {
    if (KWinWayland.isActive()) {
      KWinWayland.mouseWheel(wheelAmt);
      afterEvent();
    } else {
      super.mouseWheel(wheelAmt);
    }
  }

  @Override
  public synchronized void keyPress(int keycode) {
    if (KWinWayland.isActive()) {
      KWinWayland.keyPress(keycode);
      afterEvent();
    } else {
      super.keyPress(keycode);
    }
  }

  @Override
  public synchronized void keyRelease(int keycode) {
    if (KWinWayland.isActive()) {
      KWinWayland.keyRelease(keycode);
      afterEvent();
    } else {
      super.keyRelease(keycode);
    }
  }

  @Override
  public synchronized Color getPixelColor(int x, int y) {
    if (KWinWayland.isActive()) {
      return new Color(KWinWayland.capture(new Rectangle(x, y, 1, 1)).getRGB(0, 0));
    }
    return super.getPixelColor(x, y);
  }

  @Override
  public synchronized BufferedImage createScreenCapture(Rectangle screenRect) {
    if (KWinWayland.isActive()) {
      return KWinWayland.capture(screenRect);
    }
    return super.createScreenCapture(screenRect);
  }

  /** Same as java.awt.Robot after an event: honour auto delay / waitForIdle. */
  private void afterEvent() {
    if (isAutoWaitForIdle()) {
      waitForIdle();
    }
    int delay = getAutoDelay();
    if (delay > 0) {
      delay(delay);
    }
  }
}
