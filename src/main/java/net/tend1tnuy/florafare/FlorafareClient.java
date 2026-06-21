package net.tend1tnuy.florafare;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.network.ClientPlayerEntity;
import net.tend1tnuy.florafare.client.FlorafareHud;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;

public class FlorafareClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Приймаємо синхронізацію слотів з сервера
        ClientPlayNetworking.registerGlobalReceiver(FoodBuffSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                ClientPlayerEntity player = context.player();
                if (player != null) {
                    ((IFoodComponentProvider) player).florafare$getFoodComponent().readFromNbt(payload.nbt());
                }
            });
        });
        HudRenderCallback.EVENT.register(new FlorafareHud());
    }
}