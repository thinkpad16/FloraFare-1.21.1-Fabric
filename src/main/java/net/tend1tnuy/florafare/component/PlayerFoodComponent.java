package net.tend1tnuy.florafare.component;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;
import net.tend1tnuy.florafare.network.FoodUnlockedPayload;
import net.tend1tnuy.florafare.network.SynergyUnlockedPayload;

import java.util.*;

public class PlayerFoodComponent {

    public static final int MAX_BUFF_SLOTS = 3;

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

    // #15 — Dirty flag: only rebuild NBT when state has actually changed.
    private boolean dirty = false;

    public PlayerFoodComponent(PlayerEntity player) {
        this.player = player;
    }

    // -------------------------------------------------------------------------
    // ACCESSORS
    // -------------------------------------------------------------------------

    public boolean hasReceivedJournal()              { return hasReceivedJournal; }
    public void    setHasReceivedJournal(boolean v)  { this.hasReceivedJournal = v; }
    public Set<String>          getDiscoveredFoods()      { return discoveredFoods; }
    public Set<String>          getDiscoveredSynergies()  { return discoveredSynergies; }
    public List<ActiveFoodBuff> getActiveBuffs()          { return activeBuffs; }
    public List<ActiveFoodBuff> getActiveSynergies()      { return activeSynergies; }

    // -------------------------------------------------------------------------
    // FOOD DISCOVERY
    // -------------------------------------------------------------------------

