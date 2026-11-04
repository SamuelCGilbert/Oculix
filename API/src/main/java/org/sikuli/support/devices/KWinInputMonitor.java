/*
 * Copyright (c) 2010-2026, sikuli.org, sikulix.com, oculix-org - MIT license
 */
package org.sikuli.support.devices;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseMotionListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Global input listening for KDE Plasma on Wayland, in place of JNativeHook's
 * GlobalScreen (which hooks X11 and only sees input in X11 windows there).
 * <p>
 * Events come from the AutoKey KWin bridge plugin through "kwin-input monitor"
 * (only the user's real input, never synthetic input such as OculiX's own) and
 * are delivered to the usual JNativeHook listeners as JNativeHook events, in X11
 * coordinates, on one dispatch thread.
 */
public final class KWinInputMonitor {

  private static final List<NativeMouseListener> mouseListeners = new CopyOnWriteArrayList<>();
  private static final List<NativeMouseMotionListener> motionListeners = new CopyOnWriteArrayList<>();
  private static final List<NativeMouseWheelListener> wheelListeners = new CopyOnWriteArrayList<>();
  private static final List<NativeKeyListener> keyListeners = new CopyOnWriteArrayList<>();
  private static final Object LOCK = new Object();
  private static Process process;
  private static Thread reader;

  private KWinInputMonitor() {
  }

  /** @return true when this replaces JNativeHook (KDE Plasma Wayland with kwin-input) */
  public static boolean isActive() {
    return KWinWayland.isActive();
  }

