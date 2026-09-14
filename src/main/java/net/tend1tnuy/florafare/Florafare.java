package net.tend1tnuy.florafare;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.command.DumpFoodsCommand;
import net.tend1tnuy.florafare.command.SetBuffCommand;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodReloadListener;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.food.FoodSynergyReloadListener;
import net.tend1tnuy.florafare.mixin.PlayerManagerInvoker;
import net.tend1tnuy.florafare.network.*;
import net.tend1tnuy.registry.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for the Florafare mod.
 * Handles the registration of items, network payloads, commands, and server events.
 */
public class Florafare implements ModInitializer {
    public static final String MOD_ID = "florafare";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Florafare!");

        // 1. Config
        net.tend1tnuy.florafare.config.FlorafareConfig.load();
        net.tend1tnuy.florafare.component.PlayerFoodComponent.MAX_BUFF_SLOTS =
                net.tend1tnuy.florafare.config.FlorafareConfig.maxBuffSlots;
        for (String itemId : net.tend1tnuy.florafare.config.FlorafareConfig.ignoredFoodItems) {
            FoodBuffManager.excludeItem(itemId);
        }

        // 2. Items & creative tab
        ItemRegistry.initialize();
        net.tend1tnuy.registry.FlorafareItemGroups.registerItemGroups();

        // 3. Datapack reload listeners
        ResourceManagerHelper.get(ResourceType.SERVER_DATA)
                .registerReloadListener(new FoodReloadListener());
        ResourceManagerHelper.get(ResourceType.SERVER_DATA)
                .registerReloadListener(new FoodSynergyReloadListener());

        // 4. Runtime buff persistence (command-applied buffs).
        // Bound to the server's lifetime, not the mod's: these live in the world save
        // now, so there is no path to read until a world is open, and each world gets
        // its own set instead of every save on the installation sharing one pile.
        ServerLifecycleEvents.SERVER_STARTED.register(FoodBuffManager::loadRuntimeConfigs);
        ServerLifecycleEvents.SERVER_STOPPING.register(FoodBuffManager::saveRuntimeConfigs);

        // 5. Network payload types (S2C)
        PayloadTypeRegistry.playS2C().register(FoodUnlockedPayload.ID,    FoodUnlockedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodBuffSyncPayload.ID,    FoodBuffSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BuffStateSyncPayload.ID,   BuffStateSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenFoodJournalPayload.ID, OpenFoodJournalPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SynergyUnlockedPayload.ID, SynergyUnlockedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodConfigSyncPayload.ID,  FoodConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodSynergySyncPayload.ID, FoodSynergySyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FlorafareServerConfigSyncPayload.ID, FlorafareServerConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FoodExclusionSyncPayload.ID, FoodExclusionSyncPayload.CODEC);

        // 6. Commands — registered together in one logical block (#22)
        CommandRegistrationCallback.EVENT.register(DumpFoodsCommand::register);
        CommandRegistrationCallback.EVENT.register(SetBuffCommand::register);

