package net.tend1tnuy.florafare.component;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attribute modifier ids have to be unique per attribute <em>line</em> of a buff, not
 * just per buff.
 *
 * <p>They were {@code attr_<scope>_<path>}: no namespace and no line. Two mods'
 * attributes sharing a path, or one attribute listed twice with different operations,
 * got the same id — the second line silently replaced the first on the player, and only
 * one of the two could ever be removed again.
 */
class ModifierIdTest {

    private static final String SCOPE = "minecraft_golden_carrot";

    @Test
    @DisplayName("two mods' attributes with the same path get different ids")
    void namespaceDistinguishes() {
        assertNotEquals(
                PlayerFoodComponent.attributeModifierPath(SCOPE, Identifier.of("spell_power", "haste"), 0),
                PlayerFoodComponent.attributeModifierPath(SCOPE, Identifier.of("ranged_weapon", "haste"), 0));
    }

    @Test
    @DisplayName("one attribute listed twice gets an id per line")
    void indexDistinguishes() {
        Identifier speed = Identifier.of("minecraft", "generic.movement_speed");
        assertNotEquals(
                PlayerFoodComponent.attributeModifierPath(SCOPE, speed, 0),
                PlayerFoodComponent.attributeModifierPath(SCOPE, speed, 1));
    }

    @Test
    @DisplayName("the same line of the same buff always gets the same id, so a refresh replaces it")
    void stable() {
        Identifier speed = Identifier.of("minecraft", "generic.movement_speed");
        assertEquals(
                PlayerFoodComponent.attributeModifierPath(SCOPE, speed, 2),
                PlayerFoodComponent.attributeModifierPath(SCOPE, speed, 2));
    }

    @Test
    @DisplayName("different buffs never share an id for the same line")
    void scopeDistinguishes() {
        Identifier speed = Identifier.of("minecraft", "generic.movement_speed");
        assertNotEquals(
                PlayerFoodComponent.attributeModifierPath("minecraft_apple", speed, 0),
                PlayerFoodComponent.attributeModifierPath("minecraft_bread", speed, 0));
    }

    @Test
    @DisplayName("the result is a valid identifier path, built from a scoped buff")
    void validPath() {
        ActiveFoodBuff buff = new ActiveFoodBuff("#c:foods/fruit", "minecraft:apple", 600, 600);
        String scope = PlayerFoodComponent.modifierScope(buff, false);
        String path = PlayerFoodComponent.attributeModifierPath(scope,
                Identifier.of("some-mod.extra", "generic/odd.path_name"), 3);
        assertDoesNotThrow(() -> Identifier.of("florafare", path));
        assertTrue(path.endsWith("_3"));
        assertTrue(path.contains("some-mod.extra"), "the namespace stays readable");
    }

    @Test
    @DisplayName("sanitizing replaces what an identifier path cannot hold")
    void sanitizes() {
        assertEquals("a_b_c", PlayerFoodComponent.sanitizePath("A:B C"));
    }
}
