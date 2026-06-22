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
 * Handles network synchronization and HUD rendering.
 */
public class FlorafareClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        // Registers food buff synchronization from server
        ClientPlayNetworking.registerGlobalReceiver(FoodBuffSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                ClientPlayerEntity player = context.player();

                if (player != null) {
                    ((IFoodComponentProvider) player)
                            .florafare$getFoodComponent()
                            .readFromNbt(payload.nbt());
                }
            });
        });

        // Opens the Food Journal GUI
        ClientPlayNetworking.registerGlobalReceiver(OpenFoodJournalPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                context.client().setScreen(new FoodJournalScreen());
            });
        });

        // Shows a toast notification when a new food is discovered
        ClientPlayNetworking.registerGlobalReceiver(FoodUnlockedPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                context.client().getToastManager().add(new FoodDiscoveryToast());
            });
        });

        // Registers HUD overlay rendering
        HudRenderCallback.EVENT.register(new FlorafareHud());
    }
}