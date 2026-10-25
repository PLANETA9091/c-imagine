package dev.cim.bootstrap;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Child-first classloader that will host the relocated 1.21.1 layer
 * (M1): classes in the shadow space never leak into Paper's class space and
 * vice versa; JDK classes always resolve from the platform loader.
 *
 * M0: instantiated eagerly by {@link Bootstrap#init} so the class space
 * boundary exists and is countable from day one.
 */
public final class ShadowClassLoader extends URLClassLoader {

    private static volatile ShadowClassLoader instance;

    private static final String JDK_PREFIXES[] = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.dom.",
        "org.xml.sax.", "org.ietf.jgss.",
    };

    private final AtomicLong defined = new AtomicLong();

    private ShadowClassLoader(URL[] urls) {
        super("cim-shadow", urls, null);
    }

    /** Create (or reset) the shared shadow loader for the given jars. */
    public static void prepare(String bootstrapJar) {
        List<URL> urls = new ArrayList<>();
        try {
            if (bootstrapJar != null) {
                urls.add(Path.of(bootstrapJar).toUri().toURL());
            }
        } catch (Exception e) {
            throw new IllegalStateException("shadow loader url: " + e, e);
        }
        instance = new ShadowClassLoader(urls.toArray(new URL[0]));
    }

    public static ShadowClassLoader get() {
        ShadowClassLoader l = instance;
        if (l == null) {
            throw new IllegalStateException("ShadowClassLoader not prepared");
        }
        return l;
    }

    /** Number of classes defined inside the shadow space so far. */
    public static int loadedClassCount() {
        ShadowClassLoader l = instance;
        return l == null ? 0 : (int) l.defined.get();
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> c = super.findClass(name);
        defined.incrementAndGet();
        return c;
    }

    /** Child-first: JDK from the platform loader, everything else from here. */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isJdkClass(name)) {
                    c = getPlatformClassLoader().loadClass(name);
                } else {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = getPlatformClassLoader().loadClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private static boolean isJdkClass(String name) {
        for (String p : JDK_PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
