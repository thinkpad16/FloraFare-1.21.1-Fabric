package net.tend1tnuy.florafare.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;

import java.util.ArrayList;
import java.util.List;

public class FlorafareHud implements HudRenderCallback {

    private static class SlotState {
        String         currentId       = "";
        float          animationOffset = -150f;
        ActiveFoodBuff lastBuff        = null;
    }

    private SlotState[] slots = new SlotState[0];

    /** Resizes {@link #slots} to match the current (server-synced) buff slot count. */
    private SlotState[] slots() {
        int maxSlots = Math.max(1, PlayerFoodComponent.MAX_BUFF_SLOTS);
        if (slots.length != maxSlots) {
            SlotState[] resized = new SlotState[maxSlots];
            for (int i = 0; i < maxSlots; i++) {
                resized[i] = (i < slots.length) ? slots[i] : new SlotState();
            }
            slots = resized;
        }
        return slots;
    }

    // #12 — Tick-driven blink accumulator replaces System.currentTimeMillis().
    private float blinkTimer = 0f;

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // Advance the blink timer by the elapsed frame time (in ticks).
        blinkTimer += tickCounter.getLastFrameDuration();

        PlayerFoodComponent foodComponent =
                ((IFoodComponentProvider) client.player).florafare$getFoodComponent();
        List<ActiveFoodBuff> buffs           = foodComponent.getActiveBuffs();
        List<ActiveFoodBuff> activeSynergies = foodComponent.getActiveSynergies();

        boolean isCompact = HudConfig.layoutMode == HudConfig.LayoutMode.COMPACT;

        float smoothFactor = Math.min(1.0f, tickCounter.getLastFrameDuration() * 0.12f);

        int slotWidth   = isCompact ? 24 : 120;
        int slotHeight  = 24;
        int slotSpacing = 28;

        SlotState[] slots    = slots();
        int         maxSlots = slots.length;

        float hudScale = HudConfig.scale;

        int screenWidth  = (int) (context.getScaledWindowWidth()  / hudScale);
        int screenHeight = (int) (context.getScaledWindowHeight() / hudScale);

        int baseX = 10;
        int baseY = 10;

        switch (HudConfig.position) {
            case TOP_LEFT    -> { baseX = 10; baseY = 10; }
            case TOP_RIGHT   -> { baseX = screenWidth - slotWidth - 10; baseY = 10; }
            case BOTTOM_LEFT -> { baseX = 10;
                                  baseY = screenHeight - (maxSlots * slotSpacing) - 10; }
            case BOTTOM_RIGHT -> { baseX = screenWidth - slotWidth - 10;
                                   baseY = screenHeight - (maxSlots * slotSpacing) - 10; }
        }

        // Hover details only make sense while the cursor is actually visible and
        // positioned — during normal gameplay the mouse is locked to the camera and
        // has no meaningful on-screen location, so a tooltip would just float uselessly
        // at a stale spot. A screen being open (inventory, chat, pause menu, the
        // journal itself...) is exactly when the cursor is real, and the HUD still
        // renders underneath that screen, so hovering a buff slot there works.
        boolean cursorVisible = client.currentScreen != null;
        int mouseX = -1;
        int mouseY = -1;
        if (cursorVisible) {
            mouseX = (int) (client.mouse.getX() * client.getWindow().getScaledWidth()  / client.getWindow().getWidth());
            mouseY = (int) (client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight());
        }
        ActiveFoodBuff hoveredBuff       = null;
        boolean        hoveredSynergized = false;

        context.getMatrices().push();
        context.getMatrices().scale(hudScale, hudScale, 1.0f);

        for (int i = 0; i < maxSlots; i++) {
            ActiveFoodBuff buff  = (i < buffs.size()) ? buffs.get(i) : null;
            SlotState      state = slots[i];
            int            y     = baseY + (i * slotSpacing);

            if (buff != null) {
                String newId = buff.getConsumedItemId();
                if (!state.currentId.equals(newId)) {
                    state.animationOffset = -slotWidth * 1.5f;
                    state.currentId       = newId;
                }
                state.lastBuff        = buff;
                state.animationOffset = MathHelper.lerp(
                        smoothFactor, state.animationOffset, 0f);

                int     renderX      = calculateAnimatedX(baseX, state.animationOffset);
                boolean isSynergized = isBuffSynergized(buff, activeSynergies);
                // Icon "pops in" alongside the slide — 0 at the start of the slide, 1 once settled.
                float appearProgress = 1.0f - MathHelper.clamp(
                        Math.abs(state.animationOffset) / (slotWidth * 1.5f), 0.0f, 1.0f);
                renderSlot(context, client, buff, renderX, y, 1.0f, appearProgress,
                        slotWidth, slotHeight, isCompact, isSynergized);

                if (cursorVisible) {
                    int realX = (int) (renderX  * hudScale);
                    int realY = (int) (y        * hudScale);
                    int realW = (int) (slotWidth  * hudScale);
                    int realH = (int) (slotHeight * hudScale);
                    if (mouseX >= realX && mouseX < realX + realW
                            && mouseY >= realY && mouseY < realY + realH) {
                        hoveredBuff       = buff;
                        hoveredSynergized = isSynergized;
                    }
                }
            } else {
                state.currentId = "";
                if (state.lastBuff != null
                        && Math.abs(state.animationOffset) < slotWidth * 1.2f) {
                    state.animationOffset = MathHelper.lerp(
                            smoothFactor, state.animationOffset, -slotWidth * 1.5f);
                    float fadeAlpha = Math.max(0.0f,
                            1.0f - Math.abs(state.animationOffset) / (slotWidth * 1.5f));
                    int     renderX      = calculateAnimatedX(baseX, state.animationOffset);
                    boolean isSynergized = isBuffSynergized(state.lastBuff, activeSynergies);
                    // Same progress curve, now shrinking the icon back down as it slides away.
                    renderSlot(context, client, state.lastBuff, renderX, y, fadeAlpha, fadeAlpha,
                            slotWidth, slotHeight, isCompact, isSynergized);
                } else {
                    state.lastBuff = null;
                    renderEmptySlot(context, client, baseX, y, slotWidth, slotHeight, isCompact);
                }
            }
        }

