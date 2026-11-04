/*
 * Copyright (c) 2010-2026, sikuli.org, sikulix.com, oculix-org - MIT license
 */
package org.sikuli.support.devices;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Screen capture, mouse and keyboard for KDE Plasma on Wayland.
 * <p>
 * On Wayland, java.awt.Robot runs through XWayland: it can only see and control
 * X11 windows, captures of native Wayland windows are black and the pointer
 * position is only known over X11 windows. KWin offers the same features to
 * trusted programs: the kwin-input helper (an executable listed in a .desktop
 * file with the KWin permissions) uses KWin's ScreenShot2 D-Bus API for
 * captures, org_kde_kwin_fake_input for mouse and keyboard and KWin scripting
 * for the pointer position.
 * <p>
 * Coordinates: OculiX works in XWayland (X11) coordinates, which are KWin's
 * logical coordinates multiplied by the XWayland scale ([Xwayland] Scale in
 * kwinrc, the scale X11 apps use when KWin lets them scale themselves).
 * <p>
 * Disable with -Doculix.wayland=false or OCULIX_WAYLAND=0.
 */
public final class KWinWayland {

  private static final Object LOCK = new Object();
  private static Boolean active;
  private static Process helper;
  private static Writer helperIn;
  private static BufferedReader helperOut;
  private static double xwaylandScale = 1;
  private static final File KWINRC = new File(configHome(), "kwinrc");

  private static String configHome() {
    String dir = System.getenv("XDG_CONFIG_HOME");
    return dir != null && !dir.isEmpty() ? dir : System.getProperty("user.home") + "/.config";
  }

  private KWinWayland() {
  }

  /** @return true when running on KDE Plasma Wayland with the kwin-input helper available */
  public static boolean isActive() {
    synchronized (LOCK) {
      if (active == null) {
        active = detect();
      }
      return active;
    }
  }

  private static boolean detect() {
    if ("false".equals(System.getProperty("oculix.wayland")) || "0".equals(System.getenv("OCULIX_WAYLAND"))) {
      return false;
    }
    String session = System.getenv("XDG_SESSION_TYPE");
    String desktop = System.getenv("XDG_CURRENT_DESKTOP");
    if (!"wayland".equals(session) || desktop == null || !desktop.toUpperCase().contains("KDE")) {
      return false;
    }
    String path = helperPath();
    if (path == null) {
      System.err.println("[OculiX] KDE Wayland session, but kwin-input was not found: screen capture and input only work with X11 windows");
      return false;
    }
    System.err.println("[OculiX] KDE Wayland session: using " + path + " for screen capture, mouse and keyboard");
    return true;
  }

  /** Path of the kwin-input helper, or null. */
  static String helper() {
    return helperPath();
  }

  /** X11 coordinates / KWin logical coordinates. */
  static double xwaylandScale() {
    return scale();
  }

  private static String helperPath() {
    String[] candidates = {
        System.getenv("OCULIX_KWIN_INPUT"),
        System.getProperty("user.home") + "/.local/opt/kwin-input/bin/kwin-input",
        "/usr/local/bin/kwin-input",
        "/usr/bin/kwin-input",
    };
    for (String candidate : candidates) {
      if (candidate != null && new File(candidate).canExecute()) {
        return candidate;
      }
    }
    return null;
  }

