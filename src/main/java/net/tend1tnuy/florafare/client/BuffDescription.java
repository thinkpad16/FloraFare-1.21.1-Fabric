package net.tend1tnuy.florafare.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.client.tooltip.FoodValuesTooltip;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodSynergyData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The single place that turns a {@link FoodBuffData} or {@link FoodSynergyData} into
 * display text, and the single place that answers "has this player discovered it yet".
 *
 * <p>Four surfaces show the same buff — the item tooltip, the HUD hover tooltip, the
 * journal's detail page, and the EMI recipe panel — and before this they each formatted
 * numbers their own way, so the same golden carrot could read "0.1" in one place and
 * "+10%" in another. Everything funnels through here now.
 *
 * <p><b>Discovery gating.</b> A food's buff is hidden until the player has eaten it at
 * least once; after that everything about it is visible everywhere. That rule already
 * governed the journal and the AppleSkin overlay, and it now governs the tooltip and
 * EMI too, so a player can't read a dish's properties off a recipe screen to sidestep
 * the discovery mechanic entirely.
 *
 * <p>Client-only: it reads the local player's discovery set.
 */
public final class BuffDescription {

    private BuffDescription() {}

    // -------------------------------------------------------------------------
    // DISCOVERY
    // -------------------------------------------------------------------------

    /** The discovery set of the client's own player, or empty when there is no player. */
    private static Set<String> discoveredFoods() {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return Set.of();
        return ((IFoodComponentProvider) player).florafare$getFoodComponent().getDiscoveredFoods();
    }

    private static Set<String> discoveredSynergies() {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return Set.of();
        return ((IFoodComponentProvider) player).florafare$getFoodComponent().getDiscoveredSynergies();
    }

    /**
     * Whether this player has eaten the given food. Both the concrete item id and the
     * config's target are accepted, because a tag/namespace entry is recorded per item
     * while a {@code /florafare buff give} unlock records the target itself.
     */
    public static boolean isDiscovered(ItemStack stack, FoodBuffData data) {
        Set<String> discovered = discoveredFoods();
        if (discovered.isEmpty()) return false;
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        return discovered.contains(itemId)
                || (data != null && discovered.contains(data.target()));
    }

    public static boolean isSynergyDiscovered(FoodSynergyData synergy) {
        return discoveredSynergies().contains(synergy.id());
    }

    // -------------------------------------------------------------------------
    // TOOLTIP BLOCKS
    // -------------------------------------------------------------------------

    /**
     * The block Florafare puts on a real item tooltip.
     *
     * <p>Differs from {@link #foodTooltip} in two ways, both of which only make sense on
     * an actual tooltip. The hunger and saturation are an icon row rather than a sentence
     * — see {@link FoodValuesTooltip}, and note that it draws nothing at all when
     * AppleSkin is installed, since AppleSkin puts the same row there already. And every
     * line is held to a width that cannot push the tooltip off the edge of the screen.
     *
     * <p>{@link #foodTooltip} stays as it is for surfaces that can only take flat text —
     * EMI's recipe panel draws its lines one by one and has nowhere to put a component.
     */
    public static List<Text> itemTooltip(ItemStack stack, FoodBuffData data) {
        List<Text> lines = new ArrayList<>();
        lines.add(header());

        if (!isDiscovered(stack, data)) {
            lines.addAll(lockedLines());
            return wrapToScreen(lines);
        }

        lines.add(indent(durationLine(data.duration())));

        FoodValuesTooltip foodValues = FoodValuesTooltip.of(data);
        if (foodValues != null) {
            lines.add(foodValues.asText());
        } else if (data.nutrition() < 0 && !FoodValuesTooltip.appleSkinOwnsFoodValues()) {
            // A food that takes hunger away rather than restoring it has nothing the icon
            // row can express, so that one case is still spelled out.
            lines.add(indent(nutritionLine(data.nutrition(), data.saturation())));
        }

        lines.addAll(effectBlock(data.healthBonus(), data.attributes(), data.effects()));
        if (data.alwaysEdible()) {
            lines.add(indent(Text.translatable("tooltip.florafare.buff.always_edible")
                    .formatted(Formatting.DARK_AQUA)));
        }
        return wrapToScreen(lines);
    }

    /**
     * The block appended to a managed food's item tooltip: a heading, then either the
     * full breakdown or the locked placeholder.
     */
    public static List<Text> foodTooltip(ItemStack stack, FoodBuffData data) {
        List<Text> lines = new ArrayList<>();
        lines.add(header());

        if (!isDiscovered(stack, data)) {
            lines.addAll(lockedLines());
            return lines;
        }

        lines.add(indent(durationLine(data.duration())));
        if (data.nutrition() != 0) {
            lines.add(indent(nutritionLine(data.nutrition(), data.saturation())));
        }
        lines.addAll(effectBlock(data.healthBonus(), data.attributes(), data.effects()));
        if (data.alwaysEdible()) {
            lines.add(indent(Text.translatable("tooltip.florafare.buff.always_edible")
                    .formatted(Formatting.DARK_AQUA)));
        }
        return lines;
    }

