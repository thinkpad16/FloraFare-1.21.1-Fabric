package net.tend1tnuy.florafare;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.food.FoodReloadListener;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;
import net.tend1tnuy.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.tend1tnuy.florafare.command.SetBuffCommand;
public class Florafare implements ModInitializer {
	public static final String MOD_ID = "florafare";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Florafare is initializing!");
        ItemRegistry.initialize();
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new FoodReloadListener());
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new FoodReloadListener());
        net.tend1tnuy.florafare.food.FoodBuffManager.loadRuntimeConfigs();
        // 1. Реєстрація мережевого пакету
        PayloadTypeRegistry.playS2C().register(FoodBuffSyncPayload.ID, FoodBuffSyncPayload.CODEC);

        CommandRegistrationCallback.EVENT.register(SetBuffCommand::register);
        // 2. Івент: Відновлення бафів після смерті (Клонування)
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            // Якщо alive == true (це гравець повертається з виміру Енд), ми копіюємо бафи
            if (alive) {
                ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent()
                        .copyFrom(((IFoodComponentProvider) oldPlayer).florafare$getFoodComponent());
            }
            // Якщо alive == false (це реальна смерть), ми нічого не копіюємо.
            // Новий гравець з'явиться з порожніми слотами, а AFTER_RESPAWN синхронізує це з HUD.
        });

        // 3. Івент: Синхронізація при вході на сервер та після спавну
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ((IFoodComponentProvider) handler.player).florafare$getFoodComponent().sync();
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent().sync();
        });
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
