package net.tend1tnuy.florafare.client;

import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A server's gameplay settings must not outlive the connection that carried them.
 * Joining a server overwrites Florafare's static config fields in place, and nothing
 * used to put them back — so a server's {@code maxBuffSlots} followed the player into
 * their own singleplayer world and stayed there until the game was restarted.
 *
 * <p>These are the rules the disconnect handler depends on, driven directly against
 * the same static fields the networking code writes to.
 */
class ClientConfigOverrideTest {

    /** Puts the fields into a known "this client's own settings" state. */
    @BeforeEach
    void resetToLocalDefaults() {
        FlorafareConfig.maxBuffSlots               = 3;
        FlorafareConfig.autoGenDurationMultiplier  = 1200;
        FlorafareConfig.autoGenHealthMultiplier    = 0.5;
        FlorafareConfig.enableSynergies            = true;
        FlorafareConfig.enableAlwaysEdibleOverride = true;
        FlorafareConfig.allowEatingWhenFull        = true;
        FlorafareConfig.respectVanillaFoodEffects  = false;
        FlorafareConfig.enableForgottenMead        = true;
        PlayerFoodComponent.MAX_BUFF_SLOTS         = 3;
        FoodBuffManager.setExcludedItems(Set.of("minecraft:cake"));

        // Drop any snapshot a previous test left behind.
        ClientConfigOverride.restoreLocal();
        resetFieldsOnly();
    }

    private void resetFieldsOnly() {
        FlorafareConfig.maxBuffSlots               = 3;
        FlorafareConfig.enableSynergies            = true;
        FlorafareConfig.allowEatingWhenFull        = true;
        FlorafareConfig.enableForgottenMead        = true;
        PlayerFoodComponent.MAX_BUFF_SLOTS         = 3;
        FoodBuffManager.setExcludedItems(Set.of("minecraft:cake"));
    }

    /** Stands in for the server config sync packet arriving after a join. */
    private void serverOverrides() {
        FlorafareConfig.maxBuffSlots              = 1;
        FlorafareConfig.enableSynergies           = false;
        FlorafareConfig.allowEatingWhenFull       = false;
        FlorafareConfig.enableForgottenMead       = false;
        FlorafareConfig.autoGenHealthMultiplier   = 2.0;
        PlayerFoodComponent.MAX_BUFF_SLOTS        = 1;
        FoodBuffManager.setExcludedItems(Set.of("someothermod:stew"));
    }

    @Test
    @DisplayName("leaving a server puts this client's own settings back")
    void restoresAfterServerOverride() {
        ClientConfigOverride.rememberLocal();
        serverOverrides();

        assertTrue(ClientConfigOverride.restoreLocal(),
                "restore should report that the server had overridden something");

        assertEquals(3, FlorafareConfig.maxBuffSlots);
        assertEquals(3, PlayerFoodComponent.MAX_BUFF_SLOTS,
                "the component's slot count is what actually gates buffs, so it must follow too");
        assertTrue(FlorafareConfig.enableSynergies);
        assertTrue(FlorafareConfig.allowEatingWhenFull);
        assertTrue(FlorafareConfig.enableForgottenMead);
        assertEquals(0.5, FlorafareConfig.autoGenHealthMultiplier);
    }

    @Test
    @DisplayName("the exclusion set is restored, since nothing else rebuilds it client-side")
    void restoresExclusions() {
        ClientConfigOverride.rememberLocal();
        serverOverrides();

        ClientConfigOverride.restoreLocal();

        assertEquals(Set.of("minecraft:cake"), FoodBuffManager.getExcludedItems());
    }

    @Test
    @DisplayName("a singleplayer exit changes nothing and reports no override")
    void singleplayerExitIsANoOp() {
        ClientConfigOverride.rememberLocal();
        // The integrated server syncs back the very values just captured.

        assertFalse(ClientConfigOverride.restoreLocal(),
                "nothing differed, so the disconnect handler should not report a restore");
        assertEquals(3, FlorafareConfig.maxBuffSlots);
        assertTrue(FlorafareConfig.enableSynergies);
    }

