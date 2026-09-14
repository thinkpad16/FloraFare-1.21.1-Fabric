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
import net.tend1tnuy.florafare.config.FlorafareConfig;
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

    /** Clears leftover animation state so the next buff slides in from scratch. */
    private void resetSlots() {
        for (SlotState state : slots) {
            if (state == null) continue;
            state.currentId       = "";
            state.lastBuff        = null;
            state.animationOffset = -150f;
        }
    }

    // #12 — Tick-driven blink accumulator replaces System.currentTimeMillis().
    // Advances in ticks: 20 units per second, independent of framerate.
    private float blinkTimer = 0f;

    /** Start warning once the buff is down to this share of its full duration. */
    private static final float BLINK_PROGRESS_THRESHOLD = 0.1f;
    /** ...or this many ticks left, whichever comes first. 200 ticks = 10 seconds. */
    private static final int   BLINK_TICKS_THRESHOLD    = 200;
    /** How far below fully opaque the pulse dips at its deepest (0.18 = 82%..100%). */
    private static final float BLINK_DEPTH              = 0.18f;
    /** Pulse rate in radians per tick: ~0.32 Hz when the warning starts... */
    private static final float BLINK_RATE_MIN           = 0.10f;
    /** ...rising to ~0.57 Hz at expiry. Both a slow breath, well under a strobe. */
    private static final float BLINK_RATE_MAX           = 0.18f;

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // F1 hides every HUD element in vanilla, and players reach for it constantly
        // for screenshots and recording. An overlay that ignores it is in every shot
        // they take. Checked before the blink timer so nothing accumulates while the
        // HUD isn't drawing. This and enableHud are the only two things that hide the
        // overlay — it is on screen the whole time otherwise, buffs or no buffs.
        if (client.options.hudHidden) return;
        if (!FlorafareConfig.enableHud) {
            // Drop animation state as well, so toggling the overlay back on doesn't
            // replay the slide-out of a buff that expired while it was off.
            resetSlots();
            return;
        }

        // Advance the blink timer by the elapsed frame time (in ticks).
        blinkTimer += tickCounter.getLastFrameDuration();

        PlayerFoodComponent foodComponent =
                ((IFoodComponentProvider) client.player).florafare$getFoodComponent();
        List<ActiveFoodBuff> buffs           = foodComponent.getActiveBuffs();
        List<ActiveFoodBuff> activeSynergies = foodComponent.getActiveSynergies();

        // The overlay draws whether or not anything is active: the empty slot frames are
        // the readout of how much room is left, and a bar that comes and goes is harder
        // to find than one that is simply always in the same place. F1 and the enableHud
        // switch above are the only two things that take it off screen.

        boolean isCompact = HudConfig.layoutMode == HudConfig.LayoutMode.COMPACT;

        float smoothFactor = Math.min(1.0f, tickCounter.getLastFrameDuration() * 0.12f);

        int slotWidth   = HudLayout.slotWidth(isCompact);
        int slotHeight  = HudLayout.SLOT_HEIGHT;
        int slotSpacing = HudLayout.SLOT_SPACING;

        SlotState[] slots    = slots();
        int         maxSlots = slots.length;

        float hudScale = HudConfig.scale;

        int screenWidth  = HudLayout.scaledWidth(context.getScaledWindowWidth(), hudScale);
        int screenHeight = HudLayout.scaledHeight(context.getScaledWindowHeight(), hudScale);

        // Anchor corner plus the player's own offset, shared with the placement screen.
        int baseX = HudLayout.baseX(HudConfig.position, screenWidth, slotWidth, HudConfig.offsetX);
        int baseY = HudLayout.baseY(HudConfig.position, screenHeight, maxSlots, HudConfig.offsetY);

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
     * Hover-tooltip lines for a HUD buff slot: the dish, how long it has left, and the
     * same breakdown the item tooltip and the journal show, so a player can check what
     * a running buff does without opening anything.
     *
     * <p>No discovery gate here — you cannot be holding a buff from a food you have
     * never eaten.
     */
    private static List<Text> buildTooltipLines(ActiveFoodBuff buff, FoodBuffData data,
                                                 boolean isSynergized) {
        List<Text> lines = new ArrayList<>();
        lines.add(buff.getConsumedItemStack().getName());
        lines.add(Text.translatable("tooltip.florafare.hud.remaining",
                BuffDescription.mmss(buff.getDurationRemaining())).formatted(Formatting.GRAY));

        lines.addAll(BuffDescription.effectBlock(
                data.healthBonus(), data.attributes(), data.effects()));

        if (isSynergized) {
            lines.add(Text.translatable("tooltip.florafare.hud.synergized").formatted(Formatting.GOLD));
        }
        return lines;
    }

    /**
     * Per-channel blend of two 0xRRGGBB colours. Returns RGB only — callers OR in
     * their own alpha, the same way the literals it replaced were masked.
     */
    private static int blendRgb(int from, int to, float t) {
        float f = MathHelper.clamp(t, 0.0f, 1.0f);
        int r = Math.round(MathHelper.lerp(f, (from >> 16) & 0xFF, (to >> 16) & 0xFF));
        int g = Math.round(MathHelper.lerp(f, (from >> 8)  & 0xFF, (to >> 8)  & 0xFF));
        int b = Math.round(MathHelper.lerp(f,  from        & 0xFF,  to        & 0xFF));
        return (r << 16) | (g << 8) | b;
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
            if (data == null) continue;
            // Through the component's own matcher, not a plain equals on the target. A
            // requirement can be a "#tag" that the buff satisfies via the item eaten, and
            // the strict comparison this replaces highlighted nothing at all for any
            // synergy built on tags — the slot feeding an active synergy looked idle.
            for (String req : data.requirements()) {
                if (PlayerFoodComponent.satisfiesRequirement(buff, req)) return true;
            }
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

        // How close this buff is to running out, as a smooth 0..1 rather than a flag.
        // Whichever warning is nearer wins: the last tenth of the buff, or the last ten
        // seconds — so a 30-second snack and a ten-minute feast both warn for a
        // sensible stretch instead of one of them warning almost instantly.
        float urgency = Math.max(
                1.0f - MathHelper.clamp(progress / BLINK_PROGRESS_THRESHOLD, 0.0f, 1.0f),
                1.0f - MathHelper.clamp(remainingTicks / (float) BLINK_TICKS_THRESHOLD, 0.0f, 1.0f));

        // The pulse breathes; it does not strobe. Three things keep it calm:
        //
        //  * the wave is normalized to 0..1 before it is applied, so the slot swings
        //    between BLINK_DEPTH-below-opaque and fully opaque instead of straddling a
        //    midpoint — a raw sine here dipped to 40% alpha, which made the whole slot
        //    all but vanish twice a second and read as a fault rather than a warning;
        //  * the depth eases in with urgency from nothing, so crossing the threshold
        //    doesn't pop the slot to a dark frame mid-wave;
        //  * blinkTimer advances in ticks (measured: 20 units/second regardless of
        //    framerate), so the rate below is a real frequency and not FPS-dependent.
        float blinkAlpha = 1.0f;
        if (urgency > 0.0f) {
            float depth = urgency * BLINK_DEPTH;
            float rate  = MathHelper.lerp(urgency, BLINK_RATE_MIN, BLINK_RATE_MAX);
            float wave  = 0.5f + 0.5f * MathHelper.sin(blinkTimer * rate);
            blinkAlpha  = 1.0f - depth * (1.0f - wave);
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
            // Bleeds from green to red across the same urgency ramp. The old boolean
            // flipped both colours in a single frame, which landed as a second, harder
            // flicker right at the moment the pulse started.
            int colorTop    = blendRgb(0x55FF55, 0xFF5555, urgency) | (alphaInt << 24);
            int colorBottom = blendRgb(0x00AA00, 0xAA0000, urgency) | (alphaInt << 24);
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

    /**
     * An unused slot.
     *
     * <p>Drawn at roughly three quarters the weight of a filled one — dim enough to read
     * as empty at a glance, solid enough to actually be on screen. The alphas used to be
     * far lower (0x33 fill, 0x44 border), which was fine while empty slots were a
     * transient state the overlay hid anyway: measured against grass, the frame differed
     * from the background by five values out of 255. Now that the bar is always up, the
     * empty slots are what the player sees most of the time, and an outline nobody can
     * find is the same as not drawing one.
     */
    private void renderEmptySlot(DrawContext context, MinecraftClient client,
                                 int x, int y, int slotWidth, int slotHeight,
                                 boolean isCompact) {
        context.fill(x + 1, y + 1, x + slotWidth - 1, y + slotHeight - 1, 0x6E000000);
        context.drawBorder(x, y, slotWidth, slotHeight, 0xA0666666);
        if (!isCompact) {
            context.drawTextWithShadow(client.textRenderer,
                    Text.translatable("gui.florafare.hud.empty"),
                    x + 24, y + 8, 0x80FFFFFF);
        }
    }
}
