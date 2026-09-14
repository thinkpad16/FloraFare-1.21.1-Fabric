package net.tend1tnuy.florafare.mixin;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
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
            tooltip.addAll(1, BuffDescription.itemTooltip(stack, data));
        }
    }

    /**
     * Drops tooltip lines describing status effects that the item's vanilla
     * {@link FoodComponent} would grant but Florafare suppresses, so the tooltip stops
     * advertising effects that will not happen. Everything else on the tooltip — lore,
     * enchantments, attribute modifiers, other mods' lines — is left alone.
     */
    @Unique
    private static void florafare$removeSupersededEffectLines(ItemStack stack, List<Text> tooltip) {
        // With this on, the vanilla effects do still fire alongside the buff, so their
        // lines are telling the truth and must stay.
        if (FlorafareConfig.respectVanillaFoodEffects) return;

        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        if (food == null || food.effects().isEmpty()) return;

        Set<String> supersededNames = new HashSet<>();
        for (FoodComponent.StatusEffectEntry entry : food.effects()) {
            supersededNames.add(entry.effect().getEffectType().value().getName().getString());
        }

        // From index 1: the item name is never a candidate, however it is worded.
        //
        // Anchored at the start of the line, not matched anywhere inside it. An effect
        // line leads with its name — "Regeneration (0:45)", "Speed II (3:00)" — while an
        // attribute line leads with its sign: "+20% Movement Speed". A substring match
        // took that attribute line along with the Speed effect it was aimed at, and any
        // lore that happened to use the word.
        tooltip.subList(1, tooltip.size()).removeIf(line -> {
            String text = Formatting.strip(line.getString());
            if (text == null) return false;
            text = text.trim();
            if (text.isEmpty()) return false;
            for (String name : supersededNames) {
                if (text.startsWith(name)) return true;
            }
            return false;
        });
    }
}
