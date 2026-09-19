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
        net.tend1tnuy.florafare.component.PlayerFoodComponent.setMaxBuffSlots(
                net.tend1tnuy.florafare.config.FlorafareConfig.maxBuffSlots);
        for (String itemId : net.tend1tnuy.florafare.config.FlorafareConfig.ignoredFoodItems) {
            FoodBuffManager.excludeItem(itemId);
        }

        // 2. Items & creative tab
        ItemRegistry.initialize();
        net.tend1tnuy.registry.FlorafareItemGroups.registerItemGroups();

        // 2b. Advancement triggers. Registered before the datapack listeners below so
        // the criteria exist by the time advancement JSON referencing them is parsed.
        net.tend1tnuy.florafare.advancement.FlorafareCriteria.register();

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
     * How much of one packet a single chunk of the datapack sync is allowed to fill.
     *
     * <p>{@code CustomPayloadS2CPacket} hard-caps a payload at 1 MiB, and the failure mode
     * is not graceful: the encode throws while the packet is being written, which drops
     * the connection. The whole map used to be measured against that cap and refused
     * wholesale when it crossed it — so a pack past the line left every client with no
     * configs at all. It is split into chunks of this size instead.
     *
     * <p>Wire bytes are only half the story, which is why this is a quarter of a megabyte
     * and not something nearer the cap. The receiving side decodes NBT under an
     * {@code NbtSizeTracker} that budgets 2 MiB of <em>allocation</em>, and its
     * accounting is far heavier than the wire: every compound costs 48 bytes and every
     * key costs 28 plus two per character, so a map made of many small compounds — which
     * is exactly what a food buff with a list of attributes is — can track at three or
     * four times its encoded size. A chunk sized purely by wire bytes therefore sailed
     * past the limit and the client dropped the connection while decoding it. Chunks are
     * additionally verified against the real decoder in {@link #decodesWithinLimit}, so
     * this number only has to be a sensible starting point, not a guarantee.
     */
    static final int CHUNK_TARGET_BYTES = 250_000;

    private static List<FoodConfigSyncPayload>  cachedConfigPayloads  = List.of();
    private static List<FoodSynergySyncPayload> cachedSynergyPayloads = List.of();
    /** Entries no packet could carry on its own; see {@link #chunk}. */
    private static List<String> skippedConfigEntries  = List.of();
    private static List<String> skippedSynergyEntries = List.of();

    /**
     * Whether {@link #buildSyncPayloads()} has run since the last invalidation.
     *
     * <p>Needed because a null payload field means two different things: "not built yet"
     * and "built, and deliberately not sent because it is over the size limit". Testing
     * the fields for null conflated the two, so a pack whose maps were BOTH oversized
     * re-serialized the entire config tree on every single player connection — and wrote
     * two ERROR lines to the log each time — for payloads it had already decided not to
     * send.
     */
    private static boolean syncPayloadsBuilt;

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
        cachedConfigPayloads  = List.of();
        cachedSynergyPayloads = List.of();
        skippedConfigEntries  = List.of();
        skippedSynergyEntries = List.of();
        syncPayloadsBuilt     = false;
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
        ensureSyncPayloads();
        List<String> issues = new ArrayList<>();
        if (!skippedConfigEntries.isEmpty()) {
            issues.add("these food_buffs entries are each too large for one network packet, "
                    + "so they are not sent to clients and their tooltips and journal rows fall "
                    + "back to each client's own view: " + String.join(", ", skippedConfigEntries));
        }
        if (!skippedSynergyEntries.isEmpty()) {
            issues.add("these food_synergies are each too large for one network packet, so they "
                    + "are not sent to clients — they still fire, but the journal and EMI cannot "
                    + "list them: " + String.join(", ", skippedSynergyEntries));
        }
        return issues;
    }

    /** Sends the datapack-driven config and synergy maps, each as its chunk sequence. */
    private static void sendDatapackSync(ServerPlayerEntity player) {
        ensureSyncPayloads();
        for (FoodConfigSyncPayload  chunk : cachedConfigPayloads)  sendTo(player, chunk);
        for (FoodSynergySyncPayload chunk : cachedSynergyPayloads) sendTo(player, chunk);
    }

    /** Builds the memoized payloads once per invalidation, oversized results included. */
    private static void ensureSyncPayloads() {
        if (syncPayloadsBuilt) return;
        buildSyncPayloads();
    }

    private static void buildSyncPayloads() {
        syncPayloadsBuilt = true;

        List<String> skippedConfigs = new ArrayList<>();
        List<NbtCompound> configChunks =
                chunk(FoodBuffManager.serializeConfigs(), "food_buffs", skippedConfigs);
        List<FoodConfigSyncPayload> configPayloads = new ArrayList<>(configChunks.size());
        for (int i = 0; i < configChunks.size(); i++) {
            configPayloads.add(new FoodConfigSyncPayload(configChunks.get(i), i, configChunks.size()));
        }
        cachedConfigPayloads = List.copyOf(configPayloads);
        skippedConfigEntries = List.copyOf(skippedConfigs);

        List<String> skippedSynergies = new ArrayList<>();
        List<NbtCompound> synergyChunks =
                chunk(FoodSynergyManager.serializeSynergies(), "food_synergies", skippedSynergies);
        List<FoodSynergySyncPayload> synergyPayloads = new ArrayList<>(synergyChunks.size());
        for (int i = 0; i < synergyChunks.size(); i++) {
            synergyPayloads.add(new FoodSynergySyncPayload(synergyChunks.get(i), i, synergyChunks.size()));
        }
        cachedSynergyPayloads = List.copyOf(synergyPayloads);
        skippedSynergyEntries = List.copyOf(skippedSynergies);

        if (configChunks.size() > 1 || synergyChunks.size() > 1) {
            LOGGER.info("Florafare datapack sync split into {} config chunk(s) and {} synergy chunk(s).",
                    configChunks.size(), synergyChunks.size());
        }
    }

    /**
     * Splits one serialized map into packet-sized compounds.
     *
     * <p>Each top-level key is measured once on its own, and entries are packed until the
     * running total would cross {@link #CHUNK_TARGET_BYTES} — which leaves generous room
     * under the hard cap for the framing around them. A single entry that cannot fit in a
     * packet by itself is dropped and named to the caller: no splitting can send it, and
     * one impossible entry must not cost the pack every other one, which is exactly what
     * refusing the whole map used to do.
     *
     * @param skipped filled with the keys that had to be dropped
     * @return at least one chunk, so a sequence is always sent and the client always ends
     *         up with a map — an empty one if that is genuinely what the pack has
     */
    static List<NbtCompound> chunk(NbtCompound root, String what, List<String> skipped) {
        List<NbtCompound> chunks = new ArrayList<>();
        NbtCompound current = new NbtCompound();
        int currentBytes = 0;

        for (String key : root.getKeys()) {
            NbtCompound single = new NbtCompound();
            single.put(key, root.get(key));
            // Negative when the entry cannot be encoded at all — an NBT string over
            // 64 KiB, say, which the codec refuses outright. Treated exactly like an
            // oversized one: skipped and named, never thrown, because this runs while a
            // player is joining and an exception here would drop their connection.
            int entryBytes = encodedSizeOrInvalid(single);

            if (entryBytes < 0 || entryBytes > CHUNK_TARGET_BYTES) {
                LOGGER.error(
                        "Florafare {} entry '{}' cannot travel in a network packet ({}; the limit is "
                                + "{} bytes). It is not sent to clients. Split it up — an entry that "
                                + "size is usually hundreds of effects or attributes.",
                        what, key,
                        entryBytes < 0 ? "it could not be encoded at all" : entryBytes + " bytes",
                        CHUNK_TARGET_BYTES);
                skipped.add(key);
                continue;
            }

            if (currentBytes + entryBytes > CHUNK_TARGET_BYTES && !current.isEmpty()) {
                chunks.add(current);
                current = new NbtCompound();
                currentBytes = 0;
            }
            current.put(key, root.get(key));
            currentBytes += entryBytes;
        }

        chunks.add(current);

        // Wire size is a guess at what the receiver will allow; this is the answer. Any
        // chunk the real decoder refuses is halved until every piece gets through.
        List<NbtCompound> verified = new ArrayList<>(chunks.size());
        for (NbtCompound candidate : chunks) {
            splitUntilDecodable(candidate, what, verified, skipped);
        }
        return verified;
    }

    /**
     * Adds a chunk to {@code out}, halving it as many times as the decoder demands.
     *
     * <p>Recursion bottoms out at a single entry: one that still cannot be decoded can
     * never be sent, so it is skipped and named rather than splitting forever.
     */
    private static void splitUntilDecodable(NbtCompound chunk, String what,
                                            List<NbtCompound> out, List<String> skipped) {
        if (decodesWithinLimit(chunk)) {
            out.add(chunk);
            return;
        }

        List<String> keys = new ArrayList<>(chunk.getKeys());
        if (keys.size() <= 1) {
            String key = keys.isEmpty() ? "<empty>" : keys.get(0);
            LOGGER.error(
                    "Florafare {} entry '{}' is too large for the client to decode even on its own. "
                            + "It is not sent to clients. Split it up — an entry that size is usually "
                            + "hundreds of effects or attributes.", what, key);
            if (!keys.isEmpty()) skipped.add(key);
            return;
        }

        int half = keys.size() / 2;
        NbtCompound first  = new NbtCompound();
        NbtCompound second = new NbtCompound();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            (i < half ? first : second).put(key, chunk.get(key));
        }
        splitUntilDecodable(first,  what, out, skipped);
        splitUntilDecodable(second, what, out, skipped);
    }

    /**
     * Whether the client will accept this chunk, asked of the very codec the client
     * decodes with — {@code NbtSizeTracker}'s allowance included. Guessing at that
     * accounting from the outside is how the limit got crossed in the first place.
     */
    static boolean decodesWithinLimit(NbtCompound chunk) {
        ByteBuf buf = Unpooled.buffer();
        try {
            PacketCodecs.NBT_COMPOUND.encode(buf, chunk);
            PacketCodecs.NBT_COMPOUND.decode(buf);
            return true;
        } catch (Exception refused) {
            return false;
        } finally {
            buf.release();
        }
    }

    /** {@link #encodedSize}, answering -1 instead of throwing when the codec refuses. */
    private static int encodedSizeOrInvalid(NbtCompound nbt) {
        try {
            return encodedSize(nbt);
        } catch (Exception refused) {
            return -1;
        }
    }

    /** Exactly what the payload will occupy on the wire, measured with the real codec. */
    static int encodedSize(NbtCompound nbt) {
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