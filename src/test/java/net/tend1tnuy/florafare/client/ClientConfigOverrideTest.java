package net.tend1tnuy.florafare.client;

import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodExclusions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    /** What {@code rememberLocal} is told when the connection is to someone else's server. */
    private static final boolean REMOTE_SERVER = false;
    /** ...and when it is to this client's own integrated server. */
    private static final boolean LOCALLY_HOSTED = true;

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
        PlayerFoodComponent.setMaxBuffSlots(3);
        FoodExclusions.clearRemote();
        FoodExclusions.loadFromConfig(List.of("minecraft:cake"), List.of(), List.of());

        // Drop any snapshot a previous test left behind.
        ClientConfigOverride.restoreLocal();
        resetFieldsOnly();
    }

    private void resetFieldsOnly() {
        FlorafareConfig.maxBuffSlots               = 3;
        FlorafareConfig.enableSynergies            = true;
        FlorafareConfig.allowEatingWhenFull        = true;
        FlorafareConfig.enableForgottenMead        = true;
        PlayerFoodComponent.setMaxBuffSlots(3);
        FoodExclusions.clearRemote();
    }

    /** Stands in for the server config sync packet arriving after a join. */
    private void serverOverrides() {
        FlorafareConfig.maxBuffSlots              = 1;
        FlorafareConfig.enableSynergies           = false;
        FlorafareConfig.allowEatingWhenFull       = false;
        FlorafareConfig.enableForgottenMead       = false;
        FlorafareConfig.autoGenHealthMultiplier   = 2.0;
        PlayerFoodComponent.setMaxBuffSlots(1);
        FoodExclusions.applyRemote(List.of("someothermod:stew"), List.of());
    }

    @Test
    @DisplayName("leaving a server puts this client's own settings back")
    void restoresAfterServerOverride() {
        ClientConfigOverride.rememberLocal(REMOTE_SERVER);
        serverOverrides();

        assertTrue(ClientConfigOverride.restoreLocal(),
                "restore should report that the server had overridden something");

        assertEquals(3, FlorafareConfig.maxBuffSlots);
        assertEquals(3, PlayerFoodComponent.getMaxBuffSlots(),
                "the component's slot count is what actually gates buffs, so it must follow too");
        assertTrue(FlorafareConfig.enableSynergies);
        assertTrue(FlorafareConfig.allowEatingWhenFull);
        assertTrue(FlorafareConfig.enableForgottenMead);
        assertEquals(0.5, FlorafareConfig.autoGenHealthMultiplier);
    }

    @Test
    @DisplayName("the server's exclusion list is dropped on disconnect and this client's put back")
    void restoresExclusions() {
        ClientConfigOverride.rememberLocal(REMOTE_SERVER);
        serverOverrides();

        ClientConfigOverride.restoreLocal();

        assertEquals(Set.of("minecraft:cake"), FoodBuffManager.getExcludedItems());
    }

    @Test
    @DisplayName("a server that overrides nothing reports no restore")
    void unchangedServerIsANoOp() {
        ClientConfigOverride.rememberLocal(REMOTE_SERVER);
        // The server syncs back the very values just captured.

        assertFalse(ClientConfigOverride.restoreLocal(),
                "nothing differed, so the disconnect handler should not report a restore");
        assertEquals(3, FlorafareConfig.maxBuffSlots);
        assertTrue(FlorafareConfig.enableSynergies);
    }

    @Test
    @DisplayName("a locally hosted world takes no snapshot, so the config screen edits the live fields")
    void singleplayerTakesNoSnapshot() {
        ClientConfigOverride.rememberLocal(LOCALLY_HOSTED);

        // This is the whole point: with a snapshot in force, ModMenu writes land on the
        // snapshot and applyAndSave() skips the step that makes them take effect — so the
        // Balance tab looked like a set of dead switches until the player left the world.
        assertFalse(FlorafareConfig.isServerOverridden(),
                "this player's own integrated server is not 'a server overriding them'");

        FlorafareConfig.setLocalMaxBuffSlots(6);
        assertEquals(6, FlorafareConfig.maxBuffSlots,
                "an edit in singleplayer must reach the live field immediately");

        assertFalse(ClientConfigOverride.restoreLocal(),
                "there is nothing to restore, and the edit must not be rolled back");
        assertEquals(6, FlorafareConfig.maxBuffSlots);
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
        ClientConfigOverride.rememberLocal(REMOTE_SERVER);
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
        ClientConfigOverride.rememberLocal(REMOTE_SERVER);
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
        ClientConfigOverride.rememberLocal(REMOTE_SERVER);
        serverOverrides();
        ClientConfigOverride.restoreLocal();

        // Player changes a setting in the main menu, then joins a server again.
        FlorafareConfig.maxBuffSlots       = 5;
        PlayerFoodComponent.setMaxBuffSlots(5);

        ClientConfigOverride.rememberLocal(REMOTE_SERVER);
        serverOverrides();
        ClientConfigOverride.restoreLocal();

        assertEquals(5, FlorafareConfig.maxBuffSlots,
                "the second snapshot must capture the edited value, not the startup one");
        assertEquals(5, PlayerFoodComponent.getMaxBuffSlots());
    }
}
