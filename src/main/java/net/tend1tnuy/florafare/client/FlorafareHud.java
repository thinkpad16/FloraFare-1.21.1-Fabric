package net.tend1tnuy.florafare.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;

import java.util.List;

/**
 * Renders the Florafare HUD, displaying active food buffs and their remaining duration.
 */
public class FlorafareHud implements HudRenderCallback {

    private static final float HUD_SCALE = 0.8f;

    private static final int BASE_X = 10;
    private static final int BASE_Y = 10;

    private static final int SLOT_WIDTH = 120;
    private static final int SLOT_HEIGHT = 22;
    private static final int SLOT_SPACING = 26;

    private static final int MAX_SLOTS = 3;

    private static final float ANIMATION_SPEED = 0.15f;
    private static final float HIDDEN_POSITION = -SLOT_WIDTH * 1.5f;

    private static final float LOW_DURATION_THRESHOLD = 0.1f;
    private static final int WARNING_TICKS = 200;
    private static final float BLINK_SPEED = 100.0f;

    private static final int NAME_MAX_WIDTH = 65;
    private static final int NAME_TRIM_WIDTH = 60;

    /**
     * Stores persistent animation state for a HUD slot.
     */
    private static class SlotState {

        /**
         * Identifier of the currently displayed buff.
         */
        String currentId = "";

        /**
         * Current X position used for animations.
         */
        float x = HIDDEN_POSITION;

        /**
         * Cached buff used during slide-out animation.
         */
        ActiveFoodBuff lastBuff;
    }

    /**
     * Persistent HUD slots used to preserve animation state.
     */
    private final SlotState[] slots = {
            new SlotState(),
            new SlotState(),
            new SlotState()
    };

