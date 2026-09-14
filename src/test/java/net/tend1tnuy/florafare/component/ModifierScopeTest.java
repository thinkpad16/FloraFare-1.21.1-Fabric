package net.tend1tnuy.florafare.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attribute modifier ids have to be unique per <em>active buff</em>.
 *
 * <p>They used to be derived from the config target alone, while the buff list holds one
 * slot per item eaten. Any target shared by several items — a {@code #tag}, a
 * {@code namespace:}, {@code template:default} — therefore produced two active buffs
 * writing the same modifier id: the second overwrote the first, and whichever expired
 * first took the modifier off the one still running. The player was left looking at a
 * buff on the HUD that had none of its stats.
 */
class ModifierScopeTest {

    private static ActiveFoodBuff buff(String target, String consumedItemId) {
        return new ActiveFoodBuff(target, consumedItemId, 600, 600);
    }

    @Nested
    @DisplayName("shared targets")
    class SharedTargets {

        @Test
        @DisplayName("two items under one tag get different modifier scopes")
        void tagTargetIsScopedPerItem() {
            String apple = PlayerFoodComponent.modifierScope(
                    buff("#c:fruits", "minecraft:apple"), false);
            String berries = PlayerFoodComponent.modifierScope(
                    buff("#c:fruits", "minecraft:sweet_berries"), false);

            assertNotEquals(apple, berries,
                    "both buffs are active at once, so a shared id would let the first to "
                            + "expire strip the other's attributes");
        }

        @Test
        @DisplayName("template:default, which every food can resolve to, is scoped per item")
        void templateTargetIsScopedPerItem() {
            assertNotEquals(
                    PlayerFoodComponent.modifierScope(buff("template:default", "minecraft:bread"), false),
                    PlayerFoodComponent.modifierScope(buff("template:default", "minecraft:cookie"), false));
        }

        @Test
        @DisplayName("namespace targets are scoped per item")
        void namespaceTargetIsScopedPerItem() {
            assertNotEquals(
                    PlayerFoodComponent.modifierScope(buff("namespace:minecraft", "minecraft:bread"), false),
                    PlayerFoodComponent.modifierScope(buff("namespace:minecraft", "minecraft:cookie"), false));
        }
    }

    @Test
    @DisplayName("an item-id target needs no extra scoping — it is already one per item")
    void itemTargetStaysShort() {
        assertEquals("minecraft_apple",
                PlayerFoodComponent.modifierScope(buff("minecraft:apple", "minecraft:apple"), false));
    }

    @Test
    @DisplayName("a synergy is keyed by its own id, and only one instance ever runs")
    void synergyScopeIgnoresTheIconItem() {
        // The icon item is whichever requirement happened to match first, so letting it
        // into the id would make the same synergy's modifiers differ between activations.
        assertEquals(
                PlayerFoodComponent.modifierScope(buff("florafare:fresh_fruits", "minecraft:apple"), true),
                PlayerFoodComponent.modifierScope(buff("florafare:fresh_fruits", "minecraft:carrot"), true));
    }

    @Test
    @DisplayName("every scope is usable as an Identifier path")
    void scopeIsIdentifierSafe() {
        String scope = PlayerFoodComponent.modifierScope(
                buff("#c:foods/berry", "minecraft:sweet_berries"), false);

        // Identifier paths allow [a-z0-9/._-] and nothing else; "#" and ":" must be gone.
        assertTrue(scope.matches("[a-z0-9/._-]+"),
                "scope '" + scope + "' would make Identifier.of throw");
    }

    @Nested
    @DisplayName("malformed targets")
    class MalformedTargets {

        // FoodBuffManager#normalizeTarget deliberately hands back a target it could not
        // parse rather than dropping it, so a datapack's typo stays visible in the logs.
        // That target can then reach here through /florafare buff give, and the scope it
        // produces is fed straight to Identifier.of — outside any try/catch — a few lines
        // later. Anything that is not a valid path character therefore has to be gone by
        // the time this method returns, not just the "#" and ":" a well-formed one holds.

        @Test
        @DisplayName("uppercase is folded away rather than reaching Identifier.of")
        void uppercaseIsNeutralized() {
            String scope = PlayerFoodComponent.modifierScope(
                    buff("#c:Foods", "minecraft:apple"), false);
            assertTrue(scope.matches("[a-z0-9/._-]+"), "scope was '" + scope + "'");
        }

        @Test
        @DisplayName("spaces and punctuation are replaced, not passed through")
        void oddCharactersAreNeutralized() {
            String scope = PlayerFoodComponent.modifierScope(
                    buff("NOT AN ID!", "minecraft:apple"), false);
            assertTrue(scope.matches("[a-z0-9/._-]+"), "scope was '" + scope + "'");
        }

        @Test
        @DisplayName("two different malformed targets still get two different scopes")
        void neutralizingDoesNotCollapseDistinctTargets() {
            assertNotEquals(
                    PlayerFoodComponent.modifierScope(buff("#c:Foods", "minecraft:apple"), false),
                    PlayerFoodComponent.modifierScope(buff("#c:Meats", "minecraft:apple"), false));
        }
    }
}
