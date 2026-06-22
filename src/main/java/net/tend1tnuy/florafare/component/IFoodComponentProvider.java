package net.tend1tnuy.florafare.component;

/**
 * Interface used to access the {@link PlayerFoodComponent} attached to a player.
 * Typically implemented via Mixins on the PlayerEntity class.
 */
public interface IFoodComponentProvider {

    /**
     * Retrieves the food component for the player.
     *
     * @return the associated PlayerFoodComponent.
     */
    PlayerFoodComponent florafare$getFoodComponent();
}