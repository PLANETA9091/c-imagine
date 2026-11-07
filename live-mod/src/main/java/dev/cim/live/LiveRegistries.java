package dev.cim.live;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.DimensionTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;

/**
 * The registries a level needs and nothing more. The layer has every static registry
 * already (blocks, items, menus: what the mods registered); a {@link net.minecraft.world.level.Level}
 * also reads two data-driven ones on construction, dimension types and damage types.
 * Those come from Mojang's own bootstrap code, the same code that generates the
 * vanilla data pack, so no biome, feature or structure registry is ever loaded.
 */
final class LiveRegistries {

    private LiveRegistries() {
    }

    static RegistryAccess.Frozen build() {
        MappedRegistry<DimensionType> dimensions = bootstrap(Registries.DIMENSION_TYPE, DimensionTypes::bootstrap);
        MappedRegistry<DamageType> damage = bootstrap(Registries.DAMAGE_TYPE, DamageTypes::bootstrap);
        List<Registry<?>> all = new ArrayList<>();
        BuiltInRegistries.REGISTRY.forEach(all::add);
        all.add(dimensions);
        all.add(damage);
        return new RegistryAccess.ImmutableRegistryAccess(all).freeze();
    }

    private static <T> MappedRegistry<T> bootstrap(ResourceKey<? extends Registry<T>> key,
                                                   java.util.function.Consumer<BootstrapContext<T>> boot) {
        MappedRegistry<T> registry = new MappedRegistry<>(key, Lifecycle.stable());
        boot.accept(new BootstrapContext<>() {
            @Override
            public Holder.Reference<T> register(ResourceKey<T> id, T value, Lifecycle lifecycle) {
                return registry.register(id, value, net.minecraft.core.RegistrationInfo.BUILT_IN);
            }

            @Override
            public <S> HolderGetter<S> lookup(ResourceKey<? extends Registry<? extends S>> other) {
                throw new IllegalStateException("bootstrap of " + key.location() + " looked up " + other.location());
            }
        });
        registry.freeze();
        return registry;
    }
}
