package net.tend1tnuy.florafare.compat.emi;

import net.fabricmc.loader.api.FabricLoader;
import net.tend1tnuy.florafare.Florafare;

import java.lang.reflect.Method;

/**
 * Asks EMI to rebuild its recipe list once Florafare's synergies have arrived.
 *
 * <p>EMI rebuilds off the vanilla recipe-sync packet, which the server sends early in
 * the login sequence; Florafare's synergies travel in their own packet sent from the
 * join event, after it. So on every join EMI finishes building before the synergies
 * exist client-side, and the Synergies category comes up empty. Nudging EMI once the
 * data lands is the fix.
 *
 * <p>EMI exposes no reload call in its published API — {@code EmiReloadManager} lives
 * in its internal {@code runtime} package, and this mod compiles against the API jar
 * only. Rather than take a compile dependency on an internal class (which would turn a
 * future EMI refactor into a hard crash for everyone who has EMI installed), the call
 * is made reflectively and simply gives up if it is not there. The cost of giving up is
 * small and self-correcting: the panels still render correctly, only EMI's search index
 * lags until its next reload.
 */
public final class EmiReloadBridge {

    private EmiReloadBridge() {}

    private static final String MANAGER_CLASS = "dev.emi.emi.runtime.EmiReloadManager";
    private static final String RELOAD_METHOD = "reloadRecipes";

    private static boolean resolved = false;
    private static Method reloadRecipes = null;

    /**
     * Triggers an EMI recipe reload, if EMI is installed and still offers the call.
     * Safe to invoke when EMI is absent — it never loads an EMI class in that case.
     * Must run on the client's main thread.
     */
    public static void requestReload() {
        if (!FabricLoader.getInstance().isModLoaded("emi")) return;

        Method method = resolve();
        if (method == null) return;

        try {
            method.invoke(null);
        } catch (Throwable t) {
            Florafare.LOGGER.warn(
                    "EMI is installed but refused a recipe reload; its Synergies category "
                            + "may stay empty until EMI reloads on its own.", t);
            reloadRecipes = null;
        }
    }

    private static Method resolve() {
        if (resolved) return reloadRecipes;
        resolved = true;
        try {
            reloadRecipes = Class.forName(MANAGER_CLASS).getMethod(RELOAD_METHOD);
        } catch (Throwable t) {
            Florafare.LOGGER.info(
                    "EMI is installed but {}#{} was not found — this EMI version keeps its "
                            + "reload entry point elsewhere. Florafare's EMI panels still "
                            + "work; only their search indexing waits for EMI's own reload.",
                    MANAGER_CLASS, RELOAD_METHOD);
        }
        return reloadRecipes;
    }
}
