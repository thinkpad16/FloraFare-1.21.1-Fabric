package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

import java.util.List;

/**
 * What a buff or synergy leaves behind once it is over — the hangover after a strong drink,
 * the drained feeling after an arcane surge.
 *
 * <p>Read from the optional {@code aftermath} object of a {@code food_buffs} or
 * {@code food_synergies} entry:
 * <pre>{@code
 * "aftermath": {
 *   "duration": 6000,
 *   "health_bonus": -4,
 *   "attributes": [ { "attribute": "minecraft:generic.max_health", "amount": -0.25,
 *                     "operation": "add_multiplied_total" } ],
 *   "effects":    [ { "id": "minecraft:nausea", "duration": 300 } ]
 * }
 * }</pre>
 *
 * <p>The {@code health_bonus} and {@code attributes} are held for {@code duration} ticks,
 * as an entry of their own in the player's aftermath list — it takes no buff slot. The
 * {@code effects} are ordinary status effects granted once, the moment the aftermath
 * starts, with their own durations ({@code -1} for infinite, as everywhere else). An
 * aftermath with effects only may have a {@code duration} of zero: its effects are granted
 * and nothing is tracked.
 *
 * <p>When it starts and what it does on a refresh are the component's business; see
 * {@code PlayerFoodComponent#startAftermath}. This record is only the definition, shaped
 * like the rest of a {@link FoodBuffData}: immutable, shared by every player, and carried
 * in the same NBT that the configs travel to clients in.
 *
 * @param duration    how long the modifiers last, in ticks; {@code <= 0} for none
 * @param healthBonus a max-health modifier ({@code add_value}) held for the duration;
 *                    negative is the common case here
 * @param effects     status effects granted once when the aftermath starts
 * @param attributes  attribute modifiers held for the duration
 */
public record FoodAftermath(
        int duration,
        double healthBonus,
        List<FoodBuffData.EffectData> effects,
        List<FoodBuffData.AttributeData> attributes
) {
    /** No aftermath at all — what every entry without the object carries. */
    public static final FoodAftermath NONE = new FoodAftermath(0, 0.0, List.of(), List.of());

    /** Defensive copies, for the same reason as {@link FoodBuffData}: definitions are shared. */
    public FoodAftermath {
        effects    = effects    == null ? List.of() : List.copyOf(effects);
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    /**
     * Whether there is nothing here to apply. A {@code duration} with neither modifiers
     * nor effects behind it counts as empty: it would put a row on the HUD that does
     * nothing, which is worse than no row.
     */
    public boolean isEmpty() {
        return !hasModifiers() && effects.isEmpty();
    }

    /** Whether it carries anything held for its duration — a health bonus or attributes. */
    public boolean hasModifiers() {
        return healthBonus != 0 || !attributes.isEmpty();
    }

    /**
     * Whether it becomes an entry in the player's aftermath list: something to hold and a
     * duration to hold it for. One that is not tracked can still grant effects.
     */
    public boolean isTracked() {
        return !isEmpty() && duration > 0;
    }

    // -------------------------------------------------------------------------
    // NBT
    //
    // Nested under the owning entry's compound, and only written when there is something
    // in it. Every config in a pack is sent to every client on join (chunked, see
    // ChunkedNbtSync) and the overwhelming majority have no aftermath, so an always-written
    // empty compound would grow that payload for nothing.
    // -------------------------------------------------------------------------

    /** The key an owning entry stores this under, in both {@link FoodBuffData} and {@link FoodSynergyData}. */
    static final String NBT_KEY = "aftermath";

    private static final String KEY_DURATION     = "duration";
    private static final String KEY_HEALTH_BONUS = "healthBonus";
    private static final String KEY_EFFECTS      = "effects";
    private static final String KEY_ATTRIBUTES   = "attributes";

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt(KEY_DURATION, duration);
        if (healthBonus != 0) nbt.putDouble(KEY_HEALTH_BONUS, healthBonus);
        if (!effects.isEmpty())    nbt.put(KEY_EFFECTS,    FoodNbtHelper.effectsToNbt(effects));
        if (!attributes.isEmpty()) nbt.put(KEY_ATTRIBUTES, FoodNbtHelper.attributesToNbt(attributes));
        return nbt;
    }

    public static FoodAftermath fromNbt(NbtCompound nbt) {
        FoodAftermath read = new FoodAftermath(
                nbt.getInt(KEY_DURATION),
                nbt.getDouble(KEY_HEALTH_BONUS),
                FoodNbtHelper.effectsFromNbt(nbt, KEY_EFFECTS),
                FoodNbtHelper.attributesFromNbt(nbt, KEY_ATTRIBUTES));
        return read.isEmpty() ? NONE : read;
    }

    /** Writes {@code aftermath} into {@code owner} under {@link #NBT_KEY}, unless it is empty. */
    static void writeTo(NbtCompound owner, FoodAftermath aftermath) {
        if (aftermath != null && !aftermath.isEmpty()) owner.put(NBT_KEY, aftermath.toNbt());
    }

    /** Reads what {@link #writeTo} wrote; {@link #NONE} when the key is absent (every older save). */
    static FoodAftermath readFrom(NbtCompound owner) {
        if (!owner.contains(NBT_KEY, NbtElement.COMPOUND_TYPE)) return NONE;
        return fromNbt(owner.getCompound(NBT_KEY));
    }
}
