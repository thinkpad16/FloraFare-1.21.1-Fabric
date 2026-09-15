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
import net.minecraft.server.network.ServerPlayerEntity;
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
        ServerCommandSource source = context.getSource();
        RecipeManager recipeManager = source.getServer().getRecipeManager();
        RegistryWrapper.WrapperLookup registries = source.getRegistryManager();

        List<Item> foodItems = new ArrayList<>();
        for (Identifier id : Registries.ITEM.getIds()) {
            Item item = Registries.ITEM.get(id);
            if (item.getComponents().contains(DataComponentTypes.FOOD)) {
                foodItems.add(item);
            }
        }

        // One pass over the recipe list, not one pass per item.
        //
        // getRecipeInfo used to scan every recipe in the game to answer "how is this one
        // item made", and it was called once per food AND again for every ingredient of
        // every food as the tree was expanded. On a modpack with several thousand recipes
        // and a few hundred foods that is tens of millions of Recipe#getResult calls, all
        // on the server thread, all while the server is not ticking. Indexing the recipes
        // by their result item once makes the whole command linear in the recipe count.
        Map<Item, RecipeInfo> recipeIndex = indexRecipesByResult(recipeManager, registries);

        StringBuilder out = new StringBuilder(1 << 16);
        out.append("=================================================================================\n");
        out.append("=== FLORAFARE FOODS RECIPE TREE DUMP ===\n");
        out.append("Total edible items found: ").append(foodItems.size()).append('\n');
        out.append("=================================================================================\n\n");

        for (Item food : foodItems) {
            out.append("ITEM ID: ").append(Registries.ITEM.getId(food)).append('\n');

            RecipeInfo info = recipeIndex.get(food);
            if (info == null) {
                out.append("-> Direct Recipe: None (Raw item / drops / special condition)\n");
            } else {
                String typeStr  = info.typeId() != null ? info.typeId().toString() : "unknown";
                String yieldStr = info.outputCount() > 1
                        ? " (Yields " + info.outputCount() + ")" : "";
                out.append("-> Direct Ingredients").append(yieldStr)
                   .append(" [").append(typeStr).append("]: ")
                   .append(formatItemList(info.ingredients())).append('\n');
                out.append("-> Full Crafting Tree:\n");

                Set<Item>    alreadyExpanded = new HashSet<>();
                List<String> recipeHistory   = new ArrayList<>();
                buildTree(food, recipeIndex, alreadyExpanded, recipeHistory, 3);

                for (String line : recipeHistory) out.append(line).append('\n');
            }
            out.append("---------------------------------------------------------------------------------\n\n");
        }

        File dumpFile = new File(
                FabricLoader.getInstance().getGameDir().toFile(),
                "florafare_edible_items_dump.txt");

        // Written off the server thread. Everything above needed the recipe manager and
        // the registries, so it has to happen here; the file I/O does not, and on a big
        // pack the dump is megabytes.
        final String contents = out.toString();
        final int    count    = foodItems.size();
        // Auto-open only for the person actually sitting at this machine. On a LAN world
        // any player with the permission level could previously pop a text editor onto
        // the host's desktop, which is not something a chat command should be able to do.
        final boolean openWhenDone = isLocalHost(source);

        Util.getIoWorkerExecutor().execute(() -> {
            try (FileWriter writer = new FileWriter(dumpFile)) {
                writer.write(contents);
            } catch (IOException e) {
                Florafare.LOGGER.error("Failed to dump food recipe trees", e);
                source.getServer().execute(() -> source.sendError(Text.translatable(
                        "command.florafare.dumpfoods.error", String.valueOf(e.getMessage()))
                        .formatted(Formatting.RED)));
                return;
            }

            if (openWhenDone) Util.getOperatingSystem().open(dumpFile);
            Florafare.LOGGER.info("Exported {} items with recipe tree to {}",
                    count, dumpFile.getAbsolutePath());

            source.getServer().execute(() -> {
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
                source.sendFeedback(() -> Text.translatable(
                                "command.florafare.dumpfoods.success", count)
                        .formatted(Formatting.GREEN)
                        .append(fileLink), false);
            });
        });

        return 1;
    }

    /**
     * Whether the command came from the player running the integrated server — i.e. the
     * one person for whom opening a file in their desktop's default application is a
     * convenience rather than a surprise.
     */
    private static boolean isLocalHost(ServerCommandSource source) {
        if (source.getServer().isDedicated()) return false;
        ServerPlayerEntity player = source.getPlayer();
        // A console/command-block source on an integrated server is the host's own game.
        return player == null || source.getServer().isHost(player.getGameProfile());
    }

    /**
     * Best recipe per result item, resolved in a single pass over the recipe list.
     * "Best" is the same ranking {@link #scoreRecipeType} always applied.
     */
    private static Map<Item, RecipeInfo> indexRecipesByResult(
            RecipeManager recipeManager, RegistryWrapper.WrapperLookup registries) {

        Map<Item, RecipeInfo> best      = new HashMap<>();
        Map<Item, Integer>    bestScore = new HashMap<>();

        for (RecipeEntry<?> entry : recipeManager.values()) {
            Recipe<?> recipe = entry.value();
            try {
                ItemStack result = recipe.getResult(registries);
                if (result.isEmpty()) continue;
                Item resultItem = result.getItem();

                Map<Item, Integer> ingredientCounts = new LinkedHashMap<>();
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    ItemStack[] matchingStacks = ingredient.getMatchingStacks();
                    if (matchingStacks.length == 0) continue;
                    Item ingItem = matchingStacks[0].getItem();
                    if (ingItem == Items.AIR) continue;
                    ingredientCounts.merge(ingItem, 1, Integer::sum);
                }
                if (ingredientCounts.isEmpty()) continue;

                Identifier typeId = Registries.RECIPE_TYPE.getId(recipe.getType());
                int score = scoreRecipeType(typeId == null ? "" : typeId.getPath());

                // Penalize recipes that require a tool (damageable item) as an ingredient.
                for (Item ing : ingredientCounts.keySet()) {
                    if (ing.getComponents().contains(DataComponentTypes.MAX_DAMAGE)) {
                        score -= 200;
                        break;
                    }
                }

                if (score > bestScore.getOrDefault(resultItem, -999)) {
                    bestScore.put(resultItem, score);
                    best.put(resultItem,
                            new RecipeInfo(ingredientCounts, typeId, result.getCount()));
                }
            } catch (Exception ignored) {}
        }
        return best;
    }

    private static void buildTree(Item item, Map<Item, RecipeInfo> recipeIndex,
                                  Set<Item> alreadyExpanded, List<String> history, int depth) {
        if (!alreadyExpanded.add(item)) return;

        RecipeInfo info = recipeIndex.get(item);
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
            buildTree(subItem, recipeIndex, alreadyExpanded, history, depth + 4);
        }
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
