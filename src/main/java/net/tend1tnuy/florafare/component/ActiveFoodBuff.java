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
 * Represents an active food buff currently applied to a player.
 *
 * <p>Stores information about the consumed food, remaining duration,
 * and all attribute modifiers applied by this buff.</p>
 */
public class ActiveFoodBuff {

    private static final String DEFAULT_ITEM_ID = "minecraft:apple";

    private static final String TARGET_KEY = "Target";
    private static final String CONSUMED_ITEM_KEY = "ConsumedItem";
    private static final String DURATION_KEY = "Duration";
    private static final String INITIAL_DURATION_KEY = "InitialDuration";
    private static final String MODIFIERS_KEY = "Modifiers";

    private static final String MODIFIER_ID_KEY = "ModId";
    private static final String ATTRIBUTE_ID_KEY = "AttrId";

    /**
     * Buff configuration target.
     * Example: {@code tag:c:meats}.
     */
    private final String target;

    /**
     * Exact item consumed by the player.
     * Example: {@code minecraft:cooked_beef}.
     */
    private final String consumedItemId;

    /**
     * Remaining buff duration in ticks.
     */
    private int durationRemaining;

    /**
     * Original buff duration in ticks.
     */
    private int initialDuration;

    /**
     * Stores all applied attribute modifiers.
     *
     * <p>Key: Modifier Identifier</p>
     * <p>Value: Attribute Identifier</p>
     */
    private final Map<Identifier, Identifier> appliedModifiers =
            new HashMap<>();

    /**
     * Creates a new active food buff.
     *
     * @param target buff target definition
     * @param consumedItemId consumed item identifier
     * @param durationRemaining remaining duration in ticks
     * @param initialDuration initial duration in ticks
     */
    public ActiveFoodBuff(
            String target,
            String consumedItemId,
            int durationRemaining,
            int initialDuration
    ) {
        this.target = target;
        this.consumedItemId = consumedItemId;
        this.durationRemaining = durationRemaining;
        this.initialDuration = initialDuration;
    }

    /**
     * Updates the buff every tick.
     */
    public void tick() {
        if (durationRemaining > 0) {
            durationRemaining--;
        }
    }

    /**
     * Resets the buff duration.
     *
     * @param newDuration new duration in ticks
     */
    public void resetDuration(int newDuration) {
        this.durationRemaining = newDuration;
        this.initialDuration = newDuration;
    }

    /**
     * Registers an applied attribute modifier.
     *
     * @param modifierId modifier identifier
     * @param attributeId attribute identifier
     */
    public void addModifierRecord(
            Identifier modifierId,
            Identifier attributeId
    ) {
        appliedModifiers.put(modifierId, attributeId);
    }

    /**
     * Returns all applied modifiers.
     *
     * @return modifier map
     */
    public Map<Identifier, Identifier> getAppliedModifiers() {
        return appliedModifiers;
    }

    /**
     * Returns the buff target.
     *
     * @return target identifier
     */
    public String getTarget() {
        return target;
    }

    /**
     * Returns the consumed item identifier.
     *
     * @return item identifier
     */
    public String getConsumedItemId() {
        return consumedItemId;
    }

    /**
     * Returns the remaining duration.
     *
     * @return remaining duration in ticks
     */
    public int getDurationRemaining() {
        return durationRemaining;
    }

    /**
     * Returns the initial duration.
     *
     * @return initial duration in ticks
     */
    public int getInitialDuration() {
        return initialDuration;
    }

    /**
     * Checks whether the buff has expired.
     *
     * @return {@code true} if expired
     */
    public boolean isExpired() {
        return durationRemaining <= 0;
    }

    /**
     * Returns the consumed item as an item stack.
     *
     * @return consumed item stack
     */
    public ItemStack getConsumedItemStack() {
        Item item = Registries.ITEM.get(
                Identifier.of(consumedItemId)
        );

        return item.getDefaultStack();
    }

    /**
     * Serializes this buff into NBT.
     *
     * @return serialized NBT data
     */
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();

        nbt.putString(TARGET_KEY, target);
        nbt.putString(CONSUMED_ITEM_KEY, consumedItemId);
        nbt.putInt(DURATION_KEY, durationRemaining);
        nbt.putInt(INITIAL_DURATION_KEY, initialDuration);

        NbtList modifierList = new NbtList();

        appliedModifiers.forEach((modifierId, attributeId) -> {
            NbtCompound modifierTag = new NbtCompound();

            modifierTag.putString(
                    MODIFIER_ID_KEY,
                    modifierId.toString()
            );

            modifierTag.putString(
                    ATTRIBUTE_ID_KEY,
                    attributeId.toString()
            );

            modifierList.add(modifierTag);
        });

        nbt.put(MODIFIERS_KEY, modifierList);

        return nbt;
    }

    /**
     * Deserializes an active food buff from NBT.
     *
     * @param nbt source NBT data
     * @return reconstructed food buff
     */
    public static ActiveFoodBuff fromNbt(NbtCompound nbt) {
        String consumedItem = nbt.contains(CONSUMED_ITEM_KEY)
                ? nbt.getString(CONSUMED_ITEM_KEY)
                : DEFAULT_ITEM_ID;

        ActiveFoodBuff buff = new ActiveFoodBuff(
                nbt.getString(TARGET_KEY),
                consumedItem,
                nbt.getInt(DURATION_KEY),
                nbt.getInt(INITIAL_DURATION_KEY)
        );

        if (nbt.contains(MODIFIERS_KEY, NbtElement.LIST_TYPE)) {
            NbtList modifierList = nbt.getList(
                    MODIFIERS_KEY,
                    NbtElement.COMPOUND_TYPE
            );

            for (int i = 0; i < modifierList.size(); i++) {
                NbtCompound modifierTag =
                        modifierList.getCompound(i);

                buff.addModifierRecord(
                        Identifier.of(
                                modifierTag.getString(MODIFIER_ID_KEY)
                        ),
                        Identifier.of(
                                modifierTag.getString(ATTRIBUTE_ID_KEY)
                        )
                );
            }
        }

        return buff;
    }
}