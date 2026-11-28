package dev.f2v.vm.discovery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Insertion-ordered immutable copies. {@code Set.copyOf}/{@code Map.copyOf} iterate in a per-JVM
 * randomized order (ImmutableCollections.SALT), which made emitted profiles differ between boots.
 */
final class OrderedCopies {
    private OrderedCopies() {}

    static <T> Set<T> set(java.util.Collection<? extends T> items) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(items));
    }

    static <K, V> Map<K, V> map(Map<? extends K, ? extends V> entries) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }
}
