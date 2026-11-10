package dev.cim.scenemod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Transport/dispatch contract only; real joined-mod rendering is the gallery's proof. */
public final class CaptureBlockEntityDataTest {
    public static void main(String[] args) throws Exception {
        CompoundTag disk = new CompoundTag();
        disk.putString("components", "disk components");
        disk.putString("text", "disk value");
        CompoundTag client = new CompoundTag();
        client.putString("text", "client value");
        client.putInt("clientOnly", 7);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(CaptureBlockEntityData.snapshot(disk, client), bytes);
        CompoundTag decoded = NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()),
                NbtAccounter.unlimitedHeap());
        List<String> calls = new ArrayList<>();
        CaptureBlockEntityData.restore(decoded, tag -> {
            require(tag.equals(disk), "disk components must survive without client-field merging");
            calls.add("disk");
        }, tag -> {
            require(tag.equals(client), "the actual client payload must survive unchanged");
            require(calls.equals(List.of("disk")), "client initialization must follow disk components");
            calls.add("client");
        });
        require(calls.equals(List.of("disk", "client")), "both native loading paths required");
        calls.clear();
        CaptureBlockEntityData.restore(disk, tag -> calls.add("legacy"), tag -> {
            throw new AssertionError("legacy captures must not invent a client update");
        });
        require(calls.equals(List.of("legacy")), "legacy disk capture remains readable");
        decoded.remove("cim_capture_client");
        boolean rejected = false;
        try {
            CaptureBlockEntityData.restore(decoded, tag -> {}, tag -> {});
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "incomplete envelopes must fail instead of silently losing state");
        System.out.println("capture block entity data: ALL OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
