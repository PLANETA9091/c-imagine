package dev.cim.bootstrap;

/**
 * The mixin-gate hook the shadow loader consults before defining every
 * LAYER (game) class (harness contract §8 point 3).
 *
 * <p>An interface in {@code dev.cim.bootstrap} on purpose: under the
 * single-definition contract {@code dev.cim.bootstrap.**} is defined once on
 * the host loader, so {@link ShadowClassLoader} can hold and call the hook
 * without ever referencing a {@code dev.cim.layer} class statically (those
 * stay shadow-defined — they bind the shadow space's sponge-mixin types —
 * and a static reference from the host-defined loader would resolve them
 * through the host and fail).
 */
public interface ClassBytecodeHook {

    /**
     * Consult the mixin transformer over the class bytes about to be
     * defined. Must return the (possibly unchanged) byte array; a thrown
     * error fails the class load, exactly like the real pipeline.
     */
    byte[] transform(String name, byte[] bytes);
}
