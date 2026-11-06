package dev.f2v.bridge;

import java.util.logging.Level;
import java.util.logging.Logger;

/** F2V's log facade over java.util.logging; the original sits on slf4j. */
public final class F2VLog {
    private static final Logger LOG = Logger.getLogger("c-imagine.f2v");

    private F2VLog() {
    }

    public static void info(String msg) { LOG.fine("[f2v] " + msg); }
    public static void warn(String msg) { LOG.warning("[f2v] " + msg); }
    public static void warn(String msg, Throwable t) { LOG.log(Level.WARNING, "[f2v] " + msg, t); }
    public static void debug(String msg) { LOG.fine("[f2v] " + msg); }
}
