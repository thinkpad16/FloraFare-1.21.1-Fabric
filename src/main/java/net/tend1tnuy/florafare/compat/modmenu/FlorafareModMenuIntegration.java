package net.tend1tnuy.florafare.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.text.Text;
import net.tend1tnuy.florafare.client.HudConfig;
import net.tend1tnuy.florafare.config.FlorafareConfig;

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
                    .setSavingRunnable(HudConfig::saveToConfig);

            ConfigEntryBuilder entry = builder.entryBuilder();

            ConfigCategory general = builder.getOrCreateCategory(
                    Text.translatable("gui.florafare.config.category.general"));
            general.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.enable_synergies"),
                            FlorafareConfig.enableSynergies)
                    .setSaveConsumer(v -> FlorafareConfig.enableSynergies = v)
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
                            FlorafareConfig.enableForgottenMead)
                    .setSaveConsumer(v -> FlorafareConfig.enableForgottenMead = v)
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
                            FlorafareConfig.maxBuffSlots)
                    .setMin(1).setMax(9)
                    .setSaveConsumer(v -> FlorafareConfig.maxBuffSlots = v)
                    .build());
            balance.addEntry(entry.startIntField(
                            Text.translatable("gui.florafare.config.auto_gen_duration_multiplier"),
                            FlorafareConfig.autoGenDurationMultiplier)
                    .setMin(0)
                    .setSaveConsumer(v -> FlorafareConfig.autoGenDurationMultiplier = v)
                    .build());
            balance.addEntry(entry.startDoubleField(
                            Text.translatable("gui.florafare.config.auto_gen_health_multiplier"),
                            FlorafareConfig.autoGenHealthMultiplier)
                    .setMin(0.0)
                    .setSaveConsumer(v -> FlorafareConfig.autoGenHealthMultiplier = v)
                    .build());
            balance.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.enable_always_edible_override"),
                            FlorafareConfig.enableAlwaysEdibleOverride)
                    .setSaveConsumer(v -> FlorafareConfig.enableAlwaysEdibleOverride = v)
                    .build());
            balance.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.allow_eating_when_full"),
                            FlorafareConfig.allowEatingWhenFull)
                    .setTooltip(Text.translatable("gui.florafare.config.allow_eating_when_full.tooltip"))
                    .setSaveConsumer(v -> FlorafareConfig.allowEatingWhenFull = v)
                    .build());
            balance.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.respect_vanilla_food_effects"),
                            FlorafareConfig.respectVanillaFoodEffects)
                    .setSaveConsumer(v -> FlorafareConfig.respectVanillaFoodEffects = v)
                    .build());

            ConfigCategory hud = builder.getOrCreateCategory(
                    Text.translatable("gui.florafare.config.category.hud"));
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
                    .setMin(500).setMax(20000)
                    .setSaveConsumer(v -> FlorafareConfig.toastDisplayTimeMs = v)
                    .build());
            journal.addEntry(entry.startBooleanToggle(
                            Text.translatable("gui.florafare.config.strip_food_tooltips"),
                            FlorafareConfig.stripFoodTooltips)
                    .setSaveConsumer(v -> FlorafareConfig.stripFoodTooltips = v)
                    .build());

            return builder.build();
        };
    }
}
