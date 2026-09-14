package net.tend1tnuy.florafare.mixin;

import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.text.OrderedText;
import net.tend1tnuy.florafare.client.tooltip.FoodValuesTooltip;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns the carrier back into the component it is carrying.
 *
 * <p>{@code ItemStack#getTooltip} is typed {@code List<Text>}, and a tooltip's lines only
 * become {@link TooltipComponent}s afterwards, in {@code TooltipComponent#of}. A mod that
 * wants to draw something other than a line of text therefore has to smuggle it through
 * as a {@code Text} and unwrap it at that conversion, which is what this does for
 * {@link FoodValuesTooltip}.
 *
 * <p>Both injections are plain {@code @Inject}s that only fire for our own carrier type,
 * so they compose with AppleSkin's identically-shaped pair rather than fighting them —
 * which matters, because AppleSkin is the mod most likely to be installed next to this
 * one. In practice the two never both run anyway: Florafare does not emit this component
 * at all when AppleSkin is present.
 */
@Mixin(TooltipComponent.class)
public interface FlorafareTooltipComponentMixin {

    @Inject(
            method = "of(Lnet/minecraft/text/OrderedText;)Lnet/minecraft/client/gui/tooltip/TooltipComponent;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void florafare$ofOrderedText(OrderedText text,
                                                CallbackInfoReturnable<TooltipComponent> cir) {
        if (text instanceof FoodValuesTooltip.Carrier carrier) {
            cir.setReturnValue(carrier.component);
        }
    }

    /**
     * The same unwrap for the {@code TooltipData} route, which is how recipe viewers
     * (REI in particular) reach a tooltip component.
     */
    @Inject(
            method = "of(Lnet/minecraft/item/tooltip/TooltipData;)Lnet/minecraft/client/gui/tooltip/TooltipComponent;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void florafare$ofTooltipData(TooltipData data,
                                                CallbackInfoReturnable<TooltipComponent> cir) {
        if (data instanceof FoodValuesTooltip component) {
            cir.setReturnValue(component);
        }
    }
}
