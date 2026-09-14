package net.tend1tnuy.florafare.component;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.api.FlorafareEvents;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.network.BuffStateSyncPayload;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;
import net.tend1tnuy.florafare.network.FoodUnlockedPayload;
import net.tend1tnuy.florafare.network.SynergyUnlockedPayload;

import java.util.*;

public class PlayerFoodComponent {

    /** Configurable via {@code FlorafareConfig.maxBuffSlots}; kept in sync with clients via FlorafareServerConfigSyncPayload. */
    public static int MAX_BUFF_SLOTS = 3;

    private static final String NBT_BUFFS_KEY               = "FlorafareActiveBuffs";
    private static final String NBT_DISCOVERED_KEY          = "FlorafareDiscoveredFoods";
    private static final String NBT_JOURNAL_KEY             = "FlorafareReceivedJournal";
    private static final String NBT_DISCOVERED_SYNERGIES_KEY = "FlorafareDiscoveredSynergies";
    private static final String NBT_ACTIVE_SYNERGIES_KEY    = "FlorafareActiveSynergies";

    private final PlayerEntity player;
    private final List<ActiveFoodBuff> activeBuffs    = new ArrayList<>();
    private final Set<String>          discoveredFoods = new HashSet<>();
    private boolean hasReceivedJournal = false;

    private final Set<String>          discoveredSynergies = new HashSet<>();
    private final List<ActiveFoodBuff> activeSynergies     = new ArrayList<>();

    // #11 — Map keyed by synergy id for O(1) "is this synergy already active?" lookup.
    private final Map<String, ActiveFoodBuff> activeSynergyMap = new HashMap<>();

    /**
     * Set whenever buffs or synergies change, and flushed once at the end of the
     * player's tick. Coalescing matters: a single bite can add a buff and activate a
     * synergy, and each used to send its own full state packet.
     */
    private boolean dirty = false;

    // -------------------------------------------------------------------------
    // DURABILITY
    //
    // Journal progress lives in the player's NBT, which vanilla writes only on a clean
    // disconnect and on the world autosave — five minutes apart by default. Everything
    // discovered in between is lost if the server is killed rather than stopped, and on
    // a busy server "the server crashed" should not also mean "everyone lost an evening
    // of journal progress".
    //
    // So a new discovery asks for a save of its own. Throttled, because the flag is set
    // by eating and a player working through a modpack's food list can discover several
    // items a minute: one write per player per PERSIST_INTERVAL_TICKS at the very most,
    // and none at all for the vast majority of players, who discover nothing new in a
    // given session. The window of loss goes from five minutes to thirty seconds.
    // -------------------------------------------------------------------------

    /** Ticks between forced saves of one player. */
    private static final int PERSIST_INTERVAL_TICKS = 600;

    /** Something durable changed and has not been written to disk yet. */
    private boolean persistPending = false;

    /**
     * Server tick of the last forced save. Starts a full interval in the past so the
     * first discovery of a session is written immediately rather than after a delay.
     */
    private int lastPersistTick = -PERSIST_INTERVAL_TICKS;

    /**
     * Whether this player has durable changes worth writing now. Asked once a second
     * from the server tick — off the entity tick on purpose, so the save serializes a
     * player that is not in the middle of being ticked.
     */
    public boolean isPersistDue(int serverTick) {
        if (!persistPending) return false;
        if (serverTick - lastPersistTick < PERSIST_INTERVAL_TICKS) return false;
        lastPersistTick = serverTick;
        persistPending = false;
        return true;
    }

    /** Flags progress that must survive a crash, not just a clean shutdown. */
    private void markPersistPending() {
        persistPending = true;
    }

    public PlayerFoodComponent(PlayerEntity player) {
        this.player = player;
    }

    // -------------------------------------------------------------------------
    // ACCESSORS
    // -------------------------------------------------------------------------

    public boolean hasReceivedJournal()              { return hasReceivedJournal; }
    public void    setHasReceivedJournal(boolean v)  {
        if (this.hasReceivedJournal != v) markPersistPending();
        this.hasReceivedJournal = v;
    }
    public Set<String>          getDiscoveredFoods()      { return discoveredFoods; }
    public Set<String>          getDiscoveredSynergies()  { return discoveredSynergies; }
    public List<ActiveFoodBuff> getActiveBuffs()          { return activeBuffs; }
    public List<ActiveFoodBuff> getActiveSynergies()      { return activeSynergies; }

