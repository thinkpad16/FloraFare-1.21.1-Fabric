package net.tend1tnuy.florafare.food;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameRules;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.api.FlorafareEvents;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.registry.FlorafareAttributes;

/**
 * Natural (hunger) regeneration: the delay after damage and the optional curve.
 *
 * <h2>What is touched, and what is not</h2>
 *
 * Everything here hangs off {@code HungerManager#update}, the one place vanilla turns food
 * into health (see {@code HungerManagerMixin}). Its two regeneration branches — fast at a
 * full bar with saturation, slow at 18 and up — are both gated on
 * {@code PlayerEntity#canFoodHeal()}, so answering false there for a delayed player (or
 * for everyone in curve mode) stops both cleanly: the branch falls through to the
 * starvation check, which needs an empty bar and so cannot fire for anyone who was about
 * to regenerate, and otherwise just resets vanilla's regeneration timer. Exhaustion,
 * hunger loss and starvation run exactly as before.
 *
 * <p>Nothing else that heals is affected: the Regeneration effect, instant health,
 * golden apples, peaceful difficulty's regeneration and every other mod's healing call
 * {@code LivingEntity#heal} from somewhere other than that method.
 *
 * <h2>Hooks for packs and mods</h2>
 *
 * <ul>
 *   <li>{@code florafare:regen_delay_multiplier} scales the delay per player (food buffs,
 *       gear, effects); 0 means none.</li>
 *   <li>{@link #IGNORES_REGEN_DELAY}, a status-effect tag: while the player has any of
 *       these, the delay does not hold regeneration back.</li>
 *   <li>{@link #NO_REGEN_DELAY}, a damage-type tag: such damage does not start or extend
 *       the delay. Ships with {@code minecraft:starve}, so a starving player is not also
 *       locked out of the regeneration that eating would restore.</li>
 *   <li>{@link FlorafareEvents#REGEN_DELAY} and the delay methods on
 *       {@link net.tend1tnuy.florafare.api.FlorafareAPI}.</li>
 *   <li>{@code florafare:natural_regeneration} multiplies the regeneration itself.</li>
 * </ul>
 */
public final class NaturalRegeneration {

    private NaturalRegeneration() {}

    /** Status effects that let natural regeneration through during the delay after damage. */
    public static final TagKey<StatusEffect> IGNORES_REGEN_DELAY =
            TagKey.of(RegistryKeys.STATUS_EFFECT, Identifier.of(Florafare.MOD_ID, "ignores_regen_delay"));

    /** Damage types that do not start or extend the delay after damage. */
    public static final TagKey<DamageType> NO_REGEN_DELAY =
            TagKey.of(RegistryKeys.DAMAGE_TYPE, Identifier.of(Florafare.MOD_ID, "no_regen_delay"));

    /** Hunger Tweaks' mod id; its multipliers stack with Florafare's. */
    static final String HUNGER_TWEAKS_MOD_ID = "hunger_tweaks";

