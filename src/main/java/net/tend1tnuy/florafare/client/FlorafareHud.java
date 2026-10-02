package net.tend1tnuy.florafare.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.tend1tnuy.florafare.component.ActiveFoodBuff;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.food.SynergyOverdrive;
import net.tend1tnuy.florafare.util.RomanNumerals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class FlorafareHud implements HudRenderCallback {

    /**
     * Registers the overlay, and the hook that draws its hover tooltip on top of whatever
     * screen is open.
     *
     * <p>The tooltip used to be drawn from the HUD callback itself. The HUD renders
     * <em>before</em> the open screen in the same frame, so the very screen that makes the
     * cursor visible — the inventory, chat, the journal — was drawn over the tooltip, and
     * a player hovering a slot through a translucent screen saw it half buried. Now the
     * HUD only works out what is hovered and leaves the lines here, and each screen's
     * after-render event (the last thing drawn in the frame) puts them up.
     */
    public static void register() {
        FlorafareHud hud = new FlorafareHud();
        HudRenderCallback.EVENT.register(hud);
        // Per-screen events are rebuilt on every init, so registering on each one is the
        // supported way to be called after every screen's render.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenEvents.afterRender(screen).register((current, context, mouseX, mouseY, delta) ->
                        hud.drawDeferredTooltip(context, mouseX, mouseY)));
    }

    /** Lines the HUD found hovered this frame, waiting for the screen to finish drawing. */
    private List<Text> deferredTooltip = null;

    /**
     * Draws and drops the deferred tooltip. Dropped even when nothing draws it, so a
     * frame where the HUD did not run (F1, the overlay off) never shows a stale one.
     */
    private void drawDeferredTooltip(DrawContext context, int mouseX, int mouseY) {
        List<Text> lines = deferredTooltip;
        deferredTooltip = null;
        if (lines == null || lines.isEmpty()) return;
        context.drawTooltip(MinecraftClient.getInstance().textRenderer, lines, mouseX, mouseY);
    }

    private static class SlotState {
        String         currentId       = "";
        float          animationOffset = -150f;
        ActiveFoodBuff lastBuff        = null;
    }

    private SlotState[] slots = new SlotState[0];

    /** Resizes {@link #slots} to match the current (server-synced) buff slot count. */
    private SlotState[] slots() {
        int maxSlots = PlayerFoodComponent.getMaxBuffSlots();
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

    /** Where the remaining time starts, right of the icon. */
    private static final int    TIME_OFFSET_X       = 24;
    /** The narrowest time column, so names line up across slots whose times are all mm:ss. */
    private static final String TIME_COLUMN_SAMPLE  = "00:00";
    /** Space between the time and the name. */
    private static final int    NAME_GAP            = 5;
    /** Space kept between the name and the slot's right-hand border. */
    private static final int    NAME_RIGHT_PADDING  = 3;

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        // Whatever was left from the previous frame is stale now, drawn or not.
        deferredTooltip = null;

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
                        slotWidth, slotHeight, isCompact, isSynergized,
                        overdriveStageOf(buff, activeSynergies));

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
                            slotWidth, slotHeight, isCompact, isSynergized,
                            overdriveStageOf(state.lastBuff, activeSynergies));
                } else {
                    state.lastBuff = null;
                    renderEmptySlot(context, client, baseX, y, slotWidth, slotHeight, isCompact);
                }
            }
        }

        // Aftermaths, in rows of their own past the open end of the slot column — they
        // take no slot, so they are not drawn in one. See HudLayout#aftermathY.
        List<ActiveFoodBuff> aftermaths = foodComponent.getActiveAftermaths();
        ActiveFoodBuff hoveredAftermath = null;
        int aftermathRows = Math.min(aftermaths.size(), HudLayout.MAX_AFTERMATH_ROWS);
        for (int i = 0; i < aftermathRows; i++) {
            ActiveFoodBuff entry = aftermaths.get(i);
            int y = HudLayout.aftermathY(HudConfig.position, baseY, maxSlots, i, screenHeight);
            if (y == Integer.MIN_VALUE) break;
            renderAftermath(context, client, entry, baseX, y, slotWidth, slotHeight, isCompact);

            if (cursorVisible) {
                int realX = (int) (baseX * hudScale);
                int realY = (int) (y * hudScale);
                if (mouseX >= realX && mouseX < realX + (int) (slotWidth * hudScale)
                        && mouseY >= realY && mouseY < realY + (int) (slotHeight * hudScale)) {
                    hoveredAftermath = entry;
                }
            }
        }

        context.getMatrices().pop();

        // Handed to the screen's after-render hook (see register()), which draws it in
        // real screen coordinates — outside the HUD-scale transform above, so the
        // tooltip isn't itself scaled by the HUD's own zoom — and on top of the screen.
        if (hoveredBuff != null) {
            deferredTooltip = buildTooltipLines(hoveredBuff, configFor(hoveredBuff), hoveredSynergized,
                    activeSynergies);
        } else if (hoveredAftermath != null) {
            deferredTooltip = buildAftermathTooltipLines(hoveredAftermath);
        }
    }

    /**
     * The config a running buff came from.
     *
     * <p>Looked up by the buff's own target first. The HUD used to resolve the icon stack
     * instead, which is the item's default stack: a {@code /florafare setbuff} buff showed
     * the item's ordinary entry, and a potion buff — whose icon is a bottle, not food —
     * resolved to nothing and showed no tooltip at all.
     */
    @org.jetbrains.annotations.Nullable
    private static FoodBuffData configFor(ActiveFoodBuff buff) {
        String target = buff.getTarget();
        if (target.startsWith(STACK_TARGET_PREFIX)) {
            FoodBuffData stamped = FoodBuffManager.getRuntimeStackConfig(
                    target.substring(STACK_TARGET_PREFIX.length()));
            if (stamped != null) return stamped;
        }
        FoodBuffData exact = FoodBuffManager.getConfigByTarget(target);
        if (exact != null) return exact;
        return BuffDescription.configFor(buff.peekConsumedItemStack());
    }

    private static final String STACK_TARGET_PREFIX = "stack:";

    /**
     * Hover-tooltip lines for a HUD buff slot: the dish, how long it has left, and the
     * same breakdown the item tooltip and the journal show, so a player can check what
     * a running buff does without opening anything.
     *
     * <p>No discovery gate here — you cannot be holding a buff from a food you have
     * never eaten.
     */
    private static List<Text> buildTooltipLines(ActiveFoodBuff buff,
                                                 @org.jetbrains.annotations.Nullable FoodBuffData data,
                                                 boolean isSynergized,
                                                 List<ActiveFoodBuff> activeSynergies) {
        List<Text> lines = new ArrayList<>();
        lines.add(buff.peekConsumedItemStack().getName());
        lines.add(Text.translatable("tooltip.florafare.hud.remaining",
                BuffDescription.formatDuration(buff.getDurationRemaining())).formatted(Formatting.GRAY));

        // The modifiers the server actually put on the player, as recorded on the buff
        // and synced with it — not the entry as it reads now. The two differ whenever a
        // /reload or a /florafare edit changed the entry after the bite: the buff keeps
        // what it was granted until it is refreshed, and this is the one place a player
        // can see that. The entry is the fallback for a buff that recorded nothing.
        Map<Identifier, ActiveFoodBuff.AppliedModifier> applied = buff.getAppliedModifiers();
        List<FoodBuffData.EffectData> effects = data == null ? List.of() : data.effects();
        if (!applied.isEmpty()) {
            lines.addAll(BuffDescription.effectBlock(
                    appliedHealth(applied), appliedAttributes(applied), effects));
        } else if (data != null) {
            lines.addAll(BuffDescription.effectBlock(
                    data.healthBonus(), data.attributes(), effects));
        }

        if (isSynergized) {
            lines.add(Text.translatable("tooltip.florafare.hud.synergized").formatted(Formatting.GOLD));
            lines.addAll(overdriveLines(buff, activeSynergies));
        }

        // What it will leave behind, so the bill is visible while the meal is still on.
        if (data != null) lines.addAll(BuffDescription.aftermathBlock(data.aftermath()));
        return lines;
    }

    /**
     * For each running overdrive this buff feeds: which one, "Overdrive: stage II of IV",
     * when the next stage comes (or that this is the last), and what the stage it is at is
     * costing — its drawbacks, then what it will leave behind. The stage's benefits are already listed above as the player's own state; the
     * point here is the bill.
     *
     * <p>The player is in that stage, so its drawbacks are normally known (reaching a stage
     * is what reveals them); the "???" form only shows in the tick between the server
     * moving the stage and the discovery packet arriving, or after an admin
     * {@code synergy forget}. Its aftermath stays "???" until it has started once.
     */
    private static List<Text> overdriveLines(ActiveFoodBuff buff, List<ActiveFoodBuff> activeSynergies) {
        List<Text> lines = new ArrayList<>();
        for (ActiveFoodBuff synergyBuff : activeSynergies) {
            FoodSynergyData synergy = FoodSynergyManager.getSynergy(synergyBuff.getTarget());
            if (synergy == null || !synergy.hasOverdrive() || !feeds(buff, synergy)) continue;

            int stage = synergy.clampStage(synergyBuff.getStage());
            lines.add(Text.translatable("tooltip.florafare.hud.overdrive_stage",
                            BuffDescription.synergyName(synergy.id()),
                            RomanNumerals.of(stage), RomanNumerals.of(synergy.stageCount()))
                    .formatted(Formatting.LIGHT_PURPLE));
            // When it climbs next, counted down on the client between syncs like the
            // remaining time above — or, at the top, the warning that matters there.
            if (stage >= synergy.stageCount()) {
                lines.add(Text.translatable("tooltip.florafare.hud.overdrive_last")
                        .formatted(Formatting.RED));
            } else if (synergyBuff.hasStageClock()) {
                lines.add(Text.translatable("tooltip.florafare.hud.overdrive_next",
                                BuffDescription.formatDuration(synergyBuff.getTicksToNextStage()))
                        .formatted(Formatting.GRAY));
            }

            SynergyOverdrive.Stage fields = synergy.stage(stage);
            if (BuffDescription.isStageDiscovered(synergy.id(), stage)) {
                List<Text> drawbacks = BuffDescription.drawbackLines(fields);
                if (!drawbacks.isEmpty()) {
                    lines.add(Text.translatable("tooltip.florafare.hud.overdrive_drawbacks")
                            .formatted(Formatting.GRAY));
                    lines.addAll(drawbacks);
                }
            } else if (BuffDescription.hasDrawbacks(fields)) {
                lines.add(BuffDescription.hiddenDrawbacksLine());
            }
            lines.addAll(BuffDescription.synergyAftermathBlock(synergy, stage));
        }
        return lines;
    }

    /** Whether a buff satisfies one of a synergy's requirements; see {@link #isBuffSynergized}. */
    private static boolean feeds(ActiveFoodBuff buff, FoodSynergyData synergy) {
        for (String req : synergy.requirements()) {
            if (PlayerFoodComponent.satisfiesRequirement(buff, req)) return true;
        }
        return false;
    }

    /**
     * The overdrive stage a buff slot is feeding, for the numeral on its corner: the highest
     * stage among the running overdrives it is an ingredient of, or 0 when it feeds none.
     */
    private static int overdriveStageOf(ActiveFoodBuff buff, List<ActiveFoodBuff> activeSynergies) {
        if (buff == null || activeSynergies == null) return 0;
        int stage = 0;
        for (ActiveFoodBuff synergyBuff : activeSynergies) {
            FoodSynergyData synergy = FoodSynergyManager.getSynergy(synergyBuff.getTarget());
            if (synergy == null || !synergy.hasOverdrive() || !feeds(buff, synergy)) continue;
            stage = Math.max(stage, synergy.clampStage(synergyBuff.getStage()));
        }
        return stage;
    }

    /**
     * The stage numeral an aftermath row carries: its stage when it is the aftermath of an
     * overdrive, 0 (none) for a buff's or an ordinary synergy's.
     */
    private static int aftermathStageOf(ActiveFoodBuff entry) {
        if (!entry.isSynergy()) return 0;
        FoodSynergyData synergy = FoodSynergyManager.getSynergy(entry.getTarget());
        return synergy != null && synergy.hasOverdrive() ? entry.getStage() : 0;
    }

    /**
     * Hover lines for an aftermath row: whose aftermath it is, how long it has left, and
     * the modifiers it is holding — read off the entry, as for a buff, because that is
     * what is actually on the player. Its effects were granted once when it started and
     * show in the vanilla effect list from then on.
     */
    private static List<Text> buildAftermathTooltipLines(ActiveFoodBuff entry) {
        List<Text> lines = new ArrayList<>();
        int stage = aftermathStageOf(entry);
        lines.add((stage > 0
                ? Text.translatable("tooltip.florafare.hud.aftermath_stage", sourceName(entry),
                        RomanNumerals.of(stage))
                : Text.translatable("tooltip.florafare.hud.aftermath", sourceName(entry)))
                .formatted(Formatting.RED));
        lines.add(Text.translatable("tooltip.florafare.hud.remaining",
                BuffDescription.formatDuration(entry.getDurationRemaining())).formatted(Formatting.GRAY));
        Map<Identifier, ActiveFoodBuff.AppliedModifier> applied = entry.getAppliedModifiers();
        if (!applied.isEmpty()) {
            lines.addAll(BuffDescription.effectBlock(
                    appliedHealth(applied), appliedAttributes(applied), List.of()));
        }
        return lines;
    }

    /** What an aftermath is named after: its synergy, or the food whose buff it followed. */
    private static Text sourceName(ActiveFoodBuff entry) {
        return entry.isSynergy()
                ? BuffDescription.synergyName(entry.getTarget())
                : entry.peekConsumedItemStack().getName();
    }

    /**
     * The health bonus among a buff's recorded modifiers. PlayerFoodComponent names that
     * one {@code health_<scope>}; every other record is an attribute line.
     */
    private static double appliedHealth(Map<Identifier, ActiveFoodBuff.AppliedModifier> applied) {
        double health = 0;
        for (Map.Entry<Identifier, ActiveFoodBuff.AppliedModifier> entry : applied.entrySet()) {
            if (isHealthRecord(entry.getKey())) health += entry.getValue().amount();
        }
        return health;
    }

    /**
     * The attribute lines among a buff's recorded modifiers, back in the entry's order.
     * The records live in a hash map, but each id ends in the line's index in the entry
     * ({@code attr_<scope>_<namespace>_<path>_<index>}), so the order can be recovered.
     */
    private static List<FoodBuffData.AttributeData> appliedAttributes(
            Map<Identifier, ActiveFoodBuff.AppliedModifier> applied) {
        List<Map.Entry<Identifier, ActiveFoodBuff.AppliedModifier>> records = new ArrayList<>();
        for (Map.Entry<Identifier, ActiveFoodBuff.AppliedModifier> entry : applied.entrySet()) {
            if (!isHealthRecord(entry.getKey())) records.add(entry);
        }
        records.sort(Comparator
                .comparingInt((Map.Entry<Identifier, ActiveFoodBuff.AppliedModifier> e) ->
                        lineIndex(e.getKey().getPath()))
                .thenComparing(e -> e.getKey().getPath()));

        List<FoodBuffData.AttributeData> attributes = new ArrayList<>(records.size());
        for (Map.Entry<Identifier, ActiveFoodBuff.AppliedModifier> record : records) {
            ActiveFoodBuff.AppliedModifier modifier = record.getValue();
            attributes.add(new FoodBuffData.AttributeData(
                    modifier.attributeId(), modifier.amount(), modifier.operation()));
        }
        return attributes;
    }

    private static boolean isHealthRecord(Identifier modifierId) {
        String path = modifierId.getPath();
        // An aftermath's ids carry a prefix of their own; see PlayerFoodComponent#aftermathScope.
        if (path.startsWith(PlayerFoodComponent.AFTERMATH_MODIFIER_PREFIX)) {
            path = path.substring(PlayerFoodComponent.AFTERMATH_MODIFIER_PREFIX.length());
        }
        return path.startsWith("health_");
    }

    /** The trailing {@code _<n>} of a modifier path, or {@link Integer#MAX_VALUE} without one. */
    private static int lineIndex(String path) {
        int underscore = path.lastIndexOf('_');
        if (underscore < 0 || underscore == path.length() - 1) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(path.substring(underscore + 1));
        } catch (NumberFormatException notIndexed) {
            return Integer.MAX_VALUE;
        }
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
                            boolean isCompact, boolean isSynergized, int overdriveStage) {
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

        ItemStack stack = buff.peekConsumedItemStack();
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

        // Once the icon has settled, like the stack count it is styled after: a numeral
        // that pops in with the icon would be unreadable for the length of the slide.
        if (overdriveStage > 0 && appearProgress > 0.9f) {
            drawStageBadge(context, client, overdriveStage, x, y, slotHeight,
                    (alphaInt << 24) | STAGE_BADGE_COLOR);
        }

        if (!isCompact) {
            String timeStr = BuffDescription.formatDuration(remainingTicks);
            int    textY   = y + 8;
            int    timeX   = x + TIME_OFFSET_X;
            context.drawTextWithShadow(client.textRenderer, timeStr,
                    timeX, textY, (alphaInt << 24) | 0xFFFFFF);

            // The name starts after the time as it is actually drawn, never before the
            // column a "05:00" ends at. It used to sit at a fixed x + 58, sized for mm:ss:
            // a buff of an hour or more ("1:30:00") ran its time straight into the name.
            int timeWidth = Math.max(client.textRenderer.getWidth(timeStr),
                    client.textRenderer.getWidth(TIME_COLUMN_SAMPLE));
            int nameX     = timeX + timeWidth + NAME_GAP;
            int nameRoom  = x + slotWidth - NAME_RIGHT_PADDING - nameX;
            String name = stack.getName().getString();
            if (nameRoom <= 0) {
                name = "";
            } else if (client.textRenderer.getWidth(name) > nameRoom) {
                String ellipsis = "...";
                name = client.textRenderer.trimToWidth(name,
                        Math.max(0, nameRoom - client.textRenderer.getWidth(ellipsis))) + ellipsis;
            }
            if (!name.isEmpty()) {
                context.drawTextWithShadow(client.textRenderer, name,
                        nameX, textY, (alphaInt << 24) | 0xAAAAAA);
            }
        }
    }

    /** Frame and fill of an aftermath row: a red tint, so it never reads as a buff. */
    private static final int AFTERMATH_FILL    = 0x96280808;
    private static final int AFTERMATH_BORDER  = 0xFFAA2A2A;
    /** The corner marker, and the remaining-time bar. */
    private static final int AFTERMATH_ACCENT  = 0xFFFF5555;
    private static final int AFTERMATH_BAR     = 0xFFAA3333;
    private static final int AFTERMATH_TIME    = 0xFFFF9A9A;
    private static final int AFTERMATH_NAME    = 0xFFB08080;

    /**
     * One aftermath row. Same footprint as a buff slot, so it lines up with the column,
     * but deliberately unlike one: a red-tinted frame, a red bar, and a small red notch in
     * the corner, so a glance tells "the cost of a buff" from "a buff". No slide-in and no
     * expiry pulse either — those say "this is about to stop helping you", and an
     * aftermath running out is good news.
     */
    private void renderAftermath(DrawContext context, MinecraftClient client, ActiveFoodBuff entry,
                                 int x, int y, int slotWidth, int slotHeight, boolean isCompact) {
        float initialDuration = Math.max(1.0f, (float) entry.getInitialDuration());
        int   remainingTicks  = entry.getDurationRemaining();
        float progress        = MathHelper.clamp(remainingTicks / initialDuration, 0.0f, 1.0f);

        context.fill(x + 1, y + 1, x + slotWidth - 1, y + slotHeight - 1, AFTERMATH_FILL);
        context.drawBorder(x, y, slotWidth, slotHeight, AFTERMATH_BORDER);
        // The marker: a stepped notch in the top-right corner, three pixels deep.
        context.fill(x + slotWidth - 4, y + 1, x + slotWidth - 1, y + 2, AFTERMATH_ACCENT);
        context.fill(x + slotWidth - 3, y + 2, x + slotWidth - 1, y + 3, AFTERMATH_ACCENT);
        context.fill(x + slotWidth - 2, y + 3, x + slotWidth - 1, y + 4, AFTERMATH_ACCENT);

        int barWidth = (int) ((slotWidth - 2) * progress);
        if (barWidth > 0) {
            context.fill(x + 1, y + slotHeight - 3, x + 1 + barWidth, y + slotHeight - 1, AFTERMATH_BAR);
        }

        ItemStack stack = entry.peekConsumedItemStack();
        context.getMatrices().push();
        if (HudConfig.iconSize == HudConfig.IconSize.SMALL) {
            float scale    = 0.75f;
            int   offsetXY = (int) ((slotHeight - (16 * scale)) / 2);
            context.getMatrices().translate(x + offsetXY, y + offsetXY - 1, 0);
            context.getMatrices().scale(scale, scale, 1.0f);
            context.drawItem(stack, 0, 0);
        } else {
            context.drawItem(stack, x + 4, y + 2);
        }
        context.getMatrices().pop();

        int stage = aftermathStageOf(entry);
        if (stage > 0) drawStageBadge(context, client, stage, x, y, slotHeight, AFTERMATH_STAGE_COLOR);

        if (!isCompact) {
            String timeStr = BuffDescription.formatDuration(remainingTicks);
            int    textY   = y + 8;
            int    timeX   = x + TIME_OFFSET_X;
            context.drawTextWithShadow(client.textRenderer, timeStr, timeX, textY, AFTERMATH_TIME);

            int timeWidth = Math.max(client.textRenderer.getWidth(timeStr),
                    client.textRenderer.getWidth(TIME_COLUMN_SAMPLE));
            int nameX     = timeX + timeWidth + NAME_GAP;
            // Clear of the corner marker as well as the border.
            int nameRoom  = x + slotWidth - NAME_RIGHT_PADDING - 3 - nameX;
            String name = sourceName(entry).getString();
            if (nameRoom <= 0) {
                name = "";
            } else if (client.textRenderer.getWidth(name) > nameRoom) {
                String ellipsis = "...";
                name = client.textRenderer.trimToWidth(name,
                        Math.max(0, nameRoom - client.textRenderer.getWidth(ellipsis))) + ellipsis;
            }
            if (!name.isEmpty()) {
                context.drawTextWithShadow(client.textRenderer, name, nameX, textY, AFTERMATH_NAME);
            }
        }
    }

    /** The overdrive numeral on a buff slot: the synergized border's gold, a shade lighter. */
    private static final int STAGE_BADGE_COLOR     = 0xFFD75F;
    /** ...and on an aftermath row, the row's own red accent. */
    private static final int AFTERMATH_STAGE_COLOR = 0xFFFF7070;
    /** Badge text scale: small enough to sit on a 12-pixel icon, large enough to read "III". */
    private static final float STAGE_BADGE_SCALE   = 0.75f;

    /**
     * The overdrive stage as a small Roman numeral on the bottom-right corner of the slot's
     * icon — where vanilla draws a stack count, so it reads as a number belonging to the
     * icon, and the one corner of the slot nothing else uses: the bar runs along the bottom
     * edge below it, the aftermath notch sits top-right, and in the non-compact layout the
     * time and name start to its right. Drawn above the item (z 200) the way the count is.
     *
     * <p>Anchored to the icon rather than the slot, so it follows the icon in both icon
     * sizes and in compact mode, where the icon is all the slot shows.
     */
    private static void drawStageBadge(DrawContext context, MinecraftClient client, int stage,
                                       int x, int y, int slotHeight, int color) {
        boolean small  = HudConfig.iconSize == HudConfig.IconSize.SMALL;
        int     size   = small ? 12 : 16;
        int     iconX  = small ? x + (slotHeight - size) / 2 : x + 4;
        int     iconY  = small ? y + (slotHeight - size) / 2 - 1 : y + 2;
        String  text   = RomanNumerals.of(stage);

        context.getMatrices().push();
        context.getMatrices().translate(iconX + size + 1, iconY + size + 1, 200);
        context.getMatrices().scale(STAGE_BADGE_SCALE, STAGE_BADGE_SCALE, 1.0f);
        context.drawTextWithShadow(client.textRenderer, text,
                -client.textRenderer.getWidth(text), -8, color);
        context.getMatrices().pop();
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
