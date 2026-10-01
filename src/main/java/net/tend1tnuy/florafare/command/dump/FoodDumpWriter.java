package net.tend1tnuy.florafare.command.dump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a {@link FoodDump} into the three file formats. Pure string building: runs on the
 * IO pool and touches nothing but the dump it was handed.
 */
public final class FoodDumpWriter {

    private FoodDumpWriter() {}

    /** The formats a dump can be written in. */
    public enum Format {
        JSON("json"), TXT("txt"), CSV("csv");

        public final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        /** {@code json}, {@code txt}, {@code csv}, or {@code all} for every one; null otherwise. */
        public static List<Format> parse(String name) {
            if (name == null) return null;
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "json" -> List.of(JSON);
                case "txt", "text" -> List.of(TXT);
                case "csv" -> List.of(CSV);
                case "all" -> List.of(JSON, TXT, CSV);
                default -> null;
            };
        }
    }

    public static String write(FoodDump dump, Format format) {
        return switch (format) {
            case JSON -> json(dump);
            case TXT -> text(dump);
            case CSV -> csv(dump);
        };
    }

    // -------------------------------------------------------------------------
    // JSON
    // -------------------------------------------------------------------------

    /** The JSON root: {@code meta} and {@code foods}. The text-only trees are left out. */
    private record JsonRoot(FoodDump.Meta meta, List<FoodDump.Food> foods) {}

    public static String json(FoodDump dump) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(new JsonRoot(dump.meta(), dump.foods()));
    }

    // -------------------------------------------------------------------------
    // TEXT
    // -------------------------------------------------------------------------

    public static String text(FoodDump dump) {
        FoodDump.Meta meta = dump.meta();
        StringBuilder out = new StringBuilder(1 << 16);
        out.append("=== FLORAFARE FOOD DUMP ===\n");
        out.append("Minecraft ").append(meta.minecraftVersion())
           .append(" · FloraFare ").append(meta.modVersion())
           .append(" · ").append(meta.timestamp()).append('\n');
        FoodDump.Counts counts = meta.counts();
        out.append(counts.foods()).append(" foods, ").append(counts.potions()).append(" potion targets, ")
           .append(counts.recipesIndexed()).append(" recipe outputs indexed, ")
           .append(counts.recipesFailed()).append(" recipes failed to read");
        if (meta.modFilter() != null) out.append(" · mod filter: ").append(meta.modFilter());
        out.append(" · tree depth ").append(meta.maxDepth()).append('\n');
        out.append("Feast blocks: ").append(meta.feastDetection()).append('\n');
        for (String note : meta.notes()) out.append("Note: ").append(note).append('\n');
        out.append('\n');

        for (FoodDump.Food food : dump.foods()) {
            out.append("--- ").append(food.id()).append(" (").append(food.nameKey()).append(")\n");
            out.append("  ").append(foodValues(food)).append('\n');
            if (!food.effects().isEmpty()) {
                out.append("  effects: ").append(effects(food.effects())).append('\n');
            }
            if (food.remainder() != null) out.append("  leaves: ").append(food.remainder()).append('\n');
            if (food.feast() != null) out.append("  feast: ").append(feast(food.feast())).append('\n');
            appendFlora(out, food.florafare());
            if (!food.synergies().isEmpty()) {
                out.append("  synergies: ").append(String.join(", ", food.synergies())).append('\n');
            }

            if (food.recipes().isEmpty()) {
                out.append("  recipes: none (raw, dropped, traded or generated)\n");
            } else {
                out.append("  recipes (").append(food.recipes().size()).append("):\n");
                for (FoodDump.Recipe recipe : food.recipes()) {
                    out.append("    ").append(recipe.primary() ? "* " : "- ").append(recipeLine(recipe)).append('\n');
                }
                if (food.primaryRecipe() == null) {
                    out.append("  no primary recipe: it is only ever unpacked from storage\n");
                }
            }
            List<String> tree = dump.trees().get(food.id());
            if (tree != null && !tree.isEmpty()) {
                out.append("  tree:\n");
                for (String line : tree) out.append("    ").append(line).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String foodValues(FoodDump.Food food) {
        List<String> parts = new ArrayList<>();
        parts.add("mod " + food.mod());
        if (food.nutrition() != null) parts.add("nutrition " + food.nutrition());
        if (food.saturationModifier() != null) {
            parts.add("saturation " + num(food.saturationModifier())
                    + " (" + num(food.saturationPoints()) + " pts)");
        }
        if (food.eatSeconds() != null) parts.add("eat " + num(food.eatSeconds()) + "s");
        if (Boolean.TRUE.equals(food.alwaysEdible())) parts.add("always edible");
        parts.add("stack " + food.maxStack());
        return String.join(" · ", parts);
    }

    private static void appendFlora(StringBuilder out, FoodDump.Flora flora) {
        out.append("  florafare: ").append(flora.source());
        if (flora.target() != null) out.append(" -> ").append(flora.target());
        if (flora.file() != null) out.append(" from ").append(flora.file());
        if (flora.priority() != null) out.append(" (priority ").append(flora.priority()).append(')');
        if (flora.override()) out.append(" [edit override]");
        out.append('\n');
        if (flora.duration() != null) {
            out.append("    ");
            out.append(flora.hungerOnly() ? "hunger only, no buff" : "buff " + flora.duration() + " ticks");
            if (flora.nutrition() != null) out.append(" · nutrition ").append(flora.nutrition());
            if (flora.saturationPoints() != null) out.append(" · saturation ").append(num(flora.saturationPoints())).append(" pts");
            if (flora.healthBonus() != null && flora.healthBonus() != 0) {
                out.append(" · health ").append(flora.healthBonus() > 0 ? "+" : "").append(num(flora.healthBonus()));
            }
            out.append('\n');
        }
        if (!flora.effects().isEmpty()) out.append("    effects: ").append(effects(flora.effects())).append('\n');
        for (FoodDump.Attribute attribute : flora.attributes()) {
            out.append("    attribute: ").append(attribute.id()).append(' ').append(num(attribute.amount()))
               .append(' ').append(attribute.operation()).append('\n');
        }
        for (FoodDump.Loss loss : flora.losingDefinitions()) {
            out.append("    lost: ").append(loss.file()).append(" (priority ").append(loss.priority())
               .append(") to ").append(loss.winnerFile()).append(" (priority ").append(loss.winnerPriority())
               .append(loss.tie() ? ", tie)" : ")").append('\n');
        }
        for (FoodDump.Candidate candidate : flora.candidates()) {
            if (candidate.chosen()) continue;
            out.append("    shadowed: ").append(candidate.target()).append(" (priority ")
               .append(candidate.priority()).append(')');
            if (candidate.reason() != null) out.append(" — ").append(candidate.reason());
            out.append('\n');
        }
    }

    private static String effects(List<FoodDump.Effect> effects) {
        List<String> parts = new ArrayList<>();
        for (FoodDump.Effect effect : effects) {
            StringBuilder part = new StringBuilder(effect.id())
                    .append(' ').append(roman(effect.amplifier() + 1))
                    .append(' ').append(num(effect.duration() / 20.0)).append('s');
            if (effect.probability() < 1f) {
                part.append(" (").append(Math.round(effect.probability() * 100)).append("%)");
            }
            parts.add(part.toString());
        }
        return String.join(", ", parts);
    }

    private static String feast(FoodDump.Feast feast) {
        return "serving".equals(feast.role())
                ? "one of " + feast.servings() + " servings of " + feast.block()
                : "placeable feast with " + feast.servings() + " servings of " + feast.serving();
    }

    /** One recipe on one line: id, type, yield, flags, inputs. */
    public static String recipeLine(FoodDump.Recipe recipe) {
        StringBuilder line = new StringBuilder(recipe.id())
                .append(" [").append(RecipeGraph.shortType(recipe.type())).append(']');
        if (recipe.count() > 1) line.append(" makes ").append(recipe.count());
        if (recipe.chance() != null && recipe.chance() < 1f) {
            line.append(" (").append(Math.round(recipe.chance() * 100)).append("% chance)");
        }
        if (!recipe.flags().isEmpty()) line.append(" {").append(String.join(", ", recipe.flags())).append('}');
        line.append(" <- ").append(ingredients(recipe.ingredients()));
        if (recipe.tool() != null) line.append(" · tool ").append(recipe.tool());
        if (recipe.container() != null) line.append(" · served in ").append(recipe.container());
        return line.toString();
    }

    static String ingredients(List<FoodDump.Ingredient> ingredients) {
        List<String> parts = new ArrayList<>();
        for (FoodDump.Ingredient ingredient : ingredients) {
            parts.add(RecipeGraph.slotLabel(
                    new RecipeGraph.Slot(ingredient.choices(), ingredient.tag(), ingredient.count())));
        }
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    // -------------------------------------------------------------------------
    // CSV
    // -------------------------------------------------------------------------

    static final List<String> CSV_COLUMNS = List.of(
            "id", "kind", "mod", "name_key", "nutrition", "saturation_modifier", "saturation_points",
            "always_edible", "eat_seconds", "max_stack", "remainder", "effects",
            "florafare_source", "florafare_target", "florafare_file", "priority", "override",
            "hunger_only", "buff_duration_ticks", "buff_nutrition", "buff_saturation_points",
            "health_bonus", "buff_effects", "attributes", "synergies", "recipe_count",
            "primary_recipe", "primary_type", "primary_yield", "primary_ingredients", "feast");

    /** One row per food, RFC 4180 quoting, a header row first. */
    public static String csv(FoodDump dump) {
        StringBuilder out = new StringBuilder(1 << 14);
        row(out, CSV_COLUMNS);
        for (FoodDump.Food food : dump.foods()) {
            FoodDump.Flora flora = food.florafare();
            FoodDump.Recipe primary = null;
            for (FoodDump.Recipe recipe : food.recipes()) {
                if (recipe.primary()) primary = recipe;
            }
            List<String> attributes = new ArrayList<>();
            for (FoodDump.Attribute attribute : flora.attributes()) {
                attributes.add(attribute.id() + " " + num(attribute.amount()) + " " + attribute.operation());
            }
            row(out, List.of(
                    food.id(), food.kind(), food.mod(), food.nameKey(),
                    str(food.nutrition()), num(food.saturationModifier()), num(food.saturationPoints()),
                    str(food.alwaysEdible()), num(food.eatSeconds()), String.valueOf(food.maxStack()),
                    str(food.remainder()), effects(food.effects()),
                    flora.source(), str(flora.target()), str(flora.file()), str(flora.priority()),
                    String.valueOf(flora.override()), String.valueOf(flora.hungerOnly()),
                    str(flora.duration()), str(flora.nutrition()), num(flora.saturationPoints()),
                    num(flora.healthBonus()), effects(flora.effects()), String.join("; ", attributes),
                    String.join("; ", food.synergies()), String.valueOf(food.recipes().size()),
                    primary == null ? "" : primary.id(),
                    primary == null ? "" : primary.type(),
                    primary == null ? "" : String.valueOf(primary.count()),
                    primary == null ? "" : ingredients(primary.ingredients()),
                    food.feast() == null ? "" : feast(food.feast())));
        }
        return out.toString();
    }

    private static void row(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            out.append(csvCell(cells.get(i)));
        }
        out.append("\r\n");
    }

    /** Quoted when it holds a comma, a quote or a line break; inner quotes doubled. */
    static String csvCell(String value) {
        if (value == null) return "";
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    // -------------------------------------------------------------------------
    // NUMBERS
    // -------------------------------------------------------------------------

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** At most three decimals, no trailing zeros, Locale-independent; empty for null. */
    static String num(Number value) {
        if (value == null) return "";
        double d = value.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d)) return String.valueOf((long) d);
        String text = String.format(Locale.ROOT, "%.3f", d);
        while (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            default -> String.valueOf(level);
        };
    }
}
