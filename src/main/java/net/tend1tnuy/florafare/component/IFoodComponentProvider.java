package net.tend1tnuy.florafare.component;

/**
 * Interface used to access the {@link PlayerFoodComponent} attached to a player entity.
 * This is implemented via Mixins on the PlayerEntity class.
 */
public interface IFoodComponentProvider {

    /**
     * Retrieves the custom food component for the player.
     *
     * @return The associated PlayerFoodComponent instance.
     */
    PlayerFoodComponent florafare$getFoodComponent();
}