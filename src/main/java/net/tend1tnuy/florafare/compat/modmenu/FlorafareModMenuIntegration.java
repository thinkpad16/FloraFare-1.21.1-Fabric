package net.tend1tnuy.florafare.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.tend1tnuy.florafare.client.FoodJournalScreen;
import net.tend1tnuy.florafare.client.HudConfig;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.config.FlorafareConfig;

import java.util.Arrays;
import java.util.Optional;

/**
 * ModMenu integration: builds a full in-game settings screen for every FlorafareConfig
 * field via Cloth Config. Loaded only if ModMenu is installed (via the "modmenu"
 * entrypoint) — Cloth Config itself is bundled with the mod (jar-in-jar) so this never
 * crashes even when the server/client doesn't have Cloth Config as a separate mod.
 */
public class FlorafareModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Text.translatable("gui.florafare.config.title"))
                    .setSavingRunnable(FlorafareModMenuIntegration::applyAndSave);

            ConfigEntryBuilder entry = builder.entryBuilder();

            ConfigCategory general = builder.getOrCreateCategory(
                    Text.translatable("gui.florafare.config.category.general"));
            general.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.enable_synergies"),
                            FlorafareConfig.localEnableSynergies())
                    .setTooltip(serverNote())
                    .setSaveConsumer(FlorafareConfig::setLocalEnableSynergies)
                    .build());
            general.addEntry(entry.startEnumSelector(
                            Text.translatable("gui.florafare.config.consumption_logging"),
                            FlorafareConfig.LogLevel.class, FlorafareConfig.consumptionLogging)
                    .setSaveConsumer(v -> FlorafareConfig.consumptionLogging = v)
                    .build());
            general.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.grant_journal_on_join"),
                            FlorafareConfig.grantJournalOnFirstJoin)
                    .setSaveConsumer(v -> FlorafareConfig.grantJournalOnFirstJoin = v)
                    .build());
            general.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.enable_forgotten_mead"),
                            FlorafareConfig.localEnableForgottenMead())
                    .setTooltip(serverNote())
                    .setSaveConsumer(FlorafareConfig::setLocalEnableForgottenMead)
                    .build());
            general.addEntry(entry.startIntField(
                            Text.translatable("gui.florafare.config.command_permission_level"),
                            FlorafareConfig.commandPermissionLevel)
                    .setMin(0).setMax(4)
                    .setSaveConsumer(v -> FlorafareConfig.commandPermissionLevel = v)
                    .build());

            ConfigCategory balance = builder.getOrCreateCategory(
                    Text.translatable("gui.florafare.config.category.balance"));
            balance.addEntry(entry.startIntField(
                            Text.translatable("gui.florafare.config.max_buff_slots"),
                            FlorafareConfig.localMaxBuffSlots())
                    .setMin(FlorafareConfig.MIN_BUFF_SLOTS).setMax(FlorafareConfig.MAX_BUFF_SLOTS)
                    .setTooltip(serverNote())
                    .setSaveConsumer(FlorafareConfig::setLocalMaxBuffSlots)
                    .build());
            balance.addEntry(entry.startIntField(
                            Text.translatable("gui.florafare.config.auto_gen_duration_multiplier"),
                            FlorafareConfig.localAutoGenDurationMultiplier())
                    .setMin(0).setMax(FlorafareConfig.MAX_AUTO_GEN_DURATION_MULT)
                    .setTooltip(serverNote())
                    .setSaveConsumer(FlorafareConfig::setLocalAutoGenDurationMultiplier)
                    .build());
            balance.addEntry(entry.startDoubleField(
                            Text.translatable("gui.florafare.config.auto_gen_health_multiplier"),
                            FlorafareConfig.localAutoGenHealthMultiplier())
                    .setMin(0.0).setMax(FlorafareConfig.MAX_AUTO_GEN_HEALTH_MULT)
                    .setTooltip(serverNote())
                    .setSaveConsumer(FlorafareConfig::setLocalAutoGenHealthMultiplier)
                    .build());
            balance.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.enable_always_edible_override"),
                            FlorafareConfig.localEnableAlwaysEdibleOverride())
                    .setTooltip(serverNote())
                    .setSaveConsumer(FlorafareConfig::setLocalEnableAlwaysEdibleOverride)
                    .build());
            balance.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.allow_eating_when_full"),
                            FlorafareConfig.localAllowEatingWhenFull())
                    .setTooltip(serverNote(
                            Text.translatable("gui.florafare.config.allow_eating_when_full.tooltip")))
                    .setSaveConsumer(FlorafareConfig::setLocalAllowEatingWhenFull)
                    .build());
            balance.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.respect_vanilla_food_effects"),
                            FlorafareConfig.localRespectVanillaFoodEffects())
                    .setTooltip(serverNote())
                    .setSaveConsumer(FlorafareConfig::setLocalRespectVanillaFoodEffects)
                    .build());

            ConfigCategory hud = builder.getOrCreateCategory(
                    Text.translatable("gui.florafare.config.category.hud"));
            hud.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.enable_hud"),
                            FlorafareConfig.enableHud)
                    .setTooltip(Text.translatable("gui.florafare.config.enable_hud.tooltip"))
                    .setSaveConsumer(v -> FlorafareConfig.enableHud = v)
                    .build());
            hud.addEntry(entry.startEnumSelector(
                            Text.translatable("gui.florafare.config.hud_layout"),
                            HudConfig.LayoutMode.class, HudConfig.layoutMode)
                    .setSaveConsumer(v -> HudConfig.layoutMode = v)
                    .build());
            hud.addEntry(entry.startEnumSelector(
                            Text.translatable("gui.florafare.config.hud_icon_size"),
                            HudConfig.IconSize.class, HudConfig.iconSize)
                    .setSaveConsumer(v -> HudConfig.iconSize = v)
                    .build());
            hud.addEntry(entry.startEnumSelector(
                            Text.translatable("gui.florafare.config.hud_position"),
                            HudConfig.ScreenPosition.class, HudConfig.position)
                    .setSaveConsumer(v -> HudConfig.position = v)
                    .build());
            // Fine positioning lives on the in-game placement screen (the mod's keybind,
            // then "Move overlay"); these two mirror the stored result so a pack author
            // can still set it by hand.
            hud.addEntry(entry.startIntField(
                            Text.translatable("gui.florafare.config.hud_offset_x"),
                            HudConfig.offsetX)
                    .setSaveConsumer(v -> HudConfig.offsetX = v)
                    .build());
            hud.addEntry(entry.startIntField(
                            Text.translatable("gui.florafare.config.hud_offset_y"),
                            HudConfig.offsetY)
                    .setSaveConsumer(v -> HudConfig.offsetY = v)
                    .build());
            hud.addEntry(entry.startFloatField(
                            Text.translatable("gui.florafare.config.hud_scale"),
                            HudConfig.scale)
                    .setMin(FlorafareConfig.HUD_SCALE_MIN).setMax(FlorafareConfig.HUD_SCALE_MAX)
                    .setSaveConsumer(v -> HudConfig.scale = v)
                    .build());

            ConfigCategory journal = builder.getOrCreateCategory(
                    Text.translatable("gui.florafare.config.category.journal"));
            journal.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.enable_discovery_toasts"),
                            FlorafareConfig.enableDiscoveryToasts)
                    .setSaveConsumer(v -> FlorafareConfig.enableDiscoveryToasts = v)
                    .build());
            journal.addEntry(entry.startIntField(
                            Text.translatable("gui.florafare.config.toast_display_time_ms"),
                            FlorafareConfig.toastDisplayTimeMs)
                    .setMin(FlorafareConfig.MIN_TOAST_MS).setMax(FlorafareConfig.MAX_TOAST_MS)
                    .setSaveConsumer(v -> FlorafareConfig.toastDisplayTimeMs = v)
                    .build());
            journal.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.strip_food_tooltips"),
                            FlorafareConfig.stripFoodTooltips)
                    .setTooltip(Text.translatable("gui.florafare.config.strip_food_tooltips.tooltip"))
                    .setSaveConsumer(v -> FlorafareConfig.stripFoodTooltips = v)
                    .build());
            journal.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.show_buff_tooltips"),
                            FlorafareConfig.showBuffTooltips)
                    .setTooltip(Text.translatable("gui.florafare.config.show_buff_tooltips.tooltip"))
                    .setSaveConsumer(v -> FlorafareConfig.showBuffTooltips = v)
                    .build());
            journal.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.journal_shows_auto_generated"),
                            FlorafareConfig.journalShowsAutoGenerated)
                    .setTooltip(Text.translatable("gui.florafare.config.journal_shows_auto_generated.tooltip"))
                    .setSaveConsumer(v -> FlorafareConfig.journalShowsAutoGenerated = v)
                    .build());

            return builder.build();
        };
    }

    /**
     * Writes the edited settings out, and makes the ones that are cached elsewhere take
     * effect immediately.
     *
     * <p>Saving alone was not enough. Several of these fields are mirrored into other
     * places for speed — the buff slot count into {@code PlayerFoodComponent}, the
     * auto-generation multipliers into {@code FoodBuffManager}, the whole food list into
     * the journal's static cache — and nothing refreshed them when the screen closed. So
     * flipping "list auto-generated foods in the journal" and reopening the journal
     * showed exactly the same list as before, which reads as a dead switch rather than a
     * setting.
     *
     * <p>The gameplay mirrors are only touched while no server is overriding these
     * fields; while one is, the edit went into the player's own snapshot rather than the
     * live values, and {@code ClientConfigOverride} applies it on disconnect instead.
     * The journal cache is always dropped, because the settings that decide what the
     * journal lists are client-local and take effect at once.
     */
    private static void applyAndSave() {
        HudConfig.saveToConfig();

        if (!FlorafareConfig.isServerOverridden()) {
            PlayerFoodComponent.setMaxBuffSlots(FlorafareConfig.maxBuffSlots);
            FoodBuffManager.AUTO_GEN_DURATION_MULT = FlorafareConfig.autoGenDurationMultiplier;
            FoodBuffManager.AUTO_GEN_HEALTH_MULT   = FlorafareConfig.autoGenHealthMultiplier;
            // Auto-generated buffs are memoized, so the ones built with the old
            // multipliers have to go.
            FoodBuffManager.invalidateResolutionCache();
        }

        FoodJournalScreen.invalidateCache();
    }

    /**
     * Builds the tooltip for a setting a connected server is currently overriding.
     *
     * <p>These entries show and edit the player's OWN value, not the server's — the live
     * config fields hold the server's while a connection is up, and presenting those
     * would show a stranger's balance as though the player had chosen it (and, on Save,
     * write it into their config file). The note says so, and is added only while a
     * server is actually overriding anything.
     */
    private static Optional<Text[]> serverNote(Text... tooltip) {
        if (!FlorafareConfig.isServerOverridden()) {
            return tooltip.length == 0 ? Optional.empty() : Optional.of(tooltip);
        }
        Text[] withNote = Arrays.copyOf(tooltip, tooltip.length + 1);
        withNote[tooltip.length] =
                Text.translatable("gui.florafare.config.server_overridden.tooltip")
                        .formatted(Formatting.GOLD);
        return Optional.of(withNote);
    }
}
