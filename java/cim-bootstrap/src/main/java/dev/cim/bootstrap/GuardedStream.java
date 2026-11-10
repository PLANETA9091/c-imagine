package dev.cim.bootstrap;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Paper replaces {@code System.out} early with a stream that logs each line through
 * {@code Bukkit.getLogger()}; until the server object exists, that stream throws a
 * NullPointerException on every print. The layer loads its mods on its own thread in exactly
 * that window, and one print there killed the thread (measured: in 6 of 17 gallery slices no
 * mod loaded, "Cannot invoke Server.getLogger() because Bukkit.server is null"). This stream
 * hands each print to the stream it wraps, and to the JVM's original stream when that throws.
 */
final class GuardedStream extends PrintStream {

    private final PrintStream wrapped;
    private final PrintStream fallback;

    private GuardedStream(PrintStream wrapped, PrintStream fallback) {
        super(new OutputStream() {
            @Override
            public void write(int b) {
                try {
                    wrapped.write(b);
                } catch (RuntimeException e) {
                    fallback.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                try {
                    wrapped.write(b, off, len);
                } catch (RuntimeException e) {
                    fallback.write(b, off, len);
                }
            }

            @Override
            public void flush() {
                try {
                    wrapped.flush();
                } catch (RuntimeException e) {
                    fallback.flush();
                }
            }
        }, true);
        this.wrapped = wrapped;
        this.fallback = fallback;
    }

    /** The JVM's own streams, taken before the server can replace them. */
    private static PrintStream originalOut;
    private static PrintStream originalErr;

    static void remember() {
        if (originalOut == null) {
            originalOut = System.out;
            originalErr = System.err;
        }
    }

    /** Guards whatever System.out/err are now; idempotent. */
    static void install() {
        remember();
        if (!(System.out instanceof GuardedStream)) {
            System.setOut(new GuardedStream(System.out, originalOut));
        }
        if (!(System.err instanceof GuardedStream)) {
            System.setErr(new GuardedStream(System.err, originalErr));
        }
    }

    @Override
    public void println(String x) {
        try {
            wrapped.println(x);
        } catch (RuntimeException e) {
            fallback.println(x);
        }
    }

    @Override
    public void println(Object x) {
        println(String.valueOf(x));
    }

    @Override
    public void println() {
        println("");
    }

    @Override
    public void print(String s) {
        try {
            wrapped.print(s);
        } catch (RuntimeException e) {
            fallback.print(s);
        }
    }

    @Override
    public void print(Object o) {
        print(String.valueOf(o));
    }
}
