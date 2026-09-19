package net.tend1tnuy.florafare;

import net.minecraft.nbt.NbtCompound;
import net.tend1tnuy.florafare.network.ChunkedNbtSync;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The datapack sync splits a config map across several packets and the client puts it
 * back together. Both halves are pure data handling, and the failure they replace was
 * invisible until a pack got big — a map over the packet limit used to be dropped
 * outright, leaving every client with no configs — so the round trip is pinned here.
 */
class SyncChunkingTest {

    /** A map whose serialized form is comfortably larger than one chunk. */
    private static NbtCompound bigMap(int entries, int valueLength) {
        NbtCompound root = new NbtCompound();
        for (int i = 0; i < entries; i++) {
            NbtCompound entry = new NbtCompound();
            entry.putString("target", "florafare:food_" + i);
            entry.putString("padding", "x".repeat(valueLength));
            entry.putInt("duration", i);
            root.put("florafare:food_" + i, entry);
        }
        return root;
    }

    private static NbtCompound reassemble(List<NbtCompound> chunks) {
        ChunkedNbtSync sync = new ChunkedNbtSync("test");
        NbtCompound complete = null;
        for (int i = 0; i < chunks.size(); i++) {
            complete = sync.accept(chunks.get(i), i, chunks.size());
            if (i < chunks.size() - 1) {
                assertNull(complete, "chunk " + i + " of " + chunks.size() + " applied early");
            }
        }
        return complete;
    }

    @Nested
    @DisplayName("splitting")
    class Splitting {

        @Test
        @DisplayName("a map that fits travels as a single chunk")
        void smallMapIsOneChunk() {
            List<String> skipped = new ArrayList<>();
            List<NbtCompound> chunks = Florafare.chunk(bigMap(4, 8), "food_buffs", skipped);

            assertEquals(1, chunks.size());
            assertTrue(skipped.isEmpty());
        }

        @Test
        @DisplayName("an empty map still produces one chunk, so the client always gets a map")
        void emptyMapStillSends() {
            List<NbtCompound> chunks =
                    Florafare.chunk(new NbtCompound(), "food_buffs", new ArrayList<>());

            assertEquals(1, chunks.size());
            assertTrue(chunks.get(0).isEmpty());
        }

        @Test
        @DisplayName("a map past the limit is split, and no chunk exceeds it")
        void largeMapIsSplitWithinBudget() {
            NbtCompound root = bigMap(4_000, 400);
            assertTrue(Florafare.encodedSize(root) > Florafare.CHUNK_TARGET_BYTES,
                    "test fixture is not big enough to need splitting");

            List<NbtCompound> chunks = Florafare.chunk(root, "food_buffs", new ArrayList<>());

            assertTrue(chunks.size() > 1, "expected several chunks");
            for (NbtCompound chunk : chunks) {
                assertTrue(Florafare.encodedSize(chunk) <= Florafare.CHUNK_TARGET_BYTES,
                        "a chunk came out over the per-packet budget");
            }
        }

        @Test
        @DisplayName("one entry too big for any packet is skipped, the rest still travel")
        void impossibleEntryIsSkippedAlone() {
            NbtCompound root = bigMap(3, 16);
            // Built from many fields rather than one giant string: NBT caps a single
            // string at 64 KiB, so a monster entry is realistically made of volume.
            NbtCompound monster = new NbtCompound();
            for (int i = 0; i < 20_000; i++) {
                monster.putString("padding_" + i, "x".repeat(40));
            }
            assertTrue(Florafare.encodedSize(monster) > Florafare.CHUNK_TARGET_BYTES);
            root.put("florafare:monster", monster);

            List<String> skipped = new ArrayList<>();
            List<NbtCompound> chunks = Florafare.chunk(root, "food_buffs", skipped);

            assertEquals(List.of("florafare:monster"), skipped);
            NbtCompound rebuilt = reassemble(chunks);
            assertNotNull(rebuilt);
            assertEquals(3, rebuilt.getSize());
            assertFalse(rebuilt.contains("florafare:monster"));
        }

        @Test
        @DisplayName("an entry the codec refuses outright is skipped, not thrown")
        void unencodableEntryIsSkipped() {
            NbtCompound root = bigMap(3, 16);
            // An NBT string over 64 KiB: the codec throws rather than reporting a size.
            // This used to escape buildSyncPayloads and, since that runs while a player
            // is joining, would have dropped their connection.
            NbtCompound broken = new NbtCompound();
            broken.putString("padding", "x".repeat(70_000));
            root.put("florafare:broken", broken);

            List<String> skipped = new ArrayList<>();
            List<NbtCompound> chunks =
                    assertDoesNotThrow(() -> Florafare.chunk(root, "food_buffs", skipped));

            assertEquals(List.of("florafare:broken"), skipped);
            NbtCompound rebuilt = reassemble(chunks);
            assertNotNull(rebuilt);
            assertEquals(3, rebuilt.getSize());
        }
    }

    @Nested
    @DisplayName("reassembly")
    class Reassembly {

        @Test
        @DisplayName("the client ends up with exactly what the server serialized")
        void roundTripIsLossless() {
            NbtCompound root = bigMap(4_000, 400);

            NbtCompound rebuilt =
                    reassemble(Florafare.chunk(root, "food_buffs", new ArrayList<>()));

            assertNotNull(rebuilt, "the last chunk did not complete the sequence");
            assertEquals(root, rebuilt);
        }

        @Test
        @DisplayName("nothing is applied until the last chunk lands")
        void partialSequenceAppliesNothing() {
            List<NbtCompound> chunks = Florafare.chunk(bigMap(4_000, 400), "food_buffs",
                    new ArrayList<>());
            ChunkedNbtSync sync = new ChunkedNbtSync("test");

            assertNull(sync.accept(chunks.get(0), 0, chunks.size()));
        }

        @Test
        @DisplayName("a chunk from a superseded sequence is dropped, not merged")
        void strayChunkIsDropped() {
            ChunkedNbtSync sync = new ChunkedNbtSync("test");
            NbtCompound first = new NbtCompound();
            first.putString("a", "1");

            // Chunk 2 of a sequence whose start never arrived.
            assertNull(sync.accept(first, 1, 3));

            // A fresh sequence right after still works.
            NbtCompound only = new NbtCompound();
            only.putString("b", "2");
            NbtCompound complete = sync.accept(only, 0, 1);
            assertNotNull(complete);
            assertEquals(1, complete.getSize());
            assertTrue(complete.contains("b"));
        }

        @Test
        @DisplayName("a second sequence replaces the first rather than merging into it")
        void newSequenceStartsClean() {
            ChunkedNbtSync sync = new ChunkedNbtSync("test");
            NbtCompound oldMap = new NbtCompound();
            oldMap.putString("old", "1");
            NbtCompound newMap = new NbtCompound();
            newMap.putString("new", "2");

            assertNotNull(sync.accept(oldMap, 0, 1));
            NbtCompound second = sync.accept(newMap, 0, 1);

            assertNotNull(second);
            assertFalse(second.contains("old"), "entries leaked across two reloads");
        }
    }
}