    /**
     * Renders the HUD every frame.
     *
     * @param context rendering context
     * @param tickCounter render tick counter
     */
    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player == null) {
            return;
        }

        PlayerFoodComponent component =
                ((IFoodComponentProvider) client.player).florafare$getFoodComponent();

        List<ActiveFoodBuff> buffs = component.getActiveBuffs();

        context.getMatrices().push();
        context.getMatrices().scale(HUD_SCALE, HUD_SCALE, 1.0F);

        for (int i = 0; i < MAX_SLOTS; i++) {
            ActiveFoodBuff buff = i < buffs.size() ? buffs.get(i) : null;
            SlotState state = slots[i];

            if (buff != null) {
                String newId = buff.getConsumedItemId();

                if (!state.currentId.equals(newId)) {
                    // Start slide-in animation for a newly appeared buff.
                    state.x = HIDDEN_POSITION;
                    state.currentId = newId;
                }

                state.lastBuff = buff;

                // Smooth slide-in animation.
                state.x = MathHelper.lerp(
                        ANIMATION_SPEED,
                        state.x,
                        BASE_X
                );

                renderActiveSlot(
                        context,
                        client,
                        i,
                        buff,
                        (int) state.x,
                        1.0F
                );
            } else {
                state.currentId = "";

                // Animate the slot sliding out after the buff expires.
                if (state.lastBuff != null && state.x > HIDDEN_POSITION * 0.8F) {

                    state.x = MathHelper.lerp(
                            ANIMATION_SPEED,
                            state.x,
                            HIDDEN_POSITION
                    );

                    // Gradually fade the slot while moving off-screen.
                    float fadeAlpha = Math.max(
                            0.0F,
                            1.0F - Math.abs(BASE_X - state.x)
                                    / (SLOT_WIDTH + BASE_X)
                    );

                    renderActiveSlot(
                            context,
                            client,
                            i,
                            state.lastBuff,
                            (int) state.x,
                            fadeAlpha
                    );
                } else {
                    // Remove cached data once the slot is fully hidden.
                    state.lastBuff = null;
                    renderEmptySlot(context, client, i);
                }
            }
        }

        context.getMatrices().pop();
    }

    /**
     * Renders a slot containing an active food buff.
     *
     * @param context rendering context
     * @param client current Minecraft client
     * @param index slot index
     * @param buff active food buff
     * @param currentX animated X position
     * @param fadeAlpha transparency multiplier
     */
    private void renderActiveSlot(
            DrawContext context,
            MinecraftClient client,
            int index,
            ActiveFoodBuff buff,
            int currentX,
            float fadeAlpha
    ) {
        int y = BASE_Y + (index * SLOT_SPACING);

        float initialDuration = Math.max(
                1.0F,
                (float) buff.getInitialDuration()
        );

        int remainingTicks = buff.getDurationRemaining();

        float progress = MathHelper.clamp(
                remainingTicks / initialDuration,
                0.0F,
                1.0F
        );

        boolean isBlinking =
                progress <= LOW_DURATION_THRESHOLD
                        || remainingTicks <= WARNING_TICKS;

        float blinkAlpha = 1.0F;

        if (isBlinking) {
            blinkAlpha = 0.5F + 0.5F *
                    (float) Math.sin(System.currentTimeMillis() / BLINK_SPEED);
        }

        // Combine blinking and fade-out transparency.
        float finalAlpha = blinkAlpha * fadeAlpha;

        int alphaInt = (int) (finalAlpha * 255);
        int backgroundAlpha = (int) (finalAlpha * 150);

        // Skip rendering if the slot becomes fully transparent.
        if (alphaInt <= 5) {
            return;
        }

        int backgroundColor = (backgroundAlpha << 24) | 0x000000;
        int borderColor = (alphaInt << 24) | 0x555555;
        int primaryTextColor = (alphaInt << 24) | 0xFFFFFF;
        int secondaryTextColor = (alphaInt << 24) | 0xAAAAAA;

        context.fill(
                currentX,
                y,
                currentX + SLOT_WIDTH,
                y + SLOT_HEIGHT,
                backgroundColor
        );

        context.drawBorder(
                currentX,
                y,
                SLOT_WIDTH,
                SLOT_HEIGHT,
                borderColor
        );

        int barWidth = (int) ((SLOT_WIDTH - 2) * progress);

        if (barWidth > 0) {
            int topColor = isBlinking
                    ? 0xFFFF5555
                    : 0xFF55FF55;

            int bottomColor = isBlinking
                    ? 0xFFAA0000
                    : 0xFF00AA00;

            topColor = (topColor & 0x00FFFFFF) | (alphaInt << 24);
            bottomColor = (bottomColor & 0x00FFFFFF) | (alphaInt << 24);

            int barY = y + 17;
            int barHeight = 4;

            context.fill(
                    currentX + 1,
                    barY,
                    currentX + 1 + barWidth,
                    barY + barHeight / 2,
                    topColor
            );

            context.fill(
                    currentX + 1,
                    barY + barHeight / 2,
                    currentX + 1 + barWidth,
                    barY + barHeight,
                    bottomColor
            );
        }

        ItemStack stack = buff.getConsumedItemStack();

        context.drawItem(stack, currentX + 2, y + 2);

        int seconds = remainingTicks / 20;
        String timeText = String.format(
                "%02d:%02d",
                seconds / 60,
                seconds % 60
        );

        context.drawTextWithShadow(
                client.textRenderer,
                timeText,
                currentX + 22,
                y + 6,
                primaryTextColor
        );

        String itemName = stack.getName().getString();

        if (client.textRenderer.getWidth(itemName) > NAME_MAX_WIDTH) {
            itemName =
                    client.textRenderer.trimToWidth(
                            itemName,
                            NAME_TRIM_WIDTH
                    ) + "...";
        }

        context.drawTextWithShadow(
                client.textRenderer,
                itemName,
                currentX + 55,
                y + 6,
                secondaryTextColor
        );
    }

    /**
     * Renders an empty placeholder slot.
     *
     * @param context rendering context
     * @param client current Minecraft client
     * @param index slot index
     */
    private void renderEmptySlot(
            DrawContext context,
            MinecraftClient client,
            int index
    ) {
        int y = BASE_Y + (index * SLOT_SPACING);

        context.fill(
                BASE_X,
                y,
                BASE_X + SLOT_WIDTH,
                y + SLOT_HEIGHT,
                0x33000000
        );

        context.drawBorder(
                BASE_X,
                y,
                SLOT_WIDTH,
                SLOT_HEIGHT,
                0x44555555
        );

        context.drawTextWithShadow(
                client.textRenderer,
                "Empty",
                BASE_X + 22,
                y + 6,
                0x44FFFFFF
        );
    }
}