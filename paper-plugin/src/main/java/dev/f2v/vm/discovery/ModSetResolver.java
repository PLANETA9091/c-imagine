package dev.f2v.vm.discovery;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the physical mod set before bytecode scanners are allowed to share a classpath.
 *
 * <p>The resolver never classloads a mod. It consumes {@link ModScanner#scanMetadata(Path)}
 * profiles, selects a unique provider when dependency ranges prove one, rejects genuinely
 * ambiguous duplicate providers, and validates dependency ranges against the current loader
 * platform. This prevents an incompatible or duplicate jar from lending classes to an otherwise
 * valid mod and making discovery appear more complete than the install can actually be.</p>
 */
public final class ModSetResolver {

    public enum IssueKind {
        PROVIDER_SELECTED,
        SIDECAR_DUPLICATE_SKIPPED,
        DUPLICATE_PROVIDER,
        MISSING_DEPENDENCY,
        WRONG_MC_VERSION,
        LOADER_TOO_OLD,
        WRONG_DEPENDENCY_VERSION,
        AMBIGUOUS_DEPENDENCY_VERSION,
        INCOMPATIBLE_DEPENDENCY,
        UNRESOLVED_VERSION,
        INVALID_VERSION_RANGE
    }

    public record Candidate(ClientModSources.Source source, ModScanner.Profile metadata) {}

    public record Issue(IssueKind kind, boolean fatal, Path archive, String ownerModId,
                        String dependencyModId, String requiredRange, List<String> availableVersions,
                        List<String> providers, String side, String detail) {}

    public record Resolution(List<ClientModSources.Source> selected, List<Issue> issues) {
        public boolean clean() {
            return issues.stream().noneMatch(Issue::fatal);
        }

        public List<Issue> fatalIssues() {
            return issues.stream().filter(Issue::fatal).toList();
        }
    }

    private record Provider(Candidate candidate, String modId, String version) {}

    private record Available(String version, String provider) {}

    private ModSetResolver() {}

    public static Resolution resolve(List<Candidate> candidates, Map<String, String> platformVersions) {
        List<Candidate> input = candidates == null ? List.of() : candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.source() != null && candidate.metadata() != null)
                .sorted(Comparator.comparing(candidate -> candidate.source().path().toString()))
                .toList();
        Map<String, String> platform = platformVersions == null ? Map.of() : platformVersions;
        LinkedHashSet<Path> rejected = new LinkedHashSet<>();
        LinkedHashMap<String, Issue> issues = new LinkedHashMap<>();

        Map<String, List<Provider>> topProviders = topProviders(input);
        for (Map.Entry<String, List<Provider>> entry : topProviders.entrySet()) {
            String modId = entry.getKey();
            List<Provider> providers = entry.getValue();
            if (providers.size() <= 1) continue;

            List<Provider> serverProviders = providers.stream()
                    .filter(provider -> !provider.candidate().source().sidecar()).toList();
            List<Provider> eligible = serverProviders.isEmpty() ? providers : serverProviders;
            if (!serverProviders.isEmpty()) {
                for (Provider provider : providers) {
                    if (!provider.candidate().source().sidecar()) continue;
                    rejected.add(provider.candidate().source().path());
                    addIssue(issues, new Issue(IssueKind.SIDECAR_DUPLICATE_SKIPPED, false,
                            provider.candidate().source().path(), modId, modId, "",
                            versions(providers), providerNames(providers), "CLIENT",
                            "loaded provider takes precedence over an inert client sidecar"));
                }
            }

            if (eligible.size() <= 1) continue;
            List<ModScanner.DependencyInfo> constraints = constraintsFor(input, modId);
            List<Provider> compatible = eligible.stream()
                    .filter(provider -> satisfiesProviderConstraints(provider.version(), constraints))
                    .toList();
            if (compatible.size() == 1) {
                Provider selected = compatible.get(0);
                for (Provider provider : eligible) {
                    if (provider == selected) continue;
                    rejected.add(provider.candidate().source().path());
                }
                addIssue(issues, new Issue(IssueKind.PROVIDER_SELECTED, false,
                        selected.candidate().source().path(), modId, modId, "dependency constraints",
                        versions(eligible), providerNames(eligible), "BOTH",
                        "selected the only provider satisfying all discovered dependency ranges"));
                continue;
            }

            for (Provider provider : eligible) rejected.add(provider.candidate().source().path());
            Provider first = eligible.get(0);
            addIssue(issues, new Issue(IssueKind.DUPLICATE_PROVIDER, true,
                    first.candidate().source().path(), modId, modId, "one unambiguous provider",
                    versions(eligible), providerNames(eligible), "BOTH",
                    compatible.isEmpty() ? "no provider satisfies the discovered constraints"
                            : "multiple providers satisfy the discovered constraints"));
        }

        // Dependency rejection is iterative: removing an invalid provider can make another
        // archive's required dependency unavailable. Keep resolving until the physical set is
        // stable rather than allowing a rejected jar to satisfy downstream scans.
        boolean changed;
        do {
            changed = false;
            Map<String, List<Available>> available = availableVersions(input, rejected, platform);
            for (Candidate candidate : input) {
                Path archive = candidate.source().path();
                if (rejected.contains(archive)) continue;
                boolean rejectCandidate = false;
                for (ModScanner.Profile profile : flatten(candidate.metadata())) {
                    Set<String> owners = declaredIds(profile);
                    for (ModScanner.DependencyInfo dependency : profile.dependencies) {
                        if (dependency == null || dependency.modId == null || dependency.modId.isBlank()) continue;
                        // Malformed/foreign dependency tables must not invent an owner.
                        if (dependency.ownerModId != null && !dependency.ownerModId.isBlank()
                                && !owners.contains(dependency.ownerModId)) continue;
                        Issue issue = dependencyIssue(candidate, dependency,
                                available.getOrDefault(dependency.modId, List.of()));
                        if (issue == null) continue;
                        addIssue(issues, issue);
                        rejectCandidate |= issue.fatal();
                    }
                }
                if (rejectCandidate && rejected.add(archive)) {
                    changed = true;
                }
            }
        } while (changed);

        List<ClientModSources.Source> selected = input.stream()
                .map(Candidate::source)
                .filter(source -> !rejected.contains(source.path()))
                .toList();
        return new Resolution(List.copyOf(selected), List.copyOf(issues.values()));
    }

    private static Issue dependencyIssue(Candidate candidate, ModScanner.DependencyInfo dependency,
                                         List<Available> available) {
        String type = lower(dependency.type, "required");
        String side = upper(dependency.side, "BOTH");
        String range = dependency.versionRange == null || dependency.versionRange.isBlank()
                ? "*" : dependency.versionRange.trim();
        boolean blocking = !"CLIENT".equals(side) || candidate.source().sidecar();
        List<String> versions = available.stream().map(Available::version).distinct().sorted().toList();
        List<String> providers = available.stream().map(Available::provider).distinct().sorted().toList();
        String owner = dependency.ownerModId == null ? candidate.metadata().modId : dependency.ownerModId;

        if (available.isEmpty()) {
            if (!"required".equals(type)) return null;
            return new Issue(IssueKind.MISSING_DEPENDENCY, blocking, candidate.source().path(), owner,
                    dependency.modId, range, versions, providers, side,
                    blocking ? "required dependency is not present"
                            : "client-only dependency is absent from the dedicated-server set");
        }
        if (versions.stream().anyMatch(ModSetResolver::unresolvedVersion)) {
            return new Issue(IssueKind.UNRESOLVED_VERSION, blocking, candidate.source().path(), owner,
                    dependency.modId, range, versions, providers, side,
                    "installed provider has an unresolved metadata version");
        }

        List<Available> matching = new ArrayList<>();
        try {
            for (Available value : available) if (contains(range, value.version())) matching.add(value);
        } catch (InvalidVersionSpecificationException failure) {
            return new Issue(IssueKind.INVALID_VERSION_RANGE, blocking, candidate.source().path(), owner,
                    dependency.modId, range, versions, providers, side, failure.getMessage());
        }
        if ("incompatible".equals(type)) {
            if (matching.isEmpty()) return null;
            return new Issue(IssueKind.INCOMPATIBLE_DEPENDENCY, blocking, candidate.source().path(), owner,
                    dependency.modId, range, matching.stream().map(Available::version).distinct().toList(),
                    matching.stream().map(Available::provider).distinct().toList(), side,
                    "an explicitly incompatible provider is installed");
        }
        if (!"required".equals(type) && !"optional".equals(type)) return null;
        if (matching.isEmpty()) {
            IssueKind mismatchKind = versionMismatchKind(dependency.modId, range, versions);
            return new Issue(mismatchKind, blocking, candidate.source().path(), owner,
                    dependency.modId, range, versions, providers, side,
                    "no installed provider satisfies the declared range");
        }
        List<String> matchingVersions = matching.stream().map(Available::version).distinct().toList();
        if (matchingVersions.size() > 1) {
            return new Issue(IssueKind.AMBIGUOUS_DEPENDENCY_VERSION, blocking,
                    candidate.source().path(), owner, dependency.modId, range,
                    matchingVersions, matching.stream().map(Available::provider).distinct().toList(), side,
                    "multiple distinct installed versions satisfy the dependency");
        }
        return null;
    }

    private static IssueKind versionMismatchKind(String modId, String range,
                                                 List<String> availableVersions) {
        if ("minecraft".equals(modId)) return IssueKind.WRONG_MC_VERSION;
        if (("forge".equals(modId) || "neoforge".equals(modId))
                && loaderBelowLowerBound(range, availableVersions)) {
            return IssueKind.LOADER_TOO_OLD;
        }
        return IssueKind.WRONG_DEPENDENCY_VERSION;
    }

    private static boolean loaderBelowLowerBound(String specification, List<String> availableVersions) {
        if (availableVersions == null || availableVersions.isEmpty()) return false;
        try {
            VersionRange range = VersionRange.createFromVersionSpec(specification == null ? "" : specification.trim());
            DefaultArtifactVersion minimum = null;
            for (var restriction : range.getRestrictions()) {
                if (restriction.getLowerBound() == null) continue;
                DefaultArtifactVersion lower = new DefaultArtifactVersion(restriction.getLowerBound().toString());
                if (minimum == null || lower.compareTo(minimum) < 0) minimum = lower;
            }
            if (minimum == null) return false;
            for (String version : availableVersions) {
                if (unresolvedVersion(version)) return false;
                if (new DefaultArtifactVersion(version).compareTo(minimum) >= 0) return false;
            }
            return true;
        } catch (InvalidVersionSpecificationException ignored) {
            return false;
        }
    }

    private static Map<String, List<Provider>> topProviders(List<Candidate> candidates) {
        LinkedHashMap<String, List<Provider>> providers = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            for (String id : declaredIds(candidate.metadata())) {
                providers.computeIfAbsent(id, ignored -> new ArrayList<>())
                        .add(new Provider(candidate, id, versionOf(candidate.metadata(), id)));
            }
        }
        return providers;
    }

    private static Map<String, List<Available>> availableVersions(List<Candidate> candidates,
                                                                  Set<Path> rejected,
                                                                  Map<String, String> platform) {
        LinkedHashMap<String, List<Available>> out = new LinkedHashMap<>();
        platform.forEach((id, version) -> {
            if (id != null && version != null) {
                out.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new Available(version, "platform"));
            }
        });
        for (Candidate candidate : candidates) {
            if (rejected.contains(candidate.source().path())) continue;
            for (ModScanner.Profile profile : flatten(candidate.metadata())) {
                for (String id : declaredIds(profile)) {
                    Available value = new Available(versionOf(profile, id),
                            candidate.source().path().getFileName().toString()
                                    + (profile == candidate.metadata() ? "" : "!/" + profile.jarFile));
                    List<Available> values = out.computeIfAbsent(id, ignored -> new ArrayList<>());
                    if (!values.contains(value)) values.add(value);
                }
            }
        }
        return out;
    }

    private static List<ModScanner.DependencyInfo> constraintsFor(List<Candidate> candidates, String modId) {
        List<ModScanner.DependencyInfo> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            for (ModScanner.Profile profile : flatten(candidate.metadata())) {
                Set<String> owners = declaredIds(profile);
                for (ModScanner.DependencyInfo dependency : profile.dependencies) {
                    if (dependency == null || !modId.equals(dependency.modId)) continue;
                    if (dependency.ownerModId != null && !dependency.ownerModId.isBlank()
                            && !owners.contains(dependency.ownerModId)) continue;
                    String type = lower(dependency.type, "required");
                    if (Set.of("required", "optional", "incompatible").contains(type)) out.add(dependency);
                }
            }
        }
        return out;
    }

    private static boolean satisfiesProviderConstraints(String version,
                                                        List<ModScanner.DependencyInfo> constraints) {
        if (unresolvedVersion(version)) return false;
        for (ModScanner.DependencyInfo dependency : constraints) {
            String range = dependency.versionRange == null || dependency.versionRange.isBlank()
                    ? "*" : dependency.versionRange.trim();
            boolean matches;
            try {
                matches = contains(range, version);
            } catch (InvalidVersionSpecificationException ignored) {
                return false;
            }
            if ("incompatible".equals(lower(dependency.type, "required"))) {
                if (matches) return false;
            } else if (!matches) {
                return false;
            }
        }
        return true;
    }

    /** Maven/FML semantics: a bare version is a recommendation, not a hard restriction. */
    public static boolean contains(String specification, String version)
            throws InvalidVersionSpecificationException {
        if (specification == null || specification.isBlank() || "*".equals(specification.trim())) return true;
        VersionRange range = VersionRange.createFromVersionSpec(specification.trim());
        return range.getRestrictions().isEmpty()
                || range.containsVersion(new DefaultArtifactVersion(version));
    }

    private static Set<String> declaredIds(ModScanner.Profile profile) {
        if (profile == null) return Set.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>(profile.modIds);
        if (ids.isEmpty() && profile.modId != null && !profile.modId.isBlank() && !"?".equals(profile.modId)) {
            ids.add(profile.modId);
        }
        return ids;
    }

    private static String versionOf(ModScanner.Profile profile, String modId) {
        if (profile == null) return "?";
        String version = profile.modVersions.get(modId);
        if (version == null || version.isBlank()) version = profile.version;
        return version == null || version.isBlank() ? "?" : version;
    }

    private static List<ModScanner.Profile> flatten(ModScanner.Profile root) {
        List<ModScanner.Profile> out = new ArrayList<>();
        flatten(root, out);
        return out;
    }

    private static void flatten(ModScanner.Profile profile, Collection<ModScanner.Profile> out) {
        if (profile == null) return;
        out.add(profile);
        for (ModScanner.Profile nested : profile.nested) flatten(nested, out);
    }

    private static List<String> versions(List<Provider> providers) {
        return providers.stream().map(Provider::version).distinct().sorted().toList();
    }

    private static List<String> providerNames(List<Provider> providers) {
        return providers.stream().map(provider -> provider.candidate().source().path().getFileName().toString())
                .distinct().sorted().toList();
    }

    private static boolean unresolvedVersion(String version) {
        return version == null || version.isBlank() || "?".equals(version) || version.contains("${");
    }

    private static String lower(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toLowerCase(Locale.ROOT);
    }

    private static String upper(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toUpperCase(Locale.ROOT);
    }

    private static void addIssue(Map<String, Issue> issues, Issue issue) {
        String key = issue.kind() + "|" + issue.archive() + "|" + issue.ownerModId() + "|"
                + issue.dependencyModId() + "|" + issue.requiredRange() + "|" + issue.detail();
        issues.putIfAbsent(key, issue);
    }
}
