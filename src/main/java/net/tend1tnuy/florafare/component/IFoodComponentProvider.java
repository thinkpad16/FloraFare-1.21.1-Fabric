package net.tend1tnuy.florafare.component;

/**
 * Provides access to the {@link PlayerFoodComponent}
 * attached to a player entity.
 *
 * <p>This interface is typically implemented through Mixins
 * and serves as the primary access point for player-specific
 * Florafare data.</p>
 */
public interface IFoodComponentProvider {

    /**
     * Returns the {@link PlayerFoodComponent} associated
     * with the current player instance.
     *
     * @return the player's food component
     */
    PlayerFoodComponent florafare$getFoodComponent();
}