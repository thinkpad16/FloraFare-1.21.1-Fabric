package net.tend1tnuy.florafare.food;

import net.minecraft.entity.attribute.EntityAttributeModifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one operation parser the loaders and the runtime share. What it must never do
 * again is guess: an unrecognised name used to become {@code add_value}, so a pack
 * written with the pre-1.20.5 {@code multiply_total} turned every "+10%" into "+0.1".
 */
class AttributeOperationsTest {

    @Nested
    @DisplayName("1.21 names")
    class CanonicalNames {

        @Test
        @DisplayName("each of the three maps to its own operation")
        void parsesCanonical() {
            assertEquals(Optional.of(EntityAttributeModifier.Operation.ADD_VALUE),
                    AttributeOperations.parse("add_value"));
            assertEquals(Optional.of(EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                    AttributeOperations.parse("add_multiplied_base"));
            assertEquals(Optional.of(EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
                    AttributeOperations.parse("add_multiplied_total"));
        }

        @Test
        @DisplayName("nameOf is the inverse of parse, so a record round-trips")
        void roundTrips() {
            for (EntityAttributeModifier.Operation operation
                    : EntityAttributeModifier.Operation.values()) {
                assertEquals(Optional.of(operation),
                        AttributeOperations.parse(AttributeOperations.nameOf(operation)));
            }
        }

        @Test
        @DisplayName("the canonical list is exactly the three names nameOf produces")
        void canonicalListMatches() {
            for (EntityAttributeModifier.Operation operation
                    : EntityAttributeModifier.Operation.values()) {
                assertTrue(AttributeOperations.CANONICAL_NAMES.contains(
                        AttributeOperations.nameOf(operation)));
            }
            assertEquals(3, AttributeOperations.CANONICAL_NAMES.size());
        }
    }

    @Nested
    @DisplayName("legacy aliases")
    class Aliases {

        @Test
        @DisplayName("addition and add mean add_value")
        void additionAliases() {
            assertEquals("add_value", AttributeOperations.canonicalize("addition"));
            assertEquals("add_value", AttributeOperations.canonicalize("add"));
        }

        @Test
        @DisplayName("multiply_base and multiply_total keep their multiplier meaning")
        void multiplyAliases() {
            assertEquals("add_multiplied_base", AttributeOperations.canonicalize("multiply_base"));
            assertEquals("add_multiplied_total", AttributeOperations.canonicalize("multiply_total"));
        }

        @Test
        @DisplayName("case and surrounding whitespace are forgiven")
        void caseInsensitive() {
            assertEquals("add_multiplied_total",
                    AttributeOperations.canonicalize("  Add_Multiplied_TOTAL "));
        }

        @Test
        @DisplayName("lowercasing does not depend on the default locale")
        void localeIndependent() {
            Locale previous = Locale.getDefault();
            try {
                // In Turkish, "I" lowercases to a dotless "ı", which matched nothing.
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                assertEquals("add_multiplied_total",
                        AttributeOperations.canonicalize("MULTIPLY_TOTAL"));
            } finally {
                Locale.setDefault(previous);
            }
        }
    }

    @Nested
    @DisplayName("unknown names")
    class Unknown {

        @Test
        @DisplayName("are an absence, never a guess")
        void unknownIsEmpty() {
            assertTrue(AttributeOperations.parse("multiply").isEmpty());
            assertTrue(AttributeOperations.parse("add_multiplied").isEmpty());
            assertTrue(AttributeOperations.parse("").isEmpty());
            assertNull(AttributeOperations.canonicalize("set"));
        }

        @Test
        @DisplayName("null is unknown, not a crash")
        void nullIsEmpty() {
            assertTrue(AttributeOperations.parse(null).isEmpty());
            assertNull(AttributeOperations.canonicalize(null));
        }
    }
}
