package net.tend1tnuy.florafare;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.network.ClientPlayerEntity;
import net.tend1tnuy.florafare.client.FlorafareHud;
import net.tend1tnuy.florafare.client.FoodJournalScreen;
import net.tend1tnuy.florafare.client.toast.FoodDiscoveryToast;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;
import net.tend1tnuy.florafare.network.FoodUnlockedPayload;
import net.tend1tnuy.florafare.network.OpenFoodJournalPayload;

/**
 * Client-side entry point for the Florafare mod.
 * Handles networking synchronization and HUD rendering.
 */
public class FlorafareClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Реєстрація синхронізації бафів
        ClientPlayNetworking.registerGlobalReceiver(FoodBuffSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                ClientPlayerEntity player = context.player();
                if (player != null) {
                    ((IFoodComponentProvider) player).florafare$getFoodComponent().readFromNbt(payload.nbt());
                }
            });
        });

        // === ЕТАП 3: Відкриття графічного інтерфейсу Книги ===
        ClientPlayNetworking.registerGlobalReceiver(OpenFoodJournalPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                // Відкриваємо наш новий екран
                context.client().setScreen(new FoodJournalScreen());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(FoodUnlockedPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                // Додаємо тост у менеджер
                context.client().getToastManager().add(new FoodDiscoveryToast());
            });
        });

        // Реєстрація HUD
        HudRenderCallback.EVENT.register(new FlorafareHud());
    }
}