package net.tend1tnuy.florafare.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.tend1tnuy.florafare.client.BuffDescription;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts a food's Florafare buff on its own tooltip, and takes off the vanilla effect
 * lines Florafare has replaced.
 *
 * <p>The buff block goes directly under the item name, where every food mod puts its
 * stats, and is gated on discovery: until the player has eaten the item once it shows
 * a placeholder instead. See {@link BuffDescription}.
 *
 * <p>The removal half used to delete <em>every</em> tooltip line between the name and
 * the advanced debug block, which took custom lore, enchantments and other mods' lines
 * with it. It now removes only what Florafare actually supersedes: the item's own
 * vanilla food effects, and only while {@code respectVanillaFoodEffects} is off, which
 * is exactly when those effects genuinely do not fire.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void florafare$decorateFoodTooltip(Item.TooltipContext context, PlayerEntity player,
                                               TooltipType type,
                                               CallbackInfoReturnable<List<Text>> cir) {
        ItemStack stack = (ItemStack) (Object) this;

        FoodBuffData data = FoodBuffManager.getConfig(stack);
        if (data == null) return;

        List<Text> tooltip = cir.getReturnValue();
        if (tooltip == null || tooltip.isEmpty()) return;

        if (FlorafareConfig.stripFoodTooltips) {
            florafare$removeSupersededEffectLines(stack, tooltip);
        }

        if (FlorafareConfig.showBuffTooltips) {
            // Index 1: straight after the item name, ahead of lore and the advanced
            // block, so the buff is the first thing read rather than the last.
            //
            // itemTooltip rather than foodTooltip: this is the one surface that can carry
            // a drawn component and the one that has a screen edge to run off, so it gets
            // the icon row for hunger/saturation and a width cap on everything else.
            //
            // Shift is read here, not inside BuffDescription: this mixin is client-only,
            // while that class is also exercised by the formatter tests, which have no
            // window to ask about the keyboard. Vanilla re-renders a tooltip every frame,
            // so pressing Shift expands the block with no further plumbing.
            boolean collapsed = FlorafareConfig.collapseBuffTooltips && !Screen.hasShiftDown();
            tooltip.addAll(1, collapsed
                    ? BuffDescription.collapsedItemTooltip(stack, data)
                    : BuffDescription.itemTooltip(stack, data));
        }
    }

    /**
     * Drops tooltip lines describing status effects that the item's vanilla
     * {@link FoodComponent} would grant but Florafare suppresses, so the tooltip stops
     * advertising effects that will not happen. Everything else on the tooltip — lore,
     * enchantments, the item's own equipped attribute modifiers, other mods' lines — is
     * left alone.
     *
     * <p>An effect is not one line. Vanilla renders a set of effects as the name lines
     * ("Slowness (0:30)"), and then — for every effect that carries attribute modifiers —
     * a blank line, a "When Applied:" heading, and one line per modifier ("-5% Speed").
     * Matching effect names alone removed the first part and left the rest standing:
     * a heading with no effect above it, quoting numbers for an effect that no longer
     * happens, on a dish whose properties the journal had not unlocked yet. Farmer's
     * Delight and every Delight-family mod put exactly that block on their food tooltips.
     *
     * <p>So the block is regenerated here through the same vanilla builder that produced
     * it and matched line for line. Regenerating beats pattern-matching the rendered
     * text: it needs no assumptions about wording, translation or number formatting, and
     * an attribute line that belongs to some other mod's feature reads identically but
     * will not be in the generated set.
     */
    @Unique
    private static void florafare$removeSupersededEffectLines(ItemStack stack, List<Text> tooltip) {
        // With this on, the vanilla effects do still fire alongside the buff, so their
        // lines are telling the truth and must stay.
        if (FlorafareConfig.respectVanillaFoodEffects) return;

        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        if (food == null || food.effects().isEmpty()) return;

        Set<String> supersededNames = new HashSet<>();
        List<StatusEffectInstance> effects = new ArrayList<>(food.effects().size());
        for (FoodComponent.StatusEffectEntry entry : food.effects()) {
            supersededNames.add(entry.effect().getEffectType().value().getName().getString());
            effects.add(entry.effect());
        }

        // The whole block as vanilla would draw it. The duration multiplier and tick rate
        // only affect the name lines' "(m:ss)", which the name rule below catches anyway
        // whatever they render as, so the defaults are fine.
        Set<String> supersededLines = new HashSet<>();
        PotionContentsComponent.buildTooltip(effects, line -> {
            String text = Formatting.strip(line.getString());
            if (text != null && !text.isBlank()) supersededLines.add(text.trim());
        }, 1.0f, 20.0f);

        // From index 1: the item name is never a candidate, however it is worded.
        int i = 1;
        while (i < tooltip.size()) {
            if (!florafare$isSuperseded(tooltip.get(i), supersededNames, supersededLines)) {
                i++;
                continue;
            }
            // The blank line vanilla puts in front of the "When Applied:" heading would
            // otherwise survive as a gap where the block used to be. Only the first line
            // of a run finds a blank in front of it, so one block loses one separator.
            if (i - 1 >= 1 && florafare$isBlank(tooltip.get(i - 1))) {
                tooltip.remove(--i);
            }
            tooltip.remove(i);
        }
    }

    @Unique
    private static boolean florafare$isBlank(Text line) {
        String text = Formatting.strip(line.getString());
        return text == null || text.isBlank();
    }

    /**
     * Whether this line belongs to a superseded effect: either an exact line from the
     * regenerated block, or a line leading with an effect's name.
     *
     * <p>The name rule stays as the second half because the name lines carry a duration
     * that depends on the world's tick rate and on whatever multiplier the mod drawing
     * them passed, so the regenerated copy is not guaranteed to match them character for
     * character. It is anchored at the start of the line, never matched inside it: an
     * effect line leads with its name, while an attribute line leads with its sign, and
     * a substring match took "+20% Movement Speed" along with the Speed effect it was
     * aimed at — and any lore that happened to use the word.
     */
    @Unique
    private static boolean florafare$isSuperseded(Text line, Set<String> supersededNames,
                                                  Set<String> supersededLines) {
        String text = Formatting.strip(line.getString());
        if (text == null) return false;
        text = text.trim();
        if (text.isEmpty()) return false;
        if (supersededLines.contains(text)) return true;
        for (String name : supersededNames) {
            if (text.startsWith(name)) return true;
        }
        return false;
    }
}
