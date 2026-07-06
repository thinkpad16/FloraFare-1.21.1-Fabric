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
import net.tend1tnuy.florafare.network.SuppressHurtAnimationPayload;
import net.tend1tnuy.florafare.network.SynergyUnlockedPayload;

import java.util.*;

public class PlayerFoodComponent {

    public static final int MAX_BUFF_SLOTS = 3;
    private static final String NBT_DISCOVERED_LEGENDARIES_KEY = "FlorafareDiscoveredLegendaries";
    private final Set<String> discoveredLegendaries = new HashSet<>();
    public Set<String> getDiscoveredLegendaries() { return discoveredLegendaries; }
    private static final String NBT_BUFFS_KEY = "FlorafareActiveBuffs";
    private static final String NBT_DISCOVERED_KEY = "FlorafareDiscoveredFoods";
    private static final String NBT_JOURNAL_KEY = "FlorafareReceivedJournal";
    private static final String NBT_DISCOVERED_SYNERGIES_KEY = "FlorafareDiscoveredSynergies";
    private static final String NBT_ACTIVE_SYNERGIES_KEY = "FlorafareActiveSynergies";

    private final PlayerEntity player;
    private final List<ActiveFoodBuff> activeBuffs = new ArrayList<>();
    private final Set<String> discoveredFoods = new HashSet<>();
    private boolean hasReceivedJournal = false;

    private final Set<String> discoveredSynergies = new HashSet<>();
    private final List<ActiveFoodBuff> activeSynergies = new ArrayList<>();

    public PlayerFoodComponent(PlayerEntity player) {
        this.player = player;
    }

    public boolean hasReceivedJournal() { return hasReceivedJournal; }
    public void setHasReceivedJournal(boolean value) { this.hasReceivedJournal = value; }
    public Set<String> getDiscoveredFoods() { return discoveredFoods; }
    public Set<String> getDiscoveredSynergies() { return discoveredSynergies; }
    public List<ActiveFoodBuff> getActiveBuffs() { return activeBuffs; }
    public List<ActiveFoodBuff> getActiveSynergies() { return activeSynergies; }

