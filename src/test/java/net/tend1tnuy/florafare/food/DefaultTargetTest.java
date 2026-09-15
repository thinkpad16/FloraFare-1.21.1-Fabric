package net.tend1tnuy.florafare.food;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@code food_buffs} file holding one entry with no {@code id} takes its target from
 * the file name.
 *
 * <p>That fallback used to be {@code fileId.toString().replace("/", ":")}, which is only
 * correct for a file sitting directly in {@code food_buffs/}. Put the same file one
 * directory down — which is how anyone with more than a handful of them organises the
 * folder — and it produced a two-colon string that is not a valid identifier, so the buff
 * silently never matched anything.
 */
class DefaultTargetTest {

    @Test
    @DisplayName("a file at the root of food_buffs names its item directly")
    void rootFile() {
        assertEquals("minecraft:bread",
                FoodReloadListener.defaultTargetFor(Identifier.of("minecraft", "bread")));
    }

    @Test
    @DisplayName("a file in a subfolder names the item, not the folder path")
    void nestedFile() {
        assertEquals("mypack:beef",
                FoodReloadListener.defaultTargetFor(Identifier.of("mypack", "meats/beef")));
    }

    @Test
    @DisplayName("several levels of nesting still resolve to the file's own name")
    void deeplyNestedFile() {
        assertEquals("mypack:cooked_beef",
                FoodReloadListener.defaultTargetFor(
                        Identifier.of("mypack", "food/meats/cooked/cooked_beef")));
    }

    @Test
    @DisplayName("the result is always a parseable identifier")
    void isAlwaysParseable() {
        assertNotNull(Identifier.tryParse(
                FoodReloadListener.defaultTargetFor(Identifier.of("mypack", "meats/beef"))),
                "the old form produced 'mypack:meats:beef', which Identifier rejects");
    }
}