    // -------------------------------------------------------------------------
    // FOOD DISCOVERY
    // -------------------------------------------------------------------------

    public boolean unlockFood(String itemId) {
        if (!discoveredFoods.add(itemId)) return false;
        markPersistPending();
        // Deliberately not marked dirty: the discovery list is the largest part of the
        // state and never changes again once a food is known, so it travels in its own
        // one-string payload below instead of riding along on every buff update.
        if (player instanceof ServerPlayerEntity serverPlayer) {
            Florafare.sendTo(serverPlayer, new FoodUnlockedPayload(itemId));
        }
        if (!player.getWorld().isClient()) {
            FlorafareEvents.FOOD_DISCOVERED.invoker().onFoodDiscovered(player, itemId);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // BUFF MANAGEMENT
    // -------------------------------------------------------------------------

    public boolean tryAddBuff(ItemStack stack, FoodBuffData data) {
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        Optional<ActiveFoodBuff> existing = activeBuffs.stream()
                .filter(b -> b.getConsumedItemId().equals(itemId))
                .findFirst();

        if (existing.isPresent()) {
            ActiveFoodBuff buff = existing.get();
            buff.resetDuration(data.duration());
            applyBuffEffects(buff, data);
            if (!player.getWorld().isClient) {
                updateSynergies();
                FlorafareEvents.BUFF_APPLIED.invoker().onBuffApplied(player, stack, buff);
            }
            markDirty();
            return true;
        }

        if (activeBuffs.size() >= MAX_BUFF_SLOTS) return false;

        ActiveFoodBuff buff = new ActiveFoodBuff(
                data.target(), itemId, data.duration(), data.duration());
        applyBuffEffects(buff, data);
        activeBuffs.add(buff);
        if (!player.getWorld().isClient) {
            updateSynergies();
            FlorafareEvents.BUFF_APPLIED.invoker().onBuffApplied(player, stack, buff);
        }
        markDirty();
        return true;
    }

    /**
     * Removes one named buff, by the config target it was granted from or by the item
     * that was eaten. Forgotten Mead only ever drops the newest buff, which is no help
     * to a player holding three and wanting to swap out the one in the middle.
     *
     * @return true if a matching buff was active and has been removed
     */
    public boolean removeBuff(String targetOrItemId) {
        if (player.getWorld().isClient || targetOrItemId == null) return false;

        for (int i = 0; i < activeBuffs.size(); i++) {
            ActiveFoodBuff buff = activeBuffs.get(i);
            if (!buff.getTarget().equals(targetOrItemId)
                    && !buff.getConsumedItemId().equals(targetOrItemId)) {
                continue;
            }
            activeBuffs.remove(i);
            removeBuffAttributes(buff);
            updateSynergies();
            markDirty();
            return true;
        }
        return false;
    }

    public boolean removeLastBuff() {
        if (activeBuffs.isEmpty()) return false;
        ActiveFoodBuff buff = activeBuffs.remove(activeBuffs.size() - 1);
        removeBuffAttributes(buff);
        if (!player.getWorld().isClient) updateSynergies();
        markDirty();
        return true;
    }

    public void clearAllBuffs() {
        if (player.getWorld().isClient) return;
        boolean changed = false;

        for (int i = activeBuffs.size() - 1; i >= 0; i--) {
            removeBuffAttributes(activeBuffs.get(i));
            activeBuffs.remove(i);
            changed = true;
        }

        for (int i = activeSynergies.size() - 1; i >= 0; i--) {
            removeBuffAttributes(activeSynergies.get(i));
            activeSynergies.remove(i);
            changed = true;
        }
        activeSynergyMap.clear();

        if (changed) markDirty();
    }

    // -------------------------------------------------------------------------
    // ATTRIBUTE / EFFECT APPLICATION
    // -------------------------------------------------------------------------

    private void applyBuffEffects(ActiveFoodBuff buff, FoodBuffData data) {
        applyBuffEffects(buff, data, false);
    }

    /**
     * Builds the modifier-id scope for a buff. Modifier ids have to be unique per
     * <em>active buff</em>, not per config target.
     *
     * <p>A synergy is keyed by its own id and only ever has one instance running, so its
     * target alone is already unique. A food buff's target can be shared by many items —
     * a {@code #tag}, a {@code namespace:}, {@code template:default} — while the buff
     * list holds one slot per item eaten. Scoping those by target alone meant two buffs
     * from one target wrote the same modifier id: the second silently overwrote the
     * first, and whichever expired first stripped the modifier off the one still
     * running, leaving a buff on screen with none of its stats.
     */
    // Package-private rather than private so the collision rule can be driven directly
    // from a unit test; it needs no player and no world.
    static String modifierScope(ActiveFoodBuff buff, boolean isSynergy) {
        String raw = (isSynergy || buff.getTarget().equals(buff.getConsumedItemId()))
                ? buff.getTarget()
                : buff.getTarget() + "/" + buff.getConsumedItemId();
        // "#" and ":" are not valid Identifier path characters; "/" is.
        String scoped = raw.replace("#", "tag_").replace(":", "_");

        // Then everything else, because those two are only the characters a well-formed
        // target contains. A target that Identifier.tryParse rejected is kept verbatim by
        // normalizeTarget (deliberately — a datapack's typo should stay recognisable in
        // the logs), so an uppercase or spaced target could still reach here. It is used
        // to build an Identifier a few lines later, outside any try/catch, and
        // Identifier.of throws on a bad path — which surfaced as an exception thrown out
        // of the middle of eating. Lowercasing with Locale.ROOT, not the default locale:
        // in a Turkish locale "I" lowercases to a dotless "ı", which is not a valid path
        // character either.
        scoped = scoped.toLowerCase(Locale.ROOT);
        StringBuilder safe = new StringBuilder(scoped.length());
        for (int i = 0; i < scoped.length(); i++) {
            char c = scoped.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-';
            safe.append(valid ? c : '_');
        }
        return safe.toString();
    }

    private void applyBuffEffects(ActiveFoodBuff buff, FoodBuffData data, boolean isSynergy) {
        if (player.getWorld().isClient) return;

        String safeId = modifierScope(buff, isSynergy);

        if (data.healthBonus() != 0) {
            Identifier id = Identifier.of(Florafare.MOD_ID, "health_" + safeId);
            applyAttribute(buff, EntityAttributes.GENERIC_MAX_HEALTH, id,
                    data.healthBonus(), EntityAttributeModifier.Operation.ADD_VALUE);
        }

        for (FoodBuffData.AttributeData attr : data.attributes()) {
            Optional<RegistryEntry.Reference<net.minecraft.entity.attribute.EntityAttribute>> entry =
                    Registries.ATTRIBUTE.getEntry(attr.attributeId());
            if (entry.isEmpty()) continue;

            Identifier id = Identifier.of(Florafare.MOD_ID,
                    "attr_" + safeId + "_" + attr.attributeId().getPath());
            applyAttribute(buff, entry.get(), id, attr.amount(), mapOperation(attr.operation()));
        }

        // A negative max-health modifier can leave current health above the new maximum.
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }

        for (FoodBuffData.EffectData effect : data.effects()) {
            applyEffect(effect.id(), effect.duration(), effect.amplifier());
        }
    }

    /**
     * Grants one status effect, with the same ceiling the buffs themselves get.
     *
     * <p>A buff's own duration was capped while the effects it hands out were not, and
     * Florafare never takes a food buff's effects back — they are left to expire. A
     * datapack effect duration of a few hundred million ticks was therefore permanent in
     * every sense: the buff vanished from the overlay within minutes and the Regeneration
     * stayed for the life of the world, with nothing in the mod able to remove it.
     *
     * <p>The amplifier is floored at zero because a negative one is not a weaker effect —
     * it inverts the arithmetic inside several vanilla effects and is never intended.
     */
    private void applyEffect(Identifier effectId, int duration, int amplifier) {
        Registries.STATUS_EFFECT.getEntry(effectId).ifPresent(status ->
                player.addStatusEffect(new StatusEffectInstance(
                        status,
                        ActiveFoodBuff.clampDuration(duration),
                        clampAmplifier(amplifier)))
        );
    }

    /** Status effect amplifiers are a byte on the wire; keep them in a sane, positive range. */
    static int clampAmplifier(int amplifier) {
        return Math.min(Math.max(amplifier, 0), 255);
    }

    private void applyAttribute(ActiveFoodBuff buff,
                                RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute,
                                Identifier modifierId, double amount,
                                EntityAttributeModifier.Operation operation) {
        EntityAttributeInstance instance = player.getAttributeInstance(attribute);
        if (instance == null) return;

        instance.removeModifier(modifierId);
        try {
            // Temporary, never persistent: a persistent modifier is written into the
            // player's own vanilla attribute NBT, so any mismatch between that copy and
            // the buff list below leaves the stat stuck forever (and survives the mod
            // being uninstalled). Temporary modifiers live only in memory and are
            // rebuilt from our own saved buff list by reapplyAttributes() on load.
            instance.addTemporaryModifier(
                    new EntityAttributeModifier(modifierId, amount, operation));
            Identifier registryId = Registries.ATTRIBUTE.getId(attribute.value());
            if (registryId != null) {
                buff.addModifierRecord(modifierId, registryId, amount,
                        operationToString(operation));
            }
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to apply attribute modifier: {}", modifierId, e);
        }
    }

    /**
     * Strips every attribute modifier in the Florafare namespace from the player,
     * scanning the registry directly rather than trusting the mod's own buff list.
     *
     * <p>Two jobs: it backs {@code /florafare repair}, and it runs on every load to
     * migrate saves written by older versions, which used persistent modifiers that
     * vanilla baked into the player's attribute NBT. Without it those old modifiers
     * would load alongside the freshly reapplied temporary ones and double up.
     *
     * @return how many modifiers were removed
     */
    public int stripFlorafareModifiers() {
        int removed = 0;
        for (RegistryEntry<EntityAttribute> entry : Registries.ATTRIBUTE.streamEntries().toList()) {
            EntityAttributeInstance instance = player.getAttributeInstance(entry);
            if (instance == null) continue;

            List<Identifier> toRemove = null;
            for (EntityAttributeModifier modifier : instance.getModifiers()) {
                if (!modifier.id().getNamespace().equals(Florafare.MOD_ID)) continue;
                if (toRemove == null) toRemove = new ArrayList<>();
                toRemove.add(modifier.id());
            }
            if (toRemove == null) continue;

            for (Identifier modifierId : toRemove) {
                instance.removeModifier(modifierId);
                removed++;
            }
        }

        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
        return removed;
    }

    /**
     * Reapplies the recorded attribute modifiers of every active buff and synergy.
     * Needed whenever the player entity is (re)built — loading from disk, or being
     * recreated on a dimension change — because the modifiers are temporary and
     * vanilla carries over only BASE attribute values.
     */
    public void reapplyAttributes() {
        if (player.getWorld().isClient) return;
        for (ActiveFoodBuff buff : activeBuffs)    reapplyBuffModifiers(buff);
        for (ActiveFoodBuff buff : activeSynergies) reapplyBuffModifiers(buff);
    }

    private void reapplyBuffModifiers(ActiveFoodBuff buff) {
        buff.getAppliedModifiers().forEach((modId, modifier) -> {
            if (modifier.amount() == 0) return;
            Registries.ATTRIBUTE.getEntry(modifier.attributeId()).ifPresent(entry -> {
                EntityAttributeInstance instance = player.getAttributeInstance(entry);
                if (instance == null) return;
                instance.removeModifier(modId);
                try {
                    instance.addTemporaryModifier(new EntityAttributeModifier(
                            modId, modifier.amount(), mapOperation(modifier.operation())));
                } catch (Exception e) {
                    Florafare.LOGGER.error("Failed to reapply attribute modifier: {}", modId, e);
                }
            });
        });
    }

    private EntityAttributeModifier.Operation mapOperation(String op) {
        return switch (op.toLowerCase()) {
            case "add_multiplied_base"  -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default                     -> EntityAttributeModifier.Operation.ADD_VALUE;
        };
    }

    private static String operationToString(EntityAttributeModifier.Operation operation) {
        return switch (operation) {
            case ADD_MULTIPLIED_BASE  -> "add_multiplied_base";
            case ADD_MULTIPLIED_TOTAL -> "add_multiplied_total";
            default                   -> "add_value";
        };
    }

    // -------------------------------------------------------------------------
    // SYNERGY EVALUATION  (#11 — O(1) map lookup instead of linear stream scan)
    // -------------------------------------------------------------------------

    /**
     * Returns true if {@code buff} satisfies the given synergy requirement string.
     * Supports bare item-id requirements and "#"-prefixed tag requirements.
     *
     * <p>Public and static because the HUD needs the same answer to decide which slots
     * to mark as feeding a synergy. It used to do its own strict {@code equals} against
     * the buff's target, so a synergy built on a tag requirement highlighted nothing.
     */
    public static boolean satisfiesRequirement(ActiveFoodBuff buff, String requirement) {
        if (buff.getTarget().equals(requirement)) return true;

        Identifier consumedId = Identifier.tryParse(buff.getConsumedItemId());
        if (consumedId == null || !Registries.ITEM.containsId(consumedId)) return false;

        if (requirement.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(requirement.substring(1));
            return tagId != null && Registries.ITEM.get(consumedId).getDefaultStack()
                    .isIn(TagKey.of(RegistryKeys.ITEM, tagId));
        }
        return requirement.equals(consumedId.toString());
    }

    private boolean requirementsMet(List<String> requirements) {
        // An empty requirement list is vacuously satisfied, which would make the synergy
        // permanently active and — with no requirement to read a duration from — give it
        // Integer.MAX_VALUE ticks. The loader rejects such entries; this is the backstop
        // for a synergy that reached the map some other way (the API, an older save).
        if (requirements.isEmpty()) return false;

        for (String req : requirements) {
            boolean met = false;
            for (ActiveFoodBuff buff : activeBuffs) {
                if (satisfiesRequirement(buff, req)) { met = true; break; }
            }
            if (!met) return false;
        }
        return true;
    }

    private boolean updateSynergies() {
        if (player.getWorld().isClient) return false;

        if (!FlorafareConfig.enableSynergies) {
            boolean changed = !activeSynergies.isEmpty();
            if (changed) {
                for (int i = activeSynergies.size() - 1; i >= 0; i--) {
                    removeBuffAttributes(activeSynergies.get(i));
                }
                activeSynergies.clear();
                activeSynergyMap.clear();
            }
            return changed;
        }

        boolean changed = false;

        // Remove synergies whose requirements are no longer met or that have expired.
        for (int i = activeSynergies.size() - 1; i >= 0; i--) {
            ActiveFoodBuff synergyBuff = activeSynergies.get(i);
            FoodSynergyData synergyData = FoodSynergyManager.getSynergy(synergyBuff.getTarget());

            if (synergyData == null
                    || !requirementsMet(synergyData.requirements())
                    || synergyBuff.isExpired()) {
                removeBuffAttributes(synergyBuff);
                activeSynergies.remove(i);
                activeSynergyMap.remove(synergyBuff.getTarget()); // #11
                changed = true;
            }
        }

        // Add or refresh synergies whose requirements are now met.
        for (FoodSynergyData synergy : FoodSynergyManager.getAllSynergies()) {
            if (!requirementsMet(synergy.requirements())) continue;

            // Synergy duration mirrors the shortest remaining buff among its requirements.
            int minDuration = Integer.MAX_VALUE;
            int minInitial  = Integer.MAX_VALUE;
            for (String req : synergy.requirements()) {
                for (ActiveFoodBuff buff : activeBuffs) {
                    if (satisfiesRequirement(buff, req)) {
                        if (buff.getDurationRemaining() < minDuration) {
                            minDuration = buff.getDurationRemaining();
                            minInitial  = buff.getInitialDuration();
                        }
                        break;
                    }
                }
            }

            // ...and is then capped by the synergy's own "duration", which the datapack
            // format documents as a ceiling. Nothing read that field: it was parsed,
            // serialized and shipped to clients, and every bundled synergy sets it, but
            // the value had no effect whatsoever — a pack asking for a 10-second window
            // on a powerful combination got the full five minutes of its ingredients.
            //
            // A ceiling can only ever shorten, never lengthen: min() is applied here and
            // on the refresh path below alike, so a capped synergy is at every instant
            // no longer than the uncapped one would have been. That is what makes it safe
            // to re-apply on refresh without opening a way to extend a synergy for free.
            //
            // Zero or negative means "no ceiling", which is also what an older sync
            // payload (where the field was absent) decodes to.
            if (synergy.duration() > 0) {
                int ceiling = ActiveFoodBuff.clampDuration(synergy.duration());
                if (minDuration > ceiling) {
                    minDuration = ceiling;
                    minInitial  = ceiling;
                }
            }

            // #11 — O(1) lookup via map instead of stream().filter()
            ActiveFoodBuff existing = activeSynergyMap.get(synergy.id());

            if (existing != null) {
                if (Math.abs(existing.getDurationRemaining() - minDuration) > 10) {
                    existing.resetDuration(minDuration);
                    for (FoodBuffData.EffectData eff : synergy.effects()) {
                        applyEffect(eff.id(), minDuration, eff.amplifier());
                    }
                    changed = true;
                }
            } else {
                // Determine the HUD icon item from the first satisfied requirement.
                String iconItem = "minecraft:apple";
                if (!synergy.requirements().isEmpty()) {
                    String firstReq = synergy.requirements().get(0);
                    for (ActiveFoodBuff buff : activeBuffs) {
                        if (satisfiesRequirement(buff, firstReq)) {
                            iconItem = buff.getConsumedItemId();
                            break;
                        }
                    }
                }

                ActiveFoodBuff synergyBuff = new ActiveFoodBuff(
                        synergy.id(), iconItem, minDuration, minInitial);

                List<FoodBuffData.EffectData> dynamicEffects = new ArrayList<>();
                for (FoodBuffData.EffectData eff : synergy.effects()) {
                    dynamicEffects.add(new FoodBuffData.EffectData(
                            eff.id(), minDuration, eff.amplifier()));
                }

                FoodBuffData dummyData = new FoodBuffData(
                        synergy.id(), minDuration, 0, 0f,
                        synergy.healthBonus(), dynamicEffects, synergy.attributes(), 0, false);

                applyBuffEffects(synergyBuff, dummyData, true);
                activeSynergies.add(synergyBuff);
                activeSynergyMap.put(synergy.id(), synergyBuff); // #11

                logSynergyActivation(synergy, minDuration);
                FlorafareEvents.SYNERGY_ACTIVATED.invoker().onSynergyActivated(player, synergy, synergyBuff);

                if (discoveredSynergies.add(synergy.id())) {
                    markPersistPending();
                    if (player instanceof ServerPlayerEntity serverPlayer) {
                        Florafare.sendTo(serverPlayer, new SynergyUnlockedPayload(synergy.id()));
                    }
                }
                changed = true;
            }
        }
        if (changed) markDirty();
        return changed;
    }

    private void logSynergyActivation(FoodSynergyData synergy, int duration) {
        if (player.getWorld().isClient()
                || FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.NONE) {
            return;
        }
        String playerName = player.getName().getString();
        if (FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.ALL) {
            Florafare.LOGGER.info(
                    "[Florafare Synergy] Player {} activated synergy '{}' (foods: {}) "
                            + "| Health: +{} | Duration: {}t",
                    playerName, synergy.id(),
                    String.join(", ", synergy.requirements()),
                    synergy.healthBonus(), duration);
        } else {
            Florafare.LOGGER.info(
                    "[Florafare Synergy] Player {} activated synergy '{}'",
                    playerName, synergy.id());
        }
    }

    // -------------------------------------------------------------------------
    // TICK
    // -------------------------------------------------------------------------

    public void tick() {
        boolean isServer       = !player.getWorld().isClient;
        boolean buffsChanged   = false;

        for (int i = activeBuffs.size() - 1; i >= 0; i--) {
            ActiveFoodBuff buff = activeBuffs.get(i);
            buff.tick();
            if (buff.isExpired()) {
                if (isServer) { removeBuffAttributes(buff); buffsChanged = true; markDirty(); }
                activeBuffs.remove(i);
            }
        }

        if (isServer && buffsChanged) {
            updateSynergies();
        }

        for (int i = activeSynergies.size() - 1; i >= 0; i--) {
            ActiveFoodBuff synergyBuff = activeSynergies.get(i);
            synergyBuff.tick();
            if (synergyBuff.isExpired()) {
                if (isServer) {
                    removeBuffAttributes(synergyBuff);
                    activeSynergyMap.remove(synergyBuff.getTarget()); // #11
                    markDirty();
                }
                activeSynergies.remove(i);
            }
        }

        // One flush per tick, at the end, covering everything that changed during it.
        flushIfDirty();
    }

    // -------------------------------------------------------------------------
    // ATTRIBUTE REMOVAL
    // -------------------------------------------------------------------------

    private void removeBuffAttributes(ActiveFoodBuff buff) {
        buff.getAppliedModifiers().forEach((modId, modifier) -> {
            Registries.ATTRIBUTE.getEntry(modifier.attributeId()).ifPresent(entry -> {
                EntityAttributeInstance instance = player.getAttributeInstance(entry);
                if (instance != null) instance.removeModifier(modId);
            });
        });

        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }

        FoodSynergyData synergy = FoodSynergyManager.getSynergy(buff.getTarget());
        if (synergy != null) {
            removeSynergyEffects(buff, synergy);
        }

        // Single choke point for every removal path (expiry, Forgotten Mead, /florafare
        // clear, requirement-no-longer-met), so listeners see a consistent signal
        // regardless of why the buff/synergy ended.
        if (!player.getWorld().isClient()) {
            if (synergy != null) {
                FlorafareEvents.SYNERGY_ENDED.invoker().onSynergyEnded(player, buff);
            } else {
                FlorafareEvents.BUFF_REMOVED.invoker().onBuffRemoved(player, buff);
            }
        }
    }