        context.getMatrices().pop();

        // Drawn outside the HUD-scale transform above, in real screen coordinates —
        // same space mouseX/mouseY were computed in — so the tooltip isn't itself
        // scaled or offset by the HUD's own zoom.
        if (hoveredBuff != null) {
            FoodBuffData data = FoodBuffManager.getConfig(hoveredBuff.getConsumedItemStack());
            if (data != null) {
                context.drawTooltip(client.textRenderer,
                        buildTooltipLines(hoveredBuff, data, hoveredSynergized),
                        mouseX, mouseY);
            }
        }
    }

    /**
     * Builds the hover-tooltip lines for a HUD buff slot: name, remaining time, and
     * the same health/attribute/effect breakdown the journal shows for this buff,
     * so players don't have to open the journal mid-game just to check what a
     * buff they already have actually does.
     */
    private static List<Text> buildTooltipLines(ActiveFoodBuff buff, FoodBuffData data,
                                                 boolean isSynergized) {
        List<Text> lines = new ArrayList<>();
        lines.add(buff.getConsumedItemStack().getName());

        int seconds = buff.getDurationRemaining() / 20;
        String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);
        lines.add(Text.translatable("tooltip.florafare.hud.remaining", timeStr).formatted(Formatting.GRAY));

        if (data.healthBonus() != 0) {
            String sign = data.healthBonus() > 0 ? "+" : "";
            String val  = data.healthBonus() % 1 == 0
                    ? String.valueOf((int) data.healthBonus()) : String.valueOf(data.healthBonus());
            lines.add(Text.translatable("gui.florafare.journal.health", sign + val).formatted(Formatting.RED));
        }

        for (FoodBuffData.AttributeData attr : data.attributes()) {
            String attrKey  = "florafare.attribute." + attr.attributeId().getPath().replace("generic.", "");
            String attrName = Text.translatable(attrKey).getString();
            String sign     = attr.amount() > 0 ? "+" : "";
            String val      = attr.operation().contains("multiplied")
                    ? (int) (attr.amount() * 100) + "%"
                    : (attr.amount() % 1 == 0
                    ? String.valueOf((int) attr.amount()) : String.valueOf(attr.amount()));
            lines.add(Text.literal("• " + attrName + ": " + sign + val).formatted(Formatting.DARK_GREEN));
        }

        for (FoodBuffData.EffectData effect : data.effects()) {
            String effectKey  = "effect." + effect.id().getNamespace() + "." + effect.id().getPath();
            String effectName = Text.translatable(effectKey).getString();
            lines.add(Text.literal("• " + effectName + amplifierNumeral(effect.amplifier()))
                    .formatted(Formatting.BLUE));
        }

        if (isSynergized) {
            lines.add(Text.translatable("tooltip.florafare.hud.synergized").formatted(Formatting.GOLD));
        }

        return lines;
    }

    /**
     * Roman-numeral suffix for a potion amplifier, mirroring the journal's own
     * {@code getAmplifierNumeral} so HUD tooltips and journal pages agree.
     * Amplifier 0 = level I, and vanilla omits "I" for level-1 effects.
     */
    private static String amplifierNumeral(int amplifier) {
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

    private int calculateAnimatedX(int baseX, float offset) {
        if (HudConfig.position == HudConfig.ScreenPosition.TOP_RIGHT
                || HudConfig.position == HudConfig.ScreenPosition.BOTTOM_RIGHT) {
            return baseX - (int) offset;
        }
        return baseX + (int) offset;
    }

    private boolean isBuffSynergized(ActiveFoodBuff buff,
                                     List<ActiveFoodBuff> activeSynergies) {
        if (buff == null || activeSynergies == null || activeSynergies.isEmpty()) return false;
        for (ActiveFoodBuff synBuff : activeSynergies) {
            FoodSynergyData data = FoodSynergyManager.getSynergy(synBuff.getTarget());
            if (data != null && data.requirements().contains(buff.getTarget())) return true;
        }
        return false;
    }

    private void renderSlot(DrawContext context, MinecraftClient client,
                            ActiveFoodBuff buff, int x, int y, float fadeAlpha, float appearProgress,
                            int slotWidth, int slotHeight,
                            boolean isCompact, boolean isSynergized) {
        float initialDuration = Math.max(1.0f, (float) buff.getInitialDuration());
        int   remainingTicks  = buff.getDurationRemaining();
        float progress        = MathHelper.clamp(remainingTicks / initialDuration, 0.0f, 1.0f);

        // #12 — Blink driven by the tick-based blinkTimer instead of System.currentTimeMillis().
        // Blink speed ramps up the closer the buff is to expiring, for a stronger sense of urgency.
        // Kept to a slow, sub-1Hz pulse with a high alpha floor (never far from fully opaque) — a
        // fast full-strobe read as flicker rather than a warning, especially right before expiry.
        boolean isBlinking  = progress <= 0.1f || remainingTicks <= 200;
        float   blinkAlpha  = 1.0f;
        if (isBlinking) {
            float urgency    = 1.0f - MathHelper.clamp(progress / 0.1f, 0.0f, 1.0f);
            float blinkSpeed = MathHelper.lerp(urgency, 0.08f, 0.22f);
            blinkAlpha = 0.7f + 0.3f * MathHelper.sin(blinkTimer * blinkSpeed);
        }
        float   finalAlpha  = blinkAlpha * fadeAlpha;

        int alphaInt = (int) (finalAlpha * 255);
        if (alphaInt <= 5) return;

        context.fill(x + 1, y + 1, x + slotWidth - 1, y + slotHeight - 1,
                ((int) (finalAlpha * 150) << 24) | 0x000000);

        if (isSynergized) {
            context.drawBorder(x - 1, y - 1, slotWidth + 2, slotHeight + 2,
                    ((int) (finalAlpha * 100) << 24) | 0xFF8800);
            context.drawBorder(x, y, slotWidth, slotHeight,
                    (alphaInt << 24) | 0xFFAA00);
        } else {
            context.drawBorder(x, y, slotWidth, slotHeight,
                    (alphaInt << 24) | 0x555555);
        }

        int barWidth = (int) ((slotWidth - 2) * progress);
        if (barWidth > 0) {
            int colorTop    = (isBlinking ? 0xFFFF5555 : 0xFF55FF55) & 0x00FFFFFF | (alphaInt << 24);
            int colorBottom = (isBlinking ? 0xFFAA0000 : 0xFF00AA00) & 0x00FFFFFF | (alphaInt << 24);
            context.fill(x + 1, y + slotHeight - 3, x + 1 + barWidth, y + slotHeight - 2, colorTop);
            context.fill(x + 1, y + slotHeight - 2, x + 1 + barWidth, y + slotHeight - 1, colorBottom);
        }

        // Icon scale-pop: grows in from 70% as the slot slides into place (and shrinks
        // back down as it slides away), layered on top of the compact/normal icon scale.
        float popScale = 0.7f + 0.3f * MathHelper.clamp(appearProgress, 0.0f, 1.0f);

        ItemStack stack = buff.getConsumedItemStack();
        context.getMatrices().push();
        if (HudConfig.iconSize == HudConfig.IconSize.SMALL) {
            float scale    = 0.75f * popScale;
            int   offsetXY = (int) ((slotHeight - (16 * scale)) / 2);
            context.getMatrices().translate(x + offsetXY, y + offsetXY - 1, 0);
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawItem(stack, 0, 0);
        } else {
            int centerX = x + 4 + 8;
            int centerY = y + 2 + 8;
            context.getMatrices().translate(centerX, centerY, 0);
            context.getMatrices().scale(popScale, popScale, 1.0f);
            context.getMatrices().translate(-centerX, -centerY, 0);
            context.drawItem(stack, x + 4, y + 2);
        }
        context.getMatrices().pop();

        if (!isCompact) {
            int    seconds = remainingTicks / 20;
            String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);
            int    textY   = y + 8;
            context.drawTextWithShadow(client.textRenderer, timeStr,
                    x + 24, textY, (alphaInt << 24) | 0xFFFFFF);

            String name = stack.getName().getString();
            if (client.textRenderer.getWidth(name) > 60) {
                name = client.textRenderer.trimToWidth(name, 55) + "...";
            }
            context.drawTextWithShadow(client.textRenderer, name,
                    x + 58, textY, (alphaInt << 24) | 0xAAAAAA);
        }
    }

    private void renderEmptySlot(DrawContext context, MinecraftClient client,
                                 int x, int y, int slotWidth, int slotHeight,
                                 boolean isCompact) {
        context.fill(x + 1, y + 1, x + slotWidth - 1, y + slotHeight - 1, 0x33000000);
        context.drawBorder(x, y, slotWidth, slotHeight, 0x44555555);
        if (!isCompact) {
            context.drawTextWithShadow(client.textRenderer,
                    Text.translatable("gui.florafare.hud.empty"),
                    x + 24, y + 8, 0x44FFFFFF);
        }
    }
}
