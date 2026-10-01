package net.tend1tnuy.florafare.food;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public record FoodBuffData(
        String target,
        int duration,
        int nutrition,
        float saturation,
        double healthBonus,
        List<EffectData> effects,
        List<AttributeData> attributes,
        int priority,
        boolean alwaysEdible
) {
    /**
     * Defensive copies of the two lists, so a config handed out by
     * {@code FoodBuffManager.getConfig} cannot be mutated by whoever received it.
     *
     * <p>Every config is shared: the resolution cache hands the same instance to the
     * tooltip, the HUD, the journal, EMI and the eat path, and a datapack entry is
     * shared by every item its target covers. One caller calling {@code add()} on
     * {@code effects()} would therefore have silently changed the buff for everybody.
     * {@link List#copyOf} also rejects nulls inside the list, which is why the
     * null-list guard is spelled out rather than left to it.
     */
    public FoodBuffData {
        effects    = effects    == null ? List.of() : List.copyOf(effects);
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    // -------------------------------------------------------------------------
    // HUNGER: EXPLICIT OR INHERITED
    //
    // An entry that says nothing about nutrition or saturation keeps the eaten item's
    // own value. Both fields used to default to zero, and since Florafare's numbers
    // replace the item's on every managed bite, an entry written only to attach a buff
    // to a food silently turned that food into one that restored nothing.
    //
    // "Inherit" is a sentinel in the field rather than a nullable wrapper so the record
    // keeps its shape — it is public API, built by addons through the canonical
    // constructor — and so it survives every serialized form (NBT on disk, NBT in the
    // sync payloads) without a format change: an int and a float already round-trip
    // exactly, NaN included. The sentinel is resolved per item, at the point of use,
    // because one entry can cover many items (a #tag, a namespace:, template:default)
    // and each keeps its own value. Nothing outside this record should ever read the raw
    // field for display or arithmetic; the effective* helpers below are the way in.
    // -------------------------------------------------------------------------

    /** {@link #nutrition()} value meaning "the eaten item's own nutrition". */
    public static final int INHERIT_NUTRITION = Integer.MIN_VALUE;

    /**
     * {@link #saturation()} value meaning "the eaten item's own saturation modifier".
     * NaN, so it can never collide with a number a pack actually wrote. Test with
     * {@link #hasSaturation()}, never with {@code ==}: NaN is not equal to itself.
     */
    public static final float INHERIT_SATURATION = Float.NaN;

    /** Whether the entry sets its own nutrition, rather than keeping the item's. */
    public boolean hasNutrition() {
        return nutrition != INHERIT_NUTRITION;
    }

    /** Whether the entry sets its own saturation modifier, rather than keeping the item's. */
    public boolean hasSaturation() {
        return !Float.isNaN(saturation);
    }

    /**
     * Whether the entry leaves hunger entirely to the item. The eat hook then lets
     * vanilla's own {@code HungerManager#eat} run untouched — which also keeps any other
     * mod wrapping that call in the loop.
     */
    public boolean inheritsHunger() {
        return !hasNutrition() && !hasSaturation();
    }

    /**
     * Whether this entry grants hunger (and its {@code effects}) only, with no buff.
     *
     * <p>A {@code duration} of zero or less used to still create a buff — for a single
     * tick, in a slot, with {@code BUFF_APPLIED}, the advancement trigger, synergy
     * evaluation and, on a full list, the "slots full" message all firing for something
     * that was gone before the player could see it. Such an entry now takes no slot at
     * all: its nutrition, saturation and status effects are applied and nothing else.
     */
    public boolean isHungerOnly() {
        return duration <= 0;
    }

    /** The nutrition a bite of an item with this food component actually restores. */
    public int effectiveNutrition(@Nullable FoodComponent food) {
        return resolveNutrition(nutrition, food == null ? 0 : food.nutrition());
    }

    /** {@link #effectiveNutrition(FoodComponent)} for a stack. */
    public int effectiveNutrition(ItemStack stack) {
        return effectiveNutrition(foodOf(stack));
    }

    /**
     * The saturation <em>modifier</em> a bite applies — the meaning the datapack field and
     * {@code HungerManager#add} share.
     */
    public float effectiveSaturation(@Nullable FoodComponent food) {
        return food == null
                ? resolveSaturationModifier(saturation, 0, 0f)
                : resolveSaturationModifier(saturation, food.nutrition(), food.saturation());
    }

    /** {@link #effectiveSaturation(FoodComponent)} for a stack. */
    public float effectiveSaturation(ItemStack stack) {
        return effectiveSaturation(foodOf(stack));
    }

    /**
     * The saturation <em>points</em> a bite restores — the number every display shows.
     *
     * <p>Not simply {@code nutrition x modifier x 2} when both halves are inherited: the
     * eat hook then hands the bite to vanilla, which adds the item's saturation as it is,
     * and a modded drink with zero nutrition but some saturation has no modifier that
     * reproduces that.
     */
    public float effectiveSaturationPoints(@Nullable FoodComponent food) {
        if (inheritsHunger() && food != null) return food.saturation();
        return saturationPoints(effectiveNutrition(food), effectiveSaturation(food));
    }

    /** {@link #effectiveSaturationPoints(FoodComponent)} for a stack. */
    public float effectiveSaturationPoints(ItemStack stack) {
        return effectiveSaturationPoints(foodOf(stack));
    }

    private static FoodComponent foodOf(ItemStack stack) {
        return stack == null ? null : stack.get(DataComponentTypes.FOOD);
    }

    /**
     * The inheritance rule for nutrition, on bare numbers so a unit test can drive it.
     * Any explicit value wins — negative ones included, which drain hunger on purpose.
     */
    public static int resolveNutrition(int configured, int itemNutrition) {
        return configured == INHERIT_NUTRITION ? itemNutrition : configured;
    }

    /**
     * The inheritance rule for the saturation modifier. An inherited modifier is the one
     * the item implies — its saturation points over twice its nutrition — so a pack that
     * changes only nutrition keeps the item's saturation <em>ratio</em>.
     */
    public static float resolveSaturationModifier(float configured, int itemNutrition,
                                                  float itemSaturationPoints) {
        if (!Float.isNaN(configured)) return configured;
        return saturationModifierOf(itemNutrition, itemSaturationPoints);
    }

    /**
     * Turns a {@link FoodComponent}'s final saturation back into the modifier it was built
     * from. Zero for a food with no nutrition, where the modifier is undefined.
     */
    public static float saturationModifierOf(int nutrition, float saturationPoints) {
        return nutrition > 0 ? saturationPoints / (nutrition * 2.0f) : 0.0f;
    }

    /** What {@code HungerManager#add} turns a nutrition and modifier into. */
    public static float saturationPoints(int nutrition, float saturationModifier) {
        return nutrition * saturationModifier * 2.0f;
    }

    private static final String KEY_TARGET      = "target";
    private static final String KEY_DURATION    = "duration";
    private static final String KEY_NUTRITION   = "nutrition";
    private static final String KEY_SATURATION  = "saturation";
    private static final String KEY_HEALTH_BONUS = "healthBonus";
    private static final String KEY_EFFECTS     = "effects";
    private static final String KEY_ATTRIBUTES  = "attributes";
    private static final String KEY_PRIORITY    = "priority";
    private static final String KEY_ALWAYS_EDIBLE = "alwaysEdible";

    public record EffectData(Identifier id, int duration, int amplifier) {}
    public record AttributeData(Identifier attributeId, double amount, String operation) {}

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString(KEY_TARGET,       target);
        nbt.putInt(KEY_DURATION,        duration);
        nbt.putInt(KEY_NUTRITION,       nutrition);
        nbt.putFloat(KEY_SATURATION,    saturation);
        nbt.putDouble(KEY_HEALTH_BONUS, healthBonus);
        nbt.putInt(KEY_PRIORITY,        priority);
        nbt.putBoolean(KEY_ALWAYS_EDIBLE, alwaysEdible);
        // Delegate list serialization to the shared helper (#23)
        nbt.put(KEY_EFFECTS,    FoodNbtHelper.effectsToNbt(effects));
        nbt.put(KEY_ATTRIBUTES, FoodNbtHelper.attributesToNbt(attributes));
        return nbt;
    }

    /** Starts a fluent builder for a buff targeting the given id (item id or "#tag"). */
    public static Builder builder(String target) {
        return new Builder(target);
    }

    /**
     * Ergonomic alternative to the 9-argument record constructor for mods constructing
     * a {@link FoodBuffData} in code (e.g. to pass into {@code FlorafareAPI.registerFoodBuff}
     * or {@code FlorafareAPI.applyBuffToPlayer}). Every field defaults to the same value
     * the datapack loader uses when a JSON entry omits it — which for nutrition and
     * saturation means "keep the eaten item's own", see {@link #INHERIT_NUTRITION}.
     */
    public static final class Builder {
        private final String target;
        private int duration = 6000;
        private int nutrition = INHERIT_NUTRITION;
        private float saturation = INHERIT_SATURATION;
        private double healthBonus = 0.0;
        private final List<EffectData> effects = new ArrayList<>();
        private final List<AttributeData> attributes = new ArrayList<>();
        private int priority = 0;
        private boolean alwaysEdible = false;

        private Builder(String target) {
            this.target = target;
        }

        public Builder duration(int duration) { this.duration = duration; return this; }
        public Builder nutrition(int nutrition) { this.nutrition = nutrition; return this; }
        public Builder saturation(float saturation) { this.saturation = saturation; return this; }
        public Builder healthBonus(double healthBonus) { this.healthBonus = healthBonus; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public Builder alwaysEdible(boolean alwaysEdible) { this.alwaysEdible = alwaysEdible; return this; }

        public Builder addEffect(Identifier effectId, int duration, int amplifier) {
            this.effects.add(new EffectData(effectId, duration, amplifier));
            return this;
        }

        public Builder addAttribute(Identifier attributeId, double amount, String operation) {
            this.attributes.add(new AttributeData(attributeId, amount, operation));
            return this;
        }

        public FoodBuffData build() {
            // No copies needed here any more: the canonical constructor makes its own.
            return new FoodBuffData(target, duration, nutrition, saturation, healthBonus,
                    effects, attributes, priority, alwaysEdible);
        }
    }

    /**
     * Reads a config back. The inherit sentinels need no special handling — they are
     * plain values to NBT — but an absent key still reads as 0, not as "inherit": every
     * version has always written both keys, so a missing one can only come from a config
     * built by hand, and 0 is what such a config has always meant.
     */
    public static FoodBuffData fromNbt(NbtCompound nbt) {
        int priority = nbt.contains(KEY_PRIORITY) ? nbt.getInt(KEY_PRIORITY) : 0;
        boolean alwaysEdible = nbt.contains(KEY_ALWAYS_EDIBLE) && nbt.getBoolean(KEY_ALWAYS_EDIBLE);
        return new FoodBuffData(
                nbt.getString(KEY_TARGET),
                nbt.getInt(KEY_DURATION),
                nbt.getInt(KEY_NUTRITION),
                nbt.getFloat(KEY_SATURATION),
                nbt.getDouble(KEY_HEALTH_BONUS),
                FoodNbtHelper.effectsFromNbt(nbt, KEY_EFFECTS),
                FoodNbtHelper.attributesFromNbt(nbt, KEY_ATTRIBUTES),
                priority,
                alwaysEdible
        );
    }
}
