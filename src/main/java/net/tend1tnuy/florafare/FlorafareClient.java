package net.tend1tnuy.florafare.client;

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
        // Register buff synchronization
        ClientPlayNetworking.registerGlobalReceiver(FoodBuffSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                ClientPlayerEntity player = context.player();
                if (player != null) {
                    ((IFoodComponentProvider) player).florafare$getFoodComponent().readFromNbt(payload.nbt());
                }
            });
        });

        // === STAGE 3: Open the Journal GUI ===
        ClientPlayNetworking.registerGlobalReceiver(OpenFoodJournalPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                // Open our new screen
                context.client().setScreen(new FoodJournalScreen());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(FoodUnlockedPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                // Add toast to the manager
                context.client().getToastManager().add(new FoodDiscoveryToast());
            });
        });

        // Register HUD
        HudRenderCallback.EVENT.register(new FlorafareHud());
    }
}