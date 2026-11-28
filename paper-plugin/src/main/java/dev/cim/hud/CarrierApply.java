package dev.cim.hud;

import com.google.gson.JsonObject;

/** The server-side step of {@link CarrierComponents}: puts the mapped 26.3 components on a Paper carrier. */
final class CarrierApply {

    private CarrierApply() {
    }

    /**
     * Applies the component map to a carrier's stack and returns the result: the stack with the 26.3
     * components on it, or the stack itself when there are none or the server refuses them (logged).
     * Reflective like the other NMS helpers in the plugin: the components are decoded by the server's own
     * codecs with the server's registries, so tags and holders resolve.
     */
    public static org.bukkit.inventory.ItemStack apply(org.bukkit.inventory.ItemStack stack, String layerJson) {
        JsonObject components = CarrierComponents.map(layerJson);
        if (stack == null || components.size() == 0) {
            return stack;
        }
        try {
            Class<?> craft = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            Class<?> nmsStack = Class.forName("net.minecraft.world.item.ItemStack");
            Class<?> patchCls = Class.forName("net.minecraft.core.component.DataComponentPatch");
            Object ops = registryOps();
            Object codec = patchCls.getField("CODEC").get(null);
            Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps");
            java.lang.reflect.Method parse = Class.forName("com.mojang.serialization.Codec").getMethod("parse", dynamicOps, Object.class);
            Object result = parse.invoke(codec, ops, components);
            Object patch = result.getClass().getMethod("getOrThrow").invoke(result);
            Object nms = craft.getMethod("asNMSCopy", org.bukkit.inventory.ItemStack.class).invoke(null, stack);
            nmsStack.getMethod("applyComponents", patchCls).invoke(nms, patch);
            return copyBack(craft, nmsStack, nms);
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("c-imagine").warning(
                    "[cim] carrier components refused: " + (t.getCause() != null ? t.getCause() : t));
            return stack;
        }
    }

    /**
     * The nms -> bukkit copy-back. The classic {@code CraftItemStack.asBukkitCopy(ItemStack)}
     * measured MISSING on paper 26.3-263 (NoSuchMethod, the survival #74 receipt — every carrier
     * apply refused there and the recipes floor collapsed 300 -> 43). Pin the converter
     * empirically instead of guessing a renamed symbol: the public static methods taking exactly
     * one nms ItemStack, returning an assignably-bukkit ItemStack, and named "copy" (the copy
     * semantics — a mirror would alias the mutated nms stack, never acceptable). One match pins
     * with an info receipt; zero or several receipt the full candidate list (the next flight
     * names the truth, never a silent guess).
     */
    private static org.bukkit.inventory.ItemStack copyBack(Class<?> craft, Class<?> nmsStack,
                                                           Object nms) throws Exception {
        try {
            return (org.bukkit.inventory.ItemStack) craft
                    .getMethod("asBukkitCopy", nmsStack).invoke(null, nms);
        } catch (NoSuchMethodException renamed) {
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.List<java.lang.reflect.Method> copies = new java.util.ArrayList<>();
            for (java.lang.reflect.Method m : craft.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == nmsStack
                        && org.bukkit.inventory.ItemStack.class.isAssignableFrom(m.getReturnType())) {
                    names.add(m.getName());
                    if (m.getName().toLowerCase(java.util.Locale.ROOT).contains("copy")) {
                        copies.add(m);
                    }
                }
            }
            copies.sort(java.util.Comparator.comparing(java.lang.reflect.Method::getName));
            if (copies.isEmpty() || copies.size() > 1) {
                throw new NoSuchMethodException("no unique copy-named nms->bukkit converter on "
                        + craft.getName() + "; static one-arg candidates=" + names
                        + " copy-named=" + copies.stream().map(java.lang.reflect.Method::getName).toList());
            }
            java.lang.reflect.Method pinned = copies.get(0);
            pinned.setAccessible(true);
            java.util.logging.Logger.getLogger("c-imagine").info(
                    "[cim] carrier copy-back pinned: CraftItemStack." + pinned.getName()
                    + " (the classic asBukkitCopy is absent; candidates=" + names + ")");
            return (org.bukkit.inventory.ItemStack) pinned.invoke(null, nms);
        }
    }

    /** Registry-aware JSON ops of the running server (holders and tags resolve), as EnchantRegistry builds them. */
    private static Object registryOps() throws Exception {
        org.bukkit.Server bukkit = org.bukkit.Bukkit.getServer();
        Object server = bukkit.getClass().getMethod("getServer").invoke(bukkit);
        Object access = server.getClass().getMethod("registryAccess").invoke(server);
        Class<?> jsonOps = Class.forName("com.mojang.serialization.JsonOps");
        Object json = jsonOps.getField("INSTANCE").get(null);
        Class<?> registryOpsCls = Class.forName("net.minecraft.resources.RegistryOps");
        Class<?> provider = Class.forName("net.minecraft.core.HolderLookup$Provider");
        return registryOpsCls.getMethod("create", Class.forName("com.mojang.serialization.DynamicOps"), provider)
                .invoke(null, json, access);
    }
}
