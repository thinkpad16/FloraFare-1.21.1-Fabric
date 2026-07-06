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


        net.tend1tnuy.florafare.config.FlorafareConfig.load();
        // Register mod items and data components
        ItemRegistry.initialize();

        CommandRegistrationCallback.EVENT.register(DumpFoodsCommand::register);

        net.tend1tnuy.registry.FlorafareItemGroups.registerItemGroups();

        // Register datapack reload listener for food buff configurations
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new FoodReloadListener());

        // Register datapack reload listener for secret food synergies
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new FoodSynergyReloadListener());

        // Load runtime generated food buffs (e.g., from commands)
        FoodBuffManager.loadRuntimeConfigs();

        // Register S2C (Server to Client) network payloads
        PayloadTypeRegistry.playS2C().register(FoodUnlockedPayload.ID, FoodUnlockedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodBuffSyncPayload.ID, FoodBuffSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenFoodJournalPayload.ID, OpenFoodJournalPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SynergyUnlockedPayload.ID, SynergyUnlockedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodConfigSyncPayload.ID, FoodConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodSynergySyncPayload.ID, FoodSynergySyncPayload.CODEC);

        // Re-sync food configs and synergies to every player whenever datapacks are
        // (re)loaded, so client-side features keep showing up-to-date data.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            FoodConfigSyncPayload configPayload = new FoodConfigSyncPayload(FoodBuffManager.serializeConfigs());
            FoodSynergySyncPayload synergyPayload = new FoodSynergySyncPayload(FoodSynergyManager.serializeSynergies());
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerPlayNetworking.send(player, configPayload);
                ServerPlayNetworking.send(player, synergyPayload);
            }
        });

        // Register commands
        CommandRegistrationCallback.EVENT.register(SetBuffCommand::register);

        // Handle player state restoration (death or dimension change)
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            PlayerFoodComponent oldComp = ((IFoodComponentProvider) oldPlayer).florafare$getFoodComponent();
            PlayerFoodComponent newComp = ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent();

            if (alive) {
                newComp.copyFrom(oldComp);
                // Vanilla only copies BASE attribute values to the new player entity,
                // so the buff-granted attribute modifiers must be reapplied explicitly.
                newComp.reapplyAttributes();
            } else {
                // Ensure journal progress, discovered foods, and secret synergies persist through death
                newComp.getDiscoveredFoods().addAll(oldComp.getDiscoveredFoods());
                newComp.getDiscoveredSynergies().addAll(oldComp.getDiscoveredSynergies());
                newComp.setHasReceivedJournal(oldComp.hasReceivedJournal());
            }
        });

        // Handle initial synchronization and item distribution upon joining the server
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerFoodComponent comp = ((IFoodComponentProvider) handler.player).florafare$getFoodComponent();
            comp.sync();

            // Replicate the resolved food configs and synergies so client-side features
            // (food journal, HUD, AppleSkin tooltip) can show them on dedicated servers.
            ServerPlayNetworking.send(handler.player, new FoodConfigSyncPayload(FoodBuffManager.serializeConfigs()));
            ServerPlayNetworking.send(handler.player, new FoodSynergySyncPayload(FoodSynergyManager.serializeSynergies()));

            // Grant the culinary journal if the player has not received it yet
            if (!comp.hasReceivedJournal()) {
                ItemStack journal = new ItemStack(ItemRegistry.FOOD_JOURNAL);
                // Adds to inventory; drops on the ground if inventory is full
                handler.player.getInventory().offerOrDrop(journal);
                comp.setHasReceivedJournal(true);
            }
        });


        // Ensure client is updated immediately after the player respawns
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent().sync();
        });
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
}