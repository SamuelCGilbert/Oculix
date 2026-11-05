/*
 * Copyright (c) 2010-2021, sikuli.org, sikulix.com - MIT license
 */
package org.sikuli.natives;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.sikuli.basics.Debug;
import org.sikuli.support.devices.KWinWayland;
import org.sikuli.support.runner.ProcessRunner;
import org.sikuli.support.runner.IRunner;

public class LinuxUtil extends GenericOsUtil {

	private static final class LinuxWindow implements OsWindow {
		private long id;

		public LinuxWindow(long id) {
			this.id = id;
		}

		@Override
		public OsProcess getProcess() {
			try {
				List<String> lines = xdotool(new String[] { "getwindowpid", Long.toString(id) });

				if (!lines.isEmpty()) {
					Optional<ProcessHandle> handle = ProcessHandle.of(Long.parseLong(lines.get(0)));
					if (handle.isPresent()) {
						return new GenericOsProcess(handle.get());
					}
				}

				return null;
			} catch (XdotoolException e) {
				return null;
			}
		}

		@Override
		public String getTitle() {
			try {
				List<String> lines = xdotool(new String[] { "getwindowname", Long.toString(id) });
				return lines.stream().findFirst().orElse("");
			} catch (XdotoolException e) {
				return "";
			}
		}

		@Override
		public Rectangle getBounds() {
			try {
				List<String> lines = xdotool(new String[] { "getwindowgeometry", "--shell", Long.toString(id) });

				if (!lines.isEmpty()) {
					Properties props = new Properties();
					try {
						props.load(new StringReader(String.join("\n", lines)));
					} catch (IOException e) {
						e.printStackTrace();
						return null;
					}

					return new Rectangle(Integer.parseInt(props.getProperty("X")),
							Integer.parseInt(props.getProperty("Y")), Integer.parseInt(props.getProperty("WIDTH")),
							Integer.parseInt(props.getProperty("HEIGHT")));
				}

				return null;
			} catch (XdotoolException e) {
				return null;
			}
		}

		@Override
		public boolean focus() {
			try {
				xdotool(new String[] { "windowactivate", "--sync", Long.toString(id) });
				return true;
			} catch (XdotoolException e) {
				return false;
			}
		}

		@Override
		public boolean minimize() {
			throw new UnsupportedOperationException("minimize not implemented");
		}

		@Override
		public boolean maximize() {
			throw new UnsupportedOperationException("maximize not implemented");
		}

		@Override
		public boolean restore() {
			throw new UnsupportedOperationException("restore not implemented");
		}

		@Override
		public boolean equals(Object other) {
			return other != null && other instanceof LinuxWindow && this.id == (((LinuxWindow) other).id);
		}

		@Override
		public int hashCode() {
			return Long.hashCode(id);
		}
	}

	/** A window on KDE Plasma Wayland, where xdotool can't see native windows. */
	private static final class KWinWindow implements OsWindow {
		private final String id;

		KWinWindow(String id) {
			this.id = id;
		}

		private KWinWayland.WindowInfo info() {
			return KWinWayland.windows().stream().filter((w) -> w.id.equals(id)).findFirst().orElse(null);
		}

		@Override
		public OsProcess getProcess() {
			KWinWayland.WindowInfo info = info();
			if (info == null) {
				return null;
			}
			return ProcessHandle.of(info.pid).map((h) -> (OsProcess) new GenericOsProcess(h)).orElse(null);
		}

		@Override
		public String getTitle() {
			KWinWayland.WindowInfo info = info();
			return info == null ? "" : info.title;
		}

		@Override
		public Rectangle getBounds() {
			KWinWayland.WindowInfo info = info();
			return info == null ? null : info.bounds;
		}

		@Override
		public boolean focus() {
			return KWinWayland.activate(id);
		}

		@Override
		public boolean minimize() {
			throw new UnsupportedOperationException("minimize not implemented");
		}

		@Override
		public boolean maximize() {
			throw new UnsupportedOperationException("maximize not implemented");
		}