    public boolean unlockFood(String itemId) {
        if (!discoveredFoods.add(itemId)) return false;
        sync();
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new FoodUnlockedPayload());
        }
        return true;
    }

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
            sync();
            return true;
        }

        if (activeBuffs.size() >= MAX_BUFF_SLOTS) return false;

        ActiveFoodBuff buff = new ActiveFoodBuff(data.target(), itemId, data.duration(), data.duration());
        applyBuffEffects(buff, data);
        activeBuffs.add(buff);
        if (!player.getWorld().isClient) updateSynergies();
        sync();
        return true;
    }

    public boolean removeLastBuff() {
        if (activeBuffs.isEmpty()) return false;
        ActiveFoodBuff buff = activeBuffs.remove(activeBuffs.size() - 1);
        removeBuffAttributes(buff);

        if (!player.getWorld().isClient) {
            updateSynergies();
        }
        sync();
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

        if (changed) {
            sync();
        }
    }

    private void applyBuffEffects(ActiveFoodBuff buff, FoodBuffData data) {
        if (player.getWorld().isClient) return;

        // "#" (tag targets) is not a valid Identifier character.
        String safeId = buff.getTarget().replace("#", "tag_").replace(":", "_");

        if (data.healthBonus() != 0) {
            Identifier id = Identifier.of(Florafare.MOD_ID, "health_" + safeId);
            applyAttribute(buff, EntityAttributes.GENERIC_MAX_HEALTH, id, data.healthBonus(), EntityAttributeModifier.Operation.ADD_VALUE);
        }

        for (FoodBuffData.AttributeData attr : data.attributes()) {
            Optional<RegistryEntry.Reference<EntityAttribute>> entry = Registries.ATTRIBUTE.getEntry(attr.attributeId());
            if (entry.isEmpty()) continue;

            Identifier id = Identifier.of(Florafare.MOD_ID, "attr_" + safeId + "_" + attr.attributeId().getPath());
            applyAttribute(buff, entry.get(), id, attr.amount(), mapOperation(attr.operation()));
        }

        // A negative max-health modifier can leave current health above the new
        // maximum; vanilla only clamps on the next setHealth call, so do it now.
        clampHealthToMax();

        for (FoodBuffData.EffectData effect : data.effects()) {
            Registries.STATUS_EFFECT.getEntry(effect.id()).ifPresent(status ->
                    player.addStatusEffect(new StatusEffectInstance(status, effect.duration(), effect.amplifier()))
            );
        }
    }

    private void applyAttribute(ActiveFoodBuff buff, RegistryEntry<EntityAttribute> attribute, Identifier modifierId, double amount, EntityAttributeModifier.Operation operation) {
        EntityAttributeInstance instance = player.getAttributeInstance(attribute);
        if (instance == null) return;

        instance.removeModifier(modifierId);

        try {
            // Persistent (not temporary) so vanilla saves the modifier in the player NBT
            // and it survives relogging while the buff is still active. Removal on
            // expiry is handled by removeBuffAttributes via the recorded ids.
            instance.addPersistentModifier(new EntityAttributeModifier(modifierId, amount, operation));
            Identifier registryId = Registries.ATTRIBUTE.getId(attribute.value());
            if (registryId != null) {
                buff.addModifierRecord(modifierId, registryId, amount, operationToString(operation));
            }
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to apply attribute modifier: {}", modifierId, e);
        }
    }

    /**
     * Reapplies the recorded attribute modifiers of every active buff and synergy.
     * Needed after the player entity is recreated with the component copied over
     * (returning from the End), because vanilla only copies BASE attribute values.
     */
    public void reapplyAttributes() {
        if (player.getWorld().isClient) return;
        for (ActiveFoodBuff buff : activeBuffs) reapplyBuffModifiers(buff);
        for (ActiveFoodBuff buff : activeSynergies) reapplyBuffModifiers(buff);
    }

    private void reapplyBuffModifiers(ActiveFoodBuff buff) {
        buff.getAppliedModifiers().forEach((modId, modifier) -> {
            // Legacy records (saved before amounts were tracked) have amount 0 — skip them.
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
            case "add_multiplied_base" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> EntityAttributeModifier.Operation.ADD_VALUE;
        };
    }

    private static String operationToString(EntityAttributeModifier.Operation operation) {
        return switch (operation) {
            case ADD_MULTIPLIED_BASE -> "add_multiplied_base";
            case ADD_MULTIPLIED_TOTAL -> "add_multiplied_total";
            default -> "add_value";
        };
    }

    /**
     * Checks whether an active buff satisfies one synergy requirement.
     * A requirement matches the buff's config target literally, or the item that
     * was actually eaten: bare-id requirements ("minecraft:cod") match the consumed
     * item id, and "#" tag requirements ("#minecraft:fishes") match any consumed
     * item in that tag. Matching the consumed item is what makes tag requirements
     * work — a fish eaten through its own item config still counts for the tag —
     * and it also covers buffs from "namespace:"/"template:" configs, whose targets
     * are not item ids.
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
                if (satisfiesRequirement(buff, req)) {
                    met = true;
                    break;
                }
            }
            if (!met) return false;
        }
        return true;
    }

    private boolean updateSynergies() {
        if (player.getWorld().isClient) return false;

        if (!FlorafareConfig.enableSynergies) {
            boolean changed = false;
            if (!activeSynergies.isEmpty()) {
                for (int i = activeSynergies.size() - 1; i >= 0; i--) {
                    removeBuffAttributes(activeSynergies.get(i));
                }
                activeSynergies.clear();
                changed = true;
            }
            return changed;
        }

        boolean changed = false;

        for (int i = activeSynergies.size() - 1; i >= 0; i--) {
            ActiveFoodBuff synergyBuff = activeSynergies.get(i);
            FoodSynergyData synergyData = FoodSynergyManager.getSynergy(synergyBuff.getTarget());

            if (synergyData == null || !requirementsMet(synergyData.requirements()) || synergyBuff.isExpired()) {
                removeBuffAttributes(synergyBuff);
                activeSynergies.remove(i);
                changed = true;
            }
        }

        for (FoodSynergyData synergy : FoodSynergyManager.getAllSynergies()) {
            if (requirementsMet(synergy.requirements())) {
                int minDuration = Integer.MAX_VALUE;
                int minInitial = Integer.MAX_VALUE;
                for (String req : synergy.requirements()) {
                    for (ActiveFoodBuff buff : activeBuffs) {
                        if (satisfiesRequirement(buff, req)) {
                            if (buff.getDurationRemaining() < minDuration) {
                                minDuration = buff.getDurationRemaining();
                                minInitial = buff.getInitialDuration();
                            }
                            break;
                        }
                    }
                }

                Optional<ActiveFoodBuff> existing = activeSynergies.stream()
                        .filter(b -> b.getTarget().equals(synergy.id()))
                        .findFirst();

                if (existing.isPresent()) {
                    ActiveFoodBuff buff = existing.get();
                    if (Math.abs(buff.getDurationRemaining() - minDuration) > 10) {
                        buff.resetDuration(minDuration);

                        final int finalMinDuration = minDuration;
                        for (FoodBuffData.EffectData eff : synergy.effects()) {
                            Registries.STATUS_EFFECT.getEntry(eff.id()).ifPresent(status ->
                                    player.addStatusEffect(new StatusEffectInstance(status, finalMinDuration, eff.amplifier()))
                            );
                        }
                        changed = true;
                    }
                } else {
                    // The synergy's HUD icon is the item that actually satisfied the first
                    // requirement (works for "#" tag requirements too, unlike the raw id).
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
                    ActiveFoodBuff synergyBuff = new ActiveFoodBuff(synergy.id(), iconItem, minDuration, minInitial);

                    List<FoodBuffData.EffectData> dynamicEffects = new ArrayList<>();
                    for (FoodBuffData.EffectData eff : synergy.effects()) {
                        dynamicEffects.add(new FoodBuffData.EffectData(eff.id(), minDuration, eff.amplifier()));
                    }

                    FoodBuffData dummyData = new FoodBuffData(
                            synergy.id(), minDuration, 0, 0f, synergy.healthBonus(),
                            dynamicEffects, synergy.attributes(), 0
                    );

                    applyBuffEffects(synergyBuff, dummyData);
                    activeSynergies.add(synergyBuff);

                    logSynergyActivation(synergy, minDuration);

                    if (discoveredSynergies.add(synergy.id())) {
                        if (player instanceof ServerPlayerEntity serverPlayer) {
                            ServerPlayNetworking.send(serverPlayer, new SynergyUnlockedPayload());
                        }
                    }
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Logs a newly activated synergy according to the configured consumption log level.
     * Activations are rare, so REDUCED logs them too (one short line); ALL adds details.
     */
    private void logSynergyActivation(FoodSynergyData synergy, int duration) {
        // The component also ticks client-side in singleplayer; only the server logs.
        if (player.getWorld().isClient()
                || FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.NONE) {
            return;
        }

        String playerName = player.getName().getString();
        if (FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.ALL) {
            Florafare.LOGGER.info(
                    "[Florafare Synergy] Player {} activated synergy '{}' (foods: {}) | Health: +{} | Duration: {}t",
                    playerName, synergy.id(), String.join(", ", synergy.requirements()),
                    synergy.healthBonus(), duration
            );
        } else {
            Florafare.LOGGER.info(
                    "[Florafare Synergy] Player {} activated synergy '{}'",
                    playerName, synergy.id()
            );
        }
    }

    public void tick() {
        boolean isServer = !player.getWorld().isClient;
        boolean buffsChanged = false;
        boolean synergiesChanged = false;

        for (int i = activeBuffs.size() - 1; i >= 0; i--) {
            ActiveFoodBuff buff = activeBuffs.get(i);
            buff.tick();

            if (buff.isExpired()) {
                if (isServer) {
                    removeBuffAttributes(buff);
                    buffsChanged = true;
                }
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
                    synergiesChanged = true;
                }
                activeSynergies.remove(i);
            }
        }

        if (isServer && (buffsChanged || synergiesChanged)) {
            sync();
        }
    }

    /**
     * Clamps current health down to max health after a max-health drop.
     * The drop is administrative, not damage, so the client is told first to
     * skip the hurt flash/camera tilt for the health update that follows
     * (packets arrive in order, so the flag is always set before the clamp syncs).
     */
    private void clampHealthToMax() {
        if (player.getHealth() <= player.getMaxHealth()) return;

        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new SuppressHurtAnimationPayload());
        }
        player.setHealth(player.getMaxHealth());
    }

    private void removeBuffAttributes(ActiveFoodBuff buff) {
        buff.getAppliedModifiers().forEach((modId, modifier) -> {
            Registries.ATTRIBUTE.getEntry(modifier.attributeId()).ifPresent(entry -> {
                EntityAttributeInstance instance = player.getAttributeInstance(entry);
                if (instance != null) instance.removeModifier(modId);
            });
        });

        clampHealthToMax();

        FoodSynergyData synergy = FoodSynergyManager.getSynergy(buff.getTarget());
        if (synergy != null) {
            for (FoodBuffData.EffectData eff : synergy.effects()) {
                Registries.STATUS_EFFECT.getEntry(eff.id()).ifPresent(player::removeStatusEffect);
            }
        }
    }

    public void sync() {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new FoodBuffSyncPayload(writeToNbt(new NbtCompound())));
        }
    }

    public NbtCompound writeToNbt(NbtCompound nbt) {
        NbtList buffs = new NbtList();
        for (ActiveFoodBuff buff : activeBuffs) {
            buffs.add(buff.toNbt());
        }
        nbt.put(NBT_BUFFS_KEY, buffs);

        NbtList discovered = new NbtList();
        for (String id : discoveredFoods) {
            discovered.add(NbtString.of(id));
        }
        nbt.put(NBT_DISCOVERED_KEY, discovered);

        NbtList discoveredSyn = new NbtList();
        for (String synId : discoveredSynergies) {
            discoveredSyn.add(NbtString.of(synId));
        }
        nbt.put(NBT_DISCOVERED_SYNERGIES_KEY, discoveredSyn);

        NbtList activeSyn = new NbtList();
        for (ActiveFoodBuff b : activeSynergies) {
            activeSyn.add(b.toNbt());
        }
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
            for (int i = 0; i < list.size(); i++) {
                discoveredFoods.add(list.getString(i));
            }
        }

        discoveredSynergies.clear();
        if (nbt.contains(NBT_DISCOVERED_SYNERGIES_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_DISCOVERED_SYNERGIES_KEY, NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) {
                discoveredSynergies.add(list.getString(i));
            }
        }

        activeSynergies.clear();
        if (nbt.contains(NBT_ACTIVE_SYNERGIES_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_ACTIVE_SYNERGIES_KEY, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                activeSynergies.add(ActiveFoodBuff.fromNbt(list.getCompound(i)));
            }
        }

        hasReceivedJournal = nbt.getBoolean(NBT_JOURNAL_KEY);
    }

    public void copyFrom(PlayerFoodComponent old) {
        this.activeBuffs.clear();
        this.activeBuffs.addAll(old.activeBuffs);
        this.discoveredFoods.clear();
        this.discoveredFoods.addAll(old.discoveredFoods);

        this.discoveredSynergies.clear();
        this.discoveredSynergies.addAll(old.discoveredSynergies);
        this.activeSynergies.clear();
        this.activeSynergies.addAll(old.activeSynergies);

        this.hasReceivedJournal = old.hasReceivedJournal;
    }
}