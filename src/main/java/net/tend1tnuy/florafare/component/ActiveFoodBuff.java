package net.tend1tnuy.florafare.component;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

public class ActiveFoodBuff {

    /**
     * Ceiling on any single buff or synergy duration, in ticks — 24 real-time hours.
     *
     * <p>A hard constant rather than a config option, deliberately. It is not a balance
     * knob: nothing legitimate comes near it (the bundled foods run 2-5 minutes, and the
     * longest auto-generated buff is about 20), so anything above it is a bug — an
     * overflowed multiplication, a typo in a datapack, {@code /florafare setbuff} with a
     * duration argument the size of an int. Being a constant also means the client and
     * the server always agree on it without a config sync.
     *
     * <p>Applied in the constructor, so it covers every way a buff can come into being,
     * {@link #fromNbt} included. That last one is the point: a buff that somehow did get
     * stuck at a ludicrous duration is capped the next time the player's data loads, so a
     * server restart clears it instead of preserving it forever.
     */
    public static final int MAX_DURATION_TICKS = 1_728_000;

    /**
     * Full description of an applied attribute modifier so it can be removed
     * on expiry AND reapplied when the player entity is recreated
     * (e.g., returning from the End).
     */
    public record AppliedModifier(Identifier attributeId, double amount, String operation) {}

    /** Forces a duration into [0, {@link #MAX_DURATION_TICKS}]. */
    public static int clampDuration(int ticks) {
        if (ticks < 0) return 0;
        return Math.min(ticks, MAX_DURATION_TICKS);
    }

    private final String target;
    private final String consumedItemId;
    private int durationRemaining;
    private int initialDuration;

    private final Map<Identifier, AppliedModifier> appliedModifiers = new HashMap<>();

    // #25 — Lazily cached ItemStack so the registry is not queried every render frame.
    private ItemStack cachedItemStack = null;

    public ActiveFoodBuff(String target, String consumedItemId,
                          int durationRemaining, int initialDuration) {
        this.target            = target;
        this.consumedItemId    = consumedItemId;
        this.durationRemaining = clampDuration(durationRemaining);
        // Never below the remaining time, or the HUD's progress bar divides by a smaller
        // number than it counts down from and renders a permanently full slot.
        this.initialDuration   = Math.max(clampDuration(initialDuration), this.durationRemaining);
    }

    public void tick() {
        if (durationRemaining > 0) durationRemaining--;
    }

    public void resetDuration(int newDuration) {
        this.durationRemaining = clampDuration(newDuration);
        this.initialDuration   = this.durationRemaining;
    }

    public void addModifierRecord(Identifier modifierId, Identifier attributeId,
                                  double amount, String operation) {
        this.appliedModifiers.put(modifierId,
                new AppliedModifier(attributeId, amount, operation));
    }

    public Map<Identifier, AppliedModifier> getAppliedModifiers() { return appliedModifiers; }
    public String  getTarget()            { return target; }
    public String  getConsumedItemId()    { return consumedItemId; }
    public int     getDurationRemaining() { return durationRemaining; }
    public int     getInitialDuration()   { return initialDuration; }
    public boolean isExpired()            { return durationRemaining <= 0; }

    /**
     * The ItemStack for the consumed item, for the HUD icon and the tooltips.
     *
     * <p>The registry lookup is cached, because {@code consumedItemId} never changes
     * after construction and the HUD asks for this every frame (#25). The stack itself is
     * copied on the way out: an {@link ItemStack} is mutable, and handing the same
     * instance to the HUD renderer, the hover tooltip, EMI and the journal meant any one
     * of them setting a count or a component would have changed what all the others drew
     * — for the rest of the buff's life, since the cache is never rebuilt.
     */
    public ItemStack getConsumedItemStack() {
        if (cachedItemStack == null) {
            Identifier id = consumedItemId == null || consumedItemId.isEmpty()
                    ? null : Identifier.tryParse(consumedItemId);
            if (id != null && Registries.ITEM.containsId(id)) {
                Item item = Registries.ITEM.get(id);
                cachedItemStack = item.getDefaultStack();
            } else {
                cachedItemStack = Items.APPLE.getDefaultStack();
            }
        }
        return cachedItemStack.copy();
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("Target",          target);
        nbt.putString("ConsumedItem",    consumedItemId);
        nbt.putInt("Duration",           durationRemaining);
        nbt.putInt("InitialDuration",    initialDuration);

        NbtList modList = new NbtList();
        appliedModifiers.forEach((modId, modifier) -> {
            NbtCompound modTag = new NbtCompound();
            modTag.putString("ModId",  modId.toString());
            modTag.putString("AttrId", modifier.attributeId().toString());
            modTag.putDouble("Amount", modifier.amount());
            modTag.putString("Op",     modifier.operation());
            modList.add(modTag);
        });
        nbt.put("Modifiers", modList);

        return nbt;
    }

    public static ActiveFoodBuff fromNbt(NbtCompound nbt) {
        String consumed = nbt.contains("ConsumedItem")
                ? nbt.getString("ConsumedItem") : "minecraft:apple";

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
                Identifier modId  = Identifier.tryParse(modTag.getString("ModId"));
                Identifier attrId = Identifier.tryParse(modTag.getString("AttrId"));
                if (modId != null && attrId != null) {
                    buff.addModifierRecord(modId, attrId,
                            modTag.getDouble("Amount"),
                            modTag.contains("Op") ? modTag.getString("Op") : "add_value");
                }
            }
        }
        return buff;
    }
}