		@Override
		public boolean restore() {
			throw new UnsupportedOperationException("restore not implemented");
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof KWinWindow && id.equals(((KWinWindow) other).id);
		}

		@Override
		public int hashCode() {
			return id.hashCode();
		}
	}

	/** Windows that are visible, as xdotool search --onlyvisible finds them. Top one first. */
	private static List<OsWindow> kwinWindows(Predicate<KWinWayland.WindowInfo> filter) {
		List<KWinWayland.WindowInfo> windows = KWinWayland.windows();
		Collections.reverse(windows);
		return windows.stream().filter((w) -> w.onCurrentDesktop && !w.minimized).filter(filter)
				.map((w) -> (OsWindow) new KWinWindow(w.id)).collect(Collectors.toList());
	}

	private static boolean xdotoolAvailable = true;
	private static ProcessRunner xdotoolRunner = new ProcessRunner();

	static {
		try {
			xdotool(new String[] { "-v" });
		} catch (Exception e) {
			xdotoolAvailable = false;
			Debug.error("xdotool not available.\n"
					+ "While you can use purely process based functionallity of the App class (e.g. open(), close(), isRunning()), you need to install xdotool to use window based stuff.\n"
					+ "Error message: %s", e.getMessage());
		}
	}

	@SuppressWarnings("serial")
	private static class XdotoolException extends RuntimeException {
		public XdotoolException(String message) {
			super(message);
		}
	};

	private static synchronized List<String> xdotool(String[] args) {
		if (xdotoolAvailable) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayOutputStream err = new ByteArrayOutputStream();

			xdotoolRunner.redirect(new PrintStream(out), new PrintStream(err));

			int exitCode = xdotoolRunner.runScript("xdotool", args, new IRunner.Options());

			if (exitCode != 0) {
				String message = String.format("xdotool failed with code %s: %s", exitCode, err);
				throw new XdotoolException(message);
			}

			return Arrays.asList(out.toString().trim().split("\n"));
		}
		return new ArrayList<>(0);
	}

	@Override
	public List<OsWindow> findWindows(String title) {
		if (KWinWayland.isActive()) {
			// xdotool treats the title as a case-insensitive regular expression
			Pattern pattern;
			try {
				pattern = Pattern.compile(title, Pattern.CASE_INSENSITIVE);
			} catch (PatternSyntaxException e) {
				pattern = Pattern.compile(Pattern.quote(title), Pattern.CASE_INSENSITIVE);
			}
			Pattern name = pattern;
			return kwinWindows((w) -> name.matcher(w.title).find());
		}
		try {
			List<String> lines = xdotool(new String[] { "search", "--onlyvisible", "--name", title });
			return lines.stream().map((l) -> new LinuxWindow(Long.parseLong(l))).collect(Collectors.toList());
		} catch (XdotoolException e) {
			return new ArrayList<>(0);
		}
	}

	@Override
	public List<OsWindow> getWindows(OsProcess process) {
		if (KWinWayland.isActive()) {
			return kwinWindows((w) -> w.pid == process.getPid());
		}
		try {
			List<String> lines = xdotool(
					new String[] { "search", "--onlyvisible", "--pid", Long.toString(process.getPid()) });
			return lines.stream().map((l) -> new LinuxWindow(Long.parseLong(l))).collect(Collectors.toList());
		} catch (XdotoolException e) {
			return new ArrayList<>(0);
		}
	}

	@Override
	public List<OsWindow> getWindows() {
		if (KWinWayland.isActive()) {
			return kwinWindows((w) -> !w.title.isEmpty());
		}
		return super.getWindows();
	}

	@Override
	public OsWindow getFocusedWindow() {
		if (KWinWayland.isActive()) {
			return KWinWayland.windows().stream().filter((w) -> w.active).findFirst()
					.map((w) -> (OsWindow) new KWinWindow(w.id)).orElse(null);
		}
		try {
			List<String> lines = xdotool(new String[] { "getactivewindow" });

			if (!lines.isEmpty()) {
				return new LinuxWindow(Long.parseLong(lines.get(0)));
			}
		} catch (XdotoolException e) {
			return null;
		}

		return null;
	}
}