    /** Registers the damage listener and the start-up compatibility check. */
    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(NaturalRegeneration::onDamaged);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> warnIfHungerTweaksLoaded());
    }

    // -------------------------------------------------------------------------
    // DELAY AFTER DAMAGE
    // -------------------------------------------------------------------------

    /**
     * Starts or extends the delay when a hit lands on a player.
     *
     * <p>Fabric fires {@code AFTER_DAMAGE} at the end of {@code LivingEntity#damage} once
     * the damage has gone through, and not at all for a hit that was refused outright
     * (invulnerability frames absorbing it, creative mode, an {@code ALLOW_DAMAGE}
     * veto) or one that killed. {@code damageTaken} is 0 for a shield block, which is
     * also reported by {@code blocked}; either way nothing landed, so nothing starts.
     * A hit soaked up by absorption hearts does count — the player was in a fight.
     */
    private static void onDamaged(LivingEntity entity, DamageSource source,
                                  float baseDamageTaken, float damageTaken, boolean blocked) {
        if (!(entity instanceof ServerPlayerEntity player)) return;
        if (blocked || !(damageTaken > 0f)) return;

        int proposed = source.isIn(NO_REGEN_DELAY)
                ? 0
                : RegenMath.delayTicks(FlorafareConfig.regenDelayAfterDamage,
                        attributeValue(player, FlorafareAttributes.REGEN_DELAY_MULTIPLIER));
        int ticks = FlorafareEvents.REGEN_DELAY.invoker().regenDelayTicks(player, source, proposed);
        if (ticks > 0) component(player).extendRegenDelay(now(player), ticks);
    }

    /** Ticks of delay left for this player; always 0 on the client. */
    public static int remainingDelay(PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return 0;
        return component(serverPlayer).getRegenDelayRemaining(now(serverPlayer));
    }

    /**
     * Whether natural regeneration is held back right now: a delay is running and the
     * player has no effect in {@link #IGNORES_REGEN_DELAY}. The effect check comes second
     * and only runs while a delay is active, so the common case costs one subtraction.
     */
    public static boolean isDelayed(PlayerEntity player) {
        return remainingDelay(player) > 0 && !hasDelayIgnoringEffect(player);
    }

    /** Replaces the delay with exactly {@code ticks} from now (0 clears it). Server players only. */
    public static void setDelay(PlayerEntity player, int ticks) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            component(serverPlayer).setRegenDelay(now(serverPlayer), ticks);
        }
    }

    /** Makes the delay at least {@code ticks} from now. Server players only. */
    public static void extendDelay(PlayerEntity player, int ticks) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            component(serverPlayer).extendRegenDelay(now(serverPlayer), ticks);
        }
    }

    private static boolean hasDelayIgnoringEffect(PlayerEntity player) {
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            if (effect.getEffectType().isIn(IGNORES_REGEN_DELAY)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // HUNGERMANAGER#UPDATE HOOKS
    // -------------------------------------------------------------------------

    /**
     * Whether vanilla's regeneration branches may run for this player, on top of what
     * {@code canFoodHeal()} itself said: not in curve mode, which replaces them, and not
     * during the delay after damage.
     */
    public static boolean allowsVanillaRegen(PlayerEntity player) {
        if (FlorafareConfig.regenMode == FlorafareConfig.RegenMode.CURVE) return false;
        return !isDelayed(player);
    }

    /**
     * The health one of vanilla's regeneration heals restores, scaled by
     * {@code florafare:natural_regeneration}.
     */
    public static float scaleVanillaHeal(PlayerEntity player, float amount) {
        double multiplier = attributeValue(player, FlorafareAttributes.NATURAL_REGENERATION);
        return multiplier == 1.0 ? amount : (float) (amount * multiplier);
    }

    /** Whether the curve is the regeneration in force. */
    public static boolean isCurveMode() {
        return FlorafareConfig.regenMode == FlorafareConfig.RegenMode.CURVE;
    }

    /**
     * The curve's settings as they stand right now. Built per heal rather than cached, so a
     * {@code /reload} applies on the next one with nothing to invalidate.
     */
    public static RegenMath.Curve currentCurve() {
        return new RegenMath.Curve(
                FlorafareConfig.regenBase,
                FlorafareConfig.regenMaxHealthExponent,
                FlorafareConfig.regenMinHunger,
                FlorafareConfig.regenHungerCurve,
                FlorafareConfig.regenSaturationBonus,
                FlorafareConfig.regenCostPerHealth,
                FlorafareConfig.maxSaturation);
    }

    /**
     * One curve heal, run every {@link RegenMath#CURVE_INTERVAL_TICKS} ticks from the end
     * of {@code HungerManager#update} while curve mode is on.
     *
     * <p>Heals when the {@code naturalRegeneration} game rule allows it, the player can
     * heal at all ({@code canFoodHeal()}: alive and below max health), their hunger is at
     * least {@code regenMinHunger} and the delay after damage is not holding them back.
     * The exhaustion it costs is charged for the health the heal actually restored, so
     * topping off the last half heart costs half a heart's worth, and it goes through
     * {@code HungerManager#addExhaustion} like vanilla's regeneration cost does (and is
     * therefore scaled by {@code exhaustionMultiplier} the same way).
     */
    public static void curveHeal(PlayerEntity player, HungerManager hunger) {
        if (!(player instanceof ServerPlayerEntity)) return;
        if (!player.getWorld().getGameRules().getBoolean(GameRules.NATURAL_REGENERATION)) return;
        if (!player.canFoodHeal()) return;

        RegenMath.Curve curve = currentCurve();
        int foodLevel = hunger.getFoodLevel();
        if (!RegenMath.hungerAllows(foodLevel, curve.minHunger())) return;
        if (isDelayed(player)) return;

        float maxHealth = player.getMaxHealth();
        float amount = RegenMath.healPerInterval(curve, maxHealth, foodLevel, hunger.getSaturationLevel(),
                attributeValue(player, FlorafareAttributes.NATURAL_REGENERATION));
        if (!(amount > 0f)) return;

        float before = player.getHealth();
        player.heal(amount);
        float healed = player.getHealth() - before;

        float cost = RegenMath.exhaustionCost(healed, curve.costPerHealth(), maxHealth, curve.maxHealthExponent());
        if (cost > 0f) hunger.addExhaustion(cost);
    }

    // -------------------------------------------------------------------------
    // COMPATIBILITY
    // -------------------------------------------------------------------------

    /**
     * Hunger Tweaks multiplies the same two things Florafare now can: every exhaustion
     * addition ({@code exhaust_rate}) and vanilla's regeneration heals
     * ({@code healing_rate}). Both mods' hooks compose rather than conflict, so with both
     * installed the multipliers simply stack — which is almost never what the pack meant,
     * and impossible to tell from either config alone. Said once per server start, and
     * nothing is turned off: the pack decides which one goes.
     */
    private static void warnIfHungerTweaksLoaded() {
        if (!FabricLoader.getInstance().isModLoaded(HUNGER_TWEAKS_MOD_ID)) return;
        Florafare.LOGGER.warn("Hunger Tweaks is installed alongside Florafare's natural regeneration. "
                        + "Their multipliers stack: Hunger Tweaks' exhaust_rate multiplies on top of Florafare's "
                        + "exhaustionMultiplier (currently {}), and its healing_rate multiplies vanilla regeneration "
                        + "on top of the florafare:natural_regeneration attribute (regenMode is currently \"{}\"). "
                        + "Florafare covers both (exhaustionMultiplier, regenMode \"curve\"); removing Hunger "
                        + "Tweaks is recommended. Nothing has been disabled.",
                FlorafareConfig.exhaustionMultiplier, FlorafareConfig.regenMode.configName());
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    /**
     * An attribute's value, or its default when the player has no instance of it. Every
     * player built after bootstrap carries both of Florafare's, but
     * {@code getAttributeValue} throws on a missing one, and a regeneration tick is no
     * place to find out that another mod replaced the player's default container.
     */
    private static double attributeValue(PlayerEntity player, RegistryEntry<EntityAttribute> attribute) {
        EntityAttributeInstance instance = player.getAttributeInstance(attribute);
        return instance != null ? instance.getValue() : attribute.value().getDefaultValue();
    }

    private static long now(ServerPlayerEntity player) {
        return player.server.getTicks();
    }

    private static PlayerFoodComponent component(PlayerEntity player) {
        return ((IFoodComponentProvider) player).florafare$getFoodComponent();
    }
}