    @Test
    @DisplayName("a disconnect without a preceding connect leaves the fields alone")
    void restoreWithoutSnapshotIsHarmless() {
        FlorafareConfig.maxBuffSlots = 7;

        assertFalse(ClientConfigOverride.restoreLocal());
        assertEquals(7, FlorafareConfig.maxBuffSlots,
                "with no snapshot taken there is nothing to restore, and stale zeros "
                        + "must not be written over live settings");
    }

    @Test
    @DisplayName("while a server is connected, the local accessors still report this client's own values")
    void localAccessorsIgnoreTheServersValues() {
        ClientConfigOverride.rememberLocal();
        serverOverrides();

        // The live fields must hold the server's values — gameplay and the client's own
        // eating prediction read them directly.
        assertEquals(1, FlorafareConfig.maxBuffSlots);
        assertFalse(FlorafareConfig.allowEatingWhenFull);

        // ...but these are what the config screen shows and what save() writes, and they
        // are still the player's. Writing the live values to florafare.json is how a
        // server's balance used to become permanent: moving the HUD called save().
        assertTrue(FlorafareConfig.isServerOverridden());
        assertEquals(3, FlorafareConfig.localMaxBuffSlots());
        assertTrue(FlorafareConfig.localAllowEatingWhenFull());
        assertTrue(FlorafareConfig.localEnableSynergies());
        assertTrue(FlorafareConfig.localEnableForgottenMead());
        assertEquals(0.5, FlorafareConfig.localAutoGenHealthMultiplier());
    }

    @Test
    @DisplayName("an edit made while connected lands on this client's settings, not the server's")
    void editsWhileConnectedGoToTheLocalSnapshot() {
        ClientConfigOverride.rememberLocal();
        serverOverrides();

        // The player opens the config screen mid-session and changes their own preference.
        FlorafareConfig.setLocalMaxBuffSlots(6);
        FlorafareConfig.setLocalAllowEatingWhenFull(false);

        assertEquals(1, FlorafareConfig.maxBuffSlots,
                "the server is still authoritative for this session");
        assertEquals(6, FlorafareConfig.localMaxBuffSlots());

        ClientConfigOverride.restoreLocal();

        assertEquals(6, FlorafareConfig.maxBuffSlots,
                "the edit must take effect once the server's override ends, not be discarded");
        assertFalse(FlorafareConfig.allowEatingWhenFull);
    }

    @Test
    @DisplayName("with no server connected the local accessors are just the fields")
    void localAccessorsPassThroughWhenOffline() {
        assertFalse(FlorafareConfig.isServerOverridden());
        assertEquals(FlorafareConfig.maxBuffSlots, FlorafareConfig.localMaxBuffSlots());

        FlorafareConfig.setLocalMaxBuffSlots(4);
        assertEquals(4, FlorafareConfig.maxBuffSlots,
                "offline there is no snapshot to divert the write to");
    }

    @Test
    @DisplayName("each connect re-snapshots, so ModMenu edits between sessions survive")
    void snapshotFollowsLaterLocalEdits() {
        ClientConfigOverride.rememberLocal();
        serverOverrides();
        ClientConfigOverride.restoreLocal();

        // Player changes a setting in the main menu, then joins a server again.
        FlorafareConfig.maxBuffSlots       = 5;
        PlayerFoodComponent.MAX_BUFF_SLOTS = 5;

        ClientConfigOverride.rememberLocal();
        serverOverrides();
        ClientConfigOverride.restoreLocal();

        assertEquals(5, FlorafareConfig.maxBuffSlots,
                "the second snapshot must capture the edited value, not the startup one");
        assertEquals(5, PlayerFoodComponent.MAX_BUFF_SLOTS);
    }
}
