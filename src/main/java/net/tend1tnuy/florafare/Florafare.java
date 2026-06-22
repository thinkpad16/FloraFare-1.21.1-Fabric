package net.tend1tnuy.florafare;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodReloadListener;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;
import net.tend1tnuy.florafare.network.FoodUnlockedPayload;
import net.tend1tnuy.florafare.network.OpenFoodJournalPayload;
import net.tend1tnuy.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.tend1tnuy.florafare.command.SetBuffCommand;

public class Florafare implements ModInitializer {
    public static final String MOD_ID = "florafare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Florafare is initializing!");
        ItemRegistry.initialize();
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new FoodReloadListener());
        net.tend1tnuy.florafare.food.FoodBuffManager.loadRuntimeConfigs();

        // 1. Register network packets
        PayloadTypeRegistry.playS2C().register(FoodUnlockedPayload.ID, FoodUnlockedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodBuffSyncPayload.ID, FoodBuffSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenFoodJournalPayload.ID, OpenFoodJournalPayload.CODEC); // === STAGE 2 ===

        CommandRegistrationCallback.EVENT.register(SetBuffCommand::register);

        // 2. Event: Restore buffs after death
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            if (alive) {
                ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent()
                        .copyFrom(((IFoodComponentProvider) oldPlayer).florafare$getFoodComponent());
            } else {
                // Even after actual death, we want to save the journal progress
                PlayerFoodComponent oldComp = ((IFoodComponentProvider) oldPlayer).florafare$getFoodComponent();
                PlayerFoodComponent newComp = ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent();
                newComp.getDiscoveredFoods().addAll(oldComp.getDiscoveredFoods());
                newComp.setHasReceivedJournal(oldComp.hasReceivedJournal());
            }
        });

        // 3. Event: Synchronization on join and === GIVE JOURNAL ===
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerFoodComponent comp = ((IFoodComponentProvider) handler.player).florafare$getFoodComponent();
            comp.sync();

            // === STAGE 2: Give the journal if the player hasn't received it yet ===
            if (!comp.hasReceivedJournal()) {
                ItemStack journal = new ItemStack(ItemRegistry.FOOD_JOURNAL);
                // offerOrDrop adds to inventory, if full - drops on the ground
                handler.player.getInventory().offerOrDrop(journal);
                comp.setHasReceivedJournal(true);
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent().sync();
        });
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}