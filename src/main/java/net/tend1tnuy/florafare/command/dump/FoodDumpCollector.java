package net.tend1tnuy.florafare.command.dump;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodBuffOverrides;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the live game into a {@link FoodDump}. Server thread only: everything here asks
 * the registries, the recipe manager or Florafare's config maps.
 *
 * <p>Built to run in one pass and in time linear in the recipe count. The recipe manager
 * is read once into a {@link RecipeGraph}, and every per-food question after that is a
 * map lookup — the first version of the dump scanned every recipe for every food, and
 * again for every ingredient as the tree was expanded, which on a modpack was tens of
 * millions of {@code getResult} calls while the server stood still.
 *
 * <h2>Best effort, counted</h2>
 * A modded recipe class can throw from {@code getResult} or {@code getIngredients}, and
 * the extra details below (a cooking pot's container, a cutting board's tool and chances,
 * a feast block's serving) are found by reflection on names other mods happen to use. Any
 * of that failing costs the one recipe or detail, is counted in the dump's metadata, and
 * the first few are logged with their exception — the old {@code catch (Exception
 * ignored)} made a dump with half its recipes missing look exactly like a complete one.
 */
public final class FoodDumpCollector {

    private FoodDumpCollector() {}

    /** Bumped whenever a field of the JSON changes meaning or disappears. */
    public static final int FORMAT_VERSION = 2;

    /** Choices listed per ingredient in the dump; the full count is kept beside them. */
    public static final int MAX_LISTED_CHOICES = 32;

    /** How many unreadable recipes are logged in full before they are only counted. */
    private static final int LOGGED_FAILURES = 5;

    /**
     * @param modFilter only foods (and potion targets) from this namespace, or null for all
     * @param maxDepth  how many ingredient levels the text tree expands; see {@link RecipeGraph#tree}
     */
    public record Options(@Nullable String modFilter, int maxDepth) {}

    public static FoodDump collect(MinecraftServer server, Options options) {
        RegistryWrapper.WrapperLookup registries = server.getRegistryManager();

        // ---- recipes, once ----------------------------------------------------
        List<RecipeEntry<?>> entries = new ArrayList<>(server.getRecipeManager().values());
        entries.sort(Comparator.comparing(entry -> entry.id().toString()));
        List<RecipeGraph.Node> nodes = new ArrayList<>();
        int failed = 0;
        for (RecipeEntry<?> entry : entries) {
            try {
                nodes.addAll(readRecipe(entry, registries));
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError fatal) throw fatal;
                failed++;
                if (failed <= LOGGED_FAILURES) {
                    Florafare.LOGGER.warn("dumpfoods: could not read recipe {} ({})",
                            entry.id(), entry.value().getClass().getName(), t);
                }
            }
        }
        if (failed > LOGGED_FAILURES) {
            Florafare.LOGGER.warn("dumpfoods: {} more recipe(s) could not be read; only the first {} were logged.",
                    failed - LOGGED_FAILURES, LOGGED_FAILURES);
        }
        RecipeGraph graph = new RecipeGraph(nodes);

        // ---- which items are food -----------------------------------------------
        TreeMap<String, Item> foods = new TreeMap<>();
        Set<String> allFoodIds = new HashSet<>();
        for (Item item : Registries.ITEM) {
            if (!item.getComponents().contains(DataComponentTypes.FOOD)) continue;
            String id = Registries.ITEM.getId(item).toString();
            allFoodIds.add(id);
            if (matches(options.modFilter(), id)) foods.put(id, item);
        }

        // ---- feast blocks ---------------------------------------------------------
        FeastScan feasts = scanFeasts();
        for (Map.Entry<String, FoodDump.Feast> feast : feasts.links().entrySet()) {
            // The placeable feast is usually not food itself, which is exactly why it
            // used to be missing from the dump; it is listed beside what it serves.
            if (!foods.containsKey(feast.getKey()) && matches(options.modFilter(), feast.getKey())) {
                Identifier id = Identifier.tryParse(feast.getKey());
                if (id != null && Registries.ITEM.containsId(id)) foods.put(feast.getKey(), Registries.ITEM.get(id));
            }
        }

        List<FoodSynergyData> synergies = new ArrayList<>(FoodSynergyManager.getAllSynergies());
        synergies.sort(Comparator.comparing(FoodSynergyData::id));

        // ---- one entry per food -------------------------------------------------------
        List<FoodDump.Food> out = new ArrayList<>();
        Map<String, List<String>> trees = new LinkedHashMap<>();
        int withPrimary = 0;
        for (Map.Entry<String, Item> food : foods.entrySet()) {
            String id = food.getKey();
            ItemStack stack = food.getValue().getDefaultStack();
            RecipeGraph.Node primary = graph.primary(id);
            if (primary != null) withPrimary++;
            out.add(foodEntry(id, food.getValue(), stack, graph, primary,
                    synergiesFor(synergies, id, stack), feasts.links().get(id)));
            trees.put(id, graph.tree(id, options.maxDepth(), allFoodIds::contains));
        }

        int potions = 0;
        List<FoodBuffData> configs = new ArrayList<>(FoodBuffManager.getAllConfigs());
        configs.sort(Comparator.comparing(FoodBuffData::target));
        for (FoodBuffData config : configs) {
            if (!config.target().startsWith(FoodBuffManager.POTION_PREFIX)) continue;
            String potionId = config.target().substring(FoodBuffManager.POTION_PREFIX.length());
            if (!matches(options.modFilter(), potionId)) continue;
            out.add(potionEntry(config, synergies));
            potions++;
        }

        FoodDump.Counts counts = new FoodDump.Counts(foods.size(), potions, graph.size(), failed,
                withPrimary, feasts.blocks(), synergies.size());
        FoodDump.Meta meta = new FoodDump.Meta(FORMAT_VERSION,
                SharedConstants.getGameVersion().getName(),
                FabricLoader.getInstance().getModContainer(Florafare.MOD_ID)
                        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"),
                OffsetDateTime.now().toString(), options.modFilter(), options.maxDepth(),
                counts, feasts.description(),
                List.of("Brewing recipes are not in the recipe manager, so potion targets list none.",
                        "Ingredient tags are recovered from each recipe's serialized form; a custom "
                                + "ingredient type shows only the items it matches.",
                        "Loot tables, villager trades and mob drops are not covered: an item with no "
                                + "recipe here may still be obtainable.",
                        "Recipe flags (storage, reversible, decomposition) are inferred from recipe shapes "
                                + "and never pick the primary recipe of a food made some other way."));
        return new FoodDump(meta, out, trees);
    }

    private static boolean matches(@Nullable String modFilter, String id) {
        return modFilter == null || RecipeGraph.namespaceOf(id).equals(modFilter);
    }

    // -------------------------------------------------------------------------
    // RECIPES
    // -------------------------------------------------------------------------

    /** One recipe as graph nodes: one per output, none for a recipe with no ingredients or result. */
    private static List<RecipeGraph.Node> readRecipe(RecipeEntry<?> entry,
                                                     RegistryWrapper.WrapperLookup registries) {
        Recipe<?> recipe = entry.value();
        List<RecipeGraph.Slot> slots = readSlots(recipe.getIngredients());
        if (slots.isEmpty()) return List.of();

        Identifier typeId = Registries.RECIPE_TYPE.getId(recipe.getType());
        String type = typeId == null ? "unknown" : typeId.toString();
        String container = itemIdOf(invoke(recipe, "getOutputContainer", "getContainer"));
        String tool = toolOf(invoke(recipe, "getTool", "getToolIngredient"));

        int score = scoreRecipeType(RecipeGraph.shortType(type));
        // A damageable item as an ingredient is usually a tool the recipe does not consume
        // (a knife in a crafting grid); such a recipe is rarely "the" way to make a food.
        for (RecipeGraph.Slot slot : slots) {
            String single = slot.single();
            if (single == null) continue;
            Identifier id = Identifier.tryParse(single);
            if (id != null && Registries.ITEM.get(id).getComponents().contains(DataComponentTypes.MAX_DAMAGE)) {
                score -= 200;
                break;
            }
        }

        Map<String, Output> outputs = new LinkedHashMap<>();
        ItemStack result = recipe.getResult(registries);
        if (result != null && !result.isEmpty()) {
            outputs.put(Registries.ITEM.getId(result.getItem()).toString(),
                    new Output(result.getCount(), null));
        }
        // Several outputs with chances (Farmer's Delight's cutting board), where exposed.
        Object extra = invoke(recipe, "getRollableResults", "getResults");
        if (extra instanceof Collection<?> collection) {
            for (Object element : collection) {
                ItemStack stack = element instanceof ItemStack direct ? direct
                        : asStack(invoke(element, "stack", "getStack"));
                if (stack == null || stack.isEmpty()) continue;
                Float chance = asFloat(invoke(element, "chance", "getChance"));
                outputs.putIfAbsent(Registries.ITEM.getId(stack.getItem()).toString(),
                        new Output(stack.getCount(), chance));
            }
        }

        List<RecipeGraph.Node> nodes = new ArrayList<>(outputs.size());
        for (Map.Entry<String, Output> output : outputs.entrySet()) {
            nodes.add(new RecipeGraph.Node(entry.id().toString(), type, output.getKey(),
                    output.getValue().count(), output.getValue().chance(), slots, container, tool, score));
        }
        return nodes;
    }

    private record Output(int count, @Nullable Float chance) {}

    /** Ingredients, empty ones dropped and identical ones merged with their counts added. */
    private static List<RecipeGraph.Slot> readSlots(List<Ingredient> ingredients) {
        Map<List<String>, RecipeGraph.Slot> merged = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            ItemStack[] stacks = ingredient.getMatchingStacks();
            if (stacks.length == 0) continue;
            List<String> choices = new ArrayList<>();
            for (ItemStack stack : stacks) {
                if (stack.isEmpty() || stack.isOf(Items.AIR)) continue;
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                if (!choices.contains(id)) choices.add(id);
            }
            if (choices.isEmpty()) continue;
            // An ingredient written with a count (some modded recipe formats) carries it
            // on its matching stacks; vanilla's are always 1.
            int count = Math.max(1, stacks[0].getCount());
            String tag = tagOf(ingredient);
            List<String> key = new ArrayList<>(choices);
            key.add("#" + tag);
            RecipeGraph.Slot previous = merged.get(key);
            merged.put(key, new RecipeGraph.Slot(choices, tag,
                    previous == null ? count : previous.count() + count));
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * The tag an ingredient was written as, read back from its own codec: an ingredient
     * keeps its entries private, but serializes a tag entry as {@code {"tag": "..."}}.
     * Null for item lists, custom ingredient types and anything that fails to encode.
     */
    private static @Nullable String tagOf(Ingredient ingredient) {
        try {
            JsonElement json = Ingredient.ALLOW_EMPTY_CODEC
                    .encodeStart(JsonOps.INSTANCE, ingredient).result().orElse(null);
            if (json instanceof JsonArray array && array.size() == 1) json = array.get(0);
            if (json instanceof JsonObject object && object.has("tag") && object.size() == 1) {
                return object.get("tag").getAsString();
            }
        } catch (Exception ignored) {
            // Best effort by design: the choices are still listed without the tag.
        }
        return null;
    }

    private static @Nullable String toolOf(@Nullable Object tool) {
        if (tool instanceof Ingredient ingredient && !ingredient.isEmpty()) {
            String tag = tagOf(ingredient);
            if (tag != null) return "#" + tag;
            ItemStack[] stacks = ingredient.getMatchingStacks();
            return stacks.length == 0 ? null : Registries.ITEM.getId(stacks[0].getItem()).toString();
        }
        return itemIdOf(tool);
    }

    /**
     * How strongly a recipe type suggests "this is how the food is made". Crafting and
     * the modded kitchen stations first, the furnace family after, anything unknown last.
     */
    static int scoreRecipeType(String type) {
        if (type.equals("crafting") || type.equals("crafting_shaped")
                || type.equals("crafting_shapeless"))                       return 100;
        if (type.contains("cooking") || type.contains("mixing")
                || type.contains("baking") || type.contains("brewing")
                || type.contains("fermenting"))                             return 90;
        if (type.equals("smelting"))                                        return 50;
        if (type.equals("smoking"))                                         return 40;
        if (type.equals("campfire_cooking"))                                return 30;
        if (type.contains("cutting"))                                       return 25;
        if (type.equals("blasting"))                                        return 20;
        return 10;
    }

    // -------------------------------------------------------------------------
    // REFLECTION — for details only some mods' recipe and block classes expose
    // -------------------------------------------------------------------------

    private static final Map<String, Optional<Method>> METHODS = new HashMap<>();

    /** The first public no-argument method of these names, called; null if none or if it throws. */
    private static @Nullable Object invoke(@Nullable Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            Method method = METHODS.computeIfAbsent(target.getClass().getName() + "#" + name,
                    key -> find(target.getClass(), name, 0)).orElse(null);
            if (method == null) continue;
            try {
                return method.invoke(target);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError fatal) throw fatal;
                return null;
            }
        }
        return null;
    }

    private static Optional<Method> find(Class<?> type, String name, int parameters) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters
                    && !Modifier.isStatic(method.getModifiers())) {
                try {
                    method.setAccessible(true);
                } catch (Exception ignored) {
                    // Public already; setAccessible only helps with a non-public declaring class.
                }
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    private static @Nullable String itemIdOf(@Nullable Object value) {
        ItemStack stack = asStack(value);
        return stack == null || stack.isEmpty() ? null : Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static @Nullable ItemStack asStack(@Nullable Object value) {
        if (value instanceof ItemStack stack) return stack;
        if (value instanceof Item item) return item.getDefaultStack();
        return null;
    }

    private static @Nullable Float asFloat(@Nullable Object value) {
        return value instanceof Number number ? number.floatValue() : null;
    }

    // -------------------------------------------------------------------------
    // FEASTS
    // -------------------------------------------------------------------------

    /**
     * @param links per item id: the serving's and the placeable feast's side of each link
     * @param blocks how many blocks with a {@code servings} property were found
     */
    private record FeastScan(Map<String, FoodDump.Feast> links, int blocks, String description) {}

    /**
     * Farmer's Delight style feasts: a placeable block whose state counts down an int
     * property named {@code servings}, handing out one serving item per use. The serving
     * item is asked of the block by reflection ({@code getServingItem} /
     * {@code getServingStack}, with or without a state argument); a block that does not
     * answer is counted but not linked.
     */
    private static FeastScan scanFeasts() {
        Map<String, FoodDump.Feast> links = new HashMap<>();
        int blocks = 0;
        int linked = 0;
        for (Item item : Registries.ITEM) {
            if (!(item instanceof BlockItem blockItem)) continue;
            Block block = blockItem.getBlock();
            IntProperty servings = null;
            for (Property<?> property : block.getStateManager().getProperties()) {
                if (property instanceof IntProperty ints && property.getName().equals("servings")) servings = ints;
            }
            if (servings == null) continue;
            blocks++;

            int max = 0;
            for (Integer value : servings.getValues()) max = Math.max(max, value);
            BlockState full = block.getDefaultState().with(servings, max);
            String serving = itemIdOf(servingOf(block, full));
            String feastId = Registries.ITEM.getId(item).toString();
            if (serving == null) continue;
            linked++;
            links.put(feastId, new FoodDump.Feast(feastId, max, "feast", serving));
            links.putIfAbsent(serving, new FoodDump.Feast(feastId, max, "serving", null));
        }
        String description = blocks == 0
                ? "none found (no block has an int 'servings' property)"
                : blocks + " block(s) with a 'servings' property, " + linked
                        + " linked to their serving item by reflection";
        return new FeastScan(links, blocks, description);
    }

    private static @Nullable Object servingOf(Block block, BlockState state) {
        for (String name : new String[] {"getServingItem", "getServingStack"}) {
            for (Method method : block.getClass().getMethods()) {
                if (!method.getName().equals(name) || Modifier.isStatic(method.getModifiers())) continue;
                try {
                    if (method.getParameterCount() == 0) return method.invoke(block);
                    if (method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isInstance(state)) {
                        return method.invoke(block, state);
                    }
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError fatal) throw fatal;
                    return null;
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // ONE FOOD
    // -------------------------------------------------------------------------

    private static FoodDump.Food foodEntry(String id, Item item, ItemStack stack, RecipeGraph graph,
                                           @Nullable RecipeGraph.Node primary, List<String> synergies,
                                           @Nullable FoodDump.Feast feast) {
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        List<FoodDump.Effect> effects = new ArrayList<>();
        String remainder = null;
        if (food != null) {
            for (FoodComponent.StatusEffectEntry entry : food.effects()) {
                effects.add(effect(entry.effect(), entry.probability()));
            }
            remainder = food.usingConvertsTo().map(s -> Registries.ITEM.getId(s.getItem()).toString()).orElse(null);
        }
        if (remainder == null && item.getRecipeRemainder() != null && item.getRecipeRemainder() != Items.AIR) {
            remainder = Registries.ITEM.getId(item.getRecipeRemainder()).toString();
        }

        List<FoodDump.Recipe> recipes = new ArrayList<>();
        for (RecipeGraph.Node node : graph.recipesFor(id)) {
            recipes.add(recipe(node, graph, node == primary));
        }

        return new FoodDump.Food(id, feast != null && "feast".equals(feast.role()) && food == null ? "feast" : "item",
                item.getTranslationKey(), RecipeGraph.namespaceOf(id),
                food == null ? null : food.nutrition(),
                food == null ? null : FoodBuffData.saturationModifierOf(food.nutrition(), food.saturation()),
                food == null ? null : food.saturation(),
                food == null ? null : food.canAlwaysEat(),
                food == null ? null : food.eatSeconds(),
                effects, remainder, item.getMaxCount(),
                flora(stack), synergies, primary == null ? null : primary.id(), recipes, feast);
    }

    private static FoodDump.Recipe recipe(RecipeGraph.Node node, RecipeGraph graph, boolean primary) {
        List<FoodDump.Ingredient> ingredients = new ArrayList<>();
        for (RecipeGraph.Slot slot : node.slots()) {
            List<String> choices = slot.choices().size() > MAX_LISTED_CHOICES
                    ? slot.choices().subList(0, MAX_LISTED_CHOICES) : slot.choices();
            ingredients.add(new FoodDump.Ingredient(choices, slot.tag(), slot.count(), slot.choices().size()));
        }
        List<String> flags = new ArrayList<>();
        for (RecipeGraph.Flag flag : EnumSet.copyOf(withNone(graph.flags(node)))) {
            flags.add(flag.name().toLowerCase(Locale.ROOT));
        }
        return new FoodDump.Recipe(node.id(), node.type(), node.outputCount(), node.chance(),
                ingredients, node.container(), node.tool(), flags, primary);
    }

    /** {@link EnumSet#copyOf} refuses an empty plain set; this keeps the iteration order fixed. */
    private static Collection<RecipeGraph.Flag> withNone(Set<RecipeGraph.Flag> flags) {
        return flags.isEmpty() ? EnumSet.noneOf(RecipeGraph.Flag.class) : flags;
    }

    private static FoodDump.Effect effect(StatusEffectInstance instance, float probability) {
        String id = instance.getEffectType().getKey().map(key -> key.getValue().toString()).orElse("unknown");
        return new FoodDump.Effect(id, instance.getDuration(), instance.getAmplifier(), probability);
    }

    /** What FloraFare does with one stack, read through the same chain {@code /florafare explain} walks. */
    private static FoodDump.Flora flora(ItemStack stack) {
        FoodBuffManager.Resolution resolution = FoodBuffManager.explain(stack);
        return floraOf(resolution.source().name().toLowerCase(Locale.ROOT), resolution.target(),
                resolution.data(), stack, resolution.candidates(),
                resolution.source() == FoodBuffManager.Source.AUTO_GENERATED);
    }

    private static FoodDump.Flora floraOf(String source, @Nullable String target, @Nullable FoodBuffData data,
                                          @Nullable ItemStack stack, List<FoodBuffManager.Candidate> found,
                                          boolean generated) {
        List<FoodDump.Candidate> candidates = new ArrayList<>();
        for (FoodBuffManager.Candidate candidate : found) {
            candidates.add(new FoodDump.Candidate(candidate.target(), candidate.priority(),
                    candidate.chosen(), candidate.reason()));
        }
        List<FoodDump.Loss> losses = new ArrayList<>();
        if (target != null) {
            for (FoodBuffManager.PriorityLoss loss : FoodBuffManager.getPriorityLosses(target)) {
                losses.add(new FoodDump.Loss(loss.file(), loss.priority(), loss.winnerFile(),
                        loss.winnerPriority(), loss.tie()));
            }
        }
        if (data == null) {
            return new FoodDump.Flora(source, target, null, null, false, false, null, null, null, null,
                    null, List.of(), List.of(), null, losses, candidates);
        }

        List<FoodDump.Effect> effects = new ArrayList<>();
        for (FoodBuffData.EffectData effect : data.effects()) {
            effects.add(new FoodDump.Effect(effect.id().toString(), effect.duration(), effect.amplifier(), 1f));
        }
        List<FoodDump.Attribute> attributes = new ArrayList<>();
        for (FoodBuffData.AttributeData attribute : data.attributes()) {
            attributes.add(new FoodDump.Attribute(attribute.attributeId().toString(),
                    attribute.amount(), attribute.operation()));
        }
        FoodComponent food = stack == null ? null : stack.get(DataComponentTypes.FOOD);
        return new FoodDump.Flora(source, target,
                generated ? null : FoodBuffManager.sourceOf(target),
                generated ? null : data.priority(),
                target != null && FoodBuffOverrides.get(target) != null,
                data.isHungerOnly(), data.duration(), data.healthBonus(),
                food == null && !data.hasNutrition() ? null : data.effectiveNutrition(food),
                food == null && !data.hasSaturation() ? null : data.effectiveSaturationPoints(food),
                data.alwaysEdible(), effects, attributes, aftermathOf(data.aftermath()),
                losses, candidates);
    }

    /** The dump's form of an aftermath; null (left out of the JSON) when there is none. */
    @Nullable
    private static FoodDump.Aftermath aftermathOf(net.tend1tnuy.florafare.food.FoodAftermath aftermath) {
        if (aftermath.isEmpty()) return null;
        List<FoodDump.Effect> effects = new ArrayList<>();
        for (FoodBuffData.EffectData effect : aftermath.effects()) {
            effects.add(new FoodDump.Effect(effect.id().toString(), effect.duration(), effect.amplifier(), 1f));
        }
        List<FoodDump.Attribute> attributes = new ArrayList<>();
        for (FoodBuffData.AttributeData attribute : aftermath.attributes()) {
            attributes.add(new FoodDump.Attribute(attribute.attributeId().toString(),
                    attribute.amount(), attribute.operation()));
        }
        return new FoodDump.Aftermath(aftermath.duration(), aftermath.healthBonus(), effects, attributes);
    }

    // -------------------------------------------------------------------------
    // POTION TARGETS
    // -------------------------------------------------------------------------

    private static FoodDump.Food potionEntry(FoodBuffData config, List<FoodSynergyData> synergies) {
        String target = config.target();
        Identifier potionId = Identifier.tryParse(target.substring(FoodBuffManager.POTION_PREFIX.length()));
        Optional<RegistryEntry.Reference<Potion>> potion = potionId == null
                ? Optional.empty() : Registries.POTION.getEntry(potionId);

        List<FoodDump.Effect> effects = new ArrayList<>();
        potion.ifPresent(entry -> {
            for (StatusEffectInstance instance : entry.value().getEffects()) effects.add(effect(instance, 1f));
        });
        String nameKey = potion.<String>map(entry -> Potion.finishTranslationKey(
                Optional.<RegistryEntry<Potion>>of(entry), Items.POTION.getTranslationKey() + ".effect."))
                .orElse(target);

        List<String> referencing = new ArrayList<>();
        for (FoodSynergyData synergy : synergies) {
            if (synergy.requirements().contains(target)) referencing.add(synergy.id());
        }
        return new FoodDump.Food(target, "potion", nameKey,
                potionId == null ? "unknown" : potionId.getNamespace(),
                null, null, null, null, null, effects, "minecraft:glass_bottle", 1,
                floraOf("potion_entry", target, config, null, List.of(), false),
                referencing, null, List.of(), null);
    }

    // -------------------------------------------------------------------------
    // SYNERGIES
    // -------------------------------------------------------------------------

    /** The synergies with a requirement this item satisfies, by id or by tag. */
    private static List<String> synergiesFor(List<FoodSynergyData> synergies, String id, ItemStack stack) {
        List<String> referencing = new ArrayList<>();
        for (FoodSynergyData synergy : synergies) {
            for (String requirement : synergy.requirements()) {
                boolean match;
                if (requirement.startsWith("#")) {
                    Identifier tag = Identifier.tryParse(requirement.substring(1));
                    match = tag != null && stack.isIn(TagKey.of(RegistryKeys.ITEM, tag));
                } else {
                    match = requirement.equals(id);
                }
                if (match) {
                    referencing.add(synergy.id());
                    break;
                }
            }
        }
        return referencing;
    }
}
