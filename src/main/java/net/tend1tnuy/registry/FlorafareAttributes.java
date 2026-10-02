package net.tend1tnuy.registry;

import net.minecraft.entity.attribute.ClampedEntityAttribute;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * The player attributes Florafare adds, both about natural regeneration.
 *
 * <p>They are ordinary registered attributes on every player, so anything that can modify
 * an attribute can modify them: a Florafare food buff's {@code attributes} list, an item's
 * attribute modifiers, a status effect, {@code /attribute}, another mod.
 *
 * <h2>Why registration happens in this class's static initializer</h2>
 *
 * A player only has an attribute instance for the attributes in its <em>default</em>
 * container, and that container is built exactly once, by {@code PlayerEntity
 * #createPlayerAttributes()}, while {@code DefaultAttributeRegistry} initializes. That
 * happens during the game's bootstrap ({@code Bootstrap.logMissing()} touches it), which
 * on both the client and the dedicated server runs <em>before</em> any mod initializer. An
 * attribute registered from {@code onInitialize} therefore arrives too late to be in the
 * container, and {@code player.getAttributeInstance(...)} answers null for it forever.
 *
 * <p>So {@code PlayerAttributesMixin} adds these to the builder at the end of
 * {@code createPlayerAttributes()}, and the first touch of a field here — from inside that
 * mixin — runs this class's static initializer, which registers them on the spot. The
 * registries are still open at that point (Fabric API keeps the vanilla ones unfrozen until
 * every mod has initialized), so this is the same registration any mod does, just early.
 * {@link #init()} touches the class again from the mod initializer, so the attributes are
 * registered even in an environment that never builds a player container (data generation).
 *
 * <p>This is the approach other mods in the same position take: a mixin on
 * {@code createPlayerAttributes} whose additions come from an eagerly registered holder.
 * Fabric's {@code FabricDefaultAttributeRegistry.register(PLAYER, ...)} would also work,
 * but it <em>replaces</em> the player's whole default container, and two mods doing that
 * is a fight about whose builder wins.
 */
public final class FlorafareAttributes {

    private FlorafareAttributes() {}

    /**
     * Scales the regeneration delay after taking damage: the effective delay is
     * {@code regenDelayAfterDamage x} this value. Base 1, 0 means no delay at all.
     *
     * <p>Marked {@link EntityAttribute.Category#NEGATIVE}, so a tooltip shows an increase
     * in red: a longer wait before healing is a drawback.
     */
    public static final RegistryEntry<EntityAttribute> REGEN_DELAY_MULTIPLIER = register(
            "regen_delay_multiplier",
            new ClampedEntityAttribute("attribute.name.florafare.regen_delay_multiplier", 1.0, 0.0, 100.0)
                    .setTracked(true)
                    .setCategory(EntityAttribute.Category.NEGATIVE));

    /**
     * Multiplies natural (hunger) regeneration: in {@code "vanilla"} regeneration mode the
     * health each of vanilla's regeneration heals restores, in {@code "curve"} mode the
     * whole curve. Base 1, 0 stops natural regeneration. Never touches potions or other
     * healing.
     */
    public static final RegistryEntry<EntityAttribute> NATURAL_REGENERATION = register(
            "natural_regeneration",
            new ClampedEntityAttribute("attribute.name.florafare.natural_regeneration", 1.0, 0.0, 100.0)
                    .setTracked(true));

    private static RegistryEntry<EntityAttribute> register(String path, EntityAttribute attribute) {
        return Registry.registerReference(Registries.ATTRIBUTE, Identifier.of(Florafare.MOD_ID, path), attribute);
    }

    /**
     * Adds both attributes to a player's default container. Called by
     * {@code PlayerAttributesMixin} on the builder {@code createPlayerAttributes()} returns.
     */
    public static void addToPlayer(DefaultAttributeContainer.Builder builder) {
        builder.add(REGEN_DELAY_MULTIPLIER);
        builder.add(NATURAL_REGENERATION);
    }

    /**
     * Makes sure the attributes are registered. Normally a no-op — the player-attribute
     * mixin got there first, during bootstrap — but harmless either way.
     */
    public static void init() {
        Florafare.LOGGER.debug("Florafare attributes: {}, {}",
                REGEN_DELAY_MULTIPLIER.getIdAsString(), NATURAL_REGENERATION.getIdAsString());
    }
}