    /**
     * Takes off the status effects a synergy granted, and only those.
     *
     * <p>A synergy's effects are applied for exactly its own remaining duration, so when
     * it ends by running out they expire on their own — this matters for the other exit,
     * a requirement being lost early, where the effect would otherwise outlive the
     * synergy that justified it.
     *
     * <p>{@link PlayerEntity#removeStatusEffect} is indiscriminate: it takes the effect
     * off whatever granted it. Called unconditionally, ending a Resistance synergy
     * stripped the Resistance a player was getting from a beacon or a potion. So each
     * candidate is checked against what this synergy could plausibly have granted — same
     * amplifier, and not outlasting the synergy itself — and anything stronger or
     * longer-lived is left to its real owner.
     */
    private void removeSynergyEffects(ActiveFoodBuff buff, FoodSynergyData synergy) {
        // The refresh in updateSynergies() tolerates up to 10 ticks of drift before it
        // re-applies, so our own instance can legitimately read slightly longer than the
        // buff. A second of slack covers that without reaching a beacon's minutes.
        final int slack = 20;

        for (FoodBuffData.EffectData eff : synergy.effects()) {
            Registries.STATUS_EFFECT.getEntry(eff.id()).ifPresent(entry -> {
                StatusEffectInstance active = player.getStatusEffect(entry);
                if (active == null) return;
                if (active.isInfinite()) return;
                if (active.getAmplifier() != clampAmplifier(eff.amplifier())) return;
                if (active.getDuration() > buff.getDurationRemaining() + slack) return;
                player.removeStatusEffect(entry);
            });
        }
    }

