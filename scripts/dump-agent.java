import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Diagnostic: dump every transform round of the CRUSSTY-woven classes. */
public class DumpAgent {
    private static final AtomicInteger N = new AtomicInteger();

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                    Class<?> beingDefined, java.security.ProtectionDomain pd,
                                    byte[] classfileBuffer) {
                if (className.equals("net/minecraft/server/network/ServerHandshakePacketListenerImpl")
                        || className.equals("net/minecraft/network/Connection")
                        || className.equals("net/minecraft/server/MinecraftServer")) {
                    int n = N.incrementAndGet();
                    try {
                        Path out = Path.of(String.format("/tmp/agent-%02d-%s.class", n,
                                className.replace('/', '_')));
                        Files.write(out, classfileBuffer);
                        System.out.println("[dump-agent] " + n + " " + className + " -> " + out
                                + " (" + classfileBuffer.length + " bytes)");
                    } catch (Exception e) {
                        System.out.println("[dump-agent] write failed: " + e);
                    }
                }
                return null;
            }
        }, true);
        System.out.println("[dump-agent] installed");
    }
}
