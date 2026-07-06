package net.tend1tnuy.florafare;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.tend1tnuy.florafare.client.FlorafareHud;
import net.tend1tnuy.florafare.client.FoodJournalScreen;
import net.tend1tnuy.florafare.client.HudConfigScreen;
import net.tend1tnuy.florafare.client.toast.FoodDiscoveryToast;
import net.tend1tnuy.florafare.component.HurtAnimationSuppressor;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;
import net.tend1tnuy.florafare.network.FoodConfigSyncPayload;
import net.tend1tnuy.florafare.network.FoodSynergySyncPayload;
import net.tend1tnuy.florafare.network.FoodUnlockedPayload;
import net.tend1tnuy.florafare.network.OpenFoodJournalPayload;
import net.tend1tnuy.florafare.network.SuppressHurtAnimationPayload;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side entry point for the Florafare mod.
 * Handles networking synchronization and HUD rendering.
 */
public class FlorafareClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Restore persisted HUD settings (the config file itself is loaded in the main entrypoint)
        net.tend1tnuy.florafare.client.HudConfig.loadFromConfig();

        KeyBinding hudConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.florafare.hud_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.florafare.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (hudConfigKey.wasPressed()) {
                client.setScreen(new HudConfigScreen());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(FoodBuffSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                ClientPlayerEntity player = context.player();
                if (player != null) {
                    ((IFoodComponentProvider) player).florafare$getFoodComponent().readFromNbt(payload.nbt());
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(FoodConfigSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> FoodBuffManager.loadConfigsFromNbt(payload.nbt()));
        });

        ClientPlayNetworking.registerGlobalReceiver(FoodSynergySyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> FoodSynergyManager.loadSynergiesFromNbt(payload.nbt()));
        });

        // The next health drop is a max-health clamp (buff expiry), not damage —
        // arm the one-shot flag that skips the hurt flash/camera tilt for it.
        ClientPlayNetworking.registerGlobalReceiver(SuppressHurtAnimationPayload.ID, (payload, context) -> {
            context.client().execute(HurtAnimationSuppressor::arm);
        });

        ClientPlayNetworking.registerGlobalReceiver(OpenFoodJournalPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                context.client().setScreen(new FoodJournalScreen());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(FoodUnlockedPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                context.client().getToastManager().add(new FoodDiscoveryToast());
            });
        });

        HudRenderCallback.EVENT.register(new FlorafareHud());
    }
}