  /** Run one kwin-input command, returns the data after "ok" or throws. */
  private static String command(String... args) {
    StringBuilder line = new StringBuilder();
    for (String arg : args) {
      if (line.length() > 0) {
        line.append(' ');
      }
      line.append('"').append(arg.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    synchronized (LOCK) {
      for (int attempt = 0; attempt < 2; attempt++) {
        try {
          if (helper == null || !helper.isAlive()) {
            ProcessBuilder builder = new ProcessBuilder(helperPath(), "--delay", "0", "-");
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            helper = builder.start();
            helperIn = new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8);
            helperOut = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
          }
          helperIn.write(line.append('\n').toString());
          helperIn.flush();
          String reply = helperOut.readLine();
          if (reply == null) {
            throw new IOException("kwin-input exited");
          }
          if (reply.equals("ok")) {
            return "";
          }
          if (reply.startsWith("ok ")) {
            return reply.substring(3);
          }
          throw new IllegalStateException("kwin-input " + String.join(" ", args) + ": " + reply);
        } catch (IOException e) {
          helper = null;
          line.setLength(line.length() - 1);
          if (attempt == 1) {
            throw new IllegalStateException("kwin-input failed: " + e.getMessage(), e);
          }
        }
      }
    }
    return "";
  }

  private static long scaleChecked;

  /**
   * XWayland scale: X11 coordinates / KWin logical coordinates. Measured by
   * comparing the X11 screen size with KWin's output layout (rechecked every
   * couple of seconds, the user may change the display scale), falls back to
   * [Xwayland] Scale in kwinrc.
   */
  private static double scale() {
    synchronized (LOCK) {
      long now = System.currentTimeMillis();
      if (now - scaleChecked > 2000) {
        scaleChecked = now;
        double measured = measureScale();
        xwaylandScale = measured > 0 ? measured : readXwaylandScale();
      }
      return xwaylandScale;
    }
  }

  private static double measureScale() {
    try {
      Rectangle x11 = new Rectangle();
      for (java.awt.GraphicsDevice device : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
        x11 = x11.union(device.getDefaultConfiguration().getBounds());
      }
      Rectangle logical = new Rectangle();
      for (String output : command("outputs").split(";")) {
        String[] f = output.trim().split(" ");
        if (f.length >= 5) {
          logical = logical.union(new Rectangle((int) Double.parseDouble(f[1]), (int) Double.parseDouble(f[2]),
              (int) Double.parseDouble(f[3]), (int) Double.parseDouble(f[4])));
        }
      }
      if (logical.width > 0 && x11.width > 0) {
        return x11.width / (double) logical.width;
      }
    } catch (RuntimeException e) {
      System.err.println("[OculiX] XWayland scale: " + e.getMessage());
    }
    return -1;
  }

  private static double readXwaylandScale() {
    try {
      List<String> lines = Files.readAllLines(KWINRC.toPath(), StandardCharsets.UTF_8);
      boolean inGroup = false;
      for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.startsWith("[")) {
          inGroup = trimmed.equals("[Xwayland]");
        } else if (inGroup && trimmed.startsWith("Scale=")) {
          double value = Double.parseDouble(trimmed.substring(6));
          return value > 0 ? value : 1;
        }
      }
    } catch (IOException | NumberFormatException e) {
      // default
    }
    return 1;
  }

  // --- Screen ---------------------------------------------------------------

  /** Capture an area given in X11 coordinates; the image has the size of the area. */
  public static BufferedImage capture(Rectangle rect) {
    double s = scale();
    // Whole logical pixels around the area, cropped exactly afterwards
    int lx = (int) Math.floor(rect.x / s);
    int ly = (int) Math.floor(rect.y / s);
    int lw = (int) Math.ceil((rect.x + rect.width) / s) - lx;
    int lh = (int) Math.ceil((rect.y + rect.height) / s) - ly;
    Path file = null;
    try {
      file = Files.createTempFile(runtimeDir(), "oculix-capture-", ".raw");
      String[] meta = command("capture", "" + lx, "" + ly, "" + lw, "" + lh, file.toString()).split(" ");
      int width = Integer.parseInt(meta[0]);
      int height = Integer.parseInt(meta[1]);
      int stride = Integer.parseInt(meta[2]);
      byte[] data = Files.readAllBytes(file);
      ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
      // The captured image covers lx * f .. (lx + lw) * f, f = width / lw. Map
      // the requested X11 area into it (f == s unless outputs differ in scale).
      double f = width / (double) lw;
      double offsetX = rect.x / s * f - lx * f;
      double offsetY = rect.y / s * f - ly * f;
      double step = f / s;
      BufferedImage image = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB);
      int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
      long ox = Math.round(offsetX);
      long oy = Math.round(offsetY);
      if (Math.abs(step - 1) < 1e-9 && Math.abs(offsetX - ox) < 1e-6 && Math.abs(offsetY - oy) < 1e-6
          && ox + rect.width <= width && oy + rect.height <= height) {
        // 1:1 (the usual case): copy rows
        java.nio.IntBuffer ints = buffer.asIntBuffer();
        for (int y = 0; y < rect.height; y++) {
          ints.position((int) ((oy + y) * (stride / 4) + ox));
          ints.get(pixels, y * rect.width, rect.width);
        }
        for (int i = 0; i < pixels.length; i++) {
          pixels[i] &= 0xffffff;
        }
        return image;
      }
      int[] row = new int[rect.width];
      for (int y = 0; y < rect.height; y++) {
        int sy = Math.min(height - 1, (int) Math.round(offsetY + y * step));
        for (int x = 0; x < rect.width; x++) {
          int sx = Math.min(width - 1, (int) Math.round(offsetX + x * step));
          // BGRA little endian = 0xAARRGGBB as an int
          row[x] = buffer.getInt(sy * stride + sx * 4) & 0xffffff;
        }
        image.setRGB(0, y, rect.width, 1, row, 0, rect.width);
      }
      return image;
    } catch (IOException e) {
      throw new IllegalStateException("KWin capture failed: " + e.getMessage(), e);
    } finally {
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          // ignore
        }
      }
    }
  }

  private static Path runtimeDir() {
    String dir = System.getenv("XDG_RUNTIME_DIR");
    if (dir != null && new File(dir).isDirectory()) {
      return new File(dir).toPath();
    }
    return new File(System.getProperty("java.io.tmpdir")).toPath();
  }

  // --- Mouse ----------------------------------------------------------------

  /** Pointer position in X11 coordinates, or null. */
  public static Point pointer() {
    try {
      String[] xy = command("pointer").split(" ");
      double s = scale();
      return new Point((int) Math.round(Double.parseDouble(xy[0]) * s), (int) Math.round(Double.parseDouble(xy[1]) * s));
    } catch (RuntimeException e) {
      System.err.println("[OculiX] pointer position: " + e.getMessage());
      return null;
    }
  }

  public static void mouseMove(int x, int y) {
    double s = scale();
    command("move", String.format(java.util.Locale.ROOT, "%.3f", x / s), String.format(java.util.Locale.ROOT, "%.3f", y / s));
  }

  private static void buttons(int buttons, String action) {
    if ((buttons & (InputEvent.BUTTON1_DOWN_MASK | InputEvent.BUTTON1_MASK)) != 0) {
      command(action, "left");
    }
    if ((buttons & (InputEvent.BUTTON2_DOWN_MASK | InputEvent.BUTTON2_MASK)) != 0) {
      command(action, "middle");
    }
    if ((buttons & (InputEvent.BUTTON3_DOWN_MASK | InputEvent.BUTTON3_MASK)) != 0) {
      command(action, "right");
    }
  }

  public static void mousePress(int buttons) {
    buttons(buttons, "mousedown");
  }

  public static void mouseRelease(int buttons) {
    buttons(buttons, "mouseup");
  }

  public static void mouseWheel(int steps) {
    command("scroll", "" + steps);
  }

  // --- Keyboard -------------------------------------------------------------

  private static final Map<Integer, Integer> KEYSYMS = new HashMap<>();

  static {
    for (int c = KeyEvent.VK_A; c <= KeyEvent.VK_Z; c++) {
      KEYSYMS.put(c, (int) Character.toLowerCase((char) c));
    }
    for (int c = KeyEvent.VK_0; c <= KeyEvent.VK_9; c++) {
      KEYSYMS.put(c, c);
    }
    for (int i = 0; i < 12; i++) {
      KEYSYMS.put(KeyEvent.VK_F1 + i, 0xffbe + i);
      KEYSYMS.put(KeyEvent.VK_F13 + i, 0xffca + i);
    }
    for (int i = 0; i <= 9; i++) {
      KEYSYMS.put(KeyEvent.VK_NUMPAD0 + i, 0xffb0 + i);
    }
    int[][] map = {
        {KeyEvent.VK_ENTER, 0xff0d}, {KeyEvent.VK_BACK_SPACE, 0xff08}, {KeyEvent.VK_TAB, 0xff09},
        {KeyEvent.VK_CANCEL, 0xff69}, {KeyEvent.VK_CLEAR, 0xff0b}, {KeyEvent.VK_SHIFT, 0xffe1},
        {KeyEvent.VK_CONTROL, 0xffe3}, {KeyEvent.VK_ALT, 0xffe9}, {KeyEvent.VK_PAUSE, 0xff13},
        {KeyEvent.VK_CAPS_LOCK, 0xffe5}, {KeyEvent.VK_ESCAPE, 0xff1b}, {KeyEvent.VK_SPACE, 0x20},
        {KeyEvent.VK_PAGE_UP, 0xff55}, {KeyEvent.VK_PAGE_DOWN, 0xff56}, {KeyEvent.VK_END, 0xff57},
        {KeyEvent.VK_HOME, 0xff50}, {KeyEvent.VK_LEFT, 0xff51}, {KeyEvent.VK_UP, 0xff52},
        {KeyEvent.VK_RIGHT, 0xff53}, {KeyEvent.VK_DOWN, 0xff54}, {KeyEvent.VK_COMMA, 0x2c},
        {KeyEvent.VK_MINUS, 0x2d}, {KeyEvent.VK_PERIOD, 0x2e}, {KeyEvent.VK_SLASH, 0x2f},
        {KeyEvent.VK_SEMICOLON, 0x3b}, {KeyEvent.VK_EQUALS, 0x3d}, {KeyEvent.VK_OPEN_BRACKET, 0x5b},
        {KeyEvent.VK_BACK_SLASH, 0x5c}, {KeyEvent.VK_CLOSE_BRACKET, 0x5d}, {KeyEvent.VK_MULTIPLY, 0xffaa},
        {KeyEvent.VK_ADD, 0xffab}, {KeyEvent.VK_SEPARATOR, 0xffac}, {KeyEvent.VK_SUBTRACT, 0xffad},
        {KeyEvent.VK_DECIMAL, 0xffae}, {KeyEvent.VK_DIVIDE, 0xffaf}, {KeyEvent.VK_DELETE, 0xffff},
        {KeyEvent.VK_NUM_LOCK, 0xff7f}, {KeyEvent.VK_SCROLL_LOCK, 0xff14}, {KeyEvent.VK_PRINTSCREEN, 0xff61},
        {KeyEvent.VK_INSERT, 0xff63}, {KeyEvent.VK_HELP, 0xff6a}, {KeyEvent.VK_META, 0xffe7},
        {KeyEvent.VK_BACK_QUOTE, 0x60}, {KeyEvent.VK_QUOTE, 0x27}, {KeyEvent.VK_WINDOWS, 0xffeb},
        {KeyEvent.VK_CONTEXT_MENU, 0xff67}, {KeyEvent.VK_ALT_GRAPH, 0xfe03},
        {KeyEvent.VK_KP_UP, 0xff97}, {KeyEvent.VK_KP_DOWN, 0xff99}, {KeyEvent.VK_KP_LEFT, 0xff96},
        {KeyEvent.VK_KP_RIGHT, 0xff98}, {KeyEvent.VK_AMPERSAND, 0x26}, {KeyEvent.VK_ASTERISK, 0x2a},
        {KeyEvent.VK_QUOTEDBL, 0x22}, {KeyEvent.VK_LESS, 0x3c}, {KeyEvent.VK_GREATER, 0x3e},
        {KeyEvent.VK_BRACELEFT, 0x7b}, {KeyEvent.VK_BRACERIGHT, 0x7d}, {KeyEvent.VK_AT, 0x40},
        {KeyEvent.VK_COLON, 0x3a}, {KeyEvent.VK_CIRCUMFLEX, 0x5e}, {KeyEvent.VK_DOLLAR, 0x24},
        {KeyEvent.VK_EURO_SIGN, 0x20ac}, {KeyEvent.VK_EXCLAMATION_MARK, 0x21},
        {KeyEvent.VK_INVERTED_EXCLAMATION_MARK, 0xa1}, {KeyEvent.VK_LEFT_PARENTHESIS, 0x28},
        {KeyEvent.VK_NUMBER_SIGN, 0x23}, {KeyEvent.VK_PLUS, 0x2b}, {KeyEvent.VK_RIGHT_PARENTHESIS, 0x29},
        {KeyEvent.VK_UNDERSCORE, 0x5f},
    };
    for (int[] entry : map) {
      KEYSYMS.put(entry[0], entry[1]);
    }
  }

  private static String keysym(int keycode) {
    Integer keysym = KEYSYMS.get(keycode);
    if (keysym == null) {
      throw new IllegalArgumentException("Invalid key code " + keycode);
    }
    return String.format("0x%x", keysym);
  }

  public static void keyPress(int keycode) {
    command("keydown", keysym(keycode));
  }

  public static void keyRelease(int keycode) {
    command("keyup", keysym(keycode));
  }
}