    /** The "you haven't eaten this yet" placeholder, used by every surface. */
    public static List<Text> lockedLines() {
        List<Text> lines = new ArrayList<>();
        lines.add(indent(Text.translatable("tooltip.florafare.buff.locked")
                .formatted(Formatting.DARK_GRAY, Formatting.ITALIC)));
        lines.add(indent(Text.translatable("tooltip.florafare.buff.locked_hint")
                .formatted(Formatting.DARK_GRAY, Formatting.ITALIC)));
        return lines;
    }

    /** Everything a synergy grants, for the journal and the EMI panel. */
    public static List<Text> synergyLines(FoodSynergyData synergy) {
        List<Text> lines = new ArrayList<>();
        lines.add(indent(Text.translatable("tooltip.florafare.buff.duration",
                        Text.translatable("gui.florafare.journal.duration_dynamic"))
                .formatted(Formatting.GRAY)));
        lines.addAll(effectBlock(synergy.healthBonus(), synergy.attributes(), synergy.effects()));
        return lines;
    }

    /** Health bonus, attribute modifiers and status effects — shared by foods and synergies. */
    public static List<Text> effectBlock(double healthBonus,
                                         List<FoodBuffData.AttributeData> attributes,
                                         List<FoodBuffData.EffectData> effects) {
        List<Text> lines = new ArrayList<>();

        if (healthBonus != 0) {
            lines.add(indent(Text.translatable("tooltip.florafare.buff.health",
                            signed(healthBonus))
                    .formatted(healthBonus > 0 ? Formatting.RED : Formatting.DARK_RED)));
        }

        if (attributes != null && !attributes.isEmpty()) {
            lines.add(Text.translatable("tooltip.florafare.buff.attributes")
                    .formatted(Formatting.GRAY));
            for (FoodBuffData.AttributeData attr : attributes) {
                lines.add(bullet(Text.translatable("tooltip.florafare.buff.entry",
                                attributeName(attr.attributeId()),
                                formatAmount(attr.amount(), attr.operation()))
                        .formatted(attr.amount() >= 0 ? Formatting.BLUE : Formatting.RED)));
            }
        }

        if (effects != null && !effects.isEmpty()) {
            lines.add(Text.translatable("tooltip.florafare.buff.effects")
                    .formatted(Formatting.GRAY));
            for (FoodBuffData.EffectData effect : effects) {
                MutableText name = effectName(effect.id()).copy();
                String level = amplifierNumeral(effect.amplifier());
                if (!level.isEmpty()) name.append(level);
                lines.add(bullet(Text.translatable("tooltip.florafare.buff.entry",
                                name, mmss(effect.duration()))
                        .formatted(Formatting.BLUE)));
            }
        }

        return lines;
    }

    // -------------------------------------------------------------------------
    // WIDTH
    // -------------------------------------------------------------------------

