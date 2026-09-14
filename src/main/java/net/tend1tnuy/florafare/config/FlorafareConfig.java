package net.tend1tnuy.florafare.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.tend1tnuy.florafare.Florafare;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class FlorafareConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Supported ranges for the hand-editable numeric settings. The single source of
    // truth for three consumers that must agree: load-time clamping below, the
    // ModMenu screen's min/max, and the client's HudConfig.
    public static final int   MIN_BUFF_SLOTS = 1;
    public static final int   MAX_BUFF_SLOTS = 9;
    public static final int   MIN_TOAST_MS   = 500;
    public static final int   MAX_TOAST_MS   = 20000;
    public static final float HUD_SCALE_MIN  = 0.5f;
    public static final float HUD_SCALE_MAX  = 1.5f;

    /**
     * Ceilings for the auto-generation multipliers. Both are multiplied by a food's
     * nutrition, so an unbounded value does not produce a strong buff — it produces an
     * overflowed duration or a max-health modifier measured in hundreds of hearts.
     */
    public static final int    MAX_AUTO_GEN_DURATION_MULT = 72_000;
    public static final double MAX_AUTO_GEN_HEALTH_MULT   = 10.0;

    /**
     * Resolved on demand rather than in a static initializer: touching FabricLoader
     * while the class loads would make this config unusable anywhere the loader isn't
     * running, which is exactly where the unit tests drive these fields from.
     */
    private static File configFile() {
        return new File(FabricLoader.getInstance().getConfigDir().toFile(), "florafare.json");
    }

    // Logging levels: NONE (disabled), REDUCED (less spam, only heals), ALL (everything)
    public enum LogLevel { NONE, REDUCED, ALL }

    /**
     * Off by default. Both other levels write an INFO line per consumption on the
     * logical server — and {@code REDUCED} is not the escape hatch it sounds like,
     * since it fires for every food with a positive {@code health_bonus}, which is
     * roughly half of the bundled defaults. On a busy server that is thousands of
     * lines an hour in {@code latest.log} for something nobody asked for, so an admin
     * who wants the audit trail opts into it instead of opting out.
     */
    public static LogLevel consumptionLogging = LogLevel.NONE;

    // --- Main settings ---
    public static boolean enableSynergies = true;

    // --- Server-authoritative gameplay settings (synced to clients on join/reload) ---
    public static int maxBuffSlots = 3;
    public static int autoGenDurationMultiplier = 1200;
    public static double autoGenHealthMultiplier = 0.5;
    public static boolean enableAlwaysEdibleOverride = true;

    /**
     * On by default: every food is edible on a full hunger bar, not just the ones
     * flagged {@code always_edible}. Florafare's own logic (buffs, health bonus,
     * synergies, journal discovery) runs exactly as it does on a hungry player; only
     * the surplus nutrition/saturation is clamped away by the vanilla hunger manager.
     * Items on {@link #ignoredFoodItems} keep their vanilla gating, so mods that asked
     * Florafare to keep its hands off an item stay in control of it. Set to false to
     * restore vanilla's "only when hungry" restriction.
     */
    public static boolean allowEatingWhenFull = true;

    public static boolean respectVanillaFoodEffects = false;
    public static boolean enableForgottenMead = true;
    public static boolean grantJournalOnFirstJoin = true;

    // --- Server-only settings (not synced; read at command registration time) ---
    public static int commandPermissionLevel = 2;

    // -------------------------------------------------------------------------
    // SERVER OVERRIDE
    //
    // Joining a server overwrites the eight fields above in place, so gameplay and the
    // client's own prediction both run by the server's rules. The fields are therefore
    // NOT this user's settings while a connection is up — and two things must not treat
    // them as such: the config screen, which would show a stranger's balance as though
    // the player had chosen it, and save(), which would write it into their
    // florafare.json permanently. Moving the HUD on a server with maxBuffSlots=1 was
    // enough to adopt that server's rules for every future singleplayer world.
    //
    // While an override is in force this holds the player's own values, and the
    // localXxx() accessors below read and write those instead of the live fields.
    // -------------------------------------------------------------------------

    /** This user's own values for the server-authoritative fields; null when not connected. */
    private static LocalSettings local = null;

    private static final class LocalSettings {
        int     maxBuffSlots;
        int     autoGenDurationMultiplier;
        double  autoGenHealthMultiplier;
        boolean enableSynergies;
        boolean enableAlwaysEdibleOverride;
        boolean allowEatingWhenFull;
        boolean respectVanillaFoodEffects;
        boolean enableForgottenMead;
    }

    /**
     * Snapshots this user's own settings and marks the server-authoritative fields as
     * no longer representing them. Called immediately before a server is given the
     * chance to overwrite them.
     */
    public static void beginServerOverride() {
        // A second connect without an intervening disconnect must not re-snapshot: the
        // live fields would be the previous server's by then, and capturing those would
        // make its balance the player's "own" settings permanently.
        if (local != null) return;

        LocalSettings snapshot = new LocalSettings();
        snapshot.maxBuffSlots               = maxBuffSlots;
        snapshot.autoGenDurationMultiplier  = autoGenDurationMultiplier;
        snapshot.autoGenHealthMultiplier    = autoGenHealthMultiplier;
        snapshot.enableSynergies            = enableSynergies;
        snapshot.enableAlwaysEdibleOverride = enableAlwaysEdibleOverride;
        snapshot.allowEatingWhenFull        = allowEatingWhenFull;
        snapshot.respectVanillaFoodEffects  = respectVanillaFoodEffects;
        snapshot.enableForgottenMead        = enableForgottenMead;
        local = snapshot;
    }

    /**
     * Puts this user's own settings back into the live fields and ends the override.
     *
     * @return true if the server had actually changed any of them
     */
    public static boolean endServerOverride() {
        if (local == null) return false;
        LocalSettings snapshot = local;
        local = null;

        boolean changed = maxBuffSlots               != snapshot.maxBuffSlots
                || autoGenDurationMultiplier         != snapshot.autoGenDurationMultiplier
                || autoGenHealthMultiplier           != snapshot.autoGenHealthMultiplier
                || enableSynergies                   != snapshot.enableSynergies
                || enableAlwaysEdibleOverride        != snapshot.enableAlwaysEdibleOverride
                || allowEatingWhenFull               != snapshot.allowEatingWhenFull
                || respectVanillaFoodEffects         != snapshot.respectVanillaFoodEffects
                || enableForgottenMead               != snapshot.enableForgottenMead;

        maxBuffSlots               = snapshot.maxBuffSlots;
        autoGenDurationMultiplier  = snapshot.autoGenDurationMultiplier;
        autoGenHealthMultiplier    = snapshot.autoGenHealthMultiplier;
        enableSynergies            = snapshot.enableSynergies;
        enableAlwaysEdibleOverride = snapshot.enableAlwaysEdibleOverride;
        allowEatingWhenFull        = snapshot.allowEatingWhenFull;
        respectVanillaFoodEffects  = snapshot.respectVanillaFoodEffects;
        enableForgottenMead        = snapshot.enableForgottenMead;
        return changed;
    }

    /** Whether a server is currently overriding the gameplay settings. */
    public static boolean isServerOverridden() { return local != null; }

    // This user's own values: what the config screen edits and what save() writes.
    public static int     localMaxBuffSlots()               { return local != null ? local.maxBuffSlots               : maxBuffSlots; }
    public static int     localAutoGenDurationMultiplier()  { return local != null ? local.autoGenDurationMultiplier  : autoGenDurationMultiplier; }
    public static double  localAutoGenHealthMultiplier()    { return local != null ? local.autoGenHealthMultiplier    : autoGenHealthMultiplier; }
    public static boolean localEnableSynergies()            { return local != null ? local.enableSynergies            : enableSynergies; }
    public static boolean localEnableAlwaysEdibleOverride() { return local != null ? local.enableAlwaysEdibleOverride : enableAlwaysEdibleOverride; }
    public static boolean localAllowEatingWhenFull()        { return local != null ? local.allowEatingWhenFull        : allowEatingWhenFull; }
    public static boolean localRespectVanillaFoodEffects()  { return local != null ? local.respectVanillaFoodEffects  : respectVanillaFoodEffects; }
    public static boolean localEnableForgottenMead()        { return local != null ? local.enableForgottenMead        : enableForgottenMead; }

    public static void setLocalMaxBuffSlots(int v)               { if (local != null) local.maxBuffSlots               = v; else maxBuffSlots               = v; }
    public static void setLocalAutoGenDurationMultiplier(int v)  { if (local != null) local.autoGenDurationMultiplier  = v; else autoGenDurationMultiplier  = v; }
    public static void setLocalAutoGenHealthMultiplier(double v) { if (local != null) local.autoGenHealthMultiplier    = v; else autoGenHealthMultiplier    = v; }
    public static void setLocalEnableSynergies(boolean v)        { if (local != null) local.enableSynergies            = v; else enableSynergies            = v; }
    public static void setLocalEnableAlwaysEdibleOverride(boolean v) { if (local != null) local.enableAlwaysEdibleOverride = v; else enableAlwaysEdibleOverride = v; }
    public static void setLocalAllowEatingWhenFull(boolean v)    { if (local != null) local.allowEatingWhenFull        = v; else allowEatingWhenFull        = v; }
    public static void setLocalRespectVanillaFoodEffects(boolean v) { if (local != null) local.respectVanillaFoodEffects = v; else respectVanillaFoodEffects  = v; }
    public static void setLocalEnableForgottenMead(boolean v)    { if (local != null) local.enableForgottenMead        = v; else enableForgottenMead        = v; }

    /**
     * Item ids Florafare should never intercept, for interop with other food/hunger
     * mods that want to handle these items themselves (e.g. "modid:custom_stew").
     * Applied once at startup via FoodBuffManager.excludeItem; the resulting
     * exclusion set is what actually gets synced to clients, not this raw list.
     */
    public static java.util.List<String> ignoredFoodItems = new java.util.ArrayList<>();

    // --- Client-local cosmetic settings ---
    public static boolean enableDiscoveryToasts = true;
    public static int toastDisplayTimeMs = 5000;
    /**
     * Whether the journal lists foods whose buff was auto-generated from vanilla stats,
     * alongside the ones a datapack names explicitly. On by default — with it off the
     * journal in a modded pack lists almost nothing the player actually eats.
     */
    public static boolean journalShowsAutoGenerated = true;

    /**
     * Whether a managed food's item tooltip carries its Florafare buff, gated on the
     * player having eaten it at least once.
     */
    public static boolean showBuffTooltips = true;

    /**
     * Whether the tooltip hides the item's own vanilla food effects. Only the effects
     * Florafare actually supersedes are removed, and only while
     * {@link #respectVanillaFoodEffects} is off — lore, enchantments and other mods'
     * tooltip lines are never touched.
     */
    public static boolean stripFoodTooltips = true;

    /** Master switch for the on-screen buff overlay; the journal and buffs still work when off. */
    public static boolean enableHud = true;

    // Client HUD settings (stored as enum names; parsed by the client HudConfig)
    public static String hudLayout = "COMPACT";
    public static String hudIconSize = "LARGE";
    public static String hudPosition = "BOTTOM_LEFT";
    public static float hudScale = 0.8f;

    /** Free pixel offset from the HUD anchor corner; see the in-game placement screen. */
    public static int hudOffsetX = 0;
    public static int hudOffsetY = 0;

    public static void load() {
        File file = configFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                if (data != null) {
                    enableSynergies = data.enableSynergies;
                    if (data.consumptionLogging != null) {
                        consumptionLogging = data.consumptionLogging;
                    }
                    maxBuffSlots = data.maxBuffSlots;
                    autoGenDurationMultiplier = data.autoGenDurationMultiplier;
                    autoGenHealthMultiplier = data.autoGenHealthMultiplier;
                    enableAlwaysEdibleOverride = data.enableAlwaysEdibleOverride;
                    allowEatingWhenFull = data.allowEatingWhenFull;
                    respectVanillaFoodEffects = data.respectVanillaFoodEffects;
                    enableForgottenMead = data.enableForgottenMead;
                    grantJournalOnFirstJoin = data.grantJournalOnFirstJoin;
                    commandPermissionLevel = data.commandPermissionLevel;
                    if (data.ignoredFoodItems != null) ignoredFoodItems = data.ignoredFoodItems;
                    enableHud = data.enableHud;
                    enableDiscoveryToasts = data.enableDiscoveryToasts;
                    toastDisplayTimeMs = data.toastDisplayTimeMs;
                    journalShowsAutoGenerated = data.journalShowsAutoGenerated;
                    showBuffTooltips = data.showBuffTooltips;
                    stripFoodTooltips = data.stripFoodTooltips;
                    if (data.hudLayout != null) hudLayout = data.hudLayout;
                    if (data.hudIconSize != null) hudIconSize = data.hudIconSize;
                    if (data.hudPosition != null) hudPosition = data.hudPosition;
                    hudScale = data.hudScale;
                    hudOffsetX = data.hudOffsetX;
                    hudOffsetY = data.hudOffsetY;
                }
            } catch (Exception e) {
                // Do NOT fall through to save() here. Every field still holds its
                // default at this point, so writing the file would replace the user's
                // (merely unparseable) config with a pristine one and destroy whatever
                // they had — a single stray comma used to cost them every setting.
                // Keep the defaults in memory for this session, preserve the original
                // as a .bak they can fix by hand, and leave the file alone.
                Florafare.LOGGER.error("Failed to load config", e);
                backupBrokenConfig(file);
                return;
            }
        }

        clampToValidRanges();
        save();
    }

    /**
     * Copies an unparseable config file aside as {@code florafare.json.bak} so the
     * user can recover their settings by hand. Any previous backup is replaced —
     * the newest broken file is the one worth keeping.
     */
    private static void backupBrokenConfig(File file) {
        File backup = new File(file.getParentFile(), file.getName() + ".bak");
        try {
            java.nio.file.Files.copy(file.toPath(), backup.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Florafare.LOGGER.error(
                    "Florafare config could not be parsed. Running with defaults for this "
                            + "session; your original file was kept as {} and has NOT been "
                            + "overwritten. Fix the JSON there and restart to restore it.",
                    backup.getAbsolutePath());
        } catch (Exception copyFailure) {
            Florafare.LOGGER.error(
                    "Florafare config could not be parsed, and the backup copy failed too. "
                            + "Running with defaults for this session; {} was left untouched.",
                    file.getAbsolutePath(), copyFailure);
        }
    }

    /**
     * Forces every numeric setting back into a range the mod can actually run with.
     * These fields are hand-editable, and out-of-range values fail in ways that are
     * hard to trace back to the config — {@code maxBuffSlots: 0} silently stops every
     * buff from ever applying, a negative {@code toastDisplayTimeMs} makes toasts
     * vanish on the frame they appear, and {@code hudScale: 0} collapses the HUD to
     * nothing. The bounds mirror the ones the ModMenu screen already enforces, so a
     * hand-edited file can't reach a state the in-game UI would refuse to produce.
     */
    private static void clampToValidRanges() {
        maxBuffSlots              = clamp(maxBuffSlots, MIN_BUFF_SLOTS, MAX_BUFF_SLOTS, "maxBuffSlots");
        // Capped where a plausible typo stops being plausible. Bounding it here means the
        // auto-generated duration (nutrition x this) cannot reach the point of overflowing
        // an int, and the admin gets a log line naming the setting instead of foods that
        // quietly grant nothing.
        autoGenDurationMultiplier = clamp(autoGenDurationMultiplier, 0, MAX_AUTO_GEN_DURATION_MULT,
                "autoGenDurationMultiplier");
        commandPermissionLevel    = clamp(commandPermissionLevel, 0, 4, "commandPermissionLevel");
        toastDisplayTimeMs        = clamp(toastDisplayTimeMs, MIN_TOAST_MS, MAX_TOAST_MS, "toastDisplayTimeMs");

        if (Double.isNaN(autoGenHealthMultiplier)
                || autoGenHealthMultiplier < 0.0
                || autoGenHealthMultiplier > MAX_AUTO_GEN_HEALTH_MULT) {
            // The upper bound matters as much as the lower one: this is multiplied by a
            // food's nutrition and becomes a max-health modifier, so a stray extra digit
            // hands every player on the server hundreds of hearts.
            double corrected = Double.isNaN(autoGenHealthMultiplier)
                    ? 0.5
                    : Math.min(Math.max(autoGenHealthMultiplier, 0.0), MAX_AUTO_GEN_HEALTH_MULT);
            warnClamped("autoGenHealthMultiplier", autoGenHealthMultiplier, corrected);
            autoGenHealthMultiplier = corrected;
        }
        if (Float.isNaN(hudScale) || hudScale < HUD_SCALE_MIN || hudScale > HUD_SCALE_MAX) {
            float corrected = Float.isNaN(hudScale)
                    ? 0.8f : Math.min(Math.max(hudScale, HUD_SCALE_MIN), HUD_SCALE_MAX);
            warnClamped("hudScale", hudScale, corrected);
            hudScale = corrected;
        }
        if (ignoredFoodItems == null) {
            ignoredFoodItems = new java.util.ArrayList<>();
        } else {
            ignoredFoodItems.removeIf(id -> id == null || id.isBlank());
        }
    }

    private static int clamp(int value, int min, int max, String field) {
        int corrected = Math.min(Math.max(value, min), max);
        if (corrected != value) warnClamped(field, value, corrected);
        return corrected;
    }

    private static void warnClamped(String field, Object was, Object now) {
        Florafare.LOGGER.warn(
                "Florafare config: '{}' was {}, which is outside the supported range; "
                        + "using {} instead.", field, was, now);
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(configFile())) {
            ConfigData data = new ConfigData();
            // Through the local accessors, never the live fields: while a server is
            // connected the live ones hold ITS values, and writing those here is what
            // used to bake a server's balance into the player's own config file.
            data.enableSynergies = localEnableSynergies();
            data.consumptionLogging = consumptionLogging;
            data.maxBuffSlots = localMaxBuffSlots();
            data.autoGenDurationMultiplier = localAutoGenDurationMultiplier();
            data.autoGenHealthMultiplier = localAutoGenHealthMultiplier();
            data.enableAlwaysEdibleOverride = localEnableAlwaysEdibleOverride();
            data.allowEatingWhenFull = localAllowEatingWhenFull();
            data.respectVanillaFoodEffects = localRespectVanillaFoodEffects();
            data.enableForgottenMead = localEnableForgottenMead();
            data.grantJournalOnFirstJoin = grantJournalOnFirstJoin;
            data.commandPermissionLevel = commandPermissionLevel;
            data.ignoredFoodItems = ignoredFoodItems;
            data.enableHud = enableHud;
            data.enableDiscoveryToasts = enableDiscoveryToasts;
            data.toastDisplayTimeMs = toastDisplayTimeMs;
            data.journalShowsAutoGenerated = journalShowsAutoGenerated;
            data.showBuffTooltips = showBuffTooltips;
            data.stripFoodTooltips = stripFoodTooltips;
            data.hudLayout = hudLayout;
            data.hudIconSize = hudIconSize;
            data.hudPosition = hudPosition;
            data.hudScale = hudScale;
            data.hudOffsetX = hudOffsetX;
            data.hudOffsetY = hudOffsetY;
            GSON.toJson(data, writer);
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to save config", e);
        }
    }

    private static class ConfigData {
        public boolean enableSynergies = true;
        public LogLevel consumptionLogging = LogLevel.NONE;
        public int maxBuffSlots = 3;
        public int autoGenDurationMultiplier = 1200;
        public double autoGenHealthMultiplier = 0.5;
        public boolean enableAlwaysEdibleOverride = true;
        public boolean allowEatingWhenFull = true;
        public boolean respectVanillaFoodEffects = false;
        public boolean enableForgottenMead = true;
        public boolean grantJournalOnFirstJoin = true;
        public int commandPermissionLevel = 2;
        public java.util.List<String> ignoredFoodItems = new java.util.ArrayList<>();
        public boolean enableHud = true;
        public boolean enableDiscoveryToasts = true;
        public int toastDisplayTimeMs = 5000;
        public boolean journalShowsAutoGenerated = true;
        public boolean showBuffTooltips = true;
        public boolean stripFoodTooltips = true;
        public String hudLayout = "COMPACT";
        public String hudIconSize = "LARGE";
        public String hudPosition = "BOTTOM_LEFT";
        public float hudScale = 0.8f;
        public int hudOffsetX = 0;
        public int hudOffsetY = 0;
    }
}
