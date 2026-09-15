package net.tend1tnuy.florafare.component;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.ApiStatus;

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
    private final boolean synergy;
    private int durationRemaining;
    private int initialDuration;

    private final Map<Identifier, AppliedModifier> appliedModifiers = new HashMap<>();

    // #25 — Lazily cached ItemStack so the registry is not queried every render frame.
    private ItemStack cachedItemStack = null;

    /** A food buff, granted by eating an item. */
    public ActiveFoodBuff(String target, String consumedItemId,
                          int durationRemaining, int initialDuration) {
        this(target, consumedItemId, durationRemaining, initialDuration, false);
    }

    /**
     * A synergy, keyed by its own id.
     *
     * <p>Named rather than a bare boolean at the call site, because "true" there reads as
     * nothing at all.
     */
    public static ActiveFoodBuff synergy(String synergyId, String iconItemId,
                                         int durationRemaining, int initialDuration) {
        return new ActiveFoodBuff(synergyId, iconItemId, durationRemaining, initialDuration, true);
    }

    public ActiveFoodBuff(String target, String consumedItemId,
                          int durationRemaining, int initialDuration, boolean synergy) {
        this.target            = target;
        this.consumedItemId    = consumedItemId;
        this.synergy           = synergy;
        this.durationRemaining = clampDuration(durationRemaining);
        // Never below the remaining time, or the HUD's progress bar divides by a smaller
        // number than it counts down from and renders a permanently full slot.
        this.initialDuration   = Math.max(clampDuration(initialDuration), this.durationRemaining);
    }

    public void tick() {
        if (durationRemaining > 0) durationRemaining--;
    }

    /** Restarts the buff: it now has {@code newDuration} left, out of that same total. */
    public void resetDuration(int newDuration) {
        resetDuration(newDuration, newDuration);
    }

    /**
     * Sets both halves of the progress bar independently.
     *
     * <p>For a synergy, which does not have a length of its own — it mirrors the shortest
     * buff feeding it, and so inherits that buff's <em>elapsed</em> position too. Folding
     * the two together (as the single-argument form does) made every refreshed synergy
     * render as a full bar that then jumped, because it was told it had just started when
     * in fact it was two thirds through its ingredient's life.
     */
    public void resetDuration(int newDuration, int newInitial) {
        this.durationRemaining = clampDuration(newDuration);
        // Same invariant the constructor enforces, for the same reason.
        this.initialDuration   = Math.max(clampDuration(newInitial), this.durationRemaining);
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
     * Whether this entry is a synergy rather than a food buff.
     *
     * <p>Carried on the entry itself instead of being inferred by asking
     * {@code FoodSynergyManager} whether anything is registered under {@link #getTarget()}.
     * That inference was wrong in both directions: a food buff whose datapack target
     * happened to match a synergy id was treated as a synergy on removal — firing
     * {@code SYNERGY_ENDED} and stripping status effects against the wrong data — while a
     * synergy whose definition had been removed by a {@code /reload} stopped being
     * recognised as one. The two id spaces are unrelated and nothing stops them from
     * colliding, so the answer is recorded at construction and persisted.
     */
    public boolean isSynergy()            { return synergy; }

    /**
     * The ItemStack for the consumed item — the HUD icon, and whatever an addon wants to
     * do with a buff it got from {@code FlorafareAPI}.
     *
     * <p>The registry lookup is cached, because {@code consumedItemId} never changes
     * after construction. The stack itself is copied on the way out: an {@link ItemStack}
     * is mutable, so handing out the cached instance would let any one caller setting a
     * count or a component change what every other caller sees — for the rest of the
     * buff's life, since the cache is never rebuilt.
     */
    public ItemStack getConsumedItemStack() {
        return peekConsumedItemStack().copy();
    }

    /**
     * The same stack without the defensive copy. <b>Internal; never mutate it.</b>
     *
     * <p>This is the cache itself, shared by every subsequent caller for the life of the
     * buff — which is why {@link #getConsumedItemStack()}, the method addons are meant to
     * call, hands back a copy instead. The HUD renderer takes this one because it asks
     * several times per frame per slot and only ever draws and measures the result.
     */
    @ApiStatus.Internal
    public ItemStack peekConsumedItemStack() {
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
        return cachedItemStack;
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
        // Only written when true: a food buff is the overwhelmingly common case, and
        // leaving the key off keeps the per-buff tag the size it has always been.
        if (synergy) nbt.putBoolean("Synergy", true);

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
        return fromNbt(nbt, false);
    }

    /**
     * @param forceSynergy read the entry as a synergy whatever the tag says. Used when
     *                     deserializing the synergy list, whose entries are synergies by
     *                     construction — saves written before the flag existed carry no
     *                     "Synergy" key at all, and reading those back as food buffs
     *                     would lose the distinction on the first relog.
     */
    public static ActiveFoodBuff fromNbt(NbtCompound nbt, boolean forceSynergy) {
        String consumed = nbt.contains("ConsumedItem")
                ? nbt.getString("ConsumedItem") : "minecraft:apple";

        ActiveFoodBuff buff = new ActiveFoodBuff(
                nbt.getString("Target"),
                consumed,
                nbt.getInt("Duration"),
                nbt.getInt("InitialDuration"),
                forceSynergy || nbt.getBoolean("Synergy")
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
