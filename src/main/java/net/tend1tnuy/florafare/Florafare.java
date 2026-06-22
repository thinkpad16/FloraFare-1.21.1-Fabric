package net.tend1tnuy.florafare;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
import net.tend1tnuy.florafare.command.SetBuffCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Florafare implements ModInitializer {

    public static final String MOD_ID = "florafare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Florafare is initializing!");

        ItemRegistry.initialize();

        ResourceManagerHelper.get(ResourceType.SERVER_DATA)
                .registerReloadListener(new FoodReloadListener());

        net.tend1tnuy.florafare.food.FoodBuffManager.loadRuntimeConfigs();

        // Register network packets
        PayloadTypeRegistry.playS2C().register(FoodUnlockedPayload.ID, FoodUnlockedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodBuffSyncPayload.ID, FoodBuffSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenFoodJournalPayload.ID, OpenFoodJournalPayload.CODEC);

        CommandRegistrationCallback.EVENT.register(SetBuffCommand::register);

        // Handle player data copy on respawn/death
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            if (alive) {
                ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent()
                        .copyFrom(((IFoodComponentProvider) oldPlayer).florafare$getFoodComponent());
            } else {
                // Preserve journal progress even after death
                PlayerFoodComponent oldComp =
                        ((IFoodComponentProvider) oldPlayer).florafare$getFoodComponent();

                PlayerFoodComponent newComp =
                        ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent();

                newComp.getDiscoveredFoods().addAll(oldComp.getDiscoveredFoods());
                newComp.setHasReceivedJournal(oldComp.hasReceivedJournal());
            }
        });

        // Sync data and give journal on player join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerFoodComponent comp =
                    ((IFoodComponentProvider) handler.player).florafare$getFoodComponent();

            comp.sync();

            // Give the journal if the player has not received it yet
            if (!comp.hasReceivedJournal()) {
                ItemStack journal = new ItemStack(ItemRegistry.FOOD_JOURNAL);

                // Offers item to inventory or drops it if inventory is full
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