  /**
   * Starts the monitor if needed.
   *
   * @return false if it can't run (the KWin bridge plugin isn't loaded)
   */
  public static boolean start() {
    synchronized (LOCK) {
      if (process != null && process.isAlive()) {
        return true;
      }
      String helper = KWinWayland.helper();
      if (helper == null) {
        return false;
      }
      try {
        ProcessBuilder builder = new ProcessBuilder(helper, "monitor");
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process started = builder.start();
        BufferedReader out = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8));
        String first = out.readLine();
        if (!"ready".equals(first)) {
          started.destroy();
          System.err.println("[OculiX] input monitor not available (is the AutoKey KWin bridge plugin loaded?)");
          return false;
        }
        process = started;
        reader = new Thread(() -> readEvents(out), "OculiX KWin input monitor");
        reader.setDaemon(true);
        reader.start();
        return true;
      } catch (IOException e) {
        System.err.println("[OculiX] input monitor: " + e.getMessage());
        return false;
      }
    }
  }

  /** Stops the monitor when no listener is left. */
  public static void stopIfUnused() {
    synchronized (LOCK) {
      if (mouseListeners.isEmpty() && motionListeners.isEmpty() && wheelListeners.isEmpty() && keyListeners.isEmpty()
          && process != null) {
        process.destroy();
        process = null;
        reader = null;
      }
    }
  }

  public static void addNativeMouseListener(NativeMouseListener l) {
    mouseListeners.add(l);
  }

  public static void removeNativeMouseListener(NativeMouseListener l) {
    mouseListeners.remove(l);
  }

  public static void addNativeMouseMotionListener(NativeMouseMotionListener l) {
    motionListeners.add(l);
  }

  public static void removeNativeMouseMotionListener(NativeMouseMotionListener l) {
    motionListeners.remove(l);
  }

  public static void addNativeMouseWheelListener(NativeMouseWheelListener l) {
    wheelListeners.add(l);
  }

  public static void removeNativeMouseWheelListener(NativeMouseWheelListener l) {
    wheelListeners.remove(l);
  }

  public static void addNativeKeyListener(NativeKeyListener l) {
    keyListeners.add(l);
  }

  public static void removeNativeKeyListener(NativeKeyListener l) {
    keyListeners.remove(l);
  }

  // --- Event conversion -------------------------------------------------------

  private static int pressX = Integer.MIN_VALUE;
  private static int pressY = Integer.MIN_VALUE;

  private static void readEvents(BufferedReader out) {
    try {
      String line;
      long last = 0;
      while ((line = out.readLine()) != null) {
        // Listeners such as the recorder key events by System.currentTimeMillis():
        // never deliver two events in the same millisecond (JNativeHook events
        // come at the pace of real input).
        long now = System.currentTimeMillis();
        if (now <= last) {
          try {
            Thread.sleep(last - now + 1);
          } catch (InterruptedException e) {
            return;
          }
        }
        try {
          dispatch(line.split(" "));
        } catch (RuntimeException e) {
          System.err.println("[OculiX] input monitor: " + line + ": " + e);
        }
        // After the listeners ran: they may have taken the time during the call
        last = System.currentTimeMillis();
      }
    } catch (IOException e) {
      // process ended
    }
  }

  private static int modifiers(String bridgeMask) {
    int mask = Integer.parseInt(bridgeMask);
    int result = 0;
    if ((mask & 1) != 0) {
      result |= NativeInputEvent.SHIFT_L_MASK;
    }
    if ((mask & 2) != 0) {
      result |= NativeInputEvent.CTRL_L_MASK;
    }
    if ((mask & 4) != 0) {
      result |= NativeInputEvent.ALT_L_MASK;
    }
    if ((mask & 8) != 0) {
      result |= NativeInputEvent.META_L_MASK;
    }
    return result;
  }

  private static int buttonMasks(String bridgeButtons) {
    int buttons = Integer.parseInt(bridgeButtons);
    int result = 0;
    if ((buttons & 1) != 0) {
      result |= NativeInputEvent.BUTTON1_MASK;
    }
    if ((buttons & 4) != 0) {
      result |= NativeInputEvent.BUTTON2_MASK; // right, like libuiohook
    }
    if ((buttons & 2) != 0) {
      result |= NativeInputEvent.BUTTON3_MASK; // middle
    }
    return result;
  }

  private static int x11(String logical) {
    return (int) Math.round(Double.parseDouble(logical) * KWinWayland.xwaylandScale());
  }

  private static void dispatch(String[] f) {
    switch (f[0]) {
      case "button": {
        int button;
        switch (Integer.parseInt(f[1])) {
          case 0x110: button = NativeMouseEvent.BUTTON1; break; // BTN_LEFT
          case 0x111: button = NativeMouseEvent.BUTTON2; break; // BTN_RIGHT
          case 0x112: button = NativeMouseEvent.BUTTON3; break; // BTN_MIDDLE
          case 0x113: button = NativeMouseEvent.BUTTON4; break; // BTN_SIDE
          case 0x114: button = NativeMouseEvent.BUTTON5; break; // BTN_EXTRA
          default: return;
        }
        boolean pressed = "1".equals(f[2]);
        int x = x11(f[3]);
        int y = x11(f[4]);
        int mods = modifiers(f[5]);
        NativeMouseEvent e = new NativeMouseEvent(pressed ? NativeMouseEvent.NATIVE_MOUSE_PRESSED
            : NativeMouseEvent.NATIVE_MOUSE_RELEASED, mods, x, y, 1, button);
        for (NativeMouseListener l : mouseListeners) {
          if (pressed) {
            l.nativeMousePressed(e);
          } else {
            l.nativeMouseReleased(e);
          }
        }
        if (pressed) {
          pressX = x;
          pressY = y;
        } else if (Math.abs(x - pressX) <= 2 && Math.abs(y - pressY) <= 2) {
          NativeMouseEvent clicked = new NativeMouseEvent(NativeMouseEvent.NATIVE_MOUSE_CLICKED, mods, x, y, 1, button);
          for (NativeMouseListener l : mouseListeners) {
            l.nativeMouseClicked(clicked);
          }
        }
        break;
      }
      case "motion": {
        int buttons = buttonMasks(f[3]);
        boolean dragged = buttons != 0;
        NativeMouseEvent e = new NativeMouseEvent(dragged ? NativeMouseEvent.NATIVE_MOUSE_DRAGGED
            : NativeMouseEvent.NATIVE_MOUSE_MOVED, modifiers(f[4]) | buttons, x11(f[1]), x11(f[2]), 0, 0);
        for (NativeMouseMotionListener l : motionListeners) {
          if (dragged) {
            l.nativeMouseDragged(e);
          } else {
            l.nativeMouseMoved(e);
          }
        }
        break;
      }
      case "axis": {
        boolean vertical = "v".equals(f[1]);
        double delta = Double.parseDouble(f[2]);
        int v120 = Integer.parseInt(f[3]);
        // One wheel notch: v120 = 120, delta = 15. Positive = down / right.
        int rotation = v120 != 0 ? v120 / 120 : (int) Math.round(delta / 15);
        if (rotation == 0) {
          rotation = delta > 0 ? 1 : -1;
        }
        NativeMouseWheelEvent e = new NativeMouseWheelEvent(NativeMouseEvent.NATIVE_MOUSE_WHEEL, modifiers(f[6]),
            x11(f[4]), x11(f[5]), 0, NativeMouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation,
            vertical ? NativeMouseWheelEvent.WHEEL_VERTICAL_DIRECTION : NativeMouseWheelEvent.WHEEL_HORIZONTAL_DIRECTION);
        for (NativeMouseWheelListener l : wheelListeners) {
          l.nativeMouseWheelMoved(e);
        }
        break;
      }
      case "key": {
        int scan = Integer.parseInt(f[1]);
        int keysym = Integer.parseInt(f[2]);
        boolean pressed = "1".equals(f[3]);
        int mods = modifiers(f[5]);
        int keyCode = KEY_CODES.getOrDefault(scan, scan <= 88 ? scan : NativeKeyEvent.VC_UNDEFINED);
        int location = LOCATIONS.getOrDefault(scan, NativeKeyEvent.KEY_LOCATION_STANDARD);
        NativeKeyEvent e = new NativeKeyEvent(pressed ? NativeKeyEvent.NATIVE_KEY_PRESSED : NativeKeyEvent.NATIVE_KEY_RELEASED,
            mods, keysym, keyCode, NativeKeyEvent.CHAR_UNDEFINED, location);
        for (NativeKeyListener l : keyListeners) {
          if (pressed) {
            l.nativeKeyPressed(e);
          } else {
            l.nativeKeyReleased(e);
          }
        }
        String text = decodeText(f[6]);
        if (pressed && text.length() == 1 && !Character.isISOControl(text.charAt(0))) {
          NativeKeyEvent typed = new NativeKeyEvent(NativeKeyEvent.NATIVE_KEY_TYPED, mods, keysym,
              NativeKeyEvent.VC_UNDEFINED, text.charAt(0), NativeKeyEvent.KEY_LOCATION_UNKNOWN);
          for (NativeKeyListener l : keyListeners) {
            l.nativeKeyTyped(typed);
          }
        }
        break;
      }
      default:
        break;
    }
  }

  private static String decodeText(String hex) {
    if ("-".equals(hex) || hex.isEmpty()) {
      return "";
    }
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  /** evdev key codes that differ from JNativeHook's (set 1 scan code based) VC_ codes */
  private static final Map<Integer, Integer> KEY_CODES = new HashMap<>();
  private static final Map<Integer, Integer> LOCATIONS = new HashMap<>();

  static {
    int[][] codes = {
        {54, NativeKeyEvent.VC_SHIFT}, {96, NativeKeyEvent.VC_ENTER}, {97, NativeKeyEvent.VC_CONTROL},
        {98, NativeKeyEvent.VC_SLASH}, {99, NativeKeyEvent.VC_PRINTSCREEN}, {100, NativeKeyEvent.VC_ALT},
        {102, NativeKeyEvent.VC_HOME}, {103, NativeKeyEvent.VC_UP}, {104, NativeKeyEvent.VC_PAGE_UP},
        {105, NativeKeyEvent.VC_LEFT}, {106, NativeKeyEvent.VC_RIGHT}, {107, NativeKeyEvent.VC_END},
        {108, NativeKeyEvent.VC_DOWN}, {109, NativeKeyEvent.VC_PAGE_DOWN}, {110, NativeKeyEvent.VC_INSERT},
        {111, NativeKeyEvent.VC_DELETE}, {119, NativeKeyEvent.VC_PAUSE}, {125, NativeKeyEvent.VC_META},
        {126, NativeKeyEvent.VC_META}, {127, NativeKeyEvent.VC_CONTEXT_MENU},
        {183, NativeKeyEvent.VC_F13}, {184, NativeKeyEvent.VC_F14}, {185, NativeKeyEvent.VC_F15},
        {186, NativeKeyEvent.VC_F16}, {187, NativeKeyEvent.VC_F17}, {188, NativeKeyEvent.VC_F18},
        {189, NativeKeyEvent.VC_F19}, {190, NativeKeyEvent.VC_F20}, {191, NativeKeyEvent.VC_F21},
        {192, NativeKeyEvent.VC_F22}, {193, NativeKeyEvent.VC_F23}, {194, NativeKeyEvent.VC_F24},
    };
    for (int[] c : codes) {
      KEY_CODES.put(c[0], c[1]);
    }
    int[][] locations = {
        {42, NativeKeyEvent.KEY_LOCATION_LEFT}, {29, NativeKeyEvent.KEY_LOCATION_LEFT},
        {56, NativeKeyEvent.KEY_LOCATION_LEFT}, {125, NativeKeyEvent.KEY_LOCATION_LEFT},
        {54, NativeKeyEvent.KEY_LOCATION_RIGHT}, {97, NativeKeyEvent.KEY_LOCATION_RIGHT},
        {100, NativeKeyEvent.KEY_LOCATION_RIGHT}, {126, NativeKeyEvent.KEY_LOCATION_RIGHT},
        {96, NativeKeyEvent.KEY_LOCATION_NUMPAD}, {98, NativeKeyEvent.KEY_LOCATION_NUMPAD},
    };
    for (int[] l : locations) {
      LOCATIONS.put(l[0], l[1]);
    }
    for (int kp = 71; kp <= 83; kp++) {
      LOCATIONS.put(kp, NativeKeyEvent.KEY_LOCATION_NUMPAD);
    }
  }
}
