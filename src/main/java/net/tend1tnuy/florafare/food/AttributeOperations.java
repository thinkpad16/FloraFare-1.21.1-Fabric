package net.tend1tnuy.florafare.food;

import net.minecraft.entity.attribute.EntityAttributeModifier;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one place an attribute {@code operation} string is turned into an
 * {@link EntityAttributeModifier.Operation}.
 *
 * <p>There used to be no such place. The runtime had a private {@code mapOperation}
 * that lowercased with the default locale and sent anything it did not recognise to
 * {@code add_value}, while both datapack loaders accepted whatever string they were
 * given. So a pack written against the pre-1.20.5 names — {@code multiply_base},
 * {@code multiply_total} — loaded without a word and then quietly applied every one of
 * those modifiers as a flat addition: a "+10% speed" became "+0.1 speed", which on
 * movement speed is a doubling. Nothing in the log said so.
 *
 * <p>Now the loaders, the runtime and anything else that reads an operation share this
 * parser, and an unknown name is an <em>absence</em>, not a guess. The loaders turn that
 * into an error naming the file and entry and skip the attribute line.
 *
 * <p>Lowercased with {@link Locale#ROOT}: in a Turkish locale {@code "ADD_VALUE"}
 * lowercases to a dotless {@code "ı"}, which matches nothing.
 */
public final class AttributeOperations {

    private AttributeOperations() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The three 1.21 names, in the order the game defines them. */
    public static final List<String> CANONICAL_NAMES =
            List.of("add_value", "add_multiplied_base", "add_multiplied_total");

    /**
     * Parses an operation name.
     *
     * <p>Accepts the three 1.21 names plus the older spellings packs are still written
     * with: {@code addition}/{@code add} for {@code add_value}, {@code multiply_base} for
     * {@code add_multiplied_base}, and {@code multiply_total} for
     * {@code add_multiplied_total}. Case and surrounding whitespace are ignored.
     *
     * @return the operation, or empty for null or anything unrecognised
     */
    public static Optional<EntityAttributeModifier.Operation> parse(String name) {
        String canonical = canonicalize(name);
        if (canonical == null) return Optional.empty();
        return Optional.of(switch (canonical) {
            case "add_multiplied_base"  -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default                     -> EntityAttributeModifier.Operation.ADD_VALUE;
        });
    }

    /**
     * The 1.21 name an operation string stands for — {@code "multiply_total"} becomes
     * {@code "add_multiplied_total"} — or null when it stands for nothing.
     *
     * <p>Kept free of the game's own enum so it can be driven from a plain unit test, and
     * so the loaders can store the canonical spelling rather than whatever alias the pack
     * happened to use: the value is what {@code /florafare edit show} prints back.
     */
    public static String canonicalize(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "add_value", "addition", "add"            -> "add_value";
            case "add_multiplied_base", "multiply_base"   -> "add_multiplied_base";
            case "add_multiplied_total", "multiply_total" -> "add_multiplied_total";
            default                                       -> null;
        };
    }

    /** The 1.21 name for an operation; the inverse of {@link #parse}. */
    public static String nameOf(EntityAttributeModifier.Operation operation) {
        return switch (operation) {
            case ADD_MULTIPLIED_BASE  -> "add_multiplied_base";
            case ADD_MULTIPLIED_TOTAL -> "add_multiplied_total";
            default                   -> "add_value";
        };
    }
}
