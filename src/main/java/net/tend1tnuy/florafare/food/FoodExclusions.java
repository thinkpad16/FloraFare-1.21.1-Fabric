package net.tend1tnuy.florafare.food;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The single answer to "does Florafare touch this item at all".
 *
 * <p>An excluded item is invisible to every hook the mod has: no buff is ever applied to
 * it, its configured nutrition and saturation are never swapped in, its own vanilla
 * status effects are left to fire, {@code always_edible} never applies, the tooltip is
 * not decorated, the journal has no row for it, EMI does not list it, AppleSkin sees
 * vanilla's numbers, and it can never feed a synergy. That is true <em>even when a
 * datapack defines a buff for it</em> — exclusion is checked before resolution, so an
 * entry naming an excluded item simply never fires.
 *
 * <h2>What a rule can name</h2>
 * Three granularities, which is what makes this usable for the case it exists for —
 * "this other food mod handles its own drinks, keep out of the whole thing":
 *
 * <table border="1">
 *   <caption>Rule syntax</caption>
 *   <tr><th>Written as</th><th>Means</th></tr>
 *   <tr><td>{@code loot_n_explore:espresso}</td><td>that one item</td></tr>
 *   <tr><td>{@code loot_n_explore:*}<br>{@code namespace:loot_n_explore}<br>{@code @loot_n_explore}<br>{@code loot_n_explore}</td>
 *       <td>every food that mod adds</td></tr>
 *   <tr><td>{@code #c:drinks}</td><td>every item in that item tag</td></tr>
 * </table>
 *
 * <p>All four spellings of the mod-wide form are accepted because all four are what
 * someone reaches for first; they normalize to {@code loot_n_explore:*}, which is the
 * form written back to the config file and shown by {@code /florafare ignore list}.
 *
 * <h2>Two lists, not one</h2>
 * Rules point in both directions, and the {@link Effect#MANAGE} list wins:
 *
 * <ul>
 *   <li>{@link Effect#EXCLUDE} — keep Florafare away from this.</li>
 *   <li>{@link Effect#MANAGE} — manage this <em>anyway</em>, whatever excluded it.</li>
 * </ul>
 *
 * <p>The second list exists because of a hard limit in the first's best delivery
 * mechanism. Florafare's defaults ship in the {@code #florafare:ignored} item tag, which
 * is the right place for them — tags compose, several datapacks can each add to one, and
 * a mod update can extend the defaults without fighting anyone's edits. But a vanilla tag
 * can only be added to, never subtracted from: undoing one entry would otherwise mean
 * overriding the whole tag with {@code "replace": true} and retyping the rest. So "manage
 * this after all" is expressed as a rule of its own instead, which a player can write in
 * one line — or get by typing {@code /florafare ignore remove vinery:mead}.
 *
 * <h2>Where rules come from</h2>
 * Sources are kept apart rather than poured into one set, because they have different
 * lifetimes and one must never erase another:
 *
 * <ul>
 *   <li>{@link Source#CONFIG} — {@code ignoredFoodItems}, {@code ignoredFoodMods} and
 *       {@code managedFoodItems} in {@code florafare.json}, and {@code /florafare
 *       ignore}, which edits those same three lists. Re-read whenever the config is
 *       loaded.</li>
 *   <li>{@link Source#API} — another mod calling {@code FlorafareAPI.excludeFood} from
 *       its initializer. Set once and never re-read, so re-reading the config file must
 *       not drop it — which is exactly what a single shared set used to do.</li>
 *   <li>The built-in {@code #florafare:ignored} item tag, always in force, which is the
 *       half of this a datapack or modpack author can reach without touching a config
 *       file or writing Java.</li>
 *   <li>{@link Source#REMOTE} — what a server said. While it is in force it replaces
 *       the other three outright rather than merging with them: the server decides what
 *       Florafare manages there, and a client that merged its own rules in would predict
 *       eating differently from the server that resolves it.</li>
 * </ul>
 *
 * <p>Everything is resolved into one immutable {@link State} on every change, so the hot
 * path — {@link #isExcluded(Item)}, asked once per food tooltip frame and twice a tick
 * per player — is a volatile read plus a couple of set lookups.
 */
public final class FoodExclusions {

    private FoodExclusions() {}

    /** Which way a rule points. See the class javadoc. */
    public enum Effect {
        /** Keep Florafare away from what this names. */
        EXCLUDE,
        /** Manage what this names regardless of any {@link #EXCLUDE} rule. */
        MANAGE
    }

    /** Where a rule came from. See the class javadoc. */
    public enum Source {
        /** {@code florafare.json} and {@code /florafare ignore}. */
        CONFIG,
        /** Another mod's {@code FlorafareAPI.excludeFood} call. */
        API,
        /** Sent by the server this client is connected to; overrides the other two. */
        REMOTE
    }

    /**
     * How one rule was written. The distinction survives into the config file, the sync
     * packet and {@code /florafare ignore list}, so an admin sees back what they typed
     * rather than an expansion of it.
     */
    public enum Kind {
        /** One item, by id. */
        ITEM,
        /** Every item in one namespace. */
        MOD,
        /** Every item in one item tag. */
        TAG
    }

    /**
     * A parsed rule. {@link #value} is the bare id — {@code "minecraft:bread"},
     * {@code "loot_n_explore"}, {@code "c:drinks"} — and {@link #canonical()} is how it
     * is written back out.
     */
    public record Entry(Kind kind, String value) {
        /** The form written to the config file, the sync packet and command output. */
        public String canonical() {
            return switch (kind) {
                case ITEM -> value;
                case MOD  -> value + ":*";
                case TAG  -> "#" + value;
            };
        }
    }

    /**
     * The datapack half of the exclusion list, always in force.
     *
     * <p>A datapack or modpack author can put items in it without touching a config file
     * or writing Java, and it composes the way tags do — several datapacks can each add
     * to it. Florafare ships it populated; see {@code data/florafare/tags/item/ignored.json}.
     * To take something back <em>out</em> of it, use an {@link Effect#MANAGE} rule rather
     * than overriding the whole tag.
     */
    public static final TagKey<Item> IGNORED_TAG =
            TagKey.of(RegistryKeys.ITEM, Identifier.of(Florafare.MOD_ID, "ignored"));

    // -------------------------------------------------------------------------
    // RESOLVED STATE
    // -------------------------------------------------------------------------

    /** One direction's rules, flattened for lookup. Immutable. */
    private record Rules(Set<String> items, Set<String> mods,
                         List<String> tagIds, List<TagKey<Item>> tagKeys) {

        static final Rules NONE = new Rules(Set.of(), Set.of(), List.of(), List.of());

        boolean isEmpty() {
            return items.isEmpty() && mods.isEmpty() && tagIds.isEmpty();
        }

        /** The rule that matches this item, or null. */
        Entry match(Item item, Identifier id) {
            if (isEmpty()) return null;
            if (items.contains(id.toString())) return new Entry(Kind.ITEM, id.toString());
            if (mods.contains(id.getNamespace())) return new Entry(Kind.MOD, id.getNamespace());
            if (tagKeys.isEmpty()) return null;

            // Registries.ITEM.getEntry rather than Item#getRegistryEntry, which Mojang
            // deprecated in 1.21: same reference, without the warning.
            RegistryEntry<Item> entry = Registries.ITEM.getEntry(item);
            for (int i = 0; i < tagKeys.size(); i++) {
                if (entry.isIn(tagKeys.get(i))) return new Entry(Kind.TAG, tagIds.get(i));
            }
            return null;
        }

        boolean matches(Item item, Identifier id) {
            return match(item, id) != null;
        }
    }

    /** Both directions, replaced wholesale on every change. */
    private record State(Rules excluded, Rules managed) {}

    private static final Object LOCK = new Object();

    /** One direction's rules, per kind and per source. Guarded by {@link #LOCK}. */
    private static final class Store {
        final Map<Source, Set<String>> items = new EnumMap<>(Source.class);
        final Map<Source, Set<String>> mods  = new EnumMap<>(Source.class);
        final Map<Source, Set<String>> tags  = new EnumMap<>(Source.class);

        Set<String> setFor(Kind kind, Source source) {
            Map<Source, Set<String>> map = switch (kind) {
                case ITEM -> items;
                case MOD  -> mods;
                case TAG  -> tags;
            };
            return map.computeIfAbsent(source, ignored -> new LinkedHashSet<>());
        }

        void clear(Source source) {
            items.remove(source);
            mods.remove(source);
            tags.remove(source);
        }
    }

    private static final Store EXCLUDE_RULES = new Store();
    private static final Store MANAGE_RULES  = new Store();

    /** Guarded by LOCK; see {@link Source#REMOTE}. */
    private static boolean remoteInForce = false;

    /**
     * Declared after the stores and the flag it is built from, so the field initializer
     * below sees them populated. The built-in tag is not held in any store: it is
     * unconditional, and keeping it out means clearing a source can never take it away.
     */
    private static volatile State state = rebuildState();

    // -------------------------------------------------------------------------
    // THE HOT PATH
    // -------------------------------------------------------------------------

    /**
     * Whether Florafare has been told to leave this item alone.
     *
     * <p>Not memoized in {@code FoodBuffManager}'s per-item resolution cache, and
     * deliberately so: tag membership is reloaded and re-synced by vanilla on its own
     * schedule, with no hook Florafare could hang a cache invalidation on. The item and
     * namespace halves are set lookups, and the tag half is a flag read on the item's
     * registry entry — cheaper than the bookkeeping to memoize it correctly would be.
     */
    public static boolean isExcluded(Item item) {
        State current = state;
        // Nothing excludes anything: the common case, and the one worth answering
        // before touching the registry at all.
        if (current.excluded().isEmpty()) return false;

        Identifier id = Registries.ITEM.getId(item);
        // The allow-list wins outright, so it is asked first.
        if (current.managed().matches(item, id)) return false;
        return current.excluded().matches(item, id);
    }

    /** @see #isExcluded(Item) */
    public static boolean isExcluded(ItemStack stack) {
        return !stack.isEmpty() && isExcluded(stack.getItem());
    }

    /**
     * Which rule took this item away from Florafare, or null if none did — an item
     * claimed back by an {@link Effect#MANAGE} rule included.
     *
     * <p>Drives {@code /florafare explain}, whose whole job is answering "why did my
     * entry do nothing" — "an exclusion you forgot about" being one of the harder
     * answers to arrive at from the outside.
     */
    public static Entry matchedRule(Item item) {
        State current = state;
        if (current.excluded().isEmpty()) return null;

        Identifier id = Registries.ITEM.getId(item);
        if (current.managed().matches(item, id)) return null;
        return current.excluded().match(item, id);
    }

    /**
     * The {@link Effect#MANAGE} rule that claimed this item back from an exclusion, or
     * null — either because nothing claimed it or because nothing excluded it in the
     * first place. The other half of what {@code /florafare explain} prints.
     */
    public static Entry managedRule(Item item) {
        State current = state;
        if (current.managed().isEmpty() || current.excluded().isEmpty()) return null;

        Identifier id = Registries.ITEM.getId(item);
        Entry claimed = current.managed().match(item, id);
        // Only worth reporting when it actually overrode something.
        return claimed != null && current.excluded().matches(item, id) ? claimed : null;
    }

    // -------------------------------------------------------------------------
    // PARSING
    // -------------------------------------------------------------------------

    /**
     * Reads one written rule in any of the accepted spellings; null when it is not a
     * usable id at all. Never throws — these strings come from a hand-edited config file
     * and from chat, and one typo must cost its own line and nothing else.
     */
    public static Entry parse(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;

        if (text.startsWith("#")) {
            // The emptiness check is not redundant: Identifier.tryParse("") answers
            // "minecraft:" rather than null — an empty path is legal — so a lone "#"
            // would otherwise parse as a tag that matches whatever ends up in it.
            String tag = text.substring(1).trim();
            if (tag.isEmpty()) return null;
            Identifier tagId = Identifier.tryParse(tag);
            return tagId == null ? null : new Entry(Kind.TAG, tagId.toString());
        }
        if (text.startsWith("@")) {
            return modEntry(text.substring(1));
        }
        // "namespace:loot_n_explore" — the same prefix a datapack food_buffs entry uses
        // to name a whole mod, accepted here so one syntax means one thing throughout.
        if (text.regionMatches(true, 0, "namespace:", 0, "namespace:".length())) {
            return modEntry(text.substring("namespace:".length()));
        }
        if (text.endsWith(":*")) {
            return modEntry(text.substring(0, text.length() - 2));
        }
        if (text.indexOf(':') < 0) {
            // A bare word is a mod id. It cannot be an item id — those always carry a
            // namespace — and reading it as "minecraft:<word>" would silently exclude
            // a vanilla item nobody named.
            return modEntry(text);
        }

        // Same trap as the tag branch: "modid:" parses, and would name an item id that
        // cannot exist while looking like a rule that does something.
        if (text.endsWith(":")) return null;
        Identifier itemId = Identifier.tryParse(text);
        return itemId == null ? null : new Entry(Kind.ITEM, itemId.toString());
    }

    private static Entry modEntry(String namespace) {
        String value = namespace.trim().toLowerCase(Locale.ROOT);
        return isValidNamespace(value) ? new Entry(Kind.MOD, value) : null;
    }

    /**
     * Vanilla's namespace rule, spelled out here rather than borrowed from
     * {@code Identifier}, so this class parses without a game registry loaded and the
     * unit tests can drive it directly.
     */
    static boolean isValidNamespace(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // EDITING
    // -------------------------------------------------------------------------

    /**
     * Adds one rule to a source.
     *
     * @return the parsed rule if it was added, null if the string was unusable or the
     *         rule was already there
     */
    public static Entry add(Effect effect, Source source, String raw) {
        Entry entry = parse(raw);
        if (entry == null) return null;
        synchronized (LOCK) {
            if (!storeFor(effect).setFor(entry.kind(), source).add(entry.value())) return null;
            publish();
        }
        return entry;
    }

    /** Removes one rule from a source. @return the rule removed, or null. */
    public static Entry remove(Effect effect, Source source, String raw) {
        Entry entry = parse(raw);
        if (entry == null) return null;
        synchronized (LOCK) {
            if (!storeFor(effect).setFor(entry.kind(), source).remove(entry.value())) return null;
            publish();
        }
        return entry;
    }

    /** {@link #add(Effect, Source, String)} with {@link Effect#EXCLUDE}. */
    public static Entry add(Source source, String raw) {
        return add(Effect.EXCLUDE, source, raw);
    }

    /** {@link #remove(Effect, Source, String)} with {@link Effect#EXCLUDE}. */
    public static Entry remove(Source source, String raw) {
        return remove(Effect.EXCLUDE, source, raw);
    }

    /**
     * Replaces everything {@link Source#CONFIG} contributes, from the three config lists.
     *
     * <p>A replace and not a merge: a rule deleted from {@code florafare.json} has to
     * actually stop applying when the file is re-read, and the {@code API} rules other
     * mods registered live in their own source and survive it untouched.
     *
     * @param itemEntries    {@code ignoredFoodItems}
     * @param modEntries     {@code ignoredFoodMods}
     * @param managedEntries {@code managedFoodItems}
     */
    public static void loadFromConfig(Collection<String> itemEntries,
                                      Collection<String> modEntries,
                                      Collection<String> managedEntries) {
        synchronized (LOCK) {
            EXCLUDE_RULES.clear(Source.CONFIG);
            MANAGE_RULES.clear(Source.CONFIG);

            int rejected = 0;
            rejected += ingest(Effect.EXCLUDE, Source.CONFIG, itemEntries);
            // The mods list is a convenience spelling of the same thing, so a stray item
            // id in it still works rather than being thrown away on a technicality.
            rejected += ingest(Effect.EXCLUDE, Source.CONFIG, modEntries);
            rejected += ingest(Effect.MANAGE,  Source.CONFIG, managedEntries);
            publish();

            if (rejected > 0) {
                Florafare.LOGGER.warn(
                        "Florafare config: {} entries across ignoredFoodItems, ignoredFoodMods and "
                                + "managedFoodItems are not valid ids and were skipped. An entry is "
                                + "an item id (\"modid:item\"), a whole mod (\"modid:*\"), or an "
                                + "item tag (\"#namespace:tag\").", rejected);
            }
        }
    }

    /** @return how many entries could not be parsed */
    private static int ingest(Effect effect, Source source, Collection<String> raws) {
        if (raws == null) return 0;
        int rejected = 0;
        for (String raw : raws) {
            Entry entry = parse(raw);
            if (entry == null) {
                if (raw != null && !raw.isBlank()) rejected++;
                continue;
            }
            storeFor(effect).setFor(entry.kind(), source).add(entry.value());
        }
        return rejected;
    }

    /**
     * Takes the server's rules, which from this moment are the only ones in force on
     * this client. See {@link Source#REMOTE}.
     *
     * @return true if they differ from what was in force a moment ago
     */
    public static boolean applyRemote(Collection<String> excluded, Collection<String> managed) {
        synchronized (LOCK) {
            State before = state;
            EXCLUDE_RULES.clear(Source.REMOTE);
            MANAGE_RULES.clear(Source.REMOTE);
            ingest(Effect.EXCLUDE, Source.REMOTE, excluded);
            ingest(Effect.MANAGE,  Source.REMOTE, managed);
            remoteInForce = true;
            publish();
            return !sameRules(before, state);
        }
    }

    /**
     * Drops the server's rules and puts this client's own back. Called on every
     * disconnect from someone else's server.
     *
     * @return true if anything actually changed
     */
    public static boolean clearRemote() {
        synchronized (LOCK) {
            if (!remoteInForce) return false;
            State before = state;
            EXCLUDE_RULES.clear(Source.REMOTE);
            MANAGE_RULES.clear(Source.REMOTE);
            remoteInForce = false;
            publish();
            return !sameRules(before, state);
        }
    }

    /** Whether a server's rules are currently overriding this client's own. */
    public static boolean isRemoteInForce() {
        synchronized (LOCK) {
            return remoteInForce;
        }
    }

    // -------------------------------------------------------------------------
    // READING BACK
    // -------------------------------------------------------------------------

    /**
     * Every rule of one direction in force, in canonical spelling and sorted — what goes
     * on the wire, and what {@code /florafare ignore list} prints. For
     * {@link Effect#EXCLUDE} the built-in {@code #florafare:ignored} tag is included: it
     * is in force, and an admin wondering why an item is being skipped needs to see it.
     */
    public static List<String> canonicalEntries(Effect effect) {
        Rules rules = effect == Effect.EXCLUDE ? state.excluded() : state.managed();
        Set<String> out = new TreeSet<>(rules.items());
        for (String mod : rules.mods())  out.add(mod + ":*");
        for (String tag : rules.tagIds()) out.add("#" + tag);
        return List.copyOf(out);
    }

    /** {@link #canonicalEntries(Effect)} for {@link Effect#EXCLUDE}. */
    public static List<String> canonicalEntries() {
        return canonicalEntries(Effect.EXCLUDE);
    }

    /** Only the rules one source contributes, canonically spelled and sorted. */
    public static List<String> canonicalEntries(Effect effect, Source source) {
        synchronized (LOCK) {
            Store store = storeFor(effect);
            Set<String> out = new TreeSet<>(store.setFor(Kind.ITEM, source));
            for (String mod : store.setFor(Kind.MOD, source)) out.add(mod + ":*");
            for (String tag : store.setFor(Kind.TAG, source)) out.add("#" + tag);
            return List.copyOf(out);
        }
    }

    /** {@link #canonicalEntries(Effect, Source)} for {@link Effect#EXCLUDE}. */
    public static List<String> canonicalEntries(Source source) {
        return canonicalEntries(Effect.EXCLUDE, source);
    }

    /**
     * The bare item ids excluded.
     *
     * <p>Cannot express the mod- and tag-wide rules or the allow-list, so it is no longer
     * what the client sync carries — {@link #canonicalEntries(Effect)} is. Kept because
     * it has been public since 1.4.
     */
    public static Set<String> excludedItemIds() {
        return Set.copyOf(state.excluded().items());
    }

    /** The namespaces excluded, without the {@code :*} suffix. */
    public static Set<String> excludedNamespaces() {
        return Set.copyOf(state.excluded().mods());
    }

    // -------------------------------------------------------------------------
    // INTERNALS
    // -------------------------------------------------------------------------

    private static Store storeFor(Effect effect) {
        return effect == Effect.EXCLUDE ? EXCLUDE_RULES : MANAGE_RULES;
    }

    /** Called with LOCK held. */
    private static void publish() {
        state = rebuildState();
        // Everything downstream of an exclusion — the per-item config cache, the command
        // suggestion lists, the journal's row list — is keyed off this counter.
        FoodBuffManager.invalidateResolutionCache();
    }

    private static State rebuildState() {
        return new State(resolve(EXCLUDE_RULES, true), resolve(MANAGE_RULES, false));
    }

    /**
     * @param withBuiltInTag whether {@link #IGNORED_TAG} belongs to this direction. It is
     *                       only ever an exclusion, and it is added here rather than held
     *                       in a store so that clearing a source cannot take it away.
     */
    private static Rules resolve(Store store, boolean withBuiltInTag) {
        Set<String> items = new LinkedHashSet<>();
        Set<String> mods  = new LinkedHashSet<>();
        Set<String> tags  = new LinkedHashSet<>();

        if (withBuiltInTag) tags.add(IGNORED_TAG.id().toString());

        for (Source source : activeSources()) {
            Set<String> sourceItems = store.items.get(source);
            if (sourceItems != null) items.addAll(sourceItems);
            Set<String> sourceMods = store.mods.get(source);
            if (sourceMods != null) mods.addAll(sourceMods);
            Set<String> sourceTags = store.tags.get(source);
            if (sourceTags != null) tags.addAll(sourceTags);
        }

        if (items.isEmpty() && mods.isEmpty() && tags.isEmpty()) return Rules.NONE;

        List<String>       tagIds  = new ArrayList<>(tags.size());
        List<TagKey<Item>> tagKeys = new ArrayList<>(tags.size());
        for (String tag : tags) {
            Identifier id = Identifier.tryParse(tag);
            if (id == null) continue;
            tagIds.add(id.toString());
            tagKeys.add(TagKey.of(RegistryKeys.ITEM, id));
        }

        return new Rules(Set.copyOf(items), Set.copyOf(mods),
                List.copyOf(tagIds), List.copyOf(tagKeys));
    }

    /** Which sources feed the resolved state right now; see {@link Source#REMOTE}. */
    private static Source[] activeSources() {
        return remoteInForce
                ? new Source[] { Source.REMOTE }
                : new Source[] { Source.CONFIG, Source.API };
    }

    private static boolean sameRules(State a, State b) {
        return sameRules(a.excluded(), b.excluded()) && sameRules(a.managed(), b.managed());
    }

    private static boolean sameRules(Rules a, Rules b) {
        return a.items().equals(b.items()) && a.mods().equals(b.mods())
                && a.tagIds().equals(b.tagIds());
    }

    /**
     * Drops every source at once, back to nothing but the built-in tag.
     *
     * <p>Package-private, and no production path calls it: nothing in the mod ever wants
     * to reset all of them at the same time. It exists so the tests, which share one JVM
     * and therefore one copy of this static state, can start from a known slate instead
     * of unpicking each other's rules.
     */
    static void resetAll() {
        synchronized (LOCK) {
            for (Source source : Source.values()) {
                EXCLUDE_RULES.clear(source);
                MANAGE_RULES.clear(source);
            }
            remoteInForce = false;
            publish();
        }
    }
}
