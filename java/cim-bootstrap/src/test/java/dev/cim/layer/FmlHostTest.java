package dev.cim.layer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure ownership fixtures; no NeoForge classes or server runtime required. */
public final class FmlHostTest {
    private FmlHostTest() {
    }

    public static void main(String[] args) {
        String mod = "Lcim/shadow/neoforge/net/neoforged/fml/common/Mod;";
        List<FmlHost.ScanAnn> annotations = List.of(
                new FmlHost.ScanAnn(mod, "fixture/Base", null, Map.of("value", "base")),
                new FmlHost.ScanAnn(mod, "fixture/Extra", null, Map.of("value", "extra")),
                new FmlHost.ScanAnn(mod, "fixture/ExtraClient", null,
                        Map.of("value", "extra", "dist", "CLIENT")),
                new FmlHost.ScanAnn(mod, "fixture/ExtraServer", null,
                        Map.of("value", "extra", "dist", "DEDICATED_SERVER")));
        FmlHost.ModSpec file = new FmlHost.ModSpec("base", "/fixture.jar", "1",
                List.of("fixture.Base", "fixture.Extra", "fixture.ExtraClient", "fixture.ExtraServer"),
                Set.of("fixture"), List.of(), annotations);
        FmlHost.ModSpec base = FmlHost.forModId(file, "base");
        FmlHost.ModSpec extra = FmlHost.forModId(file, "extra");
        expect(base.entrypoints().equals(List.of("fixture.Base")), "primary owns only its entrypoint");
        expect(extra.entrypoints().equals(List.of("fixture.Extra", "fixture.ExtraClient", "fixture.ExtraServer")),
                "secondary owns all and only its entrypoints");
        expect(FmlHost.distFilteredEntrypoints(extra).equals(List.of("fixture.Extra", "fixture.ExtraServer")),
                "dedicated server excludes the client entrypoint");
        expect(extra.jar().equals(file.jar()) && extra.modulePackages().equals(file.modulePackages()),
                "containers retain the shared jar and packages");
        expect(extra.annotations() == annotations, "scan data remains shared and complete");
        expect(FmlHost.forModId(file, "library").entrypoints().isEmpty(),
                "a mod without an entrypoint never borrows another mod's class");
        // The scan's real shape for @Mod(dist = {...}): a list of enum holders (AE2's AppEngServer).
        String holder = "EnumHolder[desc=Lcim/shadow/neoforge/net/neoforged/api/distmarker/Dist;, value=%s]";
        expect(FmlHost.servesDedicatedServer("[" + holder.formatted("DEDICATED_SERVER") + "]"),
                "an array naming DEDICATED_SERVER runs on the server");
        expect(!FmlHost.servesDedicatedServer("[" + holder.formatted("CLIENT") + "]"),
                "an array naming only CLIENT does not");
        expect(FmlHost.servesDedicatedServer("[" + holder.formatted("CLIENT") + ", " + holder.formatted("DEDICATED_SERVER") + "]"),
                "both sides listed runs on the server");
        expect(FmlHost.servesDedicatedServer("DEDICATED_SERVER") && !FmlHost.servesDedicatedServer("CLIENT"),
                "the single-name form keeps its meaning");
        System.out.println("FmlHost ownership fixtures: ALL OK");
    }

    private static void expect(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