    // -------------------------------------------------------------------------
    // SYNC
    //
    // Two packet shapes, because the two halves of this component change at wildly
    // different rates:
    //
    //   * the volatile half — active buffs and active synergies — changes constantly
    //     (a buff expiring is a change every few seconds) and is small, so it goes out
    //     on its own as BuffStateSyncPayload;
    //   * the durable half — every food and synergy the player has ever discovered —
    //     is by far the largest part of the state (hundreds of item ids in a big
    //     modpack) yet only ever grows by one entry at a time, so it rides on the
    //     full FoodBuffSyncPayload at join/respawn and on single-entry discovery
    //     packets after that.
    //
    // Before the split, a buff ticking down to zero re-sent the entire discovery list
    // to that player — kilobytes per expiry, per player.
    // -------------------------------------------------------------------------

    /** Flags the volatile half as changed; {@link #tick()} sends it once at tick end. */
    private void markDirty() {
        dirty = true;
    }

    /** Sends the volatile half if anything changed this tick. */
    private void flushIfDirty() {
        if (!dirty) return;
        dirty = false;
        if (player instanceof ServerPlayerEntity serverPlayer) {
            Florafare.sendTo(serverPlayer,
                    new BuffStateSyncPayload(writeBuffStateToNbt(new NbtCompound())));
        }
    }

