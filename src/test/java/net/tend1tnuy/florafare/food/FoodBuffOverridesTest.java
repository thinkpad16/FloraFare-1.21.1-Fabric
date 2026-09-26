package net.tend1tnuy.florafare.food;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The undo model behind {@code /florafare edit}.
 *
 * <p>Putting an override in is the easy half; the half worth pinning down is what a reset
 * restores, because the value being displaced is gone from the config map the moment the
 * override lands. An operator who retunes a food, thinks better of it and resets has to
 * end up with what the datapack says — not with nothing, and not with their own earlier
 * edit. Getting that wrong is invisible until someone eats the food.
 *
 * <p>Driven through {@link FoodBuffManager} rather than through a live server: nothing
 * here needs a world, and {@code save()} no-ops while no world is open.
 */
class FoodBuffOverridesTest {

    private static final String TARGET = "minecraft:bread";

    @BeforeEach
    void reset() {
        FoodBuffManager.clear();
        FoodBuffOverrides.resetForTests();
    }

    /** Stands in for the datapack having defined this target. */
    private static void datapackDefines(String target, int duration) {
        FoodBuffManager.putConfig(target, FoodBuffData.builder(target).duration(duration).build());
    }

    private static void override(String target, int duration) {
        FoodBuffOverrides.put(target,
                FoodBuffData.builder(target).duration(duration).build());
    }

    private static int durationInForce(String target) {
        FoodBuffData data = FoodBuffManager.getConfigByTarget(target);
        assertNotNull(data, target + " should be defined");
        return data.duration();
    }

    @Test
    @DisplayName("an override replaces what the datapack defined")
    void overrideTakesEffect() {
        datapackDefines(TARGET, 600);
        override(TARGET, 2400);

        assertEquals(2400, durationInForce(TARGET));
        assertNotNull(FoodBuffOverrides.get(TARGET));
    }

    @Test
    @DisplayName("an override wins even against a higher datapack priority")
    void overrideBeatsPriority() {
        // The whole reason forcePutConfig exists: putConfig would have kept the entry
        // with the larger priority and dropped the operator's edit without a word.
        FoodBuffManager.putConfig(TARGET,
                FoodBuffData.builder(TARGET).duration(600).priority(100).build());
        override(TARGET, 2400);

        assertEquals(2400, durationInForce(TARGET));
    }

    @Test
    @DisplayName("reset puts back the datapack's own value")
    void resetRestoresDatapack() {
        datapackDefines(TARGET, 600);
        override(TARGET, 2400);

        assertTrue(FoodBuffOverrides.reset(TARGET));
        assertEquals(600, durationInForce(TARGET),
                "reset must restore the displaced entry, not merely drop the override");
        assertNull(FoodBuffOverrides.get(TARGET));
    }

    @Test
    @DisplayName("reset removes the target outright when nothing was there to displace")
    void resetRemovesWhenNoBaseline() {
        override("namespace:croptopia", 900);
        assertNotNull(FoodBuffManager.getConfigByTarget("namespace:croptopia"));

        assertTrue(FoodBuffOverrides.reset("namespace:croptopia"));
        assertNull(FoodBuffManager.getConfigByTarget("namespace:croptopia"),
                "a target the datapack never defined must stop being defined again");
    }

    @Test
    @DisplayName("editing twice still resets to the datapack, not to the first edit")
    void baselineIsCapturedOnce() {
        datapackDefines(TARGET, 600);
        override(TARGET, 2400);
        override(TARGET, 3000);

        FoodBuffOverrides.reset(TARGET);
        assertEquals(600, durationInForce(TARGET),
                "the baseline must be the datapack's value, not the previous override");
    }

    @Test
    @DisplayName("reset reports nothing to do for a target that was never overridden")
    void resetUnknownTarget() {
        datapackDefines(TARGET, 600);
        assertFalse(FoodBuffOverrides.reset(TARGET));
        assertEquals(600, durationInForce(TARGET));
    }

    @Test
    @DisplayName("a datapack reload does not revert overrides")
    void reapplySurvivesReload() {
        datapackDefines(TARGET, 600);
        override(TARGET, 2400);

        // What FoodReloadListener#apply does: wipe the map, re-read the files, then
        // re-assert the overrides over the result.
        FoodBuffManager.clear();
        datapackDefines(TARGET, 900);
        FoodBuffOverrides.reapply();

        assertEquals(2400, durationInForce(TARGET));
    }

    @Test
    @DisplayName("after a reload, reset goes back to the value that reload defined")
    void reapplyRecapturesBaseline() {
        datapackDefines(TARGET, 600);
        override(TARGET, 2400);

        // The pack author changed the file and ran /reload while the override stood.
        FoodBuffManager.clear();
        datapackDefines(TARGET, 900);
        FoodBuffOverrides.reapply();

        FoodBuffOverrides.reset(TARGET);
        assertEquals(900, durationInForce(TARGET),
                "reset must restore the current datapack value, not the one from before the reload");
    }

    @Test
    @DisplayName("a target the reload stopped defining is removed on reset, not resurrected")
    void reapplyDropsStaleBaseline() {
        datapackDefines(TARGET, 600);
        override(TARGET, 2400);

        // This time the entry was deleted from the datapack entirely.
        FoodBuffManager.clear();
        FoodBuffOverrides.reapply();

        FoodBuffOverrides.reset(TARGET);
        assertNull(FoodBuffManager.getConfigByTarget(TARGET),
                "restoring an entry the current datapack no longer defines would resurrect it");
    }

    @Test
    @DisplayName("reset all restores every displaced value at once")
    void resetAllRestores() {
        datapackDefines(TARGET, 600);
        override(TARGET, 2400);
        override("namespace:croptopia", 900);

        assertEquals(2, FoodBuffOverrides.resetAll());
        assertEquals(600, durationInForce(TARGET));
        assertNull(FoodBuffManager.getConfigByTarget("namespace:croptopia"));
        assertEquals(0, FoodBuffOverrides.count());
    }

    @Test
    @DisplayName("overrides are listed in the order they were made")
    void entriesKeepInsertionOrder() {
        override("minecraft:apple", 100);
        override(TARGET, 200);
        override("namespace:croptopia", 300);

        assertEquals(java.util.List.of("minecraft:apple", TARGET, "namespace:croptopia"),
                java.util.List.copyOf(FoodBuffOverrides.entries().keySet()));
    }

    @Test
    @DisplayName("every field survives the round trip through NBT")
    void nbtRoundTrip() {
        // The file written on each edit is nothing but these, so a component lost here is
        // a setting that silently reverts on the next restart.
        FoodBuffData original = FoodBuffData.builder(TARGET)
                .duration(2400).nutrition(7).saturation(0.8f).healthBonus(4.0)
                .priority(3).alwaysEdible(true)
                .addEffect(net.minecraft.util.Identifier.of("minecraft", "regeneration"), 200, 1)
                .addAttribute(net.minecraft.util.Identifier.of("minecraft", "generic.max_health"),
                        2.0, "add_value")
                .build();

        FoodBuffData restored = FoodBuffData.fromNbt(original.toNbt());
        assertEquals(original, restored);
    }
}
