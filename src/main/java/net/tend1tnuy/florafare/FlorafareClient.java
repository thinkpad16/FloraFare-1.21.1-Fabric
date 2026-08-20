package net.tend1tnuy.florafare;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.client.FlorafareHud;
import net.tend1tnuy.florafare.client.FoodJournalScreen;
import net.tend1tnuy.florafare.client.HudConfigScreen;
import net.tend1tnuy.florafare.client.toast.FoodDiscoveryToast;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.network.FlorafareServerConfigSyncPayload;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;
import net.tend1tnuy.florafare.network.FoodConfigSyncPayload;
import net.tend1tnuy.florafare.network.FoodExclusionSyncPayload;
import net.tend1tnuy.florafare.network.FoodSynergySyncPayload;
import net.tend1tnuy.florafare.network.FoodUnlockedPayload;
import net.tend1tnuy.florafare.network.OpenFoodJournalPayload;
import net.tend1tnuy.florafare.network.SynergyUnlockedPayload;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side entry point for the Florafare mod.
 * Handles networking synchronization and HUD rendering.
 */
public class FlorafareClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Restore persisted HUD settings.
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

        ClientPlayNetworking.registerGlobalReceiver(FoodBuffSyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    ClientPlayerEntity player = context.player();
                    if (player != null) {
                        ((IFoodComponentProvider) player)
                                .florafare$getFoodComponent().readFromNbt(payload.nbt());
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(FoodConfigSyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    FoodBuffManager.loadConfigsFromNbt(payload.nbt());
                    // Invalidate the journal cache so the next screen open re-reads
                    // the updated configs (#7).
                    FoodJournalScreen.invalidateCache();
                }));

        ClientPlayNetworking.registerGlobalReceiver(FoodSynergySyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    FoodSynergyManager.loadSynergiesFromNbt(payload.nbt());
                    // Invalidate the journal cache so synergy data is also refreshed (#7).
                    FoodJournalScreen.invalidateCache();
                }));

        ClientPlayNetworking.registerGlobalReceiver(OpenFoodJournalPayload.ID, (payload, context) ->
                context.client().execute(() ->
                        context.client().setScreen(new FoodJournalScreen())));

        ClientPlayNetworking.registerGlobalReceiver(FlorafareServerConfigSyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    // Applied in-memory only (never written to disk) so a dedicated
                    // server stays authoritative every time this client (re)connects —
                    // mirrors FoodBuffManager.loadConfigsFromNbt's approach.
                    FlorafareConfig.maxBuffSlots = payload.maxBuffSlots();
                    FlorafareConfig.autoGenDurationMultiplier = payload.autoGenDurationMultiplier();
                    FlorafareConfig.autoGenHealthMultiplier = payload.autoGenHealthMultiplier();
                    FlorafareConfig.enableSynergies = payload.enableSynergies();
                    FlorafareConfig.enableAlwaysEdibleOverride = payload.enableAlwaysEdibleOverride();
                    FlorafareConfig.respectVanillaFoodEffects = payload.respectVanillaFoodEffects();
                    FlorafareConfig.enableForgottenMead = payload.enableForgottenMead();
                    PlayerFoodComponent.MAX_BUFF_SLOTS = payload.maxBuffSlots();
                    FoodJournalScreen.invalidateCache();
                }));

        ClientPlayNetworking.registerGlobalReceiver(FoodExclusionSyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    FoodBuffManager.setExcludedItems(payload.toSet());
                    FoodJournalScreen.invalidateCache();
                }));

        ClientPlayNetworking.registerGlobalReceiver(FoodUnlockedPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    // A new food was discovered — invalidate the cache so the journal
                    // shows the updated unlock state on next open (#7).
                    FoodJournalScreen.invalidateCache();
                    if (FlorafareConfig.enableDiscoveryToasts) {
                        Identifier itemId = Identifier.tryParse(payload.itemId());
                        var stack = (itemId != null && Registries.ITEM.containsId(itemId))
                                ? Registries.ITEM.get(itemId).getDefaultStack()
                                : Items.APPLE.getDefaultStack();
                        context.client().getToastManager().add(FoodDiscoveryToast.forFood(stack));
                    }
                }));

        // Previously registered on the server but never listened for on the client, so
        // synergy discoveries never showed a toast at all — wired up alongside the
        // food-discovery toast above so both variants behave the same way.
        ClientPlayNetworking.registerGlobalReceiver(SynergyUnlockedPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    FoodJournalScreen.invalidateCache();
                    if (FlorafareConfig.enableDiscoveryToasts) {
                        Text synergyName = Text.translatable(
                                "synergy.florafare." + payload.synergyId().replace(":", "."));
                        context.client().getToastManager().add(FoodDiscoveryToast.forSynergy(synergyName));
                    }
                }));

        HudRenderCallback.EVENT.register(new FlorafareHud());
    }
}
