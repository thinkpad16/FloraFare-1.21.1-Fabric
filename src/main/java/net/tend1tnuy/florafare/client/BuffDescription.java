package net.tend1tnuy.florafare.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import net.tend1tnuy.florafare.client.tooltip.FoodValuesTooltip;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.food.AttributeOperations;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.util.DurationFormat;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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
 * <p>Client-only: it reads the local player's discovery set. The pure formatters at the
 * bottom (durations, amounts, attribute-name resolution) are kept free of registries and
 * of the running game so the unit tests can drive them without a bootstrap.
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
     * while a {@code /florafare buff give} unlock — and a drunk {@code potion:} target,
     * which has no item id of its own — records the target itself.
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
    // RESOLUTION
    // -------------------------------------------------------------------------

    /**
     * The config a stack shows: its food config, or — for a brewed potion, which is not
     * food and so never resolves through {@link FoodBuffManager#getConfig} — the
     * {@code potion:} entry for its base potion.
     *
     * <p>Potion targets have worked since the eating rework (drinking one grants the
     * entry's buff), but every display still asked {@code getConfig} alone, so the
     * bottle's tooltip and EMI panel said nothing about a buff it really grants.
     */
    @Nullable
    public static FoodBuffData configFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        FoodBuffData data = FoodBuffManager.getConfig(stack);
        return data != null ? data : FoodBuffManager.getPotionConfig(stack);
    }

    /** Whether this entry targets a brewed potion, which moves no hunger at all. */
    public static boolean isPotionTarget(FoodBuffData data) {
        return data != null && data.target().startsWith(FoodBuffManager.POTION_PREFIX);
    }

    /** Whether this entry was stamped onto one stack by {@code /florafare setbuff}. */
    public static boolean isStackBuff(FoodBuffData data) {
        return data != null && data.target().startsWith(STACK_PREFIX);
    }

    /** The target prefix {@code /florafare setbuff} gives its per-stack entries. */
    private static final String STACK_PREFIX = "stack:";

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
        lines.add(header(data));

        if (!isDiscovered(stack, data)) {
            lines.addAll(lockedLines());
            return wrapToScreen(lines);
        }

        lines.addAll(bodyLines(stack, data, true));
        return wrapToScreen(lines);
    }

    /**
     * The same block, collapsed to its heading plus a "hold Shift" hint.
     *
     * <p>Used when {@code collapseBuffTooltips} is on and Shift is not held. The shift
     * state is decided by the caller rather than read here: this class is loaded by the
     * formatter tests, which run with no window for {@code Screen.hasShiftDown()} to
     * query.
     *
     * <p>An undiscovered food is returned in full — the placeholder is already short,
     * and it is the line that tells the player the dish can be unlocked at all.
     */
    public static List<Text> collapsedItemTooltip(ItemStack stack, FoodBuffData data) {
        List<Text> lines = new ArrayList<>();
        lines.add(header(data));

        if (!isDiscovered(stack, data)) {
            lines.addAll(lockedLines());
            return wrapToScreen(lines);
        }

        lines.add(indent(Text.translatable("tooltip.florafare.buff.expand_hint")
                .formatted(Formatting.DARK_GRAY, Formatting.ITALIC)));
        return wrapToScreen(lines);
    }

    /**
     * The block appended to a managed food's item tooltip: a heading, then either the
     * full breakdown or the locked placeholder.
     */
    public static List<Text> foodTooltip(ItemStack stack, FoodBuffData data) {
        List<Text> lines = new ArrayList<>();
        lines.add(header(data));

        if (!isDiscovered(stack, data)) {
            lines.addAll(lockedLines());
            return lines;
        }

        lines.addAll(foodBuffLines(stack, data));
        return lines;
    }

    /**
     * Everything a food's buff grants, without the heading and without the discovery
     * gate in front of it.
     *
     * <p>Split out of {@link #foodTooltip} for the EMI panel, which has to measure the
     * unlocked block while the food is still locked, or the panel it measured for two
     * grey placeholder lines would clip the full breakdown the moment the player eats
     * the dish.
     */
    public static List<Text> foodBuffLines(ItemStack stack, FoodBuffData data) {
        return bodyLines(stack, data, false);
    }

    /**
     * The breakdown itself, in the order every surface prints it: what kind of entry
     * this is, how long it lasts, what it does to hunger, then modifiers and effects.
     *
     * @param iconRow whether hunger may be drawn as {@link FoodValuesTooltip}'s icon row
     *                (a real tooltip) instead of spelled out (EMI, flat text)
     */
    private static List<Text> bodyLines(ItemStack stack, FoodBuffData data, boolean iconRow) {
        List<Text> lines = new ArrayList<>();

        // A /florafare setbuff stack carries a buff of its own, synced to clients now, and
        // nothing on the tooltip used to say that this stack is not like the others.
        if (isStackBuff(data)) {
            lines.add(indent(Text.translatable("tooltip.florafare.buff.stack_buff")
                    .formatted(Formatting.LIGHT_PURPLE)));
        }

        // A hunger-only entry (duration <= 0) takes no slot and grants no buff, so it has
        // no length to show — but saying nothing left the player guessing whether the
        // missing line was a bug. It is said outright instead.
        if (data.isHungerOnly()) {
            lines.add(indent(Text.translatable("tooltip.florafare.buff.hunger_only")
                    .formatted(Formatting.DARK_GRAY, Formatting.ITALIC)));
        } else {
            lines.add(indent(durationLine(data.duration())));
        }

        lines.addAll(hungerLines(stack, data, iconRow));

        lines.addAll(effectBlock(data.healthBonus(), data.attributes(), data.effects()));
        if (data.alwaysEdible()) {
            lines.add(indent(Text.translatable("tooltip.florafare.buff.always_edible")
                    .formatted(Formatting.DARK_AQUA)));
        }
        return lines;
    }

    /**
     * What a bite does to hunger. Nothing for a potion target — drinking one moves no
     * hunger whatever the entry says — and nothing for an entry that restores nothing.
     *
     * <p>A food that <em>drains</em> hunger is always spelled out, even on a tooltip and
     * even with AppleSkin installed: the icon row has no way to draw a negative, and
     * AppleSkin is handed a clamped zero for one (see {@code AppleSkinIntegration}), so
     * the sentence is the only place the drain is visible at all.
     */
    private static List<Text> hungerLines(ItemStack stack, FoodBuffData data, boolean iconRow) {
        if (isPotionTarget(data)) return List.of();

        int nutrition = data.effectiveNutrition(stack);
        float saturation = data.effectiveSaturationPoints(stack);
        if (nutrition == 0 && saturation == 0) return List.of();

        if (iconRow && nutrition >= 0) {
            FoodValuesTooltip foodValues = FoodValuesTooltip.of(stack, data);
            if (foodValues != null) return List.of(foodValues.asText());
            // AppleSkin draws the row itself: say nothing, as before.
            if (FoodValuesTooltip.appleSkinOwnsFoodValues()) return List.of();
        }
        return List.of(indent(nutritionLine(stack, data)));
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

    /**
     * The max-health attribute, which is what a config's {@code health_bonus} is applied
     * as. Spelled out rather than read off {@code EntityAttributes}: this class is loaded
     * by the formatter tests, which run without a bootstrapped registry.
     */
    public static final Identifier MAX_HEALTH_ID =
            Identifier.of("minecraft", "generic.max_health");

    /** Health bonus, attribute modifiers and status effects — shared by foods and synergies. */
    public static List<Text> effectBlock(double healthBonus,
                                         List<FoodBuffData.AttributeData> attributes,
                                         List<FoodBuffData.EffectData> effects) {
        List<Text> lines = new ArrayList<>();

        boolean hasAttributes = attributes != null && !attributes.isEmpty();
        if (healthBonus != 0 || hasAttributes) {
            lines.add(Text.translatable("tooltip.florafare.buff.attributes")
                    .formatted(Formatting.GRAY));

            // The health bonus goes under this heading, as the first modifier, because
            // that is exactly what it is: PlayerFoodComponent applies it as a max-health
            // modifier that is removed when the buff ends. It used to be printed above
            // the heading, right under the duration, which put the one temporary effect
            // that looks permanent in the only place on the tooltip that isn't marked
            // "while active" — so "+30 max health" read as a permanent gain.
            if (healthBonus != 0) {
                lines.add(bullet(Text.translatable("tooltip.florafare.buff.entry",
                                attributeName(MAX_HEALTH_ID), signed(healthBonus))
                        // Kept red rather than the blue every other modifier gets: it is
                        // hearts, and a player scanning the block should find it at once.
                        .formatted(healthBonus > 0 ? Formatting.RED : Formatting.DARK_RED)));
            }

            if (hasAttributes) {
                for (FoodBuffData.AttributeData attr : attributes) {
                    lines.add(bullet(Text.translatable("tooltip.florafare.buff.entry",
                                    attributeName(attr.attributeId()),
                                    formatAmount(attr.amount(), attr.operation()))
                            .formatted(attributeFormatting(attr.attributeId(), attr.amount()))));
                }
            }
        }

        if (effects != null && !effects.isEmpty()) {
            // Harmful effects under their own heading. Everything used to be listed blue
            // under "Grants:", so a dish that poisons you advertised the Poison as one
            // more perk — exactly backwards, and the one line a player most needs to see.
            List<FoodBuffData.EffectData> granted = new ArrayList<>();
            List<FoodBuffData.EffectData> inflicted = new ArrayList<>();
            for (FoodBuffData.EffectData effect : effects) {
                (effectCategory(effect.id()) == StatusEffectCategory.HARMFUL ? inflicted : granted)
                        .add(effect);
            }
            appendEffects(lines, "tooltip.florafare.buff.effects", granted);
            appendEffects(lines, "tooltip.florafare.buff.inflicts", inflicted);
        }

        return lines;
    }

    private static void appendEffects(List<Text> lines, String headingKey,
                                      List<FoodBuffData.EffectData> effects) {
        if (effects.isEmpty()) return;
        lines.add(Text.translatable(headingKey).formatted(Formatting.GRAY));
        for (FoodBuffData.EffectData effect : effects) {
            lines.add(bullet(Text.translatable("tooltip.florafare.buff.entry",
                            effectLabel(effect), formatDuration(effect.duration()))
                    .formatted(effectFormatting(effectCategory(effect.id())))));
        }
    }

    /** An effect's name with its level numeral, e.g. "Regeneration II". */
    public static MutableText effectLabel(FoodBuffData.EffectData effect) {
        MutableText name = effectName(effect.id()).copy();
        String level = amplifierNumeral(effect.amplifier());
        if (!level.isEmpty()) name.append(level);
        return name;
    }

    // -------------------------------------------------------------------------
    // CATEGORIES AND COLOURS
    // -------------------------------------------------------------------------

    /**
     * Whether an effect helps, hurts or neither. An id the registry does not know is
     * {@code NEUTRAL}: it will not be applied at all, so it is neither promise nor threat.
     * The lookup is guarded because the formatter tests load this class with no registry.
     */
    public static StatusEffectCategory effectCategory(Identifier effectId) {
        try {
            StatusEffect effect = Registries.STATUS_EFFECT.get(effectId);
            return effect == null ? StatusEffectCategory.NEUTRAL : effect.getCategory();
        } catch (Throwable noRegistry) {
            return StatusEffectCategory.NEUTRAL;
        }
    }

    /**
     * Tooltip colour for an effect: blue for a benefit (vanilla's own colour for one),
     * red for harm, gold for the neutral ones that are neither — Glowing, Bad Omen's
     * relatives in modded packs — so they do not read as either.
     */
    public static Formatting effectFormatting(StatusEffectCategory category) {
        return switch (category) {
            case HARMFUL -> Formatting.RED;
            case NEUTRAL -> Formatting.GOLD;
            default      -> Formatting.BLUE;
        };
    }

    /**
     * Tooltip colour for a modifier line, through the attribute's own category where the
     * registry has it. That is vanilla's rule, and it gets "lower is better" attributes
     * right: a negative Damage Taken or Fall Damage Multiplier is good news and comes out
     * blue, where a plain sign check painted it red. Without a registry entry the sign
     * decides.
     */
    public static Formatting attributeFormatting(Identifier attributeId, double amount) {
        try {
            EntityAttribute attribute = Registries.ATTRIBUTE.get(attributeId);
            if (attribute != null) return attribute.getFormatting(amount > 0);
        } catch (Throwable noRegistry) {
            // Fall through to the sign.
        }
        return amount >= 0 ? Formatting.BLUE : Formatting.RED;
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
        return wrapToWidth(lines, budget);
    }

    /**
     * The same break-up against an explicit pixel budget, for a surface that knows its
     * own width. EMI's panels do: they draw their lines one by one inside a box of a
     * fixed width, and a line wider than the box simply ran off its right-hand edge.
     */
    public static List<Text> wrapToWidth(List<Text> lines, int budget) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || budget <= 0) return lines;

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

    /** "When eaten", or "When drunk" for a potion target, which nobody eats. */
    public static Text header(FoodBuffData data) {
        if (!isPotionTarget(data)) return header();
        return Text.translatable("tooltip.florafare.buff.header_potion")
                .formatted(Formatting.GOLD);
    }

    public static Text durationLine(int ticks) {
        return Text.translatable("tooltip.florafare.buff.duration", formatDuration(ticks))
                .formatted(Formatting.GRAY);
    }

    /**
     * Nutrition plus the saturation it actually restores. The datapack field is a
     * vanilla saturation <em>modifier</em>, so a raw "0.4" there means 1.6 saturation
     * on a nutrition-2 berry — showing the modifier (as this used to) told the player
     * nothing they could act on.
     */
    public static Text nutritionLine(int nutrition, float saturationModifier) {
        return nutritionPointsLine(nutrition, FoodBuffData.saturationPoints(nutrition, saturationModifier));
    }

    /**
     * The same line for what a config does to one particular item — the form every
     * surface should use, since an entry that leaves nutrition or saturation to the item
     * only has a number once it is resolved against that item.
     */
    public static Text nutritionLine(ItemStack stack, FoodBuffData data) {
        return nutritionPointsLine(data.effectiveNutrition(stack),
                data.effectiveSaturationPoints(stack));
    }

    /**
     * "Restores 3 hunger, 3.6 saturation", or for a food that takes hunger away (a
     * negative nutrition, allowed on purpose) "Drains 2 hunger and 1.6 saturation" —
     * the line used to print "Restores -2 hunger", which reads as a typo.
     */
    private static Text nutritionPointsLine(int nutrition, float saturationPoints) {
        if (nutrition < 0) {
            if (saturationPoints < 0) {
                return Text.translatable("tooltip.florafare.buff.nutrition_drain_saturation",
                                String.valueOf(-nutrition), trimFloat(-saturationPoints))
                        .formatted(Formatting.RED);
            }
            return Text.translatable("tooltip.florafare.buff.nutrition_drain",
                    String.valueOf(-nutrition)).formatted(Formatting.RED);
        }
        return Text.translatable("tooltip.florafare.buff.nutrition",
                        String.valueOf(nutrition), trimFloat(saturationPoints))
                .formatted(Formatting.GRAY);
    }

    // -------------------------------------------------------------------------
    // VALUE FORMATTING (pure)
    // -------------------------------------------------------------------------

    /** A tick count as {@code mm:ss}, {@code h:mm:ss} or {@code ∞}; see {@link DurationFormat}. */
    public static String formatDuration(int ticks) {
        return DurationFormat.format(ticks);
    }

    /** @deprecated callers move to {@link #formatDuration}; kept until they have. */
    @Deprecated
    public static String mmss(int ticks) {
        return formatDuration(ticks);
    }

    /**
     * The minus sign every signed number uses. A real minus (U+2212) rather than the
     * hyphen: it is as wide as the plus it stands opposite, so a column of "+2" and "−2"
     * lines up, and it reads as a sign rather than as a dash.
     */
    public static final String MINUS = "−";

    /** The multiplication sign for {@code add_multiplied_total}; Latin-1, so in every font. */
    public static final String TIMES = "×";

    /**
     * An attribute modifier's amount, rendered the way its operation means it:
     * <ul>
     *   <li>{@code add_value} — a flat amount, "+2";</li>
     *   <li>{@code add_multiplied_base} — a share of the attribute's base value, added
     *       to the total: "+10%";</li>
     *   <li>{@code add_multiplied_total} — a factor the whole final value is multiplied
     *       by: "×1.1". Different from the base form in kind, not only in size — two
     *       "+10% total" lines compound to +21% — so it gets a different symbol rather
     *       than a different word, and needs no translation.</li>
     * </ul>
     *
     * <p>The operation goes through {@link AttributeOperations}, the parser the loaders
     * and the runtime share. This used to test {@code contains("multiplied")}, which is
     * case-sensitive and knows no aliases: a pack writing {@code ADD_MULTIPLIED_BASE} or
     * the pre-1.20.5 {@code multiply_base} got a correct percentage in game and a flat
     * "+0.1" on every tooltip. An operation nothing recognises (only possible for a
     * config built in code; the loaders refuse one) is shown flat.
     */
    public static String formatAmount(double amount, String operation) {
        String canonical = AttributeOperations.canonicalize(operation);
        if ("add_multiplied_base".equals(canonical)) {
            return signedNumber(amount * 100.0) + "%";
        }
        if ("add_multiplied_total".equals(canonical)) {
            return TIMES + plainNumber(1.0 + amount);
        }
        return signed(amount);
    }

    /** A signed number with no trailing {@code .0}, and a real minus sign. */
    public static String signed(double value) {
        return signedNumber(value);
    }

    private static String signedNumber(double value) {
        String digits = trimFloat((float) Math.abs(value));
        if (digits.equals("0")) return "0";
        return (value > 0 ? "+" : MINUS) + digits;
    }

    /** An unsigned factor, with a real minus should it ever be negative. */
    private static String plainNumber(double value) {
        String digits = trimFloat((float) Math.abs(value));
        return value < 0 && !digits.equals("0") ? MINUS + digits : digits;
    }

    /** Drops a trailing {@code .0} so whole numbers read as "4", not "4.0". */
    public static String trimFloat(float value) {
        float rounded = Math.round(value * 100.0f) / 100.0f;
        if (rounded == Math.rint(rounded) && !Float.isInfinite(rounded)) {
            return String.valueOf((int) rounded);
        }
        return String.valueOf(rounded);
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

    // -------------------------------------------------------------------------
    // NAMES
    // -------------------------------------------------------------------------

    /** Prefix of every Florafare attribute label key. */
    static final String ATTRIBUTE_KEY_PREFIX = "florafare.attribute.";

    /**
     * Display name for an attribute.
     *
     * <p>This used to look the attribute up by its <em>path</em> alone, so any two mods'
     * attributes sharing a path shared a label: {@code critical_strike:chance} and every
     * other {@code *:chance} read "Bonus Chance", and a modded {@code *:fire} or
     * {@code *:haste} borrowed whichever of Florafare's labels had that word. The order
     * now (see {@link #attributeNameKey}) is Florafare's label for the full id, then —
     * vanilla ids only — Florafare's older path-keyed label, then the attribute's own
     * translation from the registry, and last a readable {@code "namespace: path"}.
     */
    public static Text attributeName(Identifier attributeId) {
        Language language = Language.getInstance();
        String key = attributeNameKey(attributeId, language::hasTranslation,
                registryTranslationKey(attributeId));
        return key != null ? Text.translatable(key) : Text.literal(fallbackAttributeName(attributeId));
    }

    /**
     * The translation key to show an attribute under, or null when nothing translates it.
     * Pure, so the ordering can be tested without a game: {@code hasTranslation} answers
     * for the current language, {@code registryKey} is the registered attribute's own
     * {@code getTranslationKey()} (null when it is not registered).
     *
     * <ol>
     *   <li>{@code florafare.attribute.<namespace>.<path>} — a Florafare override for
     *       exactly this attribute, which is how modded attributes get a label (and a
     *       Ukrainian one) without colliding with each other;</li>
     *   <li>for {@code minecraft:} only, the path-keyed {@code florafare.attribute.<path>}
     *       ({@code generic.} dropped) the mod has always shipped — short labels like
     *       "Speed", kept working rather than renamed;</li>
     *   <li>the attribute's own key, whatever its mod chose;</li>
     *   <li>the conventional {@code attribute.name.<namespace>.<path>} and vanilla's
     *       {@code attribute.name.<path>}, for when the registry could not be asked.</li>
     * </ol>
     */
    @Nullable
    static String attributeNameKey(Identifier id, Predicate<String> hasTranslation,
                                   @Nullable String registryKey) {
        String namespace = id.getNamespace();
        String path = id.getPath();

        String fullKey = ATTRIBUTE_KEY_PREFIX + namespace + "." + path;
        if (hasTranslation.test(fullKey)) return fullKey;

        boolean vanilla = Identifier.DEFAULT_NAMESPACE.equals(namespace);
        if (vanilla) {
            String legacyKey = ATTRIBUTE_KEY_PREFIX + stripGeneric(path);
            if (hasTranslation.test(legacyKey)) return legacyKey;
        }

        if (registryKey != null && hasTranslation.test(registryKey)) return registryKey;

        String conventional = "attribute.name." + namespace + "." + path;
        if (hasTranslation.test(conventional)) return conventional;
        if (vanilla) {
            String vanillaKey = "attribute.name." + path;
            if (hasTranslation.test(vanillaKey)) return vanillaKey;
        }
        return null;
    }

    /**
     * What an attribute nothing translates reads as: its path made readable, and for a
     * modded one the namespace in front of it, so "critical_strike: chance" says whose
     * chance it is instead of a bare "chance".
     */
    static String fallbackAttributeName(Identifier id) {
        String path = id.getPath();
        if (Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace())) {
            int dot = path.indexOf('.');
            // generic.max_health, player.block_break_speed: the group prefix is noise.
            return readable(dot >= 0 ? path.substring(dot + 1) : path);
        }
        return id.getNamespace() + ": " + readable(path);
    }

    private static String readable(String path) {
        return path.replace('_', ' ').replace('.', ' ').replace('/', ' ');
    }

    private static String stripGeneric(String path) {
        return path.startsWith("generic.") ? path.substring("generic.".length()) : path;
    }

    /** The registered attribute's own translation key, or null; guarded for the tests. */
    @Nullable
    private static String registryTranslationKey(Identifier attributeId) {
        try {
            EntityAttribute attribute = Registries.ATTRIBUTE.get(attributeId);
            return attribute == null ? null : attribute.getTranslationKey();
        } catch (Throwable noRegistry) {
            return null;
        }
    }

    /**
     * Display name for a status effect: the effect's own translation key from the
     * registry, then the conventional {@code effect.<namespace>.<path>}, and finally its
     * path made readable.
     */
    public static Text effectName(Identifier effectId) {
        Language language = Language.getInstance();
        try {
            StatusEffect effect = Registries.STATUS_EFFECT.get(effectId);
            if (effect != null && language.hasTranslation(effect.getTranslationKey())) {
                return Text.translatable(effect.getTranslationKey());
            }
        } catch (Throwable noRegistry) {
            // Fall through to the conventional key.
        }
        String key = "effect." + effectId.getNamespace() + "." + effectId.getPath();
        if (language.hasTranslation(key)) return Text.translatable(key);
        return Text.literal(readable(effectId.getPath()));
    }

    /** Display name for a synergy, falling back to its id when untranslated. */
    public static Text synergyName(String synergyId) {
        String key = "synergy.florafare." + synergyId.replace(":", ".");
        if (Language.getInstance().hasTranslation(key)) return Text.translatable(key);
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
