package net.tend1tnuy.florafare.food;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.network.FoodConfigSyncPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An aftermath travels in the same NBT every config does — to disk for overrides and
 * {@code setbuff} stacks, to clients on join — so it has to survive that trip intact, and
 * an entry without one has to cost nothing on the wire.
 */
class FoodAftermathTest {

    private static final Identifier MAX_HEALTH = Identifier.of("minecraft", "generic.max_health");
    private static final Identifier NAUSEA     = Identifier.of("minecraft", "nausea");

    /** The README's Arcane Overflow aftermath: -25% max health for five minutes, and a wobble. */
    private static FoodAftermath arcaneOverflow() {
        return new FoodAftermath(6000, -2.0,
                List.of(new FoodBuffData.EffectData(NAUSEA, 200, 0),
                        new FoodBuffData.EffectData(Identifier.of("minecraft", "weakness"), -1, 1)),
                List.of(new FoodBuffData.AttributeData(MAX_HEALTH, -0.25, "add_multiplied_total")));
    }

    private static FoodBuffData drink(FoodAftermath aftermath) {
        return FoodBuffData.builder("minecraft:honey_bottle")
                .duration(2400)
                .healthBonus(4)
                .addAttribute(MAX_HEALTH, 0.1, "add_multiplied_base")
                .aftermath(aftermath)
                .build();
    }

    @Test
    @DisplayName("an aftermath round-trips through NBT, infinite effect durations included")
    void aftermathRoundTrips() {
        FoodAftermath original = arcaneOverflow();
        assertEquals(original, FoodAftermath.fromNbt(original.toNbt()));
    }

    @Test
    @DisplayName("a buff config carries its aftermath through NBT")
    void buffRoundTrips() {
        FoodBuffData original = drink(arcaneOverflow());
        FoodBuffData read = FoodBuffData.fromNbt(original.toNbt());
        assertEquals(original, read);
        assertEquals(6000, read.aftermath().duration());
    }

    @Test
    @DisplayName("a synergy carries its aftermath through NBT")
    void synergyRoundTrips() {
        FoodSynergyData original = new FoodSynergyData("mypack:arcane_overflow",
                List.of("minecraft:golden_apple", "minecraft:chorus_fruit"), 2400, 0.0,
                List.of(), List.of(new FoodBuffData.AttributeData(
                        Identifier.of("minecraft", "generic.attack_damage"), 0.5, "add_multiplied_total")),
                arcaneOverflow());
        assertEquals(original, FoodSynergyData.fromNbt(original.toNbt()));
    }

    @Test
    @DisplayName("an entry without an aftermath writes no key, and reads back as NONE")
    void absentCostsNothing() {
        FoodBuffData plain = drink(FoodAftermath.NONE);
        NbtCompound nbt = plain.toNbt();
        assertFalse(nbt.contains(FoodAftermath.NBT_KEY),
                "every config is sent on join; an empty compound per entry is pure overhead");
        assertSame(FoodAftermath.NONE, FoodBuffData.fromNbt(nbt).aftermath());
    }

    @Test
    @DisplayName("the pre-aftermath constructors and a null aftermath both mean NONE")
    void olderConstructorsMeanNone() {
        FoodBuffData legacy = new FoodBuffData("minecraft:bread", 6000, 5, 0.6f, 0.0,
                List.of(), List.of(), 0, false);
        assertSame(FoodAftermath.NONE, legacy.aftermath());

        FoodSynergyData synergy = new FoodSynergyData("x", List.of("minecraft:bread"), 0, 0.0,
                List.of(), List.of());
        assertSame(FoodAftermath.NONE, synergy.aftermath());

        assertSame(FoodAftermath.NONE, drink(null).aftermath());
    }

    @Test
    @DisplayName("an aftermath with a duration and nothing in it reads back as NONE")
    void emptyIsNone() {
        FoodAftermath hollow = new FoodAftermath(600, 0.0, List.of(), List.of());
        assertTrue(hollow.isEmpty());
        assertFalse(hollow.isTracked());
        assertSame(FoodAftermath.NONE, FoodAftermath.fromNbt(hollow.toNbt()));
    }

    @Test
    @DisplayName("effects only is not tracked; a duration with modifiers is")
    void trackedOnlyWithDurationAndContent() {
        FoodAftermath effectsOnly = new FoodAftermath(0, 0.0,
                List.of(new FoodBuffData.EffectData(NAUSEA, 200, 0)), List.of());
        assertFalse(effectsOnly.isEmpty());
        assertFalse(effectsOnly.isTracked());
        assertTrue(arcaneOverflow().isTracked());
    }

    @Test
    @DisplayName("its lists are immutable copies, like the rest of a config")
    void immutable() {
        FoodAftermath aftermath = arcaneOverflow();
        assertThrows(UnsupportedOperationException.class,
                () -> aftermath.effects().add(new FoodBuffData.EffectData(NAUSEA, 1, 0)));
        assertThrows(UnsupportedOperationException.class, () -> aftermath.attributes().clear());
    }

    @Test
    @DisplayName("a config with an aftermath survives the real sync payload codec")
    void survivesThePayloadCodec() {
        NbtCompound map = new NbtCompound();
        map.put("minecraft:honey_bottle", drink(arcaneOverflow()).toNbt());

        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        try {
            FoodConfigSyncPayload.CODEC.encode(buf, new FoodConfigSyncPayload(map, 0, 1));
            NbtCompound decoded = FoodConfigSyncPayload.CODEC.decode(buf).nbt();
            assertEquals(drink(arcaneOverflow()),
                    FoodBuffData.fromNbt(decoded.getCompound("minecraft:honey_bottle")));
        } finally {
            buf.release();
        }
    }
}