    /** Sends the complete state — used on join, respawn and dimension change. */
    public void sync() {
        dirty = false;
        if (player instanceof ServerPlayerEntity serverPlayer) {
            Florafare.sendTo(serverPlayer,
                    new FoodBuffSyncPayload(writeToNbt(new NbtCompound())));
        }
    }

    // -------------------------------------------------------------------------
    // NBT SERIALIZATION
    // -------------------------------------------------------------------------

    /** The volatile half only: the active buffs and synergies. */
    public NbtCompound writeBuffStateToNbt(NbtCompound nbt) {
        NbtList buffs = new NbtList();
        for (ActiveFoodBuff buff : activeBuffs) buffs.add(buff.toNbt());
        nbt.put(NBT_BUFFS_KEY, buffs);

        NbtList activeSyn = new NbtList();
        for (ActiveFoodBuff b : activeSynergies) activeSyn.add(b.toNbt());
        nbt.put(NBT_ACTIVE_SYNERGIES_KEY, activeSyn);
        return nbt;
    }

    /** Counterpart of {@link #writeBuffStateToNbt}; leaves the discovery lists alone. */
    public void readBuffStateFromNbt(NbtCompound nbt) {
        readActiveBuffs(nbt);
        readActiveSynergies(nbt);
    }

    public NbtCompound writeToNbt(NbtCompound nbt) {
        writeBuffStateToNbt(nbt);

        NbtList discovered = new NbtList();
        for (String id : discoveredFoods) discovered.add(NbtString.of(id));
        nbt.put(NBT_DISCOVERED_KEY, discovered);

        NbtList discoveredSyn = new NbtList();
        for (String synId : discoveredSynergies) discoveredSyn.add(NbtString.of(synId));
        nbt.put(NBT_DISCOVERED_SYNERGIES_KEY, discoveredSyn);

        nbt.putBoolean(NBT_JOURNAL_KEY, hasReceivedJournal);
        return nbt;
    }

