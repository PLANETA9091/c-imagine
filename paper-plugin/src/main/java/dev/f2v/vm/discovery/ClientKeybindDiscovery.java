package dev.f2v.vm.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts bytecode-discovered client UI actions into the subset of virtual keybinds that F2V can
 * actually represent for a vanilla client. Discovery deliberately stays separate from execution:
 * these candidates only feed the server-side input/HUD bridge and never load the client class.
 */
public final class ClientKeybindDiscovery {

    public record Candidate(String key, String modId, String modName) {}
    public enum RecipeQuery { NONE, RECIPES, USES }
    public enum CatalogAction { NONE, OPEN, SEARCH, BOOKMARKS, CLEAR_SEARCH }

    /** Precomputed runtime candidates for the latest completed discovery generation. Building
     * this list walks every discovered client surface and may correlate packet handlers across
     * hundreds of profiles, so it must happen on the discovery worker rather than on player join. */
    private record Prepared(long generation, List<Candidate> candidates) {}
    private static volatile Prepared PREPARED = new Prepared(-1L, List.of());

    private ClientKeybindDiscovery() {}

    /** Called by DiscoveryManager before publishing a new generation. */
    public static void prepareCurrent(Collection<ModScanner.Profile> profiles, long generation) {
        long started = System.nanoTime();
        List<Candidate> candidates = discover(profiles);
        PREPARED = new Prepared(generation, candidates);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        dev.f2v.bridge.F2VLog.info("[vm] prepared " + candidates.size()
                + " runtime keybind candidate(s) for discovery generation " + generation
                + " in " + elapsedMs + "ms");
    }

    /** O(1) join-time lookup for the already-published discovery generation. Never fall back to
     * a profile walk here: this method is called from the Server thread and the full correlation
     * can take seconds on large modpacks. DiscoveryManager prepares the cache before publishing
     * the generation; an unusual transient mismatch simply retries on a later tick. */
    public static List<Candidate> discoverCurrent() {
        long generation = DiscoveryManager.generation();
        Prepared prepared = PREPARED;
        return generation > 0 && prepared.generation() == generation
                ? prepared.candidates() : List.of();
    }

    /** True when {@link #discoverCurrent()} is safe to publish for the current generation. */
    public static boolean currentPrepared() {
        long generation = DiscoveryManager.generation();
        return generation > 0 && PREPARED.generation() == generation;
    }

    public static void invalidateCurrent() {
        PREPARED = new Prepared(-1L, List.of());
    }

    static long preparedGenerationForTest() {
        return PREPARED.generation();
    }

    static List<Candidate> preparedCandidatesForTest(long generation) {
        Prepared prepared = PREPARED;
        return prepared.generation() == generation ? prepared.candidates() : List.of();
    }