    public boolean unlockFood(String itemId) {
        if (!discoveredFoods.add(itemId)) return false;
        dirty = true;
        syncIfDirty();
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new FoodUnlockedPayload());
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
            if (!player.getWorld().isClient) updateSynergies();
            dirty = true;
            syncIfDirty();
            return true;
        }

        if (activeBuffs.size() >= MAX_BUFF_SLOTS) return false;

        ActiveFoodBuff buff = new ActiveFoodBuff(
                data.target(), itemId, data.duration(), data.duration());
        applyBuffEffects(buff, data);
        activeBuffs.add(buff);
        if (!player.getWorld().isClient) updateSynergies();
        dirty = true;
        syncIfDirty();
        return true;
    }

    public boolean removeLastBuff() {
        if (activeBuffs.isEmpty()) return false;
        ActiveFoodBuff buff = activeBuffs.remove(activeBuffs.size() - 1);
        removeBuffAttributes(buff);
        if (!player.getWorld().isClient) updateSynergies();
        dirty = true;
        syncIfDirty();
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

        if (changed) {
            dirty = true;
            syncIfDirty();
        }
    }

    // -------------------------------------------------------------------------
    // ATTRIBUTE / EFFECT APPLICATION
    // -------------------------------------------------------------------------

    private void applyBuffEffects(ActiveFoodBuff buff, FoodBuffData data) {
        if (player.getWorld().isClient) return;

        // "#" is not a valid Identifier character — sanitize the target for use as a modifier id.
        String safeId = buff.getTarget().replace("#", "tag_").replace(":", "_");

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
            Registries.STATUS_EFFECT.getEntry(effect.id()).ifPresent(status ->
                    player.addStatusEffect(
                            new StatusEffectInstance(status, effect.duration(), effect.amplifier()))
            );
        }
    }

    private void applyAttribute(ActiveFoodBuff buff,
                                RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute,
                                Identifier modifierId, double amount,
                                EntityAttributeModifier.Operation operation) {
        EntityAttributeInstance instance = player.getAttributeInstance(attribute);
        if (instance == null) return;

        instance.removeModifier(modifierId);
        try {
            instance.addPersistentModifier(
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
     * Reapplies the recorded attribute modifiers of every active buff and synergy.
     * Needed after the player entity is recreated (returning from the End), because
     * vanilla only copies BASE attribute values.
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
                    instance.addPersistentModifier(new EntityAttributeModifier(
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
     */
    private boolean satisfiesRequirement(ActiveFoodBuff buff, String requirement) {
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

            // #11 — O(1) lookup via map instead of stream().filter()
            ActiveFoodBuff existing = activeSynergyMap.get(synergy.id());

            if (existing != null) {
                if (Math.abs(existing.getDurationRemaining() - minDuration) > 10) {
                    existing.resetDuration(minDuration);
                    final int finalMin = minDuration;
                    for (FoodBuffData.EffectData eff : synergy.effects()) {
                        Registries.STATUS_EFFECT.getEntry(eff.id()).ifPresent(status ->
                                player.addStatusEffect(new StatusEffectInstance(
                                        status, finalMin, eff.amplifier()))
                        );
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

                applyBuffEffects(synergyBuff, dummyData);
                activeSynergies.add(synergyBuff);
                activeSynergyMap.put(synergy.id(), synergyBuff); // #11

                logSynergyActivation(synergy, minDuration);

                if (discoveredSynergies.add(synergy.id())) {
                    if (player instanceof ServerPlayerEntity serverPlayer) {
                        ServerPlayNetworking.send(serverPlayer, new SynergyUnlockedPayload());
                    }
                }
                changed = true;
            }
        }
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
        boolean synergiesChanged = false;

        for (int i = activeBuffs.size() - 1; i >= 0; i--) {
            ActiveFoodBuff buff = activeBuffs.get(i);
            buff.tick();
            if (buff.isExpired()) {
                if (isServer) { removeBuffAttributes(buff); buffsChanged = true; }
                activeBuffs.remove(i);
            }
        }

        if (isServer && buffsChanged) {
            synergiesChanged = updateSynergies();
        }

        for (int i = activeSynergies.size() - 1; i >= 0; i--) {
            ActiveFoodBuff synergyBuff = activeSynergies.get(i);
            synergyBuff.tick();
            if (synergyBuff.isExpired()) {
                if (isServer) {
                    removeBuffAttributes(synergyBuff);
                    activeSynergyMap.remove(synergyBuff.getTarget()); // #11
                    synergiesChanged = true;
                }
                activeSynergies.remove(i);
            }
        }

        if (isServer && (buffsChanged || synergiesChanged)) {
            dirty = true;
            syncIfDirty();
        }
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
            for (FoodBuffData.EffectData eff : synergy.effects()) {
                Registries.STATUS_EFFECT.getEntry(eff.id())
                        .ifPresent(player::removeStatusEffect);
            }
        }
    }

    // -------------------------------------------------------------------------
    // SYNC  (#15 — dirty flag: only rebuild NBT when state changed)
    // -------------------------------------------------------------------------

    /** Sends the current state to the client only if the dirty flag is set. */
    private void syncIfDirty() {
        if (!dirty) return;
        sync();
        dirty = false;
    }

    public void sync() {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer,
                    new FoodBuffSyncPayload(writeToNbt(new NbtCompound())));
        }
    }

    // -------------------------------------------------------------------------
    // NBT SERIALIZATION
    // -------------------------------------------------------------------------

    public NbtCompound writeToNbt(NbtCompound nbt) {
        NbtList buffs = new NbtList();
        for (ActiveFoodBuff buff : activeBuffs) buffs.add(buff.toNbt());
        nbt.put(NBT_BUFFS_KEY, buffs);

        NbtList discovered = new NbtList();
        for (String id : discoveredFoods) discovered.add(NbtString.of(id));
        nbt.put(NBT_DISCOVERED_KEY, discovered);

        NbtList discoveredSyn = new NbtList();
        for (String synId : discoveredSynergies) discoveredSyn.add(NbtString.of(synId));
        nbt.put(NBT_DISCOVERED_SYNERGIES_KEY, discoveredSyn);

        NbtList activeSyn = new NbtList();
        for (ActiveFoodBuff b : activeSynergies) activeSyn.add(b.toNbt());
        nbt.put(NBT_ACTIVE_SYNERGIES_KEY, activeSyn);

        nbt.putBoolean(NBT_JOURNAL_KEY, hasReceivedJournal);
        return nbt;
    }

    public void readFromNbt(NbtCompound nbt) {
        activeBuffs.clear();
        if (nbt.contains(NBT_BUFFS_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_BUFFS_KEY, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                activeBuffs.add(ActiveFoodBuff.fromNbt(list.getCompound(i)));
            }
        }

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

        hasReceivedJournal = nbt.getBoolean(NBT_JOURNAL_KEY);
        dirty = false; // freshly loaded — not dirty
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
    }
}