    private void readActiveBuffs(NbtCompound nbt) {
        activeBuffs.clear();
        if (nbt.contains(NBT_BUFFS_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_BUFFS_KEY, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                activeBuffs.add(ActiveFoodBuff.fromNbt(list.getCompound(i)));
            }
        }
    }

    private void readActiveSynergies(NbtCompound nbt) {
        activeSynergies.clear();
        activeSynergyMap.clear(); // #11
        if (nbt.contains(NBT_ACTIVE_SYNERGIES_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_ACTIVE_SYNERGIES_KEY, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                ActiveFoodBuff b = ActiveFoodBuff.fromNbt(list.getCompound(i));
                activeSynergies.add(b);
                activeSynergyMap.put(b.getTarget(), b); // #11
            }
        }
    }

    public void readFromNbt(NbtCompound nbt) {
        readActiveBuffs(nbt);
        readActiveSynergies(nbt);

        discoveredFoods.clear();
        if (nbt.contains(NBT_DISCOVERED_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_DISCOVERED_KEY, NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) discoveredFoods.add(list.getString(i));
        }

        discoveredSynergies.clear();
        if (nbt.contains(NBT_DISCOVERED_SYNERGIES_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_DISCOVERED_SYNERGIES_KEY, NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) discoveredSynergies.add(list.getString(i));
        }

        hasReceivedJournal = nbt.getBoolean(NBT_JOURNAL_KEY);
        dirty = false;          // freshly loaded — not dirty
        persistPending = false; // ...and identical to what is on disk
    }

    public void copyFrom(PlayerFoodComponent old) {
        this.activeBuffs.clear();
        this.activeBuffs.addAll(old.activeBuffs);

        this.discoveredFoods.clear();
        this.discoveredFoods.addAll(old.discoveredFoods);

        this.discoveredSynergies.clear();
        this.discoveredSynergies.addAll(old.discoveredSynergies);

        this.activeSynergies.clear();
        this.activeSynergyMap.clear();
        for (ActiveFoodBuff b : old.activeSynergies) {
            this.activeSynergies.add(b);
            this.activeSynergyMap.put(b.getTarget(), b); // #11
        }

        this.hasReceivedJournal = old.hasReceivedJournal;
        this.dirty = false;

        // The progress came across on a brand-new entity that has never been written, so
        // it is owed to disk even though nothing was discovered just now.
        this.persistPending = true;
        this.lastPersistTick = old.lastPersistTick;
    }
}
