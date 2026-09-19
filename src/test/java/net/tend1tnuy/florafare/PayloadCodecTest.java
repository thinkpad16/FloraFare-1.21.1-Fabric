package net.tend1tnuy.florafare;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.tend1tnuy.florafare.network.ChunkedNbtSync;
import net.tend1tnuy.florafare.network.FoodConfigSyncPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every chunk the splitter produces must survive the wire.
 *
 * <p>This is the test for the bug that a size limit in wire bytes alone cannot see. The
 * client decodes NBT under an {@code NbtSizeTracker} that budgets allocation, not
 * encoded length, and a map built from many small compounds — a food buff with a list of
 * attributes, say — accounts for several times its encoded size. Chunks that looked
 * comfortably small therefore blew the limit and the client was disconnected mid-join
 * with "Failed to decode packet". Nothing below asserts a byte count: it round-trips the
 * chunks through the real payload codec, which is the only honest question.
 */
class PayloadCodecTest {

    /**
     * A map shaped like a real food_buffs pack: many entries, each a small compound with
     * nested lists of attributes and effects. The shape matters far more than the volume
     * — it is what makes the decoder's accounting outrun the encoded size.
     */
    private static NbtCompound configMap(int entries) {
        NbtCompound root = new NbtCompound();
        for (int i = 0; i < entries; i++) {
            NbtCompound entry = new NbtCompound();
            entry.putString("Target", "florafare:stress_group_" + i);
            entry.putInt("Duration", 1200 + i);
            entry.putInt("Nutrition", 4);
            entry.putFloat("Saturation", 0.6f);
            entry.putDouble("HealthBonus", 2.0);

            net.minecraft.nbt.NbtList attributes = new net.minecraft.nbt.NbtList();
            for (int a = 0; a < 8; a++) {
                NbtCompound attribute = new NbtCompound();
                attribute.putString("AttributeId", "minecraft:generic.movement_speed");
                attribute.putDouble("Amount", 0.01 * (a + 1));
                attribute.putString("Operation", "add_multiplied_total");
                attributes.add(attribute);
            }
            entry.put("Attributes", attributes);

            net.minecraft.nbt.NbtList effects = new net.minecraft.nbt.NbtList();
            for (int e = 0; e < 4; e++) {
                NbtCompound effect = new NbtCompound();
                effect.putString("Id", "minecraft:regeneration");
                effect.putInt("Duration", 200 + e);
                effect.putInt("Amplifier", 0);
                effects.add(effect);
            }
            entry.put("Effects", effects);

            root.put("#stress:group_" + i, entry);
        }
        return root;
    }

    private static NbtCompound roundTrip(NbtCompound nbt, int index, int total) {
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        try {
            FoodConfigSyncPayload.CODEC.encode(buf, new FoodConfigSyncPayload(nbt, index, total));
            FoodConfigSyncPayload decoded = FoodConfigSyncPayload.CODEC.decode(buf);
            assertEquals(index, decoded.index());
            assertEquals(total, decoded.total());
            return decoded.nbt();
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("a small map round-trips through the payload codec")
    void smallMapRoundTrips() {
        NbtCompound nbt = configMap(10);
        assertEquals(nbt, roundTrip(nbt, 0, 1));
    }

    @Test
    @DisplayName("every chunk of a pack-sized map decodes, and the pieces rebuild it")
    void everyChunkSurvivesTheWire() {
        NbtCompound root = configMap(4_000);
        List<String> skipped = new ArrayList<>();

        List<NbtCompound> chunks = Florafare.chunk(root, "food_buffs", skipped);

        assertTrue(skipped.isEmpty(), "nothing in this map is unsendable");
        assertTrue(chunks.size() > 1, "a map this size must be split");

        ChunkedNbtSync sync = new ChunkedNbtSync("test");
        NbtCompound rebuilt = null;
        for (int i = 0; i < chunks.size(); i++) {
            // Through the codec, exactly as the client receives it.
            NbtCompound overTheWire = roundTrip(chunks.get(i), i, chunks.size());
            rebuilt = sync.accept(overTheWire, i, chunks.size());
        }

        assertNotNull(rebuilt, "the sequence never completed");
        assertEquals(root, rebuilt, "what the client rebuilt differs from what the server sent");
    }

    @Test
    @DisplayName("the splitter's own verdict agrees with the decoder")
    void splitterAgreesWithDecoder() {
        for (NbtCompound chunk : Florafare.chunk(configMap(4_000), "food_buffs", new ArrayList<>())) {
            assertTrue(Florafare.decodesWithinLimit(chunk),
                    "the splitter emitted a chunk the decoder refuses");
        }
    }
}
