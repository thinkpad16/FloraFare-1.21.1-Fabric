package net.tend1tnuy.florafare.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void florafare$clearExtraneousTooltips(Item.TooltipContext context, PlayerEntity player, TooltipType type, CallbackInfoReturnable<List<Text>> cir) {
        ItemStack stack = (ItemStack) (Object) this;

        if (FoodBuffManager.getConfig(stack) != null) {
            List<Text> tooltip = cir.getReturnValue();

            if (tooltip != null && tooltip.size() > 1) {
                // Keep the item name (line 0), the debug block vanilla appends
                // at the end for F3+H advanced tooltips (durability, item id,
                // component count), and the mod-name line tooltip mods append;
                // drop everything in between — other mods' "grants effect X"
                // lines etc. The blue creative-tab names are inserted by
                // CreativeInventoryScreen after this hook, so they are unaffected.
                // Nutrition/saturation preview is handled by the AppleSkin
                // integration (icons appear once the food is discovered).
                Identifier itemId = Registries.ITEM.getId(stack.getItem());
                String modName = FabricLoader.getInstance().getModContainer(itemId.getNamespace())
                        .map(container -> container.getMetadata().getName())
                        .orElse(itemId.getNamespace());
                tooltip.subList(1, tooltip.size())
                        .removeIf(line -> !florafare$shouldKeepLine(line, itemId.toString(), modName));
            }
        }
    }

    @Unique
    private static boolean florafare$shouldKeepLine(Text line, String itemId, String modName) {
        // Every real text line is a MutableText (Text.literal/translatable).
        // Custom Text implementations are functional markers other mods smuggle
        // into the tooltip — e.g. AppleSkin's FoodOverlayTextComponent, which
        // becomes the hunger/saturation icon overlay — so never strip those.
        if (!(line instanceof MutableText)) {
            return true;
        }
        if (line.getContent() instanceof TranslatableTextContent translatable) {
            String key = translatable.getKey();
            return key.equals("item.durability")
                    || key.equals("item.components")
                    || key.equals("item.disabled");
        }
        // Literal lines worth keeping: the advanced item-id line and the
        // mod-name line appended by tooltip mods (REI/EMI etc.).
        String text = Formatting.strip(line.getString());
        return itemId.equals(text) || modName.equalsIgnoreCase(text);
    }
}