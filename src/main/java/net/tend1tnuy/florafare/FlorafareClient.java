package net.tend1tnuy.florafare;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.client.BuffDescription;
import net.tend1tnuy.florafare.client.ClientConfigOverride;
import net.tend1tnuy.florafare.client.FlorafareHud;
import net.tend1tnuy.florafare.client.FoodJournalScreen;
import net.tend1tnuy.florafare.client.HudConfigScreen;
import net.tend1tnuy.florafare.client.toast.FoodDiscoveryToast;
import net.tend1tnuy.florafare.compat.emi.EmiReloadBridge;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.network.BuffStateSyncPayload;
import net.tend1tnuy.florafare.network.ChunkedNbtSync;
import net.tend1tnuy.florafare.network.FlorafareServerConfigSyncPayload;
import net.tend1tnuy.florafare.network.FoodConfigEntrySyncPayload;
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

    /** Reassembly buffers for the two chunked datapack syncs; client-side, one connection at a time. */
    private final ChunkedNbtSync configSync  = new ChunkedNbtSync("food_buffs");
    private final ChunkedNbtSync synergySync = new ChunkedNbtSync("food_synergies");

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

        // A remote server overwrites the gameplay half of FlorafareConfig in place (see
        // the FlorafareServerConfigSyncPayload receiver below). Snapshot this client's
        // own values before that happens, and put them back when the connection ends, so
        // a server's rules can't follow the player into their next singleplayer world.
        // Skipped in singleplayer, where the "server" is this player's own config.
        ClientPlayConnectionEvents.INIT.register((handler, client) ->
                // getServer() rather than isInSingleplayer(): a world opened to LAN is
                // still this player's own game reading their own config, but
                // isInSingleplayer() answers false for it the moment it is published.
                ClientConfigOverride.rememberLocal(client.getServer() != null));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (ClientConfigOverride.restoreLocal()) {
                // Only when the server had actually overridden something: the journal
                // renders buff slots and synergy availability off these values.
                FoodJournalScreen.invalidateCache();
            }
        });

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
                    // This packet replaces the discovery lists wholesale, and the
                    // journal's static cache stores each entry's unlocked flag. It only
                    // ever happened to stay correct because the config sync — which does
                    // invalidate — arrives right after this one on join. On respawn and
                    // on a dimension change this packet is sent alone, and nothing else
                    // was dropping the cache.
                    FoodJournalScreen.invalidateCache();
                }));

        // The volatile half (active buffs and synergies) — everything the journal
        // discovery lists are deliberately left out of, so this arrives cheaply and
        // often. Read through readBuffStateFromNbt, never readFromNbt, or it would
        // wipe the discovery lists it doesn't carry.
        ClientPlayNetworking.registerGlobalReceiver(BuffStateSyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    ClientPlayerEntity player = context.player();
                    if (player != null) {
                        ((IFoodComponentProvider) player)
                                .florafare$getFoodComponent().readBuffStateFromNbt(payload.nbt());
                    }
                }));

        // Both datapack maps arrive as a numbered sequence of packets — see
        // ChunkedNbtSync. Nothing is applied until the last chunk lands, so the client's
        // map is still swapped in one piece and never holds half a pack's entries.
        ClientPlayNetworking.registerGlobalReceiver(FoodConfigSyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    NbtCompound complete =
                            configSync.accept(payload.nbt(), payload.index(), payload.total());
                    if (complete == null) return;
                    FoodBuffManager.loadConfigsFromNbt(complete);
                    // Invalidate the journal cache so the next screen open re-reads
                    // the updated configs (#7).
                    FoodJournalScreen.invalidateCache();
                    // EMI builds its recipe list from this map, and this packet no longer
                    // arrives only at join: /florafare edit re-sends it whenever an
                    // operator retunes a food, so EMI has to be told to rebuild here too.
                    EmiReloadBridge.requestReload();
                }));

        // One entry, changed by /florafare edit while we are connected. Applied straight
        // onto the map rather than through the chunked path above, which replaces it
        // wholesale and would wipe every other entry this packet does not carry.
        ClientPlayNetworking.registerGlobalReceiver(FoodConfigEntrySyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    FoodBuffManager.applyConfigEntry(payload.target(),
                            payload.removed() ? null : FoodBuffData.fromNbt(payload.data()));
                    FoodJournalScreen.invalidateCache();
                    EmiReloadBridge.requestReload();
                }));

        ClientPlayNetworking.registerGlobalReceiver(FoodSynergySyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    NbtCompound complete =
                            synergySync.accept(payload.nbt(), payload.index(), payload.total());
                    if (complete == null) return;
                    FoodSynergyManager.loadSynergiesFromNbt(complete);
                    // Invalidate the journal cache so synergy data is also refreshed (#7).
                    FoodJournalScreen.invalidateCache();
                    // EMI has already finished building its recipe list by the time this
                    // packet lands on join, so its Synergies category would otherwise be
                    // empty until something else made it reload. No-op without EMI.
                    EmiReloadBridge.requestReload();
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
                    // FoodBuffManager keeps its own copy of the multipliers, and on a
                    // dedicated server nothing else ever sets it: the reload listener that
                    // normally does is SERVER_DATA and never runs here. Without this the
                    // client generated fallback buffs off its own florafare.json, so
                    // tooltips and the journal disagreed with the server about how long a
                    // buff lasts and how much health it grants.
                    FoodBuffManager.AUTO_GEN_DURATION_MULT = payload.autoGenDurationMultiplier();
                    FoodBuffManager.AUTO_GEN_HEALTH_MULT = payload.autoGenHealthMultiplier();
                    // Auto-generated results are memoized, so the ones built with the old
                    // multipliers have to go.
                    FoodBuffManager.invalidateResolutionCache();
                    FlorafareConfig.enableSynergies = payload.enableSynergies();
                    FlorafareConfig.enableAlwaysEdibleOverride = payload.enableAlwaysEdibleOverride();
                    // Needed client-side too: Item#use runs on the client for prediction,
                    // so without this the eating animation never starts on a full bar.
                    FlorafareConfig.allowEatingWhenFull = payload.allowEatingWhenFull();
                    FlorafareConfig.respectVanillaFoodEffects = payload.respectVanillaFoodEffects();
                    FlorafareConfig.enableForgottenMead = payload.enableForgottenMead();
                    PlayerFoodComponent.setMaxBuffSlots(payload.maxBuffSlots());
                    FoodJournalScreen.invalidateCache();
                }));

        ClientPlayNetworking.registerGlobalReceiver(FoodExclusionSyncPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    // Not for our own integrated server. Client and server share one copy
                    // of FoodExclusions in that process, so "the server's list" and "this
                    // client's list" are the same object — and taking the packet's word
                    // for it would pin the live state to a snapshot of itself. Anything
                    // added afterwards (a /florafare ignore, a ModMenu edit) would then be
                    // masked by that snapshot and appear to do nothing. getServer() rather
                    // than isInSingleplayer(), for the reason rememberLocal() gives: a
                    // world opened to LAN is still this player's own game.
                    if (context.client().getServer() == null) {
                        net.tend1tnuy.florafare.food.FoodExclusions.applyRemote(
                                payload.rules(), payload.managedRules());
                    }
                    FoodJournalScreen.invalidateCache();
                    // EMI's Food Buffs category skips excluded items, and EMI has
                    // finished building it by the time this packet lands on join — so
                    // without a nudge the panel keeps listing food this server does not
                    // manage. No-op without EMI.
                    EmiReloadBridge.requestReload();
                }));

        ClientPlayNetworking.registerGlobalReceiver(FoodUnlockedPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    // This packet is now the only thing that grows the client's
                    // discovery list mid-session — the buff-state sync no longer
                    // carries it — so record it here before anything reads it.
                    ClientPlayerEntity player = context.player();
                    if (player != null) {
                        ((IFoodComponentProvider) player).florafare$getFoodComponent()
                                .getDiscoveredFoods().add(payload.itemId());
                    }
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
                    ClientPlayerEntity player = context.player();
                    if (player != null) {
                        ((IFoodComponentProvider) player).florafare$getFoodComponent()
                                .getDiscoveredSynergies().add(payload.synergyId());
                    }
                    FoodJournalScreen.invalidateCache();
                    if (FlorafareConfig.enableDiscoveryToasts) {
                        // Through BuffDescription, which falls back to the raw id when the
                        // key is untranslated. Building the key inline here meant a synergy
                        // from any datapack but this mod's own announced itself as
                        // "synergy.florafare.mypack.foo" in the toast, while the journal and
                        // the EMI panel — both of which already went through the helper —
                        // showed it properly.
                        Text synergyName = BuffDescription.synergyName(payload.synergyId());
                        context.client().getToastManager().add(FoodDiscoveryToast.forSynergy(synergyName));
                    }
                }));

        HudRenderCallback.EVENT.register(new FlorafareHud());
    }
}
