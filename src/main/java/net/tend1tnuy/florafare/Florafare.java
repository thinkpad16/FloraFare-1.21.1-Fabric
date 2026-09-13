package net.tend1tnuy.florafare;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.command.DumpFoodsCommand;
import net.tend1tnuy.florafare.command.SetBuffCommand;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodReloadListener;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.food.FoodSynergyReloadListener;
import net.tend1tnuy.florafare.network.*;
import net.tend1tnuy.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Florafare mod.
 * Handles the registration of items, network payloads, commands, and server events.
 */
public class Florafare implements ModInitializer {
    public static final String MOD_ID = "florafare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Florafare!");

        // 1. Config
        net.tend1tnuy.florafare.config.FlorafareConfig.load();
        net.tend1tnuy.florafare.component.PlayerFoodComponent.MAX_BUFF_SLOTS =
                net.tend1tnuy.florafare.config.FlorafareConfig.maxBuffSlots;
        for (String itemId : net.tend1tnuy.florafare.config.FlorafareConfig.ignoredFoodItems) {
            FoodBuffManager.excludeItem(itemId);
        }

        // 2. Items & creative tab
        ItemRegistry.initialize();
        net.tend1tnuy.registry.FlorafareItemGroups.registerItemGroups();

        // 3. Datapack reload listeners
        ResourceManagerHelper.get(ResourceType.SERVER_DATA)
                .registerReloadListener(new FoodReloadListener());
        ResourceManagerHelper.get(ResourceType.SERVER_DATA)
                .registerReloadListener(new FoodSynergyReloadListener());

        // 4. Runtime buff persistence (command-applied buffs)
        FoodBuffManager.loadRuntimeConfigs();

        // 5. Network payload types (S2C)
        PayloadTypeRegistry.playS2C().register(FoodUnlockedPayload.ID,    FoodUnlockedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodBuffSyncPayload.ID,    FoodBuffSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BuffStateSyncPayload.ID,   BuffStateSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenFoodJournalPayload.ID, OpenFoodJournalPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SynergyUnlockedPayload.ID, SynergyUnlockedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodConfigSyncPayload.ID,  FoodConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodSynergySyncPayload.ID, FoodSynergySyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FlorafareServerConfigSyncPayload.ID, FlorafareServerConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodExclusionSyncPayload.ID, FoodExclusionSyncPayload.CODEC);

        // 6. Commands — registered together in one logical block (#22)
        CommandRegistrationCallback.EVENT.register(DumpFoodsCommand::register);
        CommandRegistrationCallback.EVENT.register(SetBuffCommand::register);

        // 7. Re-sync configs to all players after a /reload
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            FoodConfigSyncPayload  configPayload  =
                    new FoodConfigSyncPayload(FoodBuffManager.serializeConfigs());
            FoodSynergySyncPayload synergyPayload =
                    new FoodSynergySyncPayload(FoodSynergyManager.serializeSynergies());
            FlorafareServerConfigSyncPayload serverConfigPayload = buildServerConfigSyncPayload();
            FoodExclusionSyncPayload exclusionPayload =
                    FoodExclusionSyncPayload.of(FoodBuffManager.getExcludedItems());
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerPlayNetworking.send(player, configPayload);
                ServerPlayNetworking.send(player, synergyPayload);
                ServerPlayNetworking.send(player, serverConfigPayload);
                ServerPlayNetworking.send(player, exclusionPayload);
            }
        });

        // 8. Player state events
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            PlayerFoodComponent oldComp =
                    ((IFoodComponentProvider) oldPlayer).florafare$getFoodComponent();
            PlayerFoodComponent newComp =
                    ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent();

            if (alive) {
                // ServerPlayerEntity#copyFrom has already run setHealth() against the
                // new entity, whose max health is still the un-buffed 20 — so a player
                // crossing a portal at 24/24 was silently clamped to 20 before the +4
                // came back. Reapply first, then restore what they actually had.
                float carriedHealth = oldPlayer.getHealth();

                newComp.copyFrom(oldComp);
                // Buff modifiers are temporary, and vanilla carries over only BASE
                // attribute values, so they must be reapplied to the new entity.
                newComp.reapplyAttributes();

                if (carriedHealth > newPlayer.getHealth()) {
                    newPlayer.setHealth(carriedHealth);
                }
            } else {
                // Journal progress and synergy discoveries persist through death.
                newComp.getDiscoveredFoods().addAll(oldComp.getDiscoveredFoods());
                newComp.getDiscoveredSynergies().addAll(oldComp.getDiscoveredSynergies());
                newComp.setHasReceivedJournal(oldComp.hasReceivedJournal());
            }
        });

        // 9. Initial sync and journal grant on join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerFoodComponent comp =
                    ((IFoodComponentProvider) handler.player).florafare$getFoodComponent();
            comp.sync();

            ServerPlayNetworking.send(handler.player,
                    new FoodConfigSyncPayload(FoodBuffManager.serializeConfigs()));
            ServerPlayNetworking.send(handler.player,
                    new FoodSynergySyncPayload(FoodSynergyManager.serializeSynergies()));
            ServerPlayNetworking.send(handler.player, buildServerConfigSyncPayload());
            ServerPlayNetworking.send(handler.player,
                    FoodExclusionSyncPayload.of(FoodBuffManager.getExcludedItems()));

            if (!comp.hasReceivedJournal()
                    && net.tend1tnuy.florafare.config.FlorafareConfig.grantJournalOnFirstJoin) {
                ItemStack journal = new ItemStack(ItemRegistry.FOOD_JOURNAL);
                handler.player.getInventory().offerOrDrop(journal);
                comp.setHasReceivedJournal(true);
            }
        });

        // 10. Re-sync after respawn
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent().sync());
    }

    /**
     * Utility method for creating mod-specific identifiers.
     *
     * @param path The path for the identifier.
     * @return The formatted Identifier.
     */
    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    /** Snapshots the server-authoritative subset of {@code FlorafareConfig} for client sync. */
    private static FlorafareServerConfigSyncPayload buildServerConfigSyncPayload() {
        return new FlorafareServerConfigSyncPayload(
                net.tend1tnuy.florafare.config.FlorafareConfig.maxBuffSlots,
                net.tend1tnuy.florafare.config.FlorafareConfig.autoGenDurationMultiplier,
                net.tend1tnuy.florafare.config.FlorafareConfig.autoGenHealthMultiplier,
                net.tend1tnuy.florafare.config.FlorafareConfig.enableSynergies,
                net.tend1tnuy.florafare.config.FlorafareConfig.enableAlwaysEdibleOverride,
                net.tend1tnuy.florafare.config.FlorafareConfig.allowEatingWhenFull,
                net.tend1tnuy.florafare.config.FlorafareConfig.respectVanillaFoodEffects,
                net.tend1tnuy.florafare.config.FlorafareConfig.enableForgottenMead
        );
    }
}