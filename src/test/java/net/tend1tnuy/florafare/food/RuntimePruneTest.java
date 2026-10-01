package net.tend1tnuy.florafare.food;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The retention rule for {@code /florafare setbuff} stack buffs: an entry no stack has
 * been seen carrying for longer than {@code runtimeBuffRetentionDays} is dropped, so
 * {@code florafare_runtime.dat} stops growing for the life of the world.
 */
class RuntimePruneTest {

    private static final long DAY = FoodBuffManager.DAY_MILLIS;

    @Test
    @DisplayName("only entries older than the limit expire, sorted; 0 keeps everything")
    void policy() {
        long now = 1_000 * DAY;
        Map<String, Long> seen = new HashMap<>();
        seen.put("b-old", now - 200 * DAY);
        seen.put("a-old", now - 181 * DAY);
        seen.put("edge", now - 180 * DAY);
        seen.put("fresh", now - DAY);
        seen.put("future", now + 5 * DAY);

        assertEquals(List.of("a-old", "b-old"), FoodBuffManager.expiredRuntimeIds(seen, now, 180 * DAY),
                "exactly at the limit is kept; a clock moved back never expires anything");
        assertTrue(FoodBuffManager.expiredRuntimeIds(seen, now, 0).isEmpty(), "0 = keep forever");
    }

    @Test
    @DisplayName("pruning removes the entries from the live map")
    void prune() {
        FoodBuffData data = FoodBuffData.builder("stack:test").duration(100).build();
        FoodBuffManager.setRuntimeStackConfig("prune-test-a", data);
        FoodBuffManager.setRuntimeStackConfig("prune-test-b", data);
        int before = FoodBuffManager.getRuntimeConfigCount();

        // Nothing is a day old yet.
        assertEquals(0, FoodBuffManager.pruneRuntimeConfigs(DAY, System.currentTimeMillis()));
        // Two days from now both are.
        int pruned = FoodBuffManager.pruneRuntimeConfigs(DAY, System.currentTimeMillis() + 2 * DAY);
        assertTrue(pruned >= 2);
        assertEquals(before - pruned, FoodBuffManager.getRuntimeConfigCount());
        assertFalse(FoodBuffManager.serializeRuntimeConfigs().contains("prune-test-a"));
    }

    @Test
    @DisplayName("the client takes a full sync as a replacement and a partial one as a merge")
    void clientSync() {
        FoodBuffData data = FoodBuffData.builder("stack:x").duration(40).build();
        net.minecraft.nbt.NbtCompound one = new net.minecraft.nbt.NbtCompound();
        one.put("sync-a", data.toNbt());
        FoodBuffManager.applyRuntimeConfigsFromNbt(one, true);
        assertEquals(1, FoodBuffManager.getRuntimeConfigCount());

        net.minecraft.nbt.NbtCompound two = new net.minecraft.nbt.NbtCompound();
        two.put("sync-b", data.toNbt());
        FoodBuffManager.applyRuntimeConfigsFromNbt(two, false);
        assertEquals(2, FoodBuffManager.getRuntimeConfigCount());

        FoodBuffManager.clearRemoteRuntimeConfigs();
        assertEquals(0, FoodBuffManager.getRuntimeConfigCount());
    }
}
