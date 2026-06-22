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
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages active food buffs for a specific player, including
 * application, removal, serialization, and synchronization.
 */
public class PlayerFoodComponent {

    public static final int MAX_SLOTS = 3;
    private static final String NBT_KEY = "FlorafareActiveBuffs";

    private final PlayerEntity player;
    private final List<ActiveFoodBuff> activeBuffs = new ArrayList<>();

    public PlayerFoodComponent(PlayerEntity player) {
        this.player = player;
    }

    /**
     * Attempts to add or refresh a buff on the player from the given food item.
     *
     * @param stack The food item consumed.
     * @param data  The configuration data for the food buff.
     * @return true if the buff was successfully added or updated.
     */
    public boolean tryAddBuff(ItemStack stack, FoodBuffData data) {
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();

        // Check if we have already consumed this specific item
        Optional<ActiveFoodBuff> existing = activeBuffs.stream()
                .filter(b -> b.getConsumedItemId().equals(itemId))
                .findFirst();

        if (existing.isPresent()) {
            ActiveFoodBuff buff = existing.get();
            buff.resetDuration(data.duration());
            applyBuffEffectsAndAttributes(buff, data);
            sync();
            return true;
        }

        if (activeBuffs.size() >= MAX_SLOTS) {
            return false;
        }

        // Create a new buff. data.target() is the config source, itemId is the actual item
        ActiveFoodBuff newBuff = new ActiveFoodBuff(data.target(), itemId, data.duration(), data.duration());
        applyBuffEffectsAndAttributes(newBuff, data);
        activeBuffs.add(newBuff);
        sync();
        return true;
    }

    public boolean removeLastBuff() {
        if (activeBuffs.isEmpty()) {
            return false;
        }

        ActiveFoodBuff lastBuff = activeBuffs.remove(activeBuffs.size() - 1);
        removeBuffAttributes(lastBuff);
        sync();
        return true;
    }

    private void applyBuffEffectsAndAttributes(ActiveFoodBuff buff, FoodBuffData data) {
        if (player.getWorld().isClient) return;

        // Use consumedItemId to generate a unique modifier ID
        String safeItemId = buff.getConsumedItemId().replace(":", "_");

        if (data.healthBonus() > 0) {
            Identifier modId = Identifier.of(Florafare.MOD_ID, "health_bonus_" + safeItemId);
            applySingleAttribute(buff, EntityAttributes.GENERIC_MAX_HEALTH, modId, data.healthBonus(), EntityAttributeModifier.Operation.ADD_VALUE);
        }

        for (FoodBuffData.AttributeData attrData : data.attributes()) {
            Optional<RegistryEntry.Reference<EntityAttribute>> attrEntryOpt = Registries.ATTRIBUTE.getEntry(attrData.attributeId());
            if (attrEntryOpt.isEmpty()) {
                Florafare.LOGGER.warn("Unknown attribute from datapack: {}", attrData.attributeId());
                continue;
            }
            Identifier modId = Identifier.of(Florafare.MOD_ID, "attr_" + safeItemId + "_" + attrData.attributeId().getPath());
            applySingleAttribute(buff, attrEntryOpt.get(), modId, attrData.amount(), mapOperation(attrData.operation()));
        }

        for (FoodBuffData.EffectData effData : data.effects()) {
            Optional<RegistryEntry.Reference<StatusEffect>> effectEntryOpt = Registries.STATUS_EFFECT.getEntry(effData.id());
            if (effectEntryOpt.isPresent()) {
                player.addStatusEffect(new StatusEffectInstance(effectEntryOpt.get(), effData.duration(), effData.amplifier()));
            } else {
                Florafare.LOGGER.warn("Unknown potion effect from datapack: {}", effData.id());
            }
        }
    }

    private void applySingleAttribute(ActiveFoodBuff buff, RegistryEntry<EntityAttribute> attributeEntry, Identifier modId, double amount, EntityAttributeModifier.Operation operation) {
        EntityAttributeInstance instance = player.getAttributeInstance(attributeEntry);

        if (instance != null) {
            instance.removeModifier(modId);

            try {
                instance.addPersistentModifier(new EntityAttributeModifier(modId, amount, operation));
                Identifier attrRegistryId = Registries.ATTRIBUTE.getId(attributeEntry.value());
                if (attrRegistryId != null) {
                    buff.addModifierRecord(modId, attrRegistryId);
                }
            } catch (Exception e) {
                Florafare.LOGGER.error("CRITICAL: Failed to apply attribute modifier {} to attribute {}", modId, attributeEntry.getIdAsString(), e);
            }
        } else {
            Florafare.LOGGER.warn("Attribute {} exists, but is not attached to PlayerEntity. Ignoring to prevent crash.", attributeEntry.getIdAsString());
        }
    }

    private EntityAttributeModifier.Operation mapOperation(String opStr) {
        return switch (opStr.toLowerCase()) {
            case "add_multiplied_base" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total" -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> EntityAttributeModifier.Operation.ADD_VALUE;
        };
    }

    /**
     * Updates buff durations and handles expiration.
     */
    public void tick() {
        boolean isServer = !player.getWorld().isClient;
        boolean needsSync = false;

        for (int i = activeBuffs.size() - 1; i >= 0; i--) {
            ActiveFoodBuff buff = activeBuffs.get(i);
            buff.tick();

            if (buff.isExpired()) {
                if (isServer) {
                    removeBuffAttributes(buff);
                    needsSync = true;
                }
                activeBuffs.remove(i);
            }
        }

        if (isServer && needsSync) {
            sync();
        }
    }

    private void removeBuffAttributes(ActiveFoodBuff buff) {
        buff.getAppliedModifiers().forEach((modId, attrId) -> {
            Optional<RegistryEntry.Reference<EntityAttribute>> entryOpt = Registries.ATTRIBUTE.getEntry(attrId);
            if (entryOpt.isPresent()) {
                EntityAttributeInstance instance = player.getAttributeInstance(entryOpt.get());
                if (instance != null) {
                    instance.removeModifier(modId);
                }
            }
        });

        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    public List<ActiveFoodBuff> getActiveBuffs() {
        return activeBuffs;
    }

    /**
     * Synchronizes buff state with the client.
     */
    public void sync() {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new FoodBuffSyncPayload(this.writeToNbt(new NbtCompound())));
        }
    }

    public NbtCompound writeToNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (ActiveFoodBuff buff : activeBuffs) {
            list.add(buff.toNbt());
        }
        nbt.put(NBT_KEY, list);
        return nbt;
    }

    public void readFromNbt(NbtCompound nbt) {
        activeBuffs.clear();
        if (nbt.contains(NBT_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_KEY, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                activeBuffs.add(ActiveFoodBuff.fromNbt(list.getCompound(i)));
            }
        }
    }

    public void copyFrom(PlayerFoodComponent oldComponent) {
        this.activeBuffs.clear();
        this.activeBuffs.addAll(oldComponent.activeBuffs);
    }
}