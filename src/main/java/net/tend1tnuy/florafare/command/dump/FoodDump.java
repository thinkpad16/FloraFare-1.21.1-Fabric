package net.tend1tnuy.florafare.command.dump;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Everything one {@code /florafare dumpfoods} run found, as plain values.
 *
 * <p>Built on the server thread by {@link FoodDumpCollector} — it is the only place the
 * registries, the recipe manager and Florafare's config maps may be read — and then
 * handed whole to the IO pool, where {@link FoodDumpWriter} turns it into files. Nothing
 * in here refers back to a game object, so the writer can run while the server ticks on.
 *
 * <p>The record components are the JSON format: Gson writes them by name, in order, and
 * leaves out the ones that are null. Field names are therefore part of the dump's
 * documented format (see the README) and should not be renamed casually.
 *
 * @param trees per food id, the text-only crafting tree; not part of the JSON
 */
public record FoodDump(Meta meta, List<Food> foods, Map<String, List<String>> trees) {

    /**
     * @param formatVersion bumped whenever a field changes meaning or disappears
     * @param feastDetection how feast blocks were looked for, and whether any were found
     * @param notes          what the dump knowingly leaves out
     */
    public record Meta(int formatVersion, String minecraftVersion, String modVersion,
                       String timestamp, @Nullable String modFilter, int maxDepth,
                       Counts counts, String feastDetection, List<String> notes) {}

    /**
     * @param recipesIndexed recipe outputs the graph was built from
     * @param recipesFailed  recipes that threw while being read; the first few are logged
     */
    public record Counts(int foods, int potions, int recipesIndexed, int recipesFailed,
                         int foodsWithPrimaryRecipe, int feastBlocks, int synergies) {}

    /**
     * One edible item, or one {@code potion:} target ({@code kind} says which).
     *
     * @param nameKey            the translation key of its name; the dump is server-side
     *                           and does not resolve it into any one language
     * @param saturationModifier the food component's modifier, as datapacks write it
     * @param saturationPoints   what a bite restores: nutrition x modifier x 2
     * @param remainder          what is left in hand after eating ({@code using_converts_to},
     *                           or the item's crafting remainder — a bowl, a bottle)
     * @param primaryRecipe      the id of the recipe {@link RecipeGraph#primary} chose
     */
    public record Food(String id, String kind, String nameKey, String mod,
                       @Nullable Integer nutrition, @Nullable Float saturationModifier,
                       @Nullable Float saturationPoints, @Nullable Boolean alwaysEdible,
                       @Nullable Float eatSeconds, List<Effect> effects,
                       @Nullable String remainder, int maxStack,
                       Flora florafare, List<String> synergies,
                       @Nullable String primaryRecipe, List<Recipe> recipes,
                       @Nullable Feast feast) {}

    /** A status effect, with the chance it is applied (1 when certain). */
    public record Effect(String id, int duration, int amplifier, float probability) {}

    /** An attribute modifier a FloraFare buff applies. */
    public record Attribute(String id, double amount, String operation) {}

    /**
     * What FloraFare does with the food.
     *
     * @param source     how it resolved: {@code item_entry}, {@code tag_entry},
     *                   {@code namespace_entry}, {@code template_entry},
     *                   {@code auto_generated}, {@code excluded_by_*}, {@code not_food}
     * @param file       the datapack file the entry in force came from, {@code <api>} for
     *                   one registered from code
     * @param override   whether a {@code /florafare edit} override is in force for it
     * @param hungerOnly a duration of 0 or less: hunger and effects, no buff
     * @param nutrition  what a bite restores with the entry applied (inheritance resolved)
     * @param aftermath  what the buff leaves behind once it ends; absent when it has none
     * @param losingDefinitions other definitions of the same target that lost on priority
     * @param candidates every entry that could have applied, as {@code /florafare explain}
     *                   prints them
     */
    public record Flora(String source, @Nullable String target, @Nullable String file,
                        @Nullable Integer priority, boolean override, boolean hungerOnly,
                        @Nullable Integer duration, @Nullable Double healthBonus,
                        @Nullable Integer nutrition, @Nullable Float saturationPoints,
                        @Nullable Boolean alwaysEdible, List<Effect> effects,
                        List<Attribute> attributes, @Nullable Aftermath aftermath,
                        List<Loss> losingDefinitions, List<Candidate> candidates) {}

    /**
     * A FloraFare entry's {@code aftermath}, as the datapack wrote it.
     *
     * @param duration    ticks its health bonus and attributes are held for; 0 for an
     *                    aftermath that only grants effects
     * @param effects     granted once when it starts, each with its own duration
     *                    ({@code probability} is always 1)
     */
    public record Aftermath(int duration, double healthBonus, List<Effect> effects,
                            List<Attribute> attributes) {}

    public record Loss(String file, int priority, String winnerFile, int winnerPriority, boolean tie) {}

    public record Candidate(String target, int priority, boolean chosen, @Nullable String reason) {}

    /**
     * One recipe producing the food.
     *
     * @param count     how many one craft yields
     * @param chance    the chance of that yield, when the recipe exposes one (cutting boards)
     * @param container a container the result is served in, when the recipe exposes one
     *                  (Farmer's Delight's cooking pot bowl)
     * @param tool      the tool a recipe requires, when it exposes one (a knife)
     * @param flags     {@code storage}, {@code reversible}, {@code decomposition},
     *                  {@code self_reference}; see {@link RecipeGraph.Flag}
     */
    public record Recipe(String id, String type, int count, @Nullable Float chance,
                         List<Ingredient> ingredients, @Nullable String container,
                         @Nullable String tool, List<String> flags, boolean primary) {}

    /**
     * @param choices      the items that satisfy the slot, capped at
     *                     {@link FoodDumpCollector#MAX_LISTED_CHOICES} — a {@code #c:foods}
     *                     slot would otherwise repeat the whole food list in every recipe
     * @param tag          the tag it was written as, when recoverable
     * @param totalChoices how many items satisfy it in all, capped or not
     */
    public record Ingredient(List<String> choices, @Nullable String tag, int count, int totalChoices) {}

    /**
     * A feast block link, Farmer's Delight style.
     *
     * @param block    the feast block's item
     * @param servings how many servings the block holds when full
     * @param role     {@code serving} on the item served from a feast, {@code feast} on the
     *                 placeable feast item itself
     * @param serving  the item one serving gives, on the {@code feast} side
     */
    public record Feast(String block, int servings, String role, @Nullable String serving) {}
}
