package dev.cim.scenemod;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Disk components and the real server-produced client update have distinct loading contracts. */
final class CaptureBlockEntityData {
    private static final String DISK = "cim_capture_disk";
    private static final String CLIENT = "cim_capture_client";

    private CaptureBlockEntityData() {}

    static CompoundTag snapshot(CompoundTag disk, CompoundTag client) {
        CompoundTag envelope = new CompoundTag();
        envelope.put(DISK, disk);
        envelope.put(CLIENT, client);
        return envelope;
    }

    static void restore(CompoundTag snapshot, Consumer<CompoundTag> diskLoader,
                        Consumer<CompoundTag> clientLoader) {
        if (!snapshot.contains(DISK) && !snapshot.contains(CLIENT)) {
            diskLoader.accept(snapshot); // Older captures contain only disk NBT.
            return;
        }
        if (!snapshot.contains(DISK, Tag.TAG_COMPOUND) || !snapshot.contains(CLIENT, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("incomplete block entity capture snapshot");
        }
        diskLoader.accept(snapshot.getCompound(DISK));
        clientLoader.accept(snapshot.getCompound(CLIENT));
    }
}