        // 7. Re-sync configs to all players after a /reload
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            invalidateSyncPayloads();
            FlorafareServerConfigSyncPayload serverConfigPayload = buildServerConfigSyncPayload();
            FoodExclusionSyncPayload exclusionPayload =
                    FoodExclusionSyncPayload.of(FoodBuffManager.getExcludedItems());
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                sendDatapackSync(player);
                sendTo(player, serverConfigPayload);
                sendTo(player, exclusionPayload);
            }
        });

        // 7b. Flush journal progress that is newer than the last world autosave.
        // Checked once a second rather than every tick: the throttle inside the component
        // is measured in tens of seconds, so a finer cadence would only cost lookups.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int tick = server.getTicks();
            if (tick % 20 != 0) return;
            PlayerManager manager = server.getPlayerManager();
            for (ServerPlayerEntity player : manager.getPlayerList()) {
                PlayerFoodComponent comp =
                        ((IFoodComponentProvider) player).florafare$getFoodComponent();
                if (comp.isPersistDue(tick)) {
                    ((PlayerManagerInvoker) manager).florafare$savePlayerData(player);
                }
            }
        });

        // 8. Player state events
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            PlayerFoodComponent oldComp =
                    ((IFoodComponentProvider) oldPlayer).florafare$getFoodComponent();
            PlayerFoodComponent newComp =
                    ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent();

            if (alive) {
                // ServerPlayerEntity#copyFrom has already run setHealth() against the
                // new entity, whose max health is still the un-buffed 20 — so a player
                // crossing a portal at 24/24 was silently clamped to 20 before the +4
                // came back. Reapply first, then restore what they actually had.
                float carriedHealth = oldPlayer.getHealth();

                newComp.copyFrom(oldComp);
                // Buff modifiers are temporary, and vanilla carries over only BASE
                // attribute values, so they must be reapplied to the new entity.
                newComp.reapplyAttributes();

                if (carriedHealth > newPlayer.getHealth()) {
                    newPlayer.setHealth(carriedHealth);
                }
            } else {
                // Journal progress and synergy discoveries persist through death.
                newComp.getDiscoveredFoods().addAll(oldComp.getDiscoveredFoods());
                newComp.getDiscoveredSynergies().addAll(oldComp.getDiscoveredSynergies());
                newComp.setHasReceivedJournal(oldComp.hasReceivedJournal());
            }
        });

        // 9. Initial sync and journal grant on join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerFoodComponent comp =
                    ((IFoodComponentProvider) handler.player).florafare$getFoodComponent();
            comp.sync();

            sendDatapackSync(handler.player);
            sendTo(handler.player, buildServerConfigSyncPayload());
            sendTo(handler.player,
                    FoodExclusionSyncPayload.of(FoodBuffManager.getExcludedItems()));

            if (!comp.hasReceivedJournal()
                    && net.tend1tnuy.florafare.config.FlorafareConfig.grantJournalOnFirstJoin) {
                ItemStack journal = new ItemStack(ItemRegistry.FOOD_JOURNAL);
                handler.player.getInventory().offerOrDrop(journal);
                comp.setHasReceivedJournal(true);
            }
        });

        // 10. Re-sync after respawn
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                ((IFoodComponentProvider) newPlayer).florafare$getFoodComponent().sync());
    }

    /**
     * Utility method for creating mod-specific identifiers.
     *
     * @param path The path for the identifier.
     * @return The formatted Identifier.
     */
    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    /**
     * Sends a payload to one player, but only if that player's client declared it.
     *
     * <p>Florafare's server half works perfectly well for a player running a vanilla
     * client — the buffs, hunger values and synergies are all resolved server-side — so
     * such a player is allowed to connect. What they must not be sent is the datapack
     * config map, which in a modpack runs to hundreds of kilobytes of NBT their client
     * will read off the wire and throw away, once on join and again after every
     * {@code /reload}. Every send in the mod goes through here.
     */
    public static void sendTo(ServerPlayerEntity player, CustomPayload payload) {
        if (!ServerPlayNetworking.canSend(player, payload.getId())) return;
        ServerPlayNetworking.send(player, payload);
    }

    // -------------------------------------------------------------------------
    // DATAPACK SYNC
    //
    // The datapack config and synergy maps are identical for every player, so they are
    // serialized once per reload and the same payload object is handed to everyone.
    // They used to be rebuilt from scratch inside the join handler, which meant every
    // single connection re-walked the whole config map and allocated a fresh NBT tree —
    // measurable on a server where players are joining continuously.
    // -------------------------------------------------------------------------

    /**
     * Refuse-to-send threshold for one datapack sync payload.
     *
     * <p>{@code CustomPayloadS2CPacket} hard-caps a payload at 1 MiB, and the failure mode
     * is not graceful: the encode throws while the packet is being written, which drops
     * the connection. A pack large enough to cross that line would therefore kick every
     * player as they joined, with a stack trace that says nothing about datapacks. The
     * margin below the cap leaves room for the packet framing around the payload.
     */
    private static final int MAX_SYNC_PAYLOAD_BYTES = 900_000;

    private static FoodConfigSyncPayload  cachedConfigPayload;
    private static FoodSynergySyncPayload cachedSynergyPayload;
    private static boolean oversizedConfigs;
    private static boolean oversizedSynergies;

    /**
     * Drops the memoized datapack payloads so the next send rebuilds them.
     *
     * <p>Public, and called from the two reload listeners rather than only from
     * {@code END_DATA_PACK_RELOAD}: Fabric fires that event from
     * {@code MinecraftServer#reloadResources} alone, which means {@code /reload} and
     * nothing else — <em>not</em> the datapack load that happens when a world opens.
     * These fields are static and outlive a singleplayer world, so loading a second
     * world in the same session used to hand its players the first world's configs. In
     * singleplayer that is the same map object the integrated server resolves against,
     * so the client's sync handler then overwrote the correct configs with the stale
     * ones. The reload listeners run on every datapack load, world open included.
     */
    public static void invalidateSyncPayloads() {
        cachedConfigPayload  = null;
        cachedSynergyPayload = null;
        oversizedConfigs     = false;
        oversizedSynergies   = false;
    }

    /**
     * Datapack maps too large to reach clients, as human-readable lines for
     * {@code /florafare validate}. Empty when everything fits.
     *
     * <p>Worth surfacing in the command and not only in the log: the symptom an admin
     * actually sees is "tooltips are wrong for everyone", hours after the startup line
     * explaining why scrolled out of view.
     */
    public static List<String> syncPayloadIssues() {
        if (cachedConfigPayload == null && cachedSynergyPayload == null) {
            buildSyncPayloads();
        }
        List<String> issues = new ArrayList<>();
        if (oversizedConfigs) {
            issues.add("the food_buffs config map is too large to send to clients "
                    + "(over " + MAX_SYNC_PAYLOAD_BYTES + " bytes); tooltips and the journal "
                    + "will not match the server. See the startup log for the exact size.");
        }
        if (oversizedSynergies) {
            issues.add("the food_synergies map is too large to send to clients "
                    + "(over " + MAX_SYNC_PAYLOAD_BYTES + " bytes); synergies still fire, "
                    + "but the journal and EMI cannot list them.");
        }
        return issues;
    }

    /** Sends the datapack-driven config and synergy maps, skipping either if it is too large. */
    private static void sendDatapackSync(ServerPlayerEntity player) {
        if (cachedConfigPayload == null && cachedSynergyPayload == null) {
            buildSyncPayloads();
        }
        if (cachedConfigPayload  != null) sendTo(player, cachedConfigPayload);
        if (cachedSynergyPayload != null) sendTo(player, cachedSynergyPayload);
    }

    private static void buildSyncPayloads() {
        NbtCompound configs = FoodBuffManager.serializeConfigs();
        int configBytes = encodedSize(configs);
        oversizedConfigs = configBytes > MAX_SYNC_PAYLOAD_BYTES;
        if (oversizedConfigs) {
            LOGGER.error(
                    "Florafare's food_buffs configs serialize to {} bytes, over the {} byte limit "
                            + "for a single network payload. They will NOT be sent to clients — item "
                            + "tooltips and the journal will fall back to each client's own view, but "
                            + "nobody will be disconnected. Reduce the number of food_buffs entries, "
                            + "or cover more items with '#tag' targets instead of one entry per item.",
                    configBytes, MAX_SYNC_PAYLOAD_BYTES);
            cachedConfigPayload = null;
        } else {
            cachedConfigPayload = new FoodConfigSyncPayload(configs);
        }

        NbtCompound synergies = FoodSynergyManager.serializeSynergies();
        int synergyBytes = encodedSize(synergies);
        oversizedSynergies = synergyBytes > MAX_SYNC_PAYLOAD_BYTES;
        if (oversizedSynergies) {
            LOGGER.error(
                    "Florafare's food_synergies serialize to {} bytes, over the {} byte limit for a "
                            + "single network payload. They will NOT be sent to clients — synergies "
                            + "still work server-side, but the journal and EMI cannot list them.",
                    synergyBytes, MAX_SYNC_PAYLOAD_BYTES);
            cachedSynergyPayload = null;
        } else {
            cachedSynergyPayload = new FoodSynergySyncPayload(synergies);
        }
    }

    /** Exactly what the payload will occupy on the wire, measured with the real codec. */
    private static int encodedSize(NbtCompound nbt) {
        ByteBuf buf = Unpooled.buffer();
        try {
            PacketCodecs.NBT_COMPOUND.encode(buf, nbt);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    /** Snapshots the server-authoritative subset of {@code FlorafareConfig} for client sync. */
    private static FlorafareServerConfigSyncPayload buildServerConfigSyncPayload() {
        return new FlorafareServerConfigSyncPayload(
                net.tend1tnuy.florafare.config.FlorafareConfig.maxBuffSlots,
                // The multipliers actually in force, read off FoodBuffManager rather than
                // off the config file. A datapack's config_generation.json overrides them
                // for the reload, and the client cannot see that file — so sending the raw
                // config values left every remote client computing auto-generated buffs
                // (i.e. most of the food in a modpack) with different numbers than the
                // server, and showing wrong durations and health bonuses in every tooltip.
                FoodBuffManager.AUTO_GEN_DURATION_MULT,
                FoodBuffManager.AUTO_GEN_HEALTH_MULT,
                net.tend1tnuy.florafare.config.FlorafareConfig.enableSynergies,
                net.tend1tnuy.florafare.config.FlorafareConfig.enableAlwaysEdibleOverride,
                net.tend1tnuy.florafare.config.FlorafareConfig.allowEatingWhenFull,
                net.tend1tnuy.florafare.config.FlorafareConfig.respectVanillaFoodEffects,
                net.tend1tnuy.florafare.config.FlorafareConfig.enableForgottenMead
        );
    }
}