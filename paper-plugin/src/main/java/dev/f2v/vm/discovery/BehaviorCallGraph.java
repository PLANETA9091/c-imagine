package dev.f2v.vm.discovery;

import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Links exact helper calls across an archive and the lazily indexed dependency classpath. */
final class BehaviorCallGraph {
    private static final int MAX_DEPENDENCY_CLASSES = 256;
    private record Invocation(String owner, String method, String descriptor, int opcode) {}

    private BehaviorCallGraph() {}

    static void link(List<BehaviorBytecodeScanner.BehaviorSurface> surfaces,
                     Map<String, ClassNode> nodes,
                     Map<String, BehaviorBytecodeScanner.RegistryRef> registryFields) {
        Map<String, BehaviorBytecodeScanner.BehaviorSurface> classes = new LinkedHashMap<>();
        Map<String, Map<String, BehaviorBytecodeScanner.MethodBehavior>> methods = new HashMap<>();
        List<BehaviorBytecodeScanner.BehaviorSurface> pending = new ArrayList<>();
        for (var surface : surfaces) {
            if (classes.putIfAbsent(surface.clazz, surface) == null) {
                methods.put(surface.clazz, index(surface));
                pending.add(surface);
            }
        }
        Set<String> localClasses = new HashSet<>();
        for (ClassNode node : nodes.values()) localClasses.add(node.name);
        Set<String> skipped = new HashSet<>();
        int dependencies = 0;
        Map<BehaviorBytecodeScanner.MethodBehavior, Set<BehaviorBytecodeScanner.MethodBehavior>> callers =
                new LinkedHashMap<>();
        // Cache names, not ASM trees: repeated framework calls otherwise keep re-inflating
        // nested dependency jars after the bounded classpath cache evicts their classes.
        Map<Invocation, String> targetOwners = new HashMap<>();
        for (int i = 0; i < pending.size(); i++) {
            var surface = pending.get(i);
            for (var caller : surface.methods) {
                for (var call : caller.invocations) {
                    if (platformClass(call.owner)) continue;
                    Invocation invocation = new Invocation(call.owner, call.method, call.descriptor, call.opcode);
                    boolean known = targetOwners.containsKey(invocation);
                    String owner;
                    BytecodeValueFlow.Target resolved = null;
                    if (known) {
                        owner = targetOwners.get(invocation);
                    } else {
                        resolved = BytecodeValueFlow.exactTarget(
                                nodes, call.owner, call.method, call.descriptor, call.opcode);
                        owner = resolved == null ? null : resolved.owner().name;
                        // Record the outcome before any early exit below. Every `continue` that
                        // skips this leaves the invocation uncached, so the next identical call
                        // site re-runs exactTarget and re-inflates the dependency archive the
                        // bounded classpath cache just evicted -- the exact cost this map exists
                        // to avoid. Negative and unusable results must be cached too.
                        if (targetOwners.size() < 16384) targetOwners.put(invocation, owner);
                    }
                    if (owner == null) continue;
                    if (!classes.containsKey(owner) && !skipped.contains(owner)) {
                        if (!localClasses.contains(owner) && dependencies >= MAX_DEPENDENCY_CLASSES) {
                            caller.analysisWarnings.add("dependency behavior budget exceeded");
                            continue;
                        }
                        // Only the resolving pass holds the callee node. A cached owner that was
                        // never materialised means an earlier pass declined it under the budget,
                        // so decline it permanently instead of dereferencing a null target.
                        if (resolved == null) {
                            skipped.add(owner);
                            continue;
                        }
                        var helper = BehaviorBytecodeScanner.inspectGraphClass(
                                resolved.owner(), nodes, registryFields);
                        if (helper == null) {
                            skipped.add(owner);
                            continue;
                        }
                        if (!localClasses.contains(owner)) dependencies++;
                        classes.put(owner, helper);
                        methods.put(owner, index(helper));
                        pending.add(helper);
                    }
                    var byMethod = methods.get(owner);
                    var child = byMethod == null ? null : byMethod.get(call.method + call.descriptor);
                    if (child != null && child != caller) {
                        callers.computeIfAbsent(child, ignored -> new LinkedHashSet<>()).add(caller);
                    }
                }
            }
        }
        List<BehaviorBytecodeScanner.MethodBehavior> allMethods = new ArrayList<>();
        for (var surface : pending) allMethods.addAll(surface.methods);
        BehaviorBytecodeScanner.propagateEffects(allMethods, callers);
        surfaces.clear();
        for (var surface : pending) {
            surface.effects.clear();
            surface.translations.clear();
            for (var method : surface.methods) surface.effects.addAll(method.effects);
            BehaviorBytecodeScanner.chooseTranslations(surface);
            if (!surface.effects.isEmpty() || surface.methods.stream().anyMatch(method ->
                    method.trigger != BehaviorBytecodeScanner.Trigger.UNKNOWN
                            || !method.collectionCardinalities.isEmpty())) {
                surfaces.add(surface);
            }
        }
    }

    private static Map<String, BehaviorBytecodeScanner.MethodBehavior> index(
            BehaviorBytecodeScanner.BehaviorSurface surface) {
        Map<String, BehaviorBytecodeScanner.MethodBehavior> result = new LinkedHashMap<>();
        for (var method : surface.methods) result.put(method.method + method.descriptor, method);
        return result;
    }

    private static boolean platformClass(String owner) {
        return owner == null || owner.startsWith("java/") || owner.startsWith("javax/")
                || owner.startsWith("net/minecraft/") || owner.startsWith("net/minecraftforge/")
                || owner.startsWith("net/neoforged/") || owner.startsWith("org/objectweb/asm/");
    }
}
