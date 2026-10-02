package net.tend1tnuy.florafare.food;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One {@code food_synergies} entry.
 *
 * @param timer        what the synergy's countdown follows; see {@link Timer}
 * @param overdrive    its stages, or null for an ordinary synergy; see {@link SynergyOverdrive}
 * @param lockout      whether it refuses to start while an aftermath it left — or, with a
 *                     {@code lockoutGroup}, one any synergy of that group left — is running
 * @param lockoutGroup an identifier synergies share to lock each other out, or null; see
 *                     {@link #lockoutKey()}
 */
public record FoodSynergyData(
        String id,
        List<String> requirements,
        int duration,
        double healthBonus,
        List<FoodBuffData.EffectData> effects,
        List<FoodBuffData.AttributeData> attributes,
        FoodAftermath aftermath,
        Timer timer,
        @Nullable SynergyOverdrive overdrive,
        boolean lockout,
        @Nullable String lockoutGroup
) {
    /**
     * What a running synergy's remaining time is measured against.
     *
     * <ul>
     *   <li>{@link #MEMBERS} — the default, and the only model there was before: it mirrors
     *       the shortest remaining buff among its requirements (capped by {@code duration})
     *       and ends the moment one of them does.</li>
     *   <li>{@link #OWN} — once its requirements come together it runs for exactly
     *       {@code duration} ticks, whatever its ingredients do afterwards; eating any of
     *       them again while it runs resets it to the full {@code duration}.</li>
     * </ul>
     */
    public enum Timer {
        MEMBERS, OWN;

        /** The JSON spelling: {@code "members"} or {@code "own"}. */
        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** {@code "members"} / {@code "own"}, any case; null for anything else. */
        @Nullable
        public static Timer parse(@Nullable String name) {
            if (name == null) return null;
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "members" -> MEMBERS;
                case "own"     -> OWN;
                default        -> null;
            };
        }
    }

    /** Defensive copies, for the same reason as {@link FoodBuffData}: every synergy is shared. */
    public FoodSynergyData {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        effects      = effects      == null ? List.of() : List.copyOf(effects);
        attributes   = attributes   == null ? List.of() : List.copyOf(attributes);
        aftermath    = aftermath    == null ? FoodAftermath.NONE : aftermath;
        timer        = timer        == null ? Timer.MEMBERS : timer;
    }

    /** Every field but the lockout, which is then off. */
    public FoodSynergyData(String id, List<String> requirements, int duration, double healthBonus,
                           List<FoodBuffData.EffectData> effects,
                           List<FoodBuffData.AttributeData> attributes,
                           FoodAftermath aftermath, Timer timer, @Nullable SynergyOverdrive overdrive) {
        this(id, requirements, duration, healthBonus, effects, attributes, aftermath, timer, overdrive,
                false, null);
    }

    /**
     * The constructor from before {@code timer}, {@code overdrive} and the lockout existed: an
     * ordinary synergy on the members timer.
     */
    public FoodSynergyData(String id, List<String> requirements, int duration, double healthBonus,
                           List<FoodBuffData.EffectData> effects,
                           List<FoodBuffData.AttributeData> attributes,
                           FoodAftermath aftermath) {
        this(id, requirements, duration, healthBonus, effects, attributes, aftermath, Timer.MEMBERS, null);
    }

    /**
     * The constructor from before {@code aftermath} existed, kept for addons calling it
     * through {@code FlorafareAPI.registerSynergy}. Equivalent to {@link FoodAftermath#NONE}.
     */
    public FoodSynergyData(String id, List<String> requirements, int duration, double healthBonus,
                           List<FoodBuffData.EffectData> effects,
                           List<FoodBuffData.AttributeData> attributes) {
        this(id, requirements, duration, healthBonus, effects, attributes, FoodAftermath.NONE);
    }

    // -------------------------------------------------------------------------
    // TIMER AND STAGES
    // -------------------------------------------------------------------------

    /** Whether it runs on its own clock rather than its ingredients'; see {@link Timer#OWN}. */
    public boolean ownTimer() {
        return timer == Timer.OWN;
    }

    /** Whether it has an {@code overdrive} block, and so stages, a refusal and maybe a lockout. */
    public boolean hasOverdrive() {
        return overdrive != null;
    }

    /** How many stages it has: 1 for an ordinary synergy, 1 + the overdrive's otherwise. */
    public int stageCount() {
        return overdrive == null ? 1 : overdrive.stageCount();
    }

    /**
     * Stage {@code stage} (1-based) as one {@link SynergyOverdrive.Stage}. Stage I is the
     * synergy's own top-level fields; anything past the last stage — a save written against
     * a definition a {@code /reload} has since shortened — reads as the last one, and
     * anything below 1 as stage I.
     */
    public SynergyOverdrive.Stage stage(int stage) {
        int clamped = clampStage(stage);
        if (clamped <= 1) return new SynergyOverdrive.Stage(healthBonus, effects, attributes, aftermath);
        return overdrive.stages().get(clamped - 2);
    }

    /** {@code stage} forced into {@code [1, stageCount()]}. */
    public int clampStage(int stage) {
        return Math.max(1, Math.min(stage, stageCount()));
    }

    /** The aftermath of stage {@code stage}; {@link #aftermath()} for stage I. */
    public FoodAftermath aftermathOf(int stage) {
        return stage(stage).aftermath();
    }

    /**
     * The id synergies lock each other out under: its {@code lockout_group}, or the
     * synergy's own id when it locks only itself out; null when it has no lockout. Two
     * synergies with the same key block each other — an aftermath left by either keeps both
     * from starting — and a synergy with its own id as the key is blocked only by itself.
     */
    @Nullable
    public String lockoutKey() {
        if (!lockout) return null;
        return lockoutGroup != null ? lockoutGroup : id;
    }

    // -------------------------------------------------------------------------
    // DISCOVERY KEYS
    //
    // What a player has learned about a synergy's stages, kept per player as plain strings
    // beside the discovered synergy ids (PlayerFoodComponent#discoveredStages). Two keys per
    // stage, because the two are learned at different moments: a stage's own drawbacks when
    // the synergy reaches it, its aftermath only when that aftermath has actually started.
    // An ordinary synergy is a one-stage synergy here, so its aftermath is "<id>#1/aftermath".
    // -------------------------------------------------------------------------

    /** {@code "<synergyId>#<stage>"} — the player has reached that stage. */
    public static String stageKey(String synergyId, int stage) {
        return synergyId + "#" + stage;
    }

    /** {@code "<synergyId>#<stage>/aftermath"} — that stage's aftermath has started on the player. */
    public static String aftermathKey(String synergyId, int stage) {
        return stageKey(synergyId, stage) + AFTERMATH_SUFFIX;
    }

    private static final String AFTERMATH_SUFFIX = "/aftermath";

    /** Every discovery key one synergy can have, stage I's included. */
    public List<String> discoveryKeys() {
        List<String> keys = new ArrayList<>(stageCount() * 2);
        for (int stage = 1; stage <= stageCount(); stage++) {
            keys.add(stageKey(id, stage));
            keys.add(aftermathKey(id, stage));
        }
        return keys;
    }

    /** Whether a discovery key belongs to {@code synergyId} — {@code "<synergyId>#..."}. */
    public static boolean isKeyOf(String key, String synergyId) {
        return key.startsWith(synergyId + "#");
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    private static final String KEY_ID           = "id";
    private static final String KEY_DURATION     = "duration";
    private static final String KEY_HEALTH_BONUS = "healthBonus";
    private static final String KEY_REQUIREMENTS = "requirements";
    private static final String KEY_EFFECTS      = "effects";
    private static final String KEY_ATTRIBUTES   = "attributes";
    private static final String KEY_TIMER        = "timer";
    private static final String KEY_LOCKOUT      = "lockout";
    private static final String KEY_LOCKOUT_GROUP = "lockoutGroup";

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString(KEY_ID,            id);
        nbt.putInt(KEY_DURATION,         duration);
        nbt.putDouble(KEY_HEALTH_BONUS,  healthBonus);

        NbtList reqList = new NbtList();
        for (String req : requirements) reqList.add(net.minecraft.nbt.NbtString.of(req));
        nbt.put(KEY_REQUIREMENTS, reqList);

        // Delegate list serialization to the shared helper (#23)
        nbt.put(KEY_EFFECTS,    FoodNbtHelper.effectsToNbt(effects));
        nbt.put(KEY_ATTRIBUTES, FoodNbtHelper.attributesToNbt(attributes));
        FoodAftermath.writeTo(nbt, aftermath);
        // Both only when they differ from what an older payload (which lacks them) means,
        // so the join payload of a pack that uses neither does not grow by a byte.
        if (timer != Timer.MEMBERS) nbt.putString(KEY_TIMER, timer.jsonName());
        SynergyOverdrive.writeTo(nbt, overdrive);
        if (lockout) nbt.putBoolean(KEY_LOCKOUT, true);
        if (lockoutGroup != null) nbt.putString(KEY_LOCKOUT_GROUP, lockoutGroup);

        return nbt;
    }

    public static FoodSynergyData fromNbt(NbtCompound nbt) {
        List<String> requirements = new ArrayList<>();
        if (nbt.contains(KEY_REQUIREMENTS, NbtElement.LIST_TYPE)) {
            NbtList reqList = nbt.getList(KEY_REQUIREMENTS, NbtElement.STRING_TYPE);
            for (int i = 0; i < reqList.size(); i++) requirements.add(reqList.getString(i));
        }

        Timer timer = nbt.contains(KEY_TIMER, NbtElement.STRING_TYPE)
                ? Timer.parse(nbt.getString(KEY_TIMER)) : Timer.MEMBERS;

        return new FoodSynergyData(
                nbt.getString(KEY_ID),
                requirements,
                nbt.getInt(KEY_DURATION),
                nbt.getDouble(KEY_HEALTH_BONUS),
                FoodNbtHelper.effectsFromNbt(nbt, KEY_EFFECTS),
                FoodNbtHelper.attributesFromNbt(nbt, KEY_ATTRIBUTES),
                FoodAftermath.readFrom(nbt),
                timer,
                SynergyOverdrive.readFrom(nbt),
                nbt.getBoolean(KEY_LOCKOUT),
                nbt.contains(KEY_LOCKOUT_GROUP, NbtElement.STRING_TYPE) ? nbt.getString(KEY_LOCKOUT_GROUP) : null
        );
    }
}
