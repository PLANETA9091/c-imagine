package dev.cim.scenemod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;

/** Raw settled-world light, independent of whichever page is live during the later dump. */
final class CaptureLightData {
    private final Map<BlockPos, int[]> samples = new HashMap<>();

    static String snapshot(BlockAndTintGetter level, String manifest) {
        Set<BlockPos> positions = new HashSet<>();
        for (String line : manifest.lines().toList()) {
            String[] row = line.split("\t");
            if (row.length < 5 || !row[0].equals("cell") || row[1].equals("x")) continue;
            BlockPos center = new BlockPos(Integer.parseInt(row[1]), Integer.parseInt(row[2]),
                    Integer.parseInt(row[3]));
            // Include face, edge and corner samples used by native AO, plus a one-block margin.
            for (int x = -2; x <= 2; x++) {
                for (int y = -2; y <= 2; y++) {
                    for (int z = -2; z <= 2; z++) positions.add(center.offset(x, y, z));
                }
            }
        }
        StringBuilder out = new StringBuilder("x\ty\tz\tblock\tsky\n");
        positions.stream().sorted(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)).forEach(pos ->
                out.append(pos.getX()).append('\t').append(pos.getY()).append('\t').append(pos.getZ())
                        .append('\t').append(level.getBrightness(LightLayer.BLOCK, pos))
                        .append('\t').append(level.getBrightness(LightLayer.SKY, pos)).append('\n'));
        return out.toString();
    }

    static CaptureLightData parse(String text) {
        CaptureLightData data = new CaptureLightData();
        if (!text.startsWith("x\ty\tz\tblock\tsky\n")) {
            throw new IllegalArgumentException("invalid light snapshot header");
        }
        for (String line : text.lines().skip(1).toList()) {
            String[] row = line.split("\t", -1);
            if (row.length != 5) throw new IllegalArgumentException("invalid light snapshot row");
            BlockPos pos = new BlockPos(Integer.parseInt(row[0]), Integer.parseInt(row[1]),
                    Integer.parseInt(row[2]));
            int block = Integer.parseInt(row[3]), sky = Integer.parseInt(row[4]);
            if (block < 0 || block > 15 || sky < 0 || sky > 15
                    || data.samples.putIfAbsent(pos, new int[]{block, sky}) != null) {
                throw new IllegalArgumentException("invalid or duplicate light sample at " + pos);
            }
        }
        return data;
    }

    int brightness(LightLayer layer, BlockPos pos) {
        int[] sample = samples.get(pos);
        if (sample == null) throw new IllegalStateException("light snapshot missing sample at " + pos);
        return sample[layer == LightLayer.BLOCK ? 0 : 1];
    }

    int rawBrightness(BlockPos pos, int darkening) {
        return Math.max(brightness(LightLayer.BLOCK, pos), brightness(LightLayer.SKY, pos) - darkening);
    }
}