    public static List<Candidate> discover(Collection<ModScanner.Profile> profiles) {
        if (profiles == null || profiles.isEmpty()) return List.of();
        List<ModScanner.Profile> ordered = flattenProfiles(profiles).stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(profile -> safe(profile.modId)))
                .toList();
        Map<String, Candidate> result = new LinkedHashMap<>();
        for (ModScanner.Profile profile : ordered) {
            if (profile.clientSurfaces == null) continue;
            List<ClientSurfaceScanner.ClassSurface> surfaces = new ArrayList<>(profile.clientSurfaces);
            surfaces.sort(Comparator.comparing(surface -> safe(surface == null ? null : surface.clazz)));
            for (ClientSurfaceScanner.ClassSurface surface : surfaces) {
                if (surface == null) continue;
                // Exact key -> Screen edges are the strongest signal: the scanner observed the
                // key being consumed and Minecraft.setScreen(...) being reached on that branch.
                if (surface.actions != null) {
                    for (ClientSurfaceScanner.Action action : surface.actions) {
                        if (action == null || action.confidence < 50) continue;
                        if (action.type == ClientSurfaceScanner.ActionType.OPEN_SCREEN) {
                            add(result, action.keybind, profile);
                        } else if (action.type == ClientSurfaceScanner.ActionType.SEND_PACKET
                                && replayablePacket(profiles, action)) {
                            // Gameplay keys are only published when a second bytecode analysis
                            // independently proves that F2V's runtime can replay the exact packet.
                            // This keeps the key list useful instead of exposing client actions that
                            // would do nothing on a vanilla player.
                            add(result, action.keybind, profile);
                        }
                    }
                }

                // Most mods construct their UI through an unresolved embedded helper or a
                // Kotlin/lambda indirection, so there is no concrete OPEN_SCREEN edge for the
                // scanner to follow even though the concrete KeyMapping ids are right there.
                //
                // This used to require exactly one key on a MAP/HUD/OVERLAY surface, which is
                // why the sidebar came up empty on real modpacks. Measured against the real
                // artifacts in firstbatch/:
                //   xaero minimap MinimapKeyMappings  kinds=[KEYBIND,HUD,MAP] 26 keys -> dropped
                //   xaero minimap XMinimapKeyMappings kinds=[KEYBIND,HUD,MAP]  2 keys -> dropped
                //   cobblemon    SummaryBinding       kinds=[KEYBIND]          2 keys -> dropped
                //   smallships   ModGameOptions       kinds=[KEYBIND]          1 key  -> dropped
                // Every one of those is a genuine, scanner-proven KeyMapping id declared by a
                // dedicated key-holder class; a mod is free to declare any number of them on one
                // class, so the arity test carried no information. Publish every key id the
                // scanner attached to a class it classified as a keybind/map/HUD/overlay surface
                // instead. Non-replayable keys are harmless here: the HUD bridge renders them and
                // ClientMirror.runCapturedKeybind falls back to the captured-screen path.
                boolean representable = surface.kinds.contains(ClientSurfaceScanner.Kind.KEYBIND)
                        || surface.kinds.contains(ClientSurfaceScanner.Kind.MAP)
                        || surface.kinds.contains(ClientSurfaceScanner.Kind.HUD)
                        || surface.kinds.contains(ClientSurfaceScanner.Kind.OVERLAY);
                if (representable && surface.keybinds != null) {
                    for (String key : surface.keybinds) {
                        add(result, key, profile);
                    }
                }
            }

            // Recipe-viewer overlays are often attached to the player's existing inventory
            // instead of opening a distinct Screen. In that shape there is no setScreen edge for
            // the scanner to follow, even though the concrete KeyMapping ids are present. Publish
            // only the narrow, semantic show-recipe/show-uses subset and only when this archive
            // also contains scanner-proven HUD/overlay code. The server-side recipe HUD handles
            // these without loading or executing the original client integration.
            boolean recipeOverlay = surfaces.stream().anyMatch(surface -> surface != null
                    && (surface.kinds.contains(ClientSurfaceScanner.Kind.HUD)
                    || surface.kinds.contains(ClientSurfaceScanner.Kind.OVERLAY)));
            if (recipeOverlay && profile.keybindNames != null) {
                for (String key : profile.keybindNames) {
                    if (!overlayKeyId(key)) continue;
                    if (recipeQuery(key, "") != RecipeQuery.NONE
                            || catalogAction(key, "") != CatalogAction.NONE) {
                        add(result, key, profile);
                    }
                }
            }

            // Finally, every remaining KeyMapping id the scanner proved this archive declares.
            // A key routinely fails to attach to any class surface -- relocated/obfuscated holder
            // class, Kotlin object initialiser, key array built in a loop -- which is why
            // smallships surfaces only 1 of its 4 real keys and why the sidebar stayed empty for
            // whole mods. The ids themselves come from proven KeyMapping construction, so they
            // are exactly as real as the ones above; only their UI edge is unknown. They sort
            // after the action-proven keys because surfaces are walked first.
            if (profile.keybindNames != null) {
                for (String key : profile.keybindNames) {
                    add(result, key, profile);
                }
            }
        }
        return List.copyOf(result.values());
    }

    /** Jar-in-jar libraries can own real KeyMappings and client surfaces even though the outer mod
     * is the only profile published at the top level. Treat nested profiles as first-class input
     * sources so embedded UI libraries are not silently absent from the runtime key list. */
    private static List<ModScanner.Profile> flattenProfiles(Collection<ModScanner.Profile> roots) {
        ArrayList<ModScanner.Profile> out = new ArrayList<>();
        java.util.Set<ModScanner.Profile> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        if (roots != null) {
            for (ModScanner.Profile root : roots) collectProfile(root, out, seen);
        }
        return out;
    }

    private static void collectProfile(ModScanner.Profile profile, List<ModScanner.Profile> out,
                                       java.util.Set<ModScanner.Profile> seen) {
        if (profile == null || !seen.add(profile)) return;
        out.add(profile);
        if (profile.nested == null) return;
        for (ModScanner.Profile child : profile.nested) collectProfile(child, out, seen);
    }

    /** Semantic subset that can be reproduced from the authoritative server recipe registry. */
    public static RecipeQuery recipeQuery(String key, String label) {
        String semantic = (safe(key) + " " + safe(label)).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        if (semantic.contains("showuses") || semantic.contains("viewuses")
                || semantic.contains("usesfor")) return RecipeQuery.USES;
        if (semantic.contains("showrecipe") || semantic.contains("showallrecipes")
                || semantic.contains("viewrecipe")
                || semantic.contains("recipesfor")) return RecipeQuery.RECIPES;
        return RecipeQuery.NONE;
    }

    /** Common item-catalog actions that can be reproduced from the server item registry. */
    public static CatalogAction catalogAction(String key, String label) {
        String semantic = (safe(key) + " " + safe(label)).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        if (semantic.contains("bookmarkoverlay") || semantic.contains("showbookmarks")
                || semantic.contains("openbookmarks")) return CatalogAction.BOOKMARKS;
        if (semantic.contains("clearsearch")) return CatalogAction.CLEAR_SEARCH;
        if (semantic.contains("focussearch") || semantic.contains("searchbar")
                || semantic.contains("opensearch")) return CatalogAction.SEARCH;
        if (semantic.contains("toggleoverlay") || semantic.contains("itemoverlay")
                || semantic.contains("ingredientoverlay")) return CatalogAction.OPEN;
        return CatalogAction.NONE;
    }

    private static boolean overlayKeyId(String key) {
        if (key == null || key.isBlank()) return false;
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("key.") || lower.startsWith("keybind."))
                && !lower.startsWith("key.categories.") && !lower.startsWith("key.minecraft.");
    }

    private static boolean replayablePacket(Collection<ModScanner.Profile> profiles,
                                            ClientSurfaceScanner.Action action) {
        String packetClass = action == null ? null : action.target;
        if (packetClass == null || packetClass.isBlank()
                || !BehaviorBytecodeScanner.hasServerHandler(profiles, packetClass)) return false;
        if (scannerProvenSingletonFactory(action, packetClass)) return true;
        if (scannerProvenStringConstructor(action)
                && ServerMutationScanner.packetHasConstructor(
                profiles, packetClass, action.constructorDesc)) return true;
        if (scannerProvenStaticFieldConstructor(action, action.constructorDesc)
                && ServerMutationScanner.packetHasConstructor(
                profiles, packetClass, action.constructorDesc)) return true;
        return BehaviorBytecodeScanner.packetRecipesForTargets(profiles, List.of(packetClass)).stream()
                .anyMatch(recipe -> {
                    if (!packetClass.replace('.', '/').equals(recipe.packetClass().replace('.', '/'))) {
                        return false;
                    }
                    String descriptor = action.constructorDesc == null
                            ? recipe.constructorDesc() : action.constructorDesc;
                    if (action.constructorDesc != null
                            && !action.constructorDesc.equals(recipe.constructorDesc())) return false;
                    if ("()V".equals(descriptor)) return true;
                    if ("(Z)V".equals(descriptor) && action.booleanArgument != null) return true;
                    if (scannerProvenStringConstructor(action)) return true;
                    if (ClientActionCompatibility.playerConstructor(descriptor)) return true;
                    return scannerProvenStaticFieldConstructor(action, descriptor);
                });
    }

    private static boolean scannerProvenStringConstructor(ClientSurfaceScanner.Action action) {
        return action != null && action.stringArgument != null
                && "(Ljava/lang/String;)V".equals(action.constructorDesc);
    }

    private static boolean scannerProvenStaticFieldConstructor(ClientSurfaceScanner.Action action,
                                                               String descriptor) {
        if (action == null || descriptor == null || action.staticFieldArgumentIndex != 0
                || action.staticFieldArgumentOwner == null || action.staticFieldArgumentName == null
                || action.staticFieldArgumentDesc == null) return false;
        return ("(" + action.staticFieldArgumentDesc + ")V").equals(descriptor);
    }

    private static boolean scannerProvenSingletonFactory(ClientSurfaceScanner.Action action,
                                                         String packetClass) {
        if (action == null || packetClass == null || action.producerOwner == null
                || action.producerMethod == null || action.producerDesc == null
                || "<init>".equals(action.producerMethod)) return false;
        String packet = packetClass.replace('.', '/');
        return packet.equals(action.producerOwner.replace('.', '/'))
                && ("()L" + packet + ";").equals(action.producerDesc);
    }

    private static boolean add(Map<String, Candidate> result, String key, ModScanner.Profile profile) {
        if (!publishableKeyId(key)) return false;
        return result.putIfAbsent(key,
                new Candidate(key, safe(profile.modId), safe(profile.modName))) == null;
    }

    /**
     * Rejects the three id shapes that are collected alongside real KeyMapping ids but are not
     * themselves bindable keys, all observed in firstbatch/ artifacts:
     * <ul>
     *   <li>vanilla ids ({@code key.minecraft.*}), which the client already binds itself;</li>
     *   <li>category ids -- both the vanilla {@code key.categories.*} spelling and the
     *       per-mod {@code key.<mod>.categories.*} one used by Cobblemon, which appear as a
     *       second "key" on every KeyMapping holder class;</li>
     *   <li>unresolved constant expressions such as Cobblemon's {@code key.asTranslated()},
     *       where the id is computed at runtime and the scanner only saw the call shape.</li>
     * </ul>
     */
    static boolean publishableKeyId(String key) {
        if (key == null || key.isBlank()) return false;
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("key.minecraft.")) return false;
        if (lower.startsWith("key.categories.") || lower.contains(".categories.")) return false;
        if (lower.startsWith("tooltip.") || lower.contains(".tooltip.")) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '(' || c == ')' || c == '<' || c == '>' || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
