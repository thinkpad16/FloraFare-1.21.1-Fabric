package net.tend1tnuy.florafare.component;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents an active food buff instance on a player, tracking duration,
 * source items, and applied attribute modifiers.
 */
public class ActiveFoodBuff {
    private final String target;
    private final String consumedItemId;
    private int durationRemaining;
    private int initialDuration;

    private final Map<Identifier, Identifier> appliedModifiers = new HashMap<>();

    public ActiveFoodBuff(String target, String consumedItemId, int durationRemaining, int initialDuration) {
        this.target = target;
        this.consumedItemId = consumedItemId;
        this.durationRemaining = durationRemaining;
        this.initialDuration = initialDuration;
    }

    public void tick() {
        if (durationRemaining > 0) {
            durationRemaining--;
        }
    }

    public void resetDuration(int newDuration) {
        this.durationRemaining = newDuration;
        this.initialDuration = newDuration;
    }

    public void addModifierRecord(Identifier modifierId, Identifier attributeId) {
        this.appliedModifiers.put(modifierId, attributeId);
    }

    public Map<Identifier, Identifier> getAppliedModifiers() {
        return appliedModifiers;
    }

    public String getTarget() {
        return target;
    }

    public String getConsumedItemId() {
        return consumedItemId;
    }

    public int getDurationRemaining() {
        return durationRemaining;
    }

    public int getInitialDuration() {
        return initialDuration;
    }

    public boolean isExpired() {
        return durationRemaining <= 0;
    }

    public ItemStack getConsumedItemStack() {
        Item item = Registries.ITEM.get(Identifier.of(this.consumedItemId));
        return item.getDefaultStack();
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("Target", target);
        nbt.putString("ConsumedItem", consumedItemId);
        nbt.putInt("Duration", durationRemaining);
        nbt.putInt("InitialDuration", initialDuration);

        NbtList modList = new NbtList();
        appliedModifiers.forEach((modId, attrId) -> {
            NbtCompound modTag = new NbtCompound();
            modTag.putString("ModId", modId.toString());
            modTag.putString("AttrId", attrId.toString());
            modList.add(modTag);
        });
        nbt.put("Modifiers", modList);

        return nbt;
    }

    public static ActiveFoodBuff fromNbt(NbtCompound nbt) {
        String consumed = nbt.contains("ConsumedItem") ? nbt.getString("ConsumedItem") : "minecraft:apple";

        ActiveFoodBuff buff = new ActiveFoodBuff(
                nbt.getString("Target"),
                consumed,
                nbt.getInt("Duration"),
                nbt.getInt("InitialDuration")
        );

        if (nbt.contains("Modifiers", NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList("Modifiers", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound modTag = list.getCompound(i);
                buff.addModifierRecord(Identifier.of(modTag.getString("ModId")), Identifier.of(modTag.getString("AttrId")));
            }
        }
        return buff;
    }
}