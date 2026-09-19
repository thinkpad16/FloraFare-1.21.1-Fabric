package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.tend1tnuy.florafare.Florafare;

/**
 * Reassembles a datapack map that arrived as several numbered payloads.
 *
 * <p>One instance per sync stream (one for configs, one for synergies), used on the
 * client only. Chunk 0 starts a fresh buffer and the last chunk hands the finished
 * compound back, so the client applies the whole map in one go exactly as it did when
 * it arrived in a single packet — no window where half a pack's configs are live.
 *
 * <p>Custom payloads travel on the ordered play channel, so chunks cannot overtake each
 * other. What can still happen is a sequence being cut short — a {@code /reload} on the
 * server starts a new one while the old is half-sent. Starting over at index 0 covers
 * that; a chunk arriving out of step with the buffer is dropped with a warning rather
 * than merged into the wrong map.
 */
public final class ChunkedNbtSync {

    private final String name;
    private NbtCompound buffer;
    private int expected;
    private int received;

    public ChunkedNbtSync(String name) {
        this.name = name;
    }

    /**
     * Takes one chunk.
     *
     * @return the complete compound when this was the last chunk, otherwise null
     */
    public NbtCompound accept(NbtCompound chunk, int index, int total) {
        if (index == 0) {
            buffer   = new NbtCompound();
            expected = total;
            received = 0;
        } else if (buffer == null || index != received || total != expected) {
            // A stray chunk: its sequence was superseded before it arrived, or the first
            // chunk of that sequence never did. Merging it would corrupt the map with a
            // mix of two reloads, so it is dropped and the sequence left incomplete —
            // the next reload or rejoin sends a fresh one.
            Florafare.LOGGER.warn(
                    "Discarding an out-of-order {} sync chunk ({} of {}); waiting for a fresh sync.",
                    name, index + 1, total);
            buffer = null;
            return null;
        }

        for (String key : chunk.getKeys()) {
            buffer.put(key, chunk.get(key));
        }
        received++;

        if (received < expected) return null;

        NbtCompound complete = buffer;
        buffer = null;
        return complete;
    }
}
