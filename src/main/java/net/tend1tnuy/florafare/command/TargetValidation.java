package net.tend1tnuy.florafare.command;

import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.food.DatapackLookup;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodExclusions;

/**
 * Whether a target typed into a command names something that exists.
 *
 * <p>{@code /florafare edit <target>} and {@code /florafare ignore add <rule>} used to
 * accept any well-formed identifier, and both <em>persist</em> what they are given — into
 * the world's override file and into {@code florafare.json}. A typo such as
 * {@code minecraft:braed} or {@code farmersdelite:*} was therefore stored, re-applied on
 * every restart, listed by {@code edit list}, and never matched anything. These checks
 * are the same ones the datapack loader runs on a file (see
 * {@code FoodReloadListener#validateTarget}), answered through the same
 * {@link DatapackLookup} so the rules are unit-tested without a running game.
 *
 * <p>Every answer carries a translation key and one argument, ready for
 * {@code Text.translatable}; the command decides how to show it.
 */
public final class TargetValidation {

    private TargetValidation() {}

    /** How bad a target is. */
    public enum Verdict {
        /** Names something real. */
        OK,
        /** Names something real that currently matches nothing — an empty tag. Allowed, with a warning. */
        WARN,
        /** Names nothing; the command must refuse it. */
        ERROR
    }

    /**
     * One answer.
     *
     * @param key      translation key of the message, or null for {@link Verdict#OK}
     * @param argument the value the message names
     */
    public record Check(Verdict verdict, String key, String argument) {
        static final Check OK = new Check(Verdict.OK, null, null);

        public boolean isError() { return verdict == Verdict.ERROR; }
        public boolean isWarning() { return verdict == Verdict.WARN; }

        static Check error(String key, String argument) {
            return new Check(Verdict.ERROR, "command.florafare.target." + key, argument);
        }

        static Check warn(String key, String argument) {
            return new Check(Verdict.WARN, "command.florafare.target." + key, argument);
        }
    }

    /**
     * The one item that is not food and still worth naming in an exclusion rule: the
     * drinkable potion. {@code potion:} entries resolve through it, so excluding it is how
     * the whole potion feature is switched off.
     */
    static final String POTION_ITEM = "minecraft:potion";

    /**
     * A buff target, already normalized by {@link FoodBuffManager#normalizeTarget}: an
     * item id, {@code #tag}, {@code namespace:<mod>}, {@code template:default} or
     * {@code potion:<potion id>}.
     */
    public static Check buffTarget(String target, DatapackLookup lookup) {
        if (target == null || target.isBlank()) return Check.error("invalid", String.valueOf(target));

        if (target.startsWith("#")) {
            return tag(target.substring(1), target, lookup);
        }
        if (target.startsWith("namespace:")) {
            return namespace(target.substring("namespace:".length()), target, lookup);
        }
        if (target.startsWith("template:")) {
            return target.equals("template:default")
                    ? Check.OK : Check.error("unknown_template", target);
        }
        if (target.startsWith(FoodBuffManager.POTION_PREFIX)) {
            Identifier potion = parse(target.substring(FoodBuffManager.POTION_PREFIX.length()));
            if (potion == null) return Check.error("invalid", target);
            return lookup.potionExists(potion) ? Check.OK : Check.error("unknown_potion", target);
        }
        // "stack:<uuid>" belongs to /florafare setbuff and is never typed.
        if (target.startsWith("stack:")) return Check.error("invalid", target);

        Identifier item = parse(target);
        if (item == null) return Check.error("invalid", target);
        if (!lookup.itemExists(item)) return Check.error("unknown_item", target);
        if (!lookup.itemIsFood(item)) return Check.error("not_food", target);
        return Check.OK;
    }

    /** An exclusion rule as {@link FoodExclusions#parse} read it. */
    public static Check exclusionRule(FoodExclusions.Entry rule, DatapackLookup lookup) {
        if (rule == null) return Check.error("invalid", "null");
        return switch (rule.kind()) {
            case TAG -> tag(rule.value(), rule.canonical(), lookup);
            case MOD -> namespace(rule.value(), rule.canonical(), lookup);
            case ITEM -> {
                Identifier item = parse(rule.value());
                if (item == null) yield Check.error("invalid", rule.canonical());
                if (!lookup.itemExists(item)) yield Check.error("unknown_item", rule.canonical());
                // Florafare never looks at anything else, so a rule naming it would sit in
                // the config doing nothing — the potion bottle being the one exception.
                if (!lookup.itemIsFood(item) && !POTION_ITEM.equals(item.toString())) {
                    yield Check.error("not_food", rule.canonical());
                }
                yield Check.OK;
            }
        };
    }

    private static Check tag(String raw, String shown, DatapackLookup lookup) {
        Identifier tag = parse(raw);
        if (tag == null) return Check.error("invalid", shown);
        return switch (lookup.tagState(tag)) {
            case MISSING -> Check.error("missing_tag", shown);
            // Allowed: a tag can be empty today and filled by the next datapack, and the
            // rule is then already in place. Worth a line, though — it does nothing yet.
            case EMPTY -> Check.warn("empty_tag", shown);
            case PRESENT -> Check.OK;
        };
    }

    private static Check namespace(String namespace, String shown, DatapackLookup lookup) {
        if (namespace == null || namespace.isBlank() || parse(namespace + ":x") == null) {
            return Check.error("invalid", shown);
        }
        return lookup.namespaceInUse(namespace) ? Check.OK : Check.error("unknown_mod", shown);
    }

    /**
     * {@link Identifier#tryParse}, minus the two inputs it accepts that name nothing:
     * an empty path ("modid:") and a bare word, which it would read as {@code minecraft:}.
     */
    private static Identifier parse(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf(':') < 0 || raw.endsWith(":")) return null;
        return Identifier.tryParse(raw.trim());
    }
}