    /**
     * Breaks any line too wide to belong on a tooltip across several lines.
     *
     * <p>A tooltip is exactly as wide as its widest line, and vanilla neither wraps it nor
     * shrinks it — once it no longer fits the screen it is simply clamped against the left
     * edge and its right-hand side runs off into nothing, taking the frame with it. So the
     * mod has to not produce such a line in the first place. The long one used to be the
     * hunger/saturation sentence, which is now an icon row; what is left is translated
     * text, and a modded effect name or a wordy translation can still get there.
     *
     * <p>The budget is a share of the window rather than a fixed pixel count, because what
     * fits depends entirely on the GUI scale the player is using — at scale 4 the whole
     * screen is only a few hundred pixels wide, which is exactly where this goes wrong.
     * Half of it leaves room for the item name, any lore, and the tooltip's own padding.
     */
    public static List<Text> wrapToScreen(List<Text> lines) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) return lines;

        int budget = MAX_TOOLTIP_WIDTH;
        if (client.getWindow() != null) {
            budget = Math.max(MIN_TOOLTIP_WIDTH,
                    Math.min(MAX_TOOLTIP_WIDTH, client.getWindow().getScaledWidth() / 2));
        }

        TextRenderer textRenderer = client.textRenderer;
        List<Text> wrapped = new ArrayList<>(lines.size());
        for (Text line : lines) {
            // The icon row measures as empty text and is laid out by its component, so it
            // must pass through untouched — wrapping it would turn it into a blank line.
            if (line instanceof FoodValuesTooltip.Carrier || textRenderer.getWidth(line) <= budget) {
                wrapped.add(line);
                continue;
            }
            boolean first = true;
            for (StringVisitable part : textRenderer.getTextHandler()
                    .wrapLines(line, budget, Style.EMPTY)) {
                Text rebuilt = rebuild(part);
                // Continuations are pushed in a little so a wrapped bullet still reads as
                // one entry rather than as two.
                wrapped.add(first ? rebuilt : Text.literal("    ").append(rebuilt));
                first = false;
            }
        }
        return wrapped;
    }

    /** Hard ceiling, for a window wide enough that half of it would still be absurd. */
    private static final int MAX_TOOLTIP_WIDTH = 220;
    /** Floor, so a tiny window produces narrow lines rather than one character per line. */
    private static final int MIN_TOOLTIP_WIDTH = 100;

    /**
     * Rebuilds a wrapped fragment as a {@link Text}, keeping the per-character styling the
     * original line had — the alternative, flattening it to a string and re-colouring it,
     * would lose the two tones a bullet line is made of.
     */
    private static Text rebuild(StringVisitable fragment) {
        MutableText result = Text.empty();
        fragment.visit((style, literal) -> {
            result.append(Text.literal(literal).setStyle(style));
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return result;
    }

    // -------------------------------------------------------------------------
    // SINGLE LINES
    // -------------------------------------------------------------------------

    public static Text header() {
        return Text.translatable("tooltip.florafare.buff.header")
                .formatted(Formatting.GOLD);
    }

    public static Text durationLine(int ticks) {
        return Text.translatable("tooltip.florafare.buff.duration", mmss(ticks))
                .formatted(Formatting.GRAY);
    }

    /**
     * Nutrition plus the saturation it actually restores. The datapack field is a
     * vanilla saturation <em>modifier</em>, so a raw "0.4" there means 1.6 saturation
     * on a nutrition-2 berry — showing the modifier (as this used to) told the player
     * nothing they could act on.
     */
    public static Text nutritionLine(int nutrition, float saturationModifier) {
        float saturation = nutrition * saturationModifier * 2.0f;
        return Text.translatable("tooltip.florafare.buff.nutrition",
                        String.valueOf(nutrition), trimFloat(saturation))
                .formatted(Formatting.GRAY);
    }

    // -------------------------------------------------------------------------
    // VALUE FORMATTING
    // -------------------------------------------------------------------------

    /** {@code mm:ss} for a tick count. */
    public static String mmss(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * An attribute modifier's amount, rendered the way the operation means it: the two
     * multiplied operations are fractions of a total and read as percentages, while
     * {@code add_value} is a flat amount.
     */
    public static String formatAmount(double amount, String operation) {
        if (operation != null && operation.contains("multiplied")) {
            return (amount > 0 ? "+" : "") + trimFloat((float) (amount * 100.0)) + "%";
        }
        return signed(amount);
    }

    /** A signed number with no trailing {@code .0}. */
    public static String signed(double value) {
        return (value > 0 ? "+" : "") + trimFloat((float) value);
    }

    /** Drops a trailing {@code .0} so whole numbers read as "4", not "4.0". */
    public static String trimFloat(float value) {
        if (value == Math.rint(value) && !Float.isInfinite(value)) {
            return String.valueOf((int) value);
        }
        return String.valueOf(Math.round(value * 100.0f) / 100.0f);
    }

    /**
     * Roman-numeral suffix for a potion amplifier. Amplifier 0 is level I, which
     * vanilla leaves unwritten; Arabic numerals take over past VIII, same as vanilla.
     */
    public static String amplifierNumeral(int amplifier) {
        return switch (amplifier) {
            case 0 -> "";
            case 1 -> " II";
            case 2 -> " III";
            case 3 -> " IV";
            case 4 -> " V";
            case 5 -> " VI";
            case 6 -> " VII";
            case 7 -> " VIII";
            default -> " " + (amplifier + 1);
        };
    }

    /**
     * Display name for an attribute. Falls back to the mod's own key set (which covers
     * a long list of modded attributes) and finally to the raw path, so an unknown
     * attribute shows something readable rather than a missing translation key.
     */
    public static Text attributeName(Identifier attributeId) {
        String key = "florafare.attribute." + attributeId.getPath().replace("generic.", "");
        MutableText translated = Text.translatable(key);
        if (!translated.getString().equals(key)) return translated;

        // Vanilla's own key, e.g. attribute.name.generic.movement_speed
        MutableText vanilla = Text.translatable("attribute.name." + attributeId.getPath());
        if (!vanilla.getString().equals("attribute.name." + attributeId.getPath())) return vanilla;

        return Text.literal(attributeId.getPath().replace("generic.", "").replace('_', ' '));
    }

    /** Display name for a status effect, falling back to its id when untranslated. */
    public static Text effectName(Identifier effectId) {
        String key = "effect." + effectId.getNamespace() + "." + effectId.getPath();
        MutableText translated = Text.translatable(key);
        if (!translated.getString().equals(key)) return translated;
        return Text.literal(effectId.getPath().replace('_', ' '));
    }

    /** Display name for a synergy, falling back to its id when untranslated. */
    public static Text synergyName(String synergyId) {
        String key = "synergy.florafare." + synergyId.replace(":", ".");
        MutableText translated = Text.translatable(key);
        if (!translated.getString().equals(key)) return translated;
        return Text.literal(synergyId);
    }

    // -------------------------------------------------------------------------
    // LAYOUT HELPERS
    // -------------------------------------------------------------------------

    /** Indents a detail line one space under its heading. */
    public static Text indent(Text line) {
        return Text.literal(" ").append(line);
    }

    /** A bulleted detail line, indented under its section heading. */
    public static Text bullet(Text line) {
        return Text.literal("  • ").formatted(Formatting.DARK_GRAY).append(line);
    }
}
