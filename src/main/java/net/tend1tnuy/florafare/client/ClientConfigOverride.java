package net.tend1tnuy.florafare.client;

import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps a server's gameplay settings from outliving the connection that carried them.
 *
 * <p>Florafare's server-authoritative settings live in {@code static} fields on
 * {@link FlorafareConfig}, and joining a server overwrites them in place so the client
 * predicts eating exactly the way the server resolves it. That part is deliberate. The
 * problem was that nothing ever put them back: {@code FlorafareConfig.load()} runs once
 * at mod init, so after leaving a server with, say, {@code maxBuffSlots = 1} and
 * {@code allowEatingWhenFull = false}, the player's own singleplayer world kept playing
 * by that server's rules until they restarted the game entirely.
 *
 * <p>So the local values are captured on connect and restored on disconnect. Capture
 * happens at connect time rather than at startup on purpose — it picks up any edit the
 * player made in the ModMenu screen while in the main menu, which a startup snapshot
 * would silently revert.
 *
 * <p>The snapshot itself lives in {@link FlorafareConfig}, not here, because restoring on
 * disconnect was only half the job: anything that called {@code FlorafareConfig.save()}
 * mid-session — moving the HUD, pressing Save in the config screen — wrote the server's
 * values straight into the player's {@code florafare.json}, where the disconnect restore
 * could no longer reach them. Keeping the snapshot next to {@code save()} lets it write
 * the player's own values instead, and lets the config screen edit them.
 *
 * <p>The datapack config and synergy maps are deliberately <em>not</em> restored here.
 * They repopulate themselves: loading any world runs the datapack reload listeners, and
 * joining any server re-syncs them. Clearing them on disconnect would instead race the
 * integrated server, which is still ticking (and still saving players) while a
 * singleplayer world shuts down.
 */
public final class ClientConfigOverride {

    private ClientConfigOverride() {}

    private static Set<String> excludedItems = Set.of();
    private static boolean captured = false;

    /**
     * Snapshots this client's own settings, immediately before a server is given the
     * chance to overwrite them.
     *
     * <p><b>Not for a locally hosted world.</b> An integrated server is this player's own game
     * reading this player's own config file, so there is nothing to protect them from —
     * and taking the snapshot anyway did active harm. Every edit in the ModMenu screen
     * went into the snapshot instead of the live fields, and {@code applyAndSave} skips
     * the "make it take effect now" step whenever an override is in force. The result was
     * a Balance tab where changing the buff slot count or the auto-generation multipliers
     * appeared to do nothing at all until the player left the world and came back, and
     * where every one of those settings carried a tooltip claiming a server was
     * overriding it.
     *
     * @param locallyHosted whether the connection being opened is to this client's own
     *                      integrated server (a singleplayer world, or one opened to LAN)
     */
    public static void rememberLocal(boolean locallyHosted) {
        if (locallyHosted) return;
        FlorafareConfig.beginServerOverride();
        excludedItems = new HashSet<>(FoodBuffManager.getExcludedItems());
        captured = true;
    }

    /**
     * Puts the snapshotted settings back. No-op if no snapshot was ever taken.
     *
     * @return true if anything actually differed from the snapshot — i.e. the server
     *         really had overridden something. False for a plain singleplayer exit,
     *         where the integrated server synced back these very values.
     */
    public static boolean restoreLocal() {
        if (!captured) return false;
        captured = false;

        boolean exclusionsChanged = !FoodBuffManager.getExcludedItems().equals(excludedItems);
        boolean changed = FlorafareConfig.endServerOverride() || exclusionsChanged;

        // Restored alongside the config fields because, unlike the datapack config
        // maps, nothing else ever rebuilds this set on the client: it is populated
        // once at mod init from ignoredFoodItems and replaced wholesale by each
        // server's sync packet.
        FoodBuffManager.setExcludedItems(excludedItems);

        PlayerFoodComponent.setMaxBuffSlots(FlorafareConfig.maxBuffSlots);

        // The server's sync writes these too (they drive auto-generated buffs), and on a
        // dedicated server they are the only thing that ever sets them client-side — so
        // they need putting back as well, or a server's balance would keep applying to
        // the player's own worlds. Seeded from the restored config the same way
        // FoodReloadListener seeds them; the cache holds results built from the old ones.
        FoodBuffManager.AUTO_GEN_DURATION_MULT = FlorafareConfig.autoGenDurationMultiplier;
        FoodBuffManager.AUTO_GEN_HEALTH_MULT   = FlorafareConfig.autoGenHealthMultiplier;
        FoodBuffManager.invalidateResolutionCache();

        if (changed) {
            Florafare.LOGGER.info(
                    "Restored local Florafare settings after leaving a server that overrode them "
                            + "(maxBuffSlots={}, enableSynergies={}, allowEatingWhenFull={}).",
                    FlorafareConfig.maxBuffSlots, FlorafareConfig.enableSynergies,
                    FlorafareConfig.allowEatingWhenFull);
        }
        return changed;
    }
}
