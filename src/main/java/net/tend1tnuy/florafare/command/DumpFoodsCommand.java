package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.tend1tnuy.florafare.Florafare;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class DumpFoodsCommand {

    /**
     * Holds the resolved ingredients and metadata for a single recipe result.
     *
     * @param ingredients Map of ingredient item to slot count.
     * @param typeId      Registry id of the recipe type (e.g. "minecraft:crafting").
     * @param outputCount Number of items produced by the recipe (e.g. 9 for nuggets).
     */
    private record RecipeInfo(Map<Item, Integer> ingredients, Identifier typeId, int outputCount) {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
                CommandManager.literal("florafare")
                        .requires(source -> source.hasPermissionLevel(
                                net.tend1tnuy.florafare.config.FlorafareConfig.commandPermissionLevel))
                        .then(CommandManager.literal("dumpfoods")
                                .executes(DumpFoodsCommand::executeDump)
                        )
        );
    }

    private static int executeDump(CommandContext<ServerCommandSource> context) {
        List<Item> foodItems = new ArrayList<>();
        ServerCommandSource source = context.getSource();
        RecipeManager recipeManager = source.getServer().getRecipeManager();
        RegistryWrapper.WrapperLookup registries = source.getRegistryManager();

        for (Identifier id : Registries.ITEM.getIds()) {
            Item item = Registries.ITEM.get(id);
            if (item.getComponents().contains(DataComponentTypes.FOOD)) {
                foodItems.add(item);
            }
        }

        File dumpFile = new File(
                FabricLoader.getInstance().getGameDir().toFile(),
                "florafare_edible_items_dump.txt");

        try (FileWriter writer = new FileWriter(dumpFile)) {
            writer.write("=================================================================================\n");
            writer.write("=== FLORAFARE FOODS RECIPE TREE DUMP ===\n");
            writer.write("Total edible items found: " + foodItems.size() + "\n");
            writer.write("=================================================================================\n\n");

            for (Item food : foodItems) {
                Identifier foodId = Registries.ITEM.getId(food);
                writer.write("ITEM ID: " + foodId.toString() + "\n");

                RecipeInfo info = getRecipeInfo(food, recipeManager, registries);

                if (info == null) {
                    writer.write("-> Direct Recipe: None (Raw item / drops / special condition)\n");
                } else {
                    String typeStr  = info.typeId() != null ? info.typeId().toString() : "unknown";
                    // Prefix the yield count when a recipe produces more than one item.
                    String yieldStr = info.outputCount() > 1
                            ? " (Yields " + info.outputCount() + ")" : "";
                    writer.write("-> Direct Ingredients" + yieldStr
                            + " [" + typeStr + "]: "
                            + formatItemList(info.ingredients()) + "\n");
                    writer.write("-> Full Crafting Tree:\n");

                    Set<Item>    alreadyExpanded = new HashSet<>();
                    List<String> recipeHistory   = new ArrayList<>();
                    buildTree(food, recipeManager, registries,
                            alreadyExpanded, recipeHistory, 3);

                    for (String line : recipeHistory) writer.write(line + "\n");
                }
                writer.write("---------------------------------------------------------------------------------\n\n");
            }

            // Open the file automatically on integrated (non-dedicated) servers.
            if (!source.getServer().isDedicated()) {
                Util.getOperatingSystem().open(dumpFile);
            }

            MutableText fileLink = Text.literal(dumpFile.getName())
                    .formatted(Formatting.UNDERLINE, Formatting.AQUA)
                    .styled(style -> style
                            .withClickEvent(new ClickEvent(
                                    ClickEvent.Action.COPY_TO_CLIPBOARD,
                                    dumpFile.getAbsolutePath()))
                            .withHoverEvent(new HoverEvent(
                                    HoverEvent.Action.SHOW_TEXT,
                                    Text.translatable("command.florafare.dumpfoods.hover.copy")))
                    );

            MutableText successMessage = Text.translatable(
                            "command.florafare.dumpfoods.success", foodItems.size())
                    .formatted(Formatting.GREEN)
                    .append(fileLink);

            source.sendFeedback(() -> successMessage, false);
            Florafare.LOGGER.info("Exported {} items with recipe tree to {}",
                    foodItems.size(), dumpFile.getAbsolutePath());

        } catch (IOException e) {
            source.sendError(Text.translatable(
                    "command.florafare.dumpfoods.error", e.getMessage())
                    .formatted(Formatting.RED));
            Florafare.LOGGER.error("Failed to dump food recipe trees", e);
        }

        return 1;
    }

    private static void buildTree(Item item, RecipeManager recipeManager,
                                  RegistryWrapper.WrapperLookup registries,
                                  Set<Item> alreadyExpanded, List<String> history, int depth) {
        if (alreadyExpanded.contains(item)) return;
        alreadyExpanded.add(item);

        RecipeInfo info = getRecipeInfo(item, recipeManager, registries);
        if (info == null) return;

        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append(" ");

        // Prefix the result name with "Nx" when the recipe yields more than one item.
        String outputPrefix = info.outputCount() > 1 ? info.outputCount() + "x " : "";
        String typeStr      = info.typeId() != null
                ? " [" + info.typeId().getPath() + "]" : "";

        history.add(indent + "└─ " + outputPrefix + Registries.ITEM.getId(item)
                + typeStr + " requires: " + formatItemList(info.ingredients()));

        for (Item subItem : info.ingredients().keySet()) {
            buildTree(subItem, recipeManager, registries, alreadyExpanded, history, depth + 4);
        }
    }

    private static RecipeInfo getRecipeInfo(Item item, RecipeManager recipeManager,
                                            RegistryWrapper.WrapperLookup registries) {
        RecipeInfo bestInfo  = null;
        int        bestScore = -999;

        for (RecipeEntry<?> entry : recipeManager.values()) {
            Recipe<?> recipe = entry.value();
            try {
                ItemStack result = recipe.getResult(registries);
                if (!result.isOf(item)) continue;

                // Number of items produced by this recipe.
                int outputCount = result.getCount();

                Map<Item, Integer> ingredientCounts = new LinkedHashMap<>();
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (!ingredient.isEmpty()) {
                        ItemStack[] matchingStacks = ingredient.getMatchingStacks();
                        if (matchingStacks.length > 0) {
                            Item ingItem = matchingStacks[0].getItem();
                            if (ingItem != Items.AIR) {
                                ingredientCounts.put(ingItem,
                                        ingredientCounts.getOrDefault(ingItem, 0) + 1);
                            }
                        }
                    }
                }

                if (!ingredientCounts.isEmpty()) {
                    Identifier typeId = Registries.RECIPE_TYPE.getId(recipe.getType());
                    int        score  = scoreRecipeType(typeId.getPath());

                    // Penalize recipes that require a tool (damageable item) as an ingredient.
                    for (Item ing : ingredientCounts.keySet()) {
                        if (ing.getComponents().contains(DataComponentTypes.MAX_DAMAGE)) {
                            score -= 200;
                            break;
                        }
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        bestInfo  = new RecipeInfo(
                                new LinkedHashMap<>(ingredientCounts), typeId, outputCount);
                    }
                }
            } catch (Exception ignored) {}
        }
        return bestInfo;
    }

    private static int scoreRecipeType(String type) {
        if (type.equals("crafting"))                                        return 100;
        if (type.contains("cooking") || type.contains("mixing")
                || type.contains("baking"))                                 return 90;
        if (type.equals("smelting"))                                        return 50;
        if (type.equals("smoking"))                                         return 40;
        if (type.equals("campfire_cooking"))                                return 30;
        if (type.equals("blasting"))                                        return 20;
        return 10;
    }

    private static String formatItemList(Map<Item, Integer> ingredients) {
        List<String> formatted = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : ingredients.entrySet()) {
            int    count  = entry.getValue();
            String prefix = count > 1 ? count + "x " : "";
            formatted.add(prefix + Registries.ITEM.getId(entry.getKey()).toString());
        }
        return formatted.toString();
    }
}
