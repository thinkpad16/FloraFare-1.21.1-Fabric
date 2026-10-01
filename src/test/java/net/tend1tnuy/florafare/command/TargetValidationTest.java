package net.tend1tnuy.florafare.command;

import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.food.DatapackLookup;
import net.tend1tnuy.florafare.food.FoodExclusions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The checks {@code /florafare edit} and {@code /florafare ignore add} run before they
 * persist a target — the typos that used to be written to disk and re-applied forever.
 */
class TargetValidationTest {

    /** Bread and an apple are food, stone is not; one tag of each state; mods by namespace. */
    private static final DatapackLookup GAME = new DatapackLookup() {
        final Map<String, Boolean> items = Map.of(
                "minecraft:bread", true, "minecraft:apple", true, "minecraft:stone", false,
                "minecraft:potion", false, "farmersdelight:tomato", true);
        final Set<String> namespaces = Set.of("minecraft", "farmersdelight");

        @Override public boolean itemExists(Identifier id) { return items.containsKey(id.toString()); }
        @Override public boolean itemIsFood(Identifier id) { return items.getOrDefault(id.toString(), false); }
        @Override public boolean potionExists(Identifier id) { return id.toString().equals("minecraft:strength"); }
        @Override public boolean effectExists(Identifier id) { return false; }
        @Override public boolean attributeExists(Identifier id) { return false; }
        @Override public boolean modLoaded(String namespace) { return namespace.equals("minecraft"); }
        @Override public boolean namespaceInUse(String namespace) { return namespaces.contains(namespace); }

        @Override
        public TagState tagState(Identifier tagId) {
            return switch (tagId.toString()) {
                case "c:foods" -> TagState.PRESENT;
                case "c:foods/empty" -> TagState.EMPTY;
                default -> TagState.MISSING;
            };
        }
    };

    private static TargetValidation.Verdict target(String target) {
        return TargetValidation.buffTarget(target, GAME).verdict();
    }

    private static TargetValidation.Verdict rule(String raw) {
        return TargetValidation.exclusionRule(FoodExclusions.parse(raw), GAME).verdict();
    }

    @Test
    @DisplayName("edit targets: real food, tags, namespaces, templates and potions pass; typos do not")
    void buffTargets() {
        assertEquals(TargetValidation.Verdict.OK, target("minecraft:bread"));
        assertEquals(TargetValidation.Verdict.ERROR, target("minecraft:braed"), "unknown item");
        assertEquals(TargetValidation.Verdict.ERROR, target("minecraft:stone"), "not food");
        assertEquals(TargetValidation.Verdict.OK, target("#c:foods"));
        assertEquals(TargetValidation.Verdict.WARN, target("#c:foods/empty"), "empty tag: allowed, warned");
        assertEquals(TargetValidation.Verdict.ERROR, target("#c:fods"), "missing tag");
        assertEquals(TargetValidation.Verdict.OK, target("namespace:farmersdelight"),
                "a namespace with items counts even when no mod has that id");
        assertEquals(TargetValidation.Verdict.ERROR, target("namespace:farmersdelite"));
        assertEquals(TargetValidation.Verdict.OK, target("template:default"));
        assertEquals(TargetValidation.Verdict.ERROR, target("template:fancy"));
        assertEquals(TargetValidation.Verdict.OK, target("potion:minecraft:strength"));
        assertEquals(TargetValidation.Verdict.ERROR, target("potion:minecraft:strenght"));
        assertEquals(TargetValidation.Verdict.ERROR, target("stack:1234"), "setbuff ids are never typed");
        assertEquals(TargetValidation.Verdict.ERROR, target("minecraft:"), "empty path");
    }

    @Test
    @DisplayName("ignore rules: items must be food (or the potion bottle), mods in use, tags present")
    void exclusionRules() {
        assertEquals(TargetValidation.Verdict.OK, rule("minecraft:apple"));
        assertEquals(TargetValidation.Verdict.ERROR, rule("minecraft:aple"));
        assertEquals(TargetValidation.Verdict.ERROR, rule("minecraft:stone"));
        assertEquals(TargetValidation.Verdict.OK, rule("minecraft:potion"),
                "excluding the potion bottle is how the potion feature is switched off");
        assertEquals(TargetValidation.Verdict.OK, rule("farmersdelight:*"));
        assertEquals(TargetValidation.Verdict.OK, rule("farmersdelight"));
        assertEquals(TargetValidation.Verdict.ERROR, rule("mod:*"), "the finding: mod:typo was accepted");
        assertEquals(TargetValidation.Verdict.OK, rule("#c:foods"));
        assertEquals(TargetValidation.Verdict.WARN, rule("#c:foods/empty"));
        assertEquals(TargetValidation.Verdict.ERROR, rule("#c:nothing"));
    }

    @Test
    @DisplayName("every answer names a translation key under command.florafare.target")
    void messages() {
        TargetValidation.Check check = TargetValidation.buffTarget("minecraft:braed", GAME);
        assertEquals("command.florafare.target.unknown_item", check.key());
        assertEquals("minecraft:braed", check.argument());
        assertNull(TargetValidation.buffTarget("minecraft:bread", GAME).key());
    }
}
