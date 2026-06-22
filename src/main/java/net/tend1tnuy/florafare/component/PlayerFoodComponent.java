package net.tend1tnuy.florafare.component;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;
import net.tend1tnuy.florafare.network.FoodUnlockedPayload;

import java.util.*;

public class PlayerFoodComponent {

    public static final int MAX_BUFF_SLOTS = 3;

    private static final String NBT_BUFFS_KEY = "FlorafareActiveBuffs";
    private static final String NBT_DISCOVERED_KEY = "FlorafareDiscoveredFoods";
    private static final String NBT_JOURNAL_KEY = "FlorafareReceivedJournal";

    private final PlayerEntity player;

    private final List<ActiveFoodBuff> activeBuffs = new ArrayList<>();
    private final Set<String> discoveredFoods = new HashSet<>();

    private boolean hasReceivedJournal = false;

    public PlayerFoodComponent(PlayerEntity player) {
        this.player = player;
    }

    public boolean hasReceivedJournal() {
        return hasReceivedJournal;
    }

    public void setHasReceivedJournal(boolean value) {
        this.hasReceivedJournal = value;
    }

    public Set<String> getDiscoveredFoods() {
        return discoveredFoods;
    }

    /**
     * Unlocks a food entry for the player.
     */
    public boolean unlockFood(String itemId) {
        if (!discoveredFoods.add(itemId)) return false;

        sync();

        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new FoodUnlockedPayload());
        }

        return true;
    }

    /**
     * Attempts to apply or refresh a food buff.
     */
    public boolean tryAddBuff(ItemStack stack, FoodBuffData data) {
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();

        Optional<ActiveFoodBuff> existing = activeBuffs.stream()
                .filter(b -> b.getConsumedItemId().equals(itemId))
                .findFirst();

        if (existing.isPresent()) {
            ActiveFoodBuff buff = existing.get();
            buff.resetDuration(data.duration());
            applyBuffEffects(buff, data);
            sync();
            return true;
        }

        if (activeBuffs.size() >= MAX_BUFF_SLOTS) return false;

        ActiveFoodBuff buff = new ActiveFoodBuff(
                data.target(),
                itemId,
                data.duration(),
                data.duration()
        );

        applyBuffEffects(buff, data);
        activeBuffs.add(buff);
        sync();

        return true;
    }

    public boolean removeLastBuff() {
        if (activeBuffs.isEmpty()) return false;

        ActiveFoodBuff buff = activeBuffs.remove(activeBuffs.size() - 1);
        removeBuffAttributes(buff);
        sync();

        return true;
    }

    /**
     * Applies effects and attribute modifiers from food data.
     */
    private void applyBuffEffects(ActiveFoodBuff buff, FoodBuffData data) {
        if (player.getWorld().isClient) return;

        String safeItemId = buff.getConsumedItemId().replace(":", "_");

        // Apply health bonus
        if (data.healthBonus() > 0) {
            Identifier id = Identifier.of(Florafare.MOD_ID, "health_" + safeItemId);
            applyAttribute(buff, EntityAttributes.GENERIC_MAX_HEALTH, id,
                    data.healthBonus(), EntityAttributeModifier.Operation.ADD_VALUE);
        }

        // Apply custom attributes
        for (FoodBuffData.AttributeData attr : data.attributes()) {
            Optional<RegistryEntry.Reference<EntityAttribute>> entry =
                    Registries.ATTRIBUTE.getEntry(attr.attributeId());

            if (entry.isEmpty()) continue;

            Identifier id = Identifier.of(
                    Florafare.MOD_ID,
                    "attr_" + safeItemId + "_" + attr.attributeId().getPath()
            );

            applyAttribute(buff, entry.get(), id,
                    attr.amount(), mapOperation(attr.operation()));
        }

        // Apply effects
        for (FoodBuffData.EffectData effect : data.effects()) {
            Optional<RegistryEntry.Reference<StatusEffect>> entry =
                    Registries.STATUS_EFFECT.getEntry(effect.id());

            entry.ifPresent(status ->
                    player.addStatusEffect(new StatusEffectInstance(
                            status, effect.duration(), effect.amplifier()
                    ))
            );
        }
    }

    private void applyAttribute(
            ActiveFoodBuff buff,
            RegistryEntry<EntityAttribute> attribute,
            Identifier modifierId,
            double amount,
            EntityAttributeModifier.Operation operation
    ) {
        EntityAttributeInstance instance = player.getAttributeInstance(attribute);
        if (instance == null) return;

        instance.removeModifier(modifierId);

        try {
            instance.addPersistentModifier(
                    new EntityAttributeModifier(modifierId, amount, operation)
            );

            Identifier registryId = Registries.ATTRIBUTE.getId(attribute.value());
            if (registryId != null) {
                buff.addModifierRecord(modifierId, registryId);
            }

        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to apply attribute modifier", e);
        }
    }

    private EntityAttributeModifier.Operation mapOperation(String op) {
        return switch (op.toLowerCase()) {
            case "add_multiplied_base" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> EntityAttributeModifier.Operation.ADD_VALUE;
        };
    }

    public void tick() {
        boolean server = !player.getWorld().isClient;
        boolean changed = false;

        for (int i = activeBuffs.size() - 1; i >= 0; i--) {
            ActiveFoodBuff buff = activeBuffs.get(i);
            buff.tick();

            if (!buff.isExpired()) continue;

            if (server) {
                removeBuffAttributes(buff);
                changed = true;
            }

            activeBuffs.remove(i);
        }

        if (server && changed) sync();
    }

    private void removeBuffAttributes(ActiveFoodBuff buff) {
        buff.getAppliedModifiers().forEach((modId, attrId) -> {
            Registries.ATTRIBUTE.getEntry(attrId).ifPresent(entry -> {
                EntityAttributeInstance instance = player.getAttributeInstance(entry);
                if (instance != null) instance.removeModifier(modId);
            });
        });

        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    public List<ActiveFoodBuff> getActiveBuffs() {
        return activeBuffs;
    }

    public void sync() {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(
                    serverPlayer,
                    new FoodBuffSyncPayload(writeToNbt(new NbtCompound()))
            );
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

        hasReceivedJournal = nbt.getBoolean(NBT_JOURNAL_KEY);
    }

    public void copyFrom(PlayerFoodComponent old) {
        activeBuffs.clear();
        activeBuffs.addAll(old.activeBuffs);

        discoveredFoods.clear();
        discoveredFoods.addAll(old.discoveredFoods);

        hasReceivedJournal = old.hasReceivedJournal;
    }
}