package dev.f2v.capture;

import java.util.Collection;
import java.util.Map;
import org.objectweb.asm.tree.ClassNode;

/** Entity renderer binding is a model concern; the GUI path has no use for it here. */
public final class IsolatedModelCapture {
    private IsolatedModelCapture() {
    }

    public static Map<String, String> entityRendererBindings(Collection<ClassNode> classes) {
        return Map.of();
    }
}
