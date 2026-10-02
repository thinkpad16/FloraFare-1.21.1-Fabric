package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The optional {@code overdrive} block of a {@code food_synergies} entry: a synergy that can
 * be pushed past its first form, one stage at a time.
 *
 * <pre>{@code
 * "overdrive": {
 *   "stages": [ { "attributes": [...], "effects": [...], "health_bonus": 0, "aftermath": {...} }, ... ]
 * }
 * }</pre>
 *
 * <p>Whether a synergy refuses to start while the bill for its last run is still being paid
 * is not part of this block: {@code lockout} and {@code lockout_group} sit at the synergy's
 * top level ({@link FoodSynergyData#lockoutKey()}), because an ordinary synergy can be locked
 * out just as well as an overdrive.
 *
 * <p>The synergy's own top-level {@code attributes}, {@code effects}, {@code health_bonus}
 * and {@code aftermath} are <b>stage I</b>; {@link #stages()} holds stages II, III, ... in
 * order. Every stage is a full replacement rather than a delta — moving up strips stage N's
 * modifiers and applies stage N+1's — so a stage reads on its own, the way the journal and
 * the tooltip print it, without adding anything up.
 *
 * <p>How a synergy moves through its stages, and what ends it, is the component's business
 * ({@code PlayerFoodComponent#updateSynergies} and {@code #tick}); the short version is "it
 * climbs a stage at each boundary of its stage clock it is still running at, and a bite that
 * prolongs it at the last stage overloads it". This record is
 * only the definition — immutable, shared by every player, and carried in the synergy's NBT
 * to clients so the HUD and the journal can describe stages they have not been told about.
 *
 * @param stages stages II and up, in order; empty for an overdrive whose stage I is already
 *               its last (prolonging it at all overloads it)
 */
public record SynergyOverdrive(List<Stage> stages) {

    /** Defensive copy, for the same reason as {@link FoodSynergyData}: definitions are shared. */
    public SynergyOverdrive {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /**
     * One stage: what it grants while it is the synergy's current one, and what it leaves
     * behind if the synergy ends while it is. Shaped like the synergy's own fields, so stage
     * I can be expressed as one too — see {@link FoodSynergyData#stage(int)}.
     */
    public record Stage(double healthBonus,
                        List<FoodBuffData.EffectData> effects,
                        List<FoodBuffData.AttributeData> attributes,
                        FoodAftermath aftermath) {
        public Stage {
            effects    = effects    == null ? List.of() : List.copyOf(effects);
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
            aftermath  = aftermath  == null ? FoodAftermath.NONE : aftermath;
        }
    }

    /** How many stages there are in all, stage I included. */
    public int stageCount() {
        return 1 + stages.size();
    }

    // -------------------------------------------------------------------------
    // NBT
    //
    // Written under the synergy's compound only when the synergy has an overdrive, for the
    // same reason FoodAftermath is: every synergy travels to every client on join, and the
    // overwhelming majority have none.
    // -------------------------------------------------------------------------

    /** The key a synergy stores this under. */
    static final String NBT_KEY = "overdrive";

    private static final String KEY_STAGES        = "stages";
    private static final String KEY_HEALTH_BONUS  = "healthBonus";
    private static final String KEY_EFFECTS       = "effects";
    private static final String KEY_ATTRIBUTES    = "attributes";

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        NbtList list = new NbtList();
        for (Stage stage : stages) {
            NbtCompound tag = new NbtCompound();
            if (stage.healthBonus() != 0) tag.putDouble(KEY_HEALTH_BONUS, stage.healthBonus());
            if (!stage.effects().isEmpty())    tag.put(KEY_EFFECTS,    FoodNbtHelper.effectsToNbt(stage.effects()));
            if (!stage.attributes().isEmpty()) tag.put(KEY_ATTRIBUTES, FoodNbtHelper.attributesToNbt(stage.attributes()));
            FoodAftermath.writeTo(tag, stage.aftermath());
            list.add(tag);
        }
        nbt.put(KEY_STAGES, list);
        return nbt;
    }

    public static SynergyOverdrive fromNbt(NbtCompound nbt) {
        List<Stage> stages = new ArrayList<>();
        if (nbt.contains(KEY_STAGES, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(KEY_STAGES, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound tag = list.getCompound(i);
                stages.add(new Stage(
                        tag.getDouble(KEY_HEALTH_BONUS),
                        FoodNbtHelper.effectsFromNbt(tag, KEY_EFFECTS),
                        FoodNbtHelper.attributesFromNbt(tag, KEY_ATTRIBUTES),
                        FoodAftermath.readFrom(tag)));
            }
        }
        return new SynergyOverdrive(stages);
    }

    /** Writes {@code overdrive} into {@code owner} under {@link #NBT_KEY}, unless it is null. */
    static void writeTo(NbtCompound owner, @Nullable SynergyOverdrive overdrive) {
        if (overdrive != null) owner.put(NBT_KEY, overdrive.toNbt());
    }

    /** Reads what {@link #writeTo} wrote; null when the key is absent (every older payload). */
    @Nullable
    static SynergyOverdrive readFrom(NbtCompound owner) {
        if (!owner.contains(NBT_KEY, NbtElement.COMPOUND_TYPE)) return null;
        return fromNbt(owner.getCompound(NBT_KEY));
    }
}
