package dev.f2v.vm.discovery;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Correlates an otherwise-unresolved client key with a scanner-discovered scene/story player.
 *
 * <p>The correlation is intentionally structural. It does not inspect mod ids, class names,
 * translation text, or key names. A key qualifies only when it is the sole unresolved real key in
 * its owning profile, that profile owns an interactive client screen surface, and the surrounding
 * archive tree also contains scanner-recovered scene metadata. Other keys may coexist when their
 * actions are already known. This covers jar-in-jar UI engines without turning every unknown
 * client key into a scene trigger.</p>
 */
public final class SceneKeybindDiscovery {

    private SceneKeybindDiscovery() {}

    public static boolean isScenePlaybackKey(Collection<ModScanner.Profile> roots, String key) {
        if (roots == null || key == null || key.isBlank()) return false;
        for (ModScanner.Profile root : roots) {
            if (root == null || !treeHasScenes(root)) continue;
            if (treeHasMatchingKeyOwner(root, key)) return true;
        }
        return false;
    }

    private static boolean treeHasMatchingKeyOwner(ModScanner.Profile profile, String key) {
        if (profile == null) return false;
        if (isMatchingKeyOwner(profile, key)) return true;
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                if (treeHasMatchingKeyOwner(child, key)) return true;
            }
        }
        return false;
    }

    private static boolean isMatchingKeyOwner(ModScanner.Profile profile, String key) {
        if (profile.keybindNames == null || profile.clientSurfaces == null) return false;

        Set<String> unresolvedKeys = new LinkedHashSet<>();
        for (String candidate : profile.keybindNames) {
            if (!ClientKeybindDiscovery.publishableKeyId(candidate)) continue;
            if (ClientSurfaceScanner.keyActions(List.of(profile), candidate).isEmpty()) {
                unresolvedKeys.add(candidate);
            }
        }
        // An exact scanner action always wins. This fallback exists only for client frameworks
        // whose key identity and UI are proven independently but whose helper edge is opaque.
        if (unresolvedKeys.size() != 1 || !unresolvedKeys.contains(key)) return false;

        for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
            if (surface == null || !surface.scanComplete) continue;
            if (surface.kinds.contains(ClientSurfaceScanner.Kind.SCREEN)
                    && surface.kinds.contains(ClientSurfaceScanner.Kind.KEYBIND)
                    && surface.kinds.contains(ClientSurfaceScanner.Kind.INPUT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean treeHasScenes(ModScanner.Profile profile) {
        if (profile == null) return false;
        if (profile.ponderScenes != null && !profile.ponderScenes.isEmpty()) return true;
        if (profile.nested != null) {
            for (ModScanner.Profile child : profile.nested) {
                if (treeHasScenes(child)) return true;
            }
        }
        return false;
    }
}
