package net.tend1tnuy.florafare.component;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;
import net.tend1tnuy.florafare.api.FlorafareEvents;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.AttributeOperations;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;
import net.tend1tnuy.florafare.network.BuffStateSyncPayload;
import net.tend1tnuy.florafare.network.FoodBuffSyncPayload;
import net.tend1tnuy.florafare.network.FoodUnlockedPayload;
import net.tend1tnuy.florafare.network.SynergyUnlockedPayload;

import java.util.*;

public class PlayerFoodComponent {

    /**
     * How many food buffs one player can carry at once.
     *
     * <p>Configurable via {@code FlorafareConfig.maxBuffSlots} and kept in sync with
     * clients via {@code FlorafareServerConfigSyncPayload}. Private behind an accessor
     * pair rather than a bare public field: it is written from five places (mod init, the
     * server config packet, the ModMenu screen, the disconnect restore, and the tests),
     * and a value of zero silently stops every buff from ever applying while a negative
     * one makes the HUD allocate a negative-length array. Clamping lives in one place now
     * instead of at each of those call sites — where, in practice, it lived at none.
     */
    private static volatile int maxBuffSlots = 3;

    /** The buff slot count in force. Never below 1. */
    public static int getMaxBuffSlots() {
        return maxBuffSlots;
    }

    /**
     * Sets the buff slot count, clamped to the range {@code FlorafareConfig} accepts.
     * Out-of-range input is corrected rather than rejected: it reaches here from a
     * hand-edited config file and from a remote server, and neither is worth
     * disconnecting over.
     */
    public static void setMaxBuffSlots(int slots) {
        maxBuffSlots = Math.min(Math.max(slots, FlorafareConfig.MIN_BUFF_SLOTS),
                                FlorafareConfig.MAX_BUFF_SLOTS);
    }

    private static final String NBT_BUFFS_KEY               = "FlorafareActiveBuffs";
    private static final String NBT_DISCOVERED_KEY          = "FlorafareDiscoveredFoods";
    private static final String NBT_JOURNAL_KEY             = "FlorafareReceivedJournal";
    private static final String NBT_DISCOVERED_SYNERGIES_KEY = "FlorafareDiscoveredSynergies";
    private static final String NBT_ACTIVE_SYNERGIES_KEY    = "FlorafareActiveSynergies";
    private static final String NBT_SPENT_SYNERGIES_KEY     = "FlorafareSpentSynergies";

    private final PlayerEntity player;
    private final List<ActiveFoodBuff> activeBuffs    = new ArrayList<>();
    private final Set<String>          discoveredFoods = new HashSet<>();
    private boolean hasReceivedJournal = false;

    private final Set<String>          discoveredSynergies = new HashSet<>();
    private final List<ActiveFoodBuff> activeSynergies     = new ArrayList<>();

    // #11 — Map keyed by synergy id for O(1) "is this synergy already active?" lookup.
    private final Map<String, ActiveFoodBuff> activeSynergyMap = new HashMap<>();

    /**
     * Synergies that ran their full window out while their requirements were still met,
     * and so must not start again until one of those requirements is eaten afresh.
     *
     * <p>A synergy with a {@code duration} cap shorter than its ingredients used to be
     * re-granted by anything that re-evaluated synergies: every bite of any food reset it
     * to its full cap, and once it did expire, the next bite or the next unrelated buff
     * running out started it over — a fresh {@code SYNERGY_ACTIVATED}, log line and set of
     * effects each time. With five-minute ingredients and a ten-second cap that was a
     * ten-second synergy which in practice lasted five minutes. An id leaves this set when
     * a requirement buff is gained or refreshed (which restarts it), or when its
     * requirements stop being met (so the next time they are, it starts normally).
     */
    private final Set<String> spentSynergies = new HashSet<>();

    /**
     * Listener notifications waiting to be delivered — see {@link #fireQueuedEvents()}.
     *
     * <p>Every state change used to fire its event on the spot, which put
     * {@code BUFF_REMOVED} and {@code SYNERGY_ENDED} inside index loops over the very lists
     * being changed. A listener doing the reasonable thing — calling
     * {@code FlorafareAPI.removeBuff} or {@code clearBuffs} in response — then shrank the
     * list under the loop, which skipped entries or threw. Events now wait here until the
     * change that produced them is complete, and are delivered from the queue, so a
     * listener always sees, and may freely change, a consistent component.
     */
    private final ArrayDeque<Runnable> pendingEvents = new ArrayDeque<>();

    /** Set while {@link #fireQueuedEvents()} is delivering, so re-entry only enqueues. */
    private boolean firingEvents = false;

    /**
     * Set whenever buffs or synergies change, and flushed once at the end of the
     * player's tick. Coalescing matters: a single bite can add a buff and activate a
     * synergy, and each used to send its own full state packet.
     */
    private boolean dirty = false;

    // -------------------------------------------------------------------------
    // DURABILITY
    //
    // Journal progress lives in the player's NBT, which vanilla writes only on a clean
    // disconnect and on the world autosave — five minutes apart by default. Everything
    // discovered in between is lost if the server is killed rather than stopped, and on
    // a busy server "the server crashed" should not also mean "everyone lost an evening
    // of journal progress".
    //
    // So a durable change asks for a save of its own. Throttled, because these flags are
    // set by eating and a player working through a modpack's food list can discover
    // several items a minute: one write per player per interval at the very most, and none
    // at all for the vast majority of players, who change nothing durable in a given
    // session. The window of loss goes from five minutes to five seconds for the two
    // things a player would actually miss — a buff, and a newly discovered food — and to
    // thirty for everything else.
    // -------------------------------------------------------------------------

    /** Ticks between forced saves for journal progress. */
    static final int PERSIST_INTERVAL_TICKS = 600;

    /**
     * The same, for a buff being gained or lost — five seconds rather than thirty.
     *
     * <p>Shorter than {@link #PERSIST_INTERVAL_TICKS} because a buff lost to a crash is
     * the thing the player was in the middle of using, and losing it is indistinguishable
     * from the bug this whole area exists to stop — "I ate it and got nothing".
     * {@link #DISCOVERY_PERSIST_INTERVAL_TICKS} matches it for the same reason; what is
     * left on the slow deadline is only the journal-granted flag, which no player can
     * tell has been rolled back.
     *
     * <p>Affordable because eating is rate-limited by the game: a bite takes 1.6 seconds,
     * so this can cost at most one player save per player per five seconds even if
     * somebody does nothing but eat.
     */
    static final int BUFF_PERSIST_INTERVAL_TICKS = 100;

    /**
     * The same again, for a food being discovered.
     *
     * <p>Thirty seconds at first, on the reasoning that a lost discovery costs a journal
     * row the player re-earns the next time they eat that food. Which is true, and still
     * the wrong trade for a server: the row is the thing the player was collecting, the
     * loss is invisible until they next open the journal, and "the journal forgot what I
     * ate" is indistinguishable from the bug where the bite never registered at all.
     *
     * <p>Costs no more than the buff deadline does, and for the same reason: a bite takes
     * 1.6 seconds, so a player who does nothing but eat their way through a modpack still
     * cannot ask for more than one save per five seconds — and the two requests arrive
     * together anyway, since eating a new food both discovers it and grants its buff.
     */
    static final int DISCOVERY_PERSIST_INTERVAL_TICKS = 100;

    /** Something durable changed and has not been written to disk yet. */
    private boolean persistPending = false;

    /**
     * Server tick of the last forced save. Starts a full interval in the past so the
     * first discovery of a session is written immediately rather than after a delay.
     */
    private int lastPersistTick = -PERSIST_INTERVAL_TICKS;

    /**
     * How long the pending change is willing to wait. Set by whichever request wants it
     * soonest, so a buff gained in the same second as a discovery does not inherit the
     * discovery's slower deadline.
     */
    private int persistInterval = PERSIST_INTERVAL_TICKS;

    /**
     * Whether this player has durable changes worth writing now. Asked once a second
     * from the server tick — off the entity tick on purpose, so the save serializes a
     * player that is not in the middle of being ticked.
     */
    public boolean isPersistDue(int serverTick) {
        if (!persistPending) return false;
        if (serverTick - lastPersistTick < persistInterval) return false;
        lastPersistTick = serverTick;
        persistPending = false;
        persistInterval = PERSIST_INTERVAL_TICKS;
        return true;
    }

    /** Flags journal progress that must survive a crash, not just a clean shutdown. */
    private void markPersistPending() {
        requestPersist(PERSIST_INTERVAL_TICKS);
    }

    /**
     * The same, for a discovery, which is owed to disk sooner.
     *
     * @see #DISCOVERY_PERSIST_INTERVAL_TICKS
     */
    private void markDiscoveryPending() {
        requestPersist(DISCOVERY_PERSIST_INTERVAL_TICKS);
    }

    /**
     * @param interval the longest this change may sit in memory, in ticks
     */
    // Package-private, like modifierScope above and for the same reason: the two
    // deadlines and the way they interact are the whole point of this, and driving them
    // from a test needs neither a player nor a world.
    void requestPersist(int interval) {
        if (!persistPending || interval < persistInterval) persistInterval = interval;
        persistPending = true;
    }

    public PlayerFoodComponent(PlayerEntity player) {
        this.player = player;
    }

    // -------------------------------------------------------------------------
    // ACCESSORS
    // -------------------------------------------------------------------------

    public boolean hasReceivedJournal()              { return hasReceivedJournal; }
    public void    setHasReceivedJournal(boolean v)  {
        if (this.hasReceivedJournal != v) markPersistPending();
        this.hasReceivedJournal = v;
    }
    public Set<String>          getDiscoveredFoods()      { return discoveredFoods; }
    public Set<String>          getDiscoveredSynergies()  { return discoveredSynergies; }
    public List<ActiveFoodBuff> getActiveBuffs()          { return activeBuffs; }
    public List<ActiveFoodBuff> getActiveSynergies()      { return activeSynergies; }

    // -------------------------------------------------------------------------
    // FOOD DISCOVERY
    // -------------------------------------------------------------------------

    public boolean unlockFood(String itemId) {
        if (!discoveredFoods.add(itemId)) return false;
        markDiscoveryPending();
        // Deliberately not marked dirty: the discovery list is the largest part of the
        // state and never changes again once a food is known, so it travels in its own
        // one-string payload below instead of riding along on every buff update.
        if (player instanceof ServerPlayerEntity serverPlayer) {
            Florafare.sendTo(serverPlayer, new FoodUnlockedPayload(itemId));
        }
        if (!player.getWorld().isClient()) {
            FlorafareEvents.FOOD_DISCOVERED.invoker().onFoodDiscovered(player, itemId);
        }
        return true;
    }

    /**
     * Discovers several foods at once, for {@code /florafare journal unlock … all}.
     *
     * <p>Not a loop over {@link #unlockFood}: that sends one packet per food and the
     * client answers each with a discovery toast, so unlocking a modpack's worth of
     * them would queue several hundred toasts and as many packets. One full state sync
     * covers the lot.
     *
     * @return how many of the ids were not already known
     */
    public int unlockFoods(Collection<String> itemIds) {
        int added = 0;
        for (String itemId : itemIds) {
            if (!discoveredFoods.add(itemId)) continue;
            added++;
            if (!player.getWorld().isClient()) {
                FlorafareEvents.FOOD_DISCOVERED.invoker().onFoodDiscovered(player, itemId);
            }
        }
        if (added > 0) {
            markDiscoveryPending();
            sync();
        }
        return added;
    }

    /**
     * Forgets one discovered food, so its journal entry goes back to locked.
     *
     * <p>Re-syncs the whole component rather than sending a "locked" packet, because
     * there is no such packet: the client's discovery set only ever grows on its own
     * ({@link FoodUnlockedPayload} adds to it, nothing removes), and the full sync is
     * the one thing that replaces it wholesale.
     *
     * @return false if the player had not discovered it in the first place
     */
    public boolean lockFood(String itemId) {
        if (!discoveredFoods.remove(itemId)) return false;
        markPersistPending();
        sync();
        return true;
    }

    /** Forgets every discovered food. @return how many entries were dropped */
    public int lockAllFoods() {
        int removed = discoveredFoods.size();
        if (removed == 0) return 0;
        discoveredFoods.clear();
        markPersistPending();
        sync();
        return removed;
    }

    /**
     * Records a synergy as discovered without having to actually trigger it. Sends the
     * same toast the real discovery does, since there is only ever one at a time.
     *
     * @return false if it was already known
     */
    public boolean unlockSynergy(String synergyId) {
        if (!discoveredSynergies.add(synergyId)) return false;
        markDiscoveryPending();
        if (player instanceof ServerPlayerEntity serverPlayer) {
            Florafare.sendTo(serverPlayer, new SynergyUnlockedPayload(synergyId));
        }
        return true;
    }

    /** Bulk counterpart of {@link #unlockSynergy}; one sync, no toasts. */
    public int unlockSynergies(Collection<String> synergyIds) {
        int added = 0;
        for (String synergyId : synergyIds) {
            if (discoveredSynergies.add(synergyId)) added++;
        }
        if (added > 0) {
            markDiscoveryPending();
            sync();
        }
        return added;
    }

    /** @return false if the player had not discovered it. See {@link #lockFood}. */
    public boolean lockSynergy(String synergyId) {
        if (!discoveredSynergies.remove(synergyId)) return false;
        markPersistPending();
        sync();
        return true;
    }

    /** Forgets every discovered synergy. @return how many entries were dropped */
    public int lockAllSynergies() {
        int removed = discoveredSynergies.size();
        if (removed == 0) return 0;
        discoveredSynergies.clear();
        markPersistPending();
        sync();
        return removed;
    }

    // -------------------------------------------------------------------------
    // THE BITE IN PROGRESS
    //
    // Florafare takes two things away from vanilla eating and puts its own in their
    // place: the food's nutrition/saturation (the HungerManager#eat wrapper in
    // PlayerEntityMixin) and the food's own status effects (LivingEntityEatMixin).
    // Both of those used to fire on nothing more than "this item has a Florafare
    // config", while the buff that justifies them was granted somewhere else entirely
    // and could refuse.
    //
    // A BUFF_APPLYING listener saying no therefore produced the worst of both worlds:
    // no Florafare buff, no vanilla effects either, and the datapack's hunger numbers
    // applied anyway. The whole point of that event is "leave this bite alone", so
    // the two removals now ask whether the bite was actually taken over.
    // -------------------------------------------------------------------------

    /**
     * Whether Florafare owns the bite currently being processed.
     *
     * <p>Reset at the top of every {@code eatFood} by {@link #beginBite()} and set only by
     * {@link #eatFood}, so it always describes the bite in progress and never a previous
     * one. Deliberately <em>not</em> touched by {@link #tryAddBuff}: that is the API and
     * command entry point, and a {@code BUFF_APPLIED} listener calling
     * {@code FlorafareAPI.applyBuffToPlayer} in the middle of a bite used to overwrite this
     * with the answer for its own, unrelated buff.
     */
    private boolean vanillaSuperseded = false;

    /**
     * Called at the head of {@code PlayerEntity#eatFood}, before anything else, so that
     * every path out of the eat hook — an excluded food, a non-player entity being fed,
     * an early return — leaves this at "vanilla owns the bite" rather than at whatever
     * the previous bite decided.
     */
    public void beginBite() {
        vanillaSuperseded = false;
    }

    /**
     * Whether Florafare's numbers replace vanilla's for the bite in progress. Callers
     * have already established that the stack has a Florafare config.
     *
     * <p>Answers true unconditionally on the client. The decision is the server's — the
     * event that can refuse a buff is server-only — and the client has always predicted
     * the common case here. A refused bite means the client briefly shows the datapack's
     * hunger instead of vanilla's, which the server's next hunger sync corrects; status
     * effects are server-driven and never predicted at all.
     */
    public static boolean handlesCurrentBite(PlayerEntity player) {
        if (player.getWorld().isClient) return true;
        return ((IFoodComponentProvider) player).florafare$getFoodComponent().vanillaSuperseded;
    }

    /**
     * The stack whose edibility {@code PlayerEntity#canConsume} is being asked about, set
     * by {@code ItemUseMixin} around that one call in {@code Item#use}; null otherwise.
     * canConsume itself is handed no stack, and without this it could only guess from
     * both hands.
     */
    private ItemStack consumeCheckStack = null;

    @org.jetbrains.annotations.ApiStatus.Internal
    public ItemStack getConsumeCheckStack() {
        return consumeCheckStack;
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public void setConsumeCheckStack(ItemStack stack) {
        this.consumeCheckStack = stack;
    }

    // -------------------------------------------------------------------------
    // REGENERATION DELAY
    //
    // When natural regeneration may resume after the player was hurt, as an absolute
    // server tick (MinecraftServer#getTicks). Absolute rather than a countdown, so nothing
    // has to tick it and a delay simply lapses by time passing, including while the
    // player sits in a portal or a loading screen.
    //
    // Deliberately not persisted: a two-second wait has no business surviving a relog,
    // and the server tick counter it is measured in restarts with the server anyway. A
    // respawn builds a new component and so starts with no delay — the player is back at
    // full health then regardless. Carried across the "alive" copy (the end-portal
    // return) by copyFrom, and untouched by an ordinary dimension change, which keeps the
    // same entity. See NaturalRegeneration for who sets and reads it.
    // -------------------------------------------------------------------------

    /** The server tick natural regeneration may resume at; anything in the past means "now". */
    private long regenDelayUntilTick = 0L;

    /** Ticks of regeneration delay left at server tick {@code nowTick}; 0 when none. */
    public int getRegenDelayRemaining(long nowTick) {
        return net.tend1tnuy.florafare.food.RegenMath.remainingTicks(regenDelayUntilTick, nowTick);
    }

    /** Replaces the delay with exactly {@code ticks} from now; 0 or less clears it. */
    public void setRegenDelay(long nowTick, int ticks) {
        regenDelayUntilTick = ticks > 0 ? nowTick + ticks : 0L;
    }

    /** Makes the delay at least {@code ticks} from now, keeping a longer one already running. */
    public void extendRegenDelay(long nowTick, int ticks) {
        regenDelayUntilTick = net.tend1tnuy.florafare.food.RegenMath.extendedUntil(regenDelayUntilTick, nowTick, ticks);
    }

    // -------------------------------------------------------------------------
    // BUFF MANAGEMENT
    // -------------------------------------------------------------------------

    /** What became of one bite (or one drink) of a configured food. */
    public enum BiteOutcome {
        /**
         * A {@code BUFF_APPLYING} listener refused. Nothing of the entry was applied, and
         * for a bite the hunger and the vanilla effects are handed back to vanilla.
         */
        VETOED,
        /** A buff was granted, or the running one for this food refreshed. */
        GRANTED,
        /**
         * A hunger-only entry ({@code duration <= 0}): its hunger and status effects
         * applied, and by design no buff, no slot and no buff events.
         */
        HUNGER_ONLY,
        /**
         * Every slot was taken. The entry's hunger and status effects still applied —
         * the food is still Florafare's to manage — but no buff, so no attributes.
         */
        SLOTS_FULL
    }

    /**
     * The eat hook's entry point: one bite of a configured food.
     *
     * <p>Differs from {@link #tryAddBuff} in two ways. It records whether the bite was
     * taken over, for the two mixins that take hunger and vanilla effects away. And when
     * there is no slot free it still applies the entry's {@code effects}: the bite's
     * vanilla effects are suppressed on the strength of the entry replacing them, so an
     * entry whose buff could not be granted used to leave the player with neither — a
     * golden apple eaten on a full slot list gave its datapack hunger and nothing else.
     */
    public BiteOutcome eatFood(ItemStack stack, FoodBuffData data) {
        BiteOutcome outcome = grant(stack, itemIdOf(stack), data, true);
        // Recorded before any listener runs, so nothing a listener does can change it.
        vanillaSuperseded = outcome != BiteOutcome.VETOED;
        fireQueuedEvents();
        return outcome;
    }

    /**
     * A potion with a {@code potion:} entry has just been drunk. Same rules as a bite,
     * keyed by the potion target rather than an item id (every potion is the one item
     * {@code minecraft:potion}), and with no hunger and no effect suppression — the
     * potion's own effects stay entirely vanilla.
     */
    public BiteOutcome drinkPotion(ItemStack stack, String potionTarget, FoodBuffData data) {
        BiteOutcome outcome = grant(stack, potionTarget, data, true);
        fireQueuedEvents();
        return outcome;
    }

    /**
     * Grants a buff from code — {@code FlorafareAPI.applyBuffToPlayer} and
     * {@code /florafare buff give}.
     *
     * @return true if the entry was applied: the buff is now active, or the entry is
     *         hunger-only and its effects were granted. False when every slot is taken
     *         or a listener vetoed it.
     */
    public boolean tryAddBuff(ItemStack stack, FoodBuffData data) {
        BiteOutcome outcome = grant(stack, itemIdOf(stack), data, false);
        fireQueuedEvents();
        return outcome == BiteOutcome.GRANTED || outcome == BiteOutcome.HUNGER_ONLY;
    }

    private static String itemIdOf(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    /**
     * The shared core of every way a buff is granted.
     *
     * @param consumedId        what the buff records as eaten, and the key a second bite
     *                          of the same thing refreshes by
     * @param effectsWithoutSlot whether a full slot list still gets the entry's status
     *                          effects — yes for a real bite or drink, no for the API
     */
    private BiteOutcome grant(ItemStack stack, String consumedId, FoodBuffData data,
                              boolean effectsWithoutSlot) {
        boolean isServer = !player.getWorld().isClient;

        // Asked before anything is decided, including before the "is it already active"
        // branch: a veto has to cover the refresh of a running buff too, or a mod that
        // blocks a food can be worked around by eating it twice. Asked for hunger-only
        // entries as well, since a veto is what hands the bite back to vanilla.
        if (isServer && !FlorafareEvents.BUFF_APPLYING.invoker().allowBuff(player, stack, data)) {
            return BiteOutcome.VETOED;
        }

        if (data.isHungerOnly()) {
            applyStatusEffects(data);
            return BiteOutcome.HUNGER_ONLY;
        }

        ActiveFoodBuff existing = null;
        for (ActiveFoodBuff buff : activeBuffs) {
            if (buff.getConsumedItemId().equals(consumedId)) { existing = buff; break; }
        }

        if (existing != null) {
            existing.resetDuration(data.duration());
            // Strips whatever the buff was carrying before re-applying, so a refresh after
            // a /reload or a /florafare edit converges on the entry as it now stands.
            applyBuffEffects(existing, data, false);
            if (isServer) {
                updateSynergies(existing);
                ActiveFoodBuff refreshed = existing;
                queueEvent(() -> FlorafareEvents.BUFF_APPLIED.invoker()
                        .onBuffApplied(player, stack, refreshed));
            }
            markBuffChanged();
            return BiteOutcome.GRANTED;
        }

        if (activeBuffs.size() >= maxBuffSlots) {
            if (effectsWithoutSlot) applyStatusEffects(data);
            return BiteOutcome.SLOTS_FULL;
        }

        ActiveFoodBuff buff = new ActiveFoodBuff(
                data.target(), consumedId, data.duration(), data.duration());
        applyBuffEffects(buff, data, false);
        activeBuffs.add(buff);
        if (isServer) {
            updateSynergies(buff);
            queueEvent(() -> FlorafareEvents.BUFF_APPLIED.invoker()
                    .onBuffApplied(player, stack, buff));
        }
        markBuffChanged();
        return BiteOutcome.GRANTED;
    }

    /**
     * Removes one named buff, by the config target it was granted from or by the item
     * that was eaten. Forgotten Mead only ever drops the newest buff, which is no help
     * to a player holding three and wanting to swap out the one in the middle.
     *
     * @return true if a matching buff was active and has been removed
     */
    public boolean removeBuff(String targetOrItemId) {
        if (player.getWorld().isClient || targetOrItemId == null) return false;

        for (int i = 0; i < activeBuffs.size(); i++) {
            ActiveFoodBuff buff = activeBuffs.get(i);
            if (!buff.getTarget().equals(targetOrItemId)
                    && !buff.getConsumedItemId().equals(targetOrItemId)) {
                continue;
            }
            activeBuffs.remove(i);
            endBuff(buff);
            updateSynergies(null);
            markBuffChanged();
            fireQueuedEvents();
            return true;
        }
        return false;
    }

    public boolean removeLastBuff() {
        if (activeBuffs.isEmpty()) return false;
        ActiveFoodBuff buff = activeBuffs.remove(activeBuffs.size() - 1);
        endBuff(buff);
        if (!player.getWorld().isClient) updateSynergies(null);
        markBuffChanged();
        fireQueuedEvents();
        return true;
    }

    /**
     * Drops every buff and synergy — {@code /florafare clear}, the API, and death.
     *
     * <p>The lists are emptied first and the entries ended from a copy, so a
     * {@code BUFF_REMOVED} listener that calls back into the component finds it already
     * in its final state rather than half-way through an index loop.
     */
    public void clearAllBuffs() {
        if (player.getWorld().isClient) return;
        spentSynergies.clear();
        if (activeBuffs.isEmpty() && activeSynergies.isEmpty()) return;

        List<ActiveFoodBuff> buffs     = new ArrayList<>(activeBuffs);
        List<ActiveFoodBuff> synergies = new ArrayList<>(activeSynergies);
        activeBuffs.clear();
        activeSynergies.clear();
        activeSynergyMap.clear();

        for (ActiveFoodBuff buff : buffs)     endBuff(buff);
        for (ActiveFoodBuff buff : synergies) endBuff(buff);

        markBuffChanged();
        fireQueuedEvents();
    }

    // -------------------------------------------------------------------------
    // EVENT DELIVERY
    // -------------------------------------------------------------------------

    /** Queues a listener notification; dropped on the client, where no event fires. */
    private void queueEvent(Runnable event) {
        if (player.getWorld().isClient) return;
        pendingEvents.add(event);
    }

    /**
     * Delivers every queued notification. Called at the end of each public entry point,
     * once the component is consistent again.
     *
     * <p>Re-entrant by design: a listener that changes the component queues its own
     * events, and those are delivered by this same loop rather than by a nested one, so
     * listeners still hear about changes in the order they happened.
     */
    private void fireQueuedEvents() {
        if (firingEvents) return;
        firingEvents = true;
        try {
            Runnable next;
            while ((next = pendingEvents.poll()) != null) next.run();
        } finally {
            firingEvents = false;
        }
    }

    // -------------------------------------------------------------------------
    // ATTRIBUTE / EFFECT APPLICATION
    // -------------------------------------------------------------------------

    /**
     * Builds the modifier-id scope for a buff. Modifier ids have to be unique per
     * <em>active buff</em>, not per config target.
     *
     * <p>A synergy is keyed by its own id and only ever has one instance running, so its
     * target alone is already unique. A food buff's target can be shared by many items —
     * a {@code #tag}, a {@code namespace:}, {@code template:default} — while the buff
     * list holds one slot per item eaten. Scoping those by target alone meant two buffs
     * from one target wrote the same modifier id: the second silently overwrote the
     * first, and whichever expired first stripped the modifier off the one still
     * running, leaving a buff on screen with none of its stats.
     */
    // Package-private rather than private so the collision rule can be driven directly
    // from a unit test; it needs no player and no world.
    static String modifierScope(ActiveFoodBuff buff, boolean isSynergy) {
        String raw = (isSynergy || buff.getTarget().equals(buff.getConsumedItemId()))
                ? buff.getTarget()
                : buff.getTarget() + "/" + buff.getConsumedItemId();
        // "#" and ":" are not valid Identifier path characters; "/" is.
        String scoped = raw.replace("#", "tag_").replace(":", "_");

        // Then everything else, because those two are only the characters a well-formed
        // target contains. A target that Identifier.tryParse rejected is kept verbatim by
        // normalizeTarget (deliberately — a datapack's typo should stay recognisable in
        // the logs), so an uppercase or spaced target could still reach here. It is used
        // to build an Identifier a few lines later, outside any try/catch, and
        // Identifier.of throws on a bad path — which surfaced as an exception thrown out
        // of the middle of eating. Lowercasing with Locale.ROOT, not the default locale:
        // in a Turkish locale "I" lowercases to a dotless "ı", which is not a valid path
        // character either.
        return sanitizePath(scoped);
    }

    /**
     * Reduces a string to characters an {@link Identifier} path accepts, lowercasing with
     * {@link Locale#ROOT} and replacing anything else with an underscore.
     */
    static String sanitizePath(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder safe = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-';
            safe.append(valid ? c : '_');
        }
        return safe.toString();
    }

    /**
     * The modifier id path for one attribute line of one active buff:
     * {@code attr_<scope>_<namespace>_<path>_<index>}.
     *
     * <p>It used to be {@code attr_<scope>_<path>}, dropping the attribute's namespace
     * and the line it came from, so two lines on one buff could share an id: two mods'
     * attributes with the same path ({@code spell_power:haste} and
     * {@code ranged_weapon:haste}), or one attribute listed twice with different
     * operations. The second line silently replaced the first's modifier on the player
     * while both were recorded on the buff under the one id — and only one of them could
     * ever be taken off again. The line index alone makes every id on a buff unique; the
     * namespace and path are there so the id still says what it is in a {@code /attribute}
     * listing.
     *
     * <p>Modifiers written under the old scheme are still removed correctly: removal goes
     * by the id recorded on the buff, whatever its format, and the load-time
     * {@link #stripFlorafareModifiers()} takes off anything in the Florafare namespace.
     */
    static String attributeModifierPath(String scope, Identifier attributeId, int index) {
        return "attr_" + scope
                + "_" + sanitizePath(attributeId.getNamespace())
                + "_" + sanitizePath(attributeId.getPath())
                + "_" + index;
    }

    /**
     * Applies an entry's health bonus, attributes and status effects to a buff.
     *
     * <p>Starts by taking off every modifier the buff already carries. A refresh — eating
     * the same food again, or a synergy being re-evaluated — used to only add and
     * overwrite, so a modifier the entry no longer lists (after a {@code /reload}, a
     * {@code /florafare edit}, or with a {@code setbuff} stack now resolving differently)
     * stayed on the player for as long as the buff kept being refreshed, and its record
     * was overwritten so nothing would ever remove it.
     */
    private void applyBuffEffects(ActiveFoodBuff buff, FoodBuffData data, boolean isSynergy) {
        if (player.getWorld().isClient) return;

        // No health clamp between the strip and the re-apply: taking a max-health bonus
        // off and putting it straight back must not cost the player the hearts above the
        // un-buffed maximum. The clamp further down runs once, on the final state.
        removeRecordedModifiers(buff);
        buff.clearModifierRecords();

        String safeId = modifierScope(buff, isSynergy);

        if (data.healthBonus() != 0) {
            Identifier id = Identifier.of(Florafare.MOD_ID, "health_" + safeId);
            applyAttribute(buff, EntityAttributes.GENERIC_MAX_HEALTH, id,
                    data.healthBonus(), EntityAttributeModifier.Operation.ADD_VALUE);
        }

        List<FoodBuffData.AttributeData> attributes = data.attributes();
        for (int i = 0; i < attributes.size(); i++) {
            FoodBuffData.AttributeData attr = attributes.get(i);
            Optional<RegistryEntry.Reference<net.minecraft.entity.attribute.EntityAttribute>> entry =
                    Registries.ATTRIBUTE.getEntry(attr.attributeId());
            if (entry.isEmpty()) continue;

            // The loaders reject an unknown operation, so this only catches a config built
            // in code. Skipped rather than guessed: add_value in place of a multiplier is
            // a wildly different number, not a near miss.
            Optional<EntityAttributeModifier.Operation> operation =
                    AttributeOperations.parse(attr.operation());
            if (operation.isEmpty()) {
                Florafare.LOGGER.error(
                        "Buff '{}' gives attribute {} the unknown operation '{}'; skipped.",
                        data.target(), attr.attributeId(), attr.operation());
                continue;
            }

            Identifier id = Identifier.of(Florafare.MOD_ID,
                    attributeModifierPath(safeId, attr.attributeId(), i));
            applyAttribute(buff, entry.get(), id, attr.amount(), operation.get());
        }

        // A negative max-health modifier can leave current health above the new maximum.
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }

        applyStatusEffects(data);
    }

    /**
     * Grants an entry's {@code effects} list. Split out because it is also what a bite
     * gets when no buff can be granted — every slot taken, or a hunger-only entry — since
     * those bites still have their vanilla effects suppressed in favor of these.
     */
    private void applyStatusEffects(FoodBuffData data) {
        if (player.getWorld().isClient) return;
        for (FoodBuffData.EffectData effect : data.effects()) {
            applyEffect(effect.id(), effect.duration(), effect.amplifier());
        }
    }

    /**
     * Grants one status effect, with the same ceiling the buffs themselves get.
     *
     * <p>A buff's own duration was capped while the effects it hands out were not, and
     * Florafare never takes a food buff's effects back — they are left to expire. A
     * datapack effect duration of a few hundred million ticks was therefore permanent in
     * every sense: the buff vanished from the overlay within minutes and the Regeneration
     * stayed for the life of the world, with nothing in the mod able to remove it.
     *
     * <p>The amplifier is floored at zero because a negative one is not a weaker effect —
     * it inverts the arithmetic inside several vanilla effects and is never intended.
     */
    private void applyEffect(Identifier effectId, int duration, int amplifier) {
        int ticks = effectDurationTicks(duration);
        if (ticks == 0) return;
        Registries.STATUS_EFFECT.getEntry(effectId).ifPresent(status ->
                player.addStatusEffect(new StatusEffectInstance(status, ticks, clampAmplifier(amplifier)))
        );
    }

    /**
     * The tick count handed to {@link StatusEffectInstance} for a datapack effect.
     *
     * <p>{@code -1} is vanilla's infinite duration and the tooltip already shows it as ∞, so
     * it passes through untouched instead of being clamped to zero like any other negative
     * value used to be (which silently made an "infinite" effect do nothing). Other
     * negatives and zero return 0, which the caller treats as "don't apply": a zero-tick
     * effect would only flash its icon. Everything else is clamped to the buff ceiling.
     */
    static int effectDurationTicks(int duration) {
        if (duration == StatusEffectInstance.INFINITE) return StatusEffectInstance.INFINITE;
        return ActiveFoodBuff.clampDuration(duration);
    }

    /** Status effect amplifiers are a byte on the wire; keep them in a sane, positive range. */
    static int clampAmplifier(int amplifier) {
        return Math.min(Math.max(amplifier, 0), 255);
    }

    private void applyAttribute(ActiveFoodBuff buff,
                                RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute,
                                Identifier modifierId, double amount,
                                EntityAttributeModifier.Operation operation) {
        EntityAttributeInstance instance = player.getAttributeInstance(attribute);
        if (instance == null) return;

        instance.removeModifier(modifierId);
        try {
            // Temporary, never persistent: a persistent modifier is written into the
            // player's own vanilla attribute NBT, so any mismatch between that copy and
            // the buff list below leaves the stat stuck forever (and survives the mod
            // being uninstalled). Temporary modifiers live only in memory and are
            // rebuilt from our own saved buff list by reapplyAttributes() on load.
            instance.addTemporaryModifier(
                    new EntityAttributeModifier(modifierId, amount, operation));
            Identifier registryId = Registries.ATTRIBUTE.getId(attribute.value());
            if (registryId != null) {
                buff.addModifierRecord(modifierId, registryId, amount,
                        AttributeOperations.nameOf(operation));
            }
        } catch (Exception e) {
            Florafare.LOGGER.error("Failed to apply attribute modifier: {}", modifierId, e);
        }
    }

    /**
     * Strips every attribute modifier in the Florafare namespace from the player,
     * scanning the registry directly rather than trusting the mod's own buff list.
     *
     * <p>Two jobs: it backs {@code /florafare repair}, and it runs on every load to
     * migrate saves written by older versions, which used persistent modifiers that
     * vanilla baked into the player's attribute NBT. Without it those old modifiers
     * would load alongside the freshly reapplied temporary ones and double up.
     *
     * @return how many modifiers were removed
     */
    public int stripFlorafareModifiers() {
        int removed = 0;
        // Iterated straight off the stream: the previous .toList() allocated a list of
        // every attribute in the game on each call, and this runs on every player load.
        for (Iterator<RegistryEntry.Reference<EntityAttribute>> it =
                     Registries.ATTRIBUTE.streamEntries().iterator(); it.hasNext(); ) {
            RegistryEntry<EntityAttribute> entry = it.next();
            EntityAttributeInstance instance = player.getAttributeInstance(entry);
            if (instance == null) continue;

            List<Identifier> toRemove = null;
            for (EntityAttributeModifier modifier : instance.getModifiers()) {
                if (!modifier.id().getNamespace().equals(Florafare.MOD_ID)) continue;
                if (toRemove == null) toRemove = new ArrayList<>();
                toRemove.add(modifier.id());
            }
            if (toRemove == null) continue;

            for (Identifier modifierId : toRemove) {
                instance.removeModifier(modifierId);
                removed++;
            }
        }

        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
        return removed;
    }

    /**
     * Takes every Florafare modifier off the player and puts back the ones that a buff
     * still running is entitled to — which is the whole of what {@code /florafare repair}
     * should mean.
     *
     * <p>The command used to call {@link #stripFlorafareModifiers()} and stop there. That
     * scan is indiscriminate by design — it has to be, because the orphans it exists to
     * find are precisely the ones the buff list no longer knows about — so running it on a
     * player who simply had three buffs going stripped all three of them down to nothing.
     * The buffs stayed in the overlay, kept their slots and kept counting down, with none
     * of their stats; and nothing would ever have put those back, because the apply path
     * runs on eating and the reapply path runs on loading. An admin reaching for the
     * command to un-stick one player quietly broke every buff that player was holding.
     *
     * <p>The load path in {@code PlayerEntityMixin} always did both halves. This is the
     * same pair, in one method, so the two cannot be called apart again.
     *
     * @return how many modifiers were left off — the genuinely orphaned ones
     */
    public int repairModifiers() {
        int stripped  = stripFlorafareModifiers();
        int reapplied = reapplyAttributes();
        // A modifier recorded with an amount of zero is stripped but not reapplied (it
        // does nothing either way), so clamp rather than report a negative count.
        return Math.max(0, stripped - reapplied);
    }

    /**
     * Reapplies the recorded attribute modifiers of every active buff and synergy.
     * Needed whenever the player entity is (re)built — loading from disk, or being
     * recreated on a dimension change — because the modifiers are temporary and
     * vanilla carries over only BASE attribute values.
     *
     * @return how many modifiers were put back
     */
    public int reapplyAttributes() {
        if (player.getWorld().isClient) return 0;
        int reapplied = 0;
        for (ActiveFoodBuff buff : activeBuffs)     reapplied += reapplyBuffModifiers(buff);
        for (ActiveFoodBuff buff : activeSynergies) reapplied += reapplyBuffModifiers(buff);
        return reapplied;
    }

    private int reapplyBuffModifiers(ActiveFoodBuff buff) {
        int reapplied = 0;
        for (Map.Entry<Identifier, ActiveFoodBuff.AppliedModifier> record
                : buff.getAppliedModifiers().entrySet()) {
            Identifier modId = record.getKey();
            ActiveFoodBuff.AppliedModifier modifier = record.getValue();
            if (modifier.amount() == 0) continue;

            Optional<RegistryEntry.Reference<EntityAttribute>> entry =
                    Registries.ATTRIBUTE.getEntry(modifier.attributeId());
            if (entry.isEmpty()) continue;

            EntityAttributeInstance instance = player.getAttributeInstance(entry.get());
            if (instance == null) continue;

            // Records are written by applyAttribute with a canonical name, so an
            // unparseable one means a hand-edited or corrupted save. Leave it off.
            Optional<EntityAttributeModifier.Operation> operation =
                    AttributeOperations.parse(modifier.operation());
            if (operation.isEmpty()) continue;

            instance.removeModifier(modId);
            try {
                instance.addTemporaryModifier(new EntityAttributeModifier(
                        modId, modifier.amount(), operation.get()));
                reapplied++;
            } catch (Exception e) {
                Florafare.LOGGER.error("Failed to reapply attribute modifier: {}", modId, e);
            }
        }
        return reapplied;
    }

    // -------------------------------------------------------------------------
    // SYNERGY EVALUATION  (#11 — O(1) map lookup instead of linear stream scan)
    // -------------------------------------------------------------------------

    /**
     * Returns true if {@code buff} satisfies the given synergy requirement string.
     * Supports bare item-id requirements and "#"-prefixed tag requirements.
     *
     * <p>Public and static because the HUD needs the same answer to decide which slots
     * to mark as feeding a synergy. It used to do its own strict {@code equals} against
     * the buff's target, so a synergy built on a tag requirement highlighted nothing.
     */
    public static boolean satisfiesRequirement(ActiveFoodBuff buff, String requirement) {
        if (buff.getTarget().equals(requirement)) return true;

        Identifier consumedId = Identifier.tryParse(buff.getConsumedItemId());
        if (consumedId == null || !Registries.ITEM.containsId(consumedId)) return false;

        if (requirement.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(requirement.substring(1));
            return tagId != null && Registries.ITEM.get(consumedId).getDefaultStack()
                    .isIn(TagKey.of(RegistryKeys.ITEM, tagId));
        }
        return requirement.equals(consumedId.toString());
    }

    private boolean requirementsMet(List<String> requirements) {
        // An empty requirement list is vacuously satisfied, which would make the synergy
        // permanently active and — with no requirement to read a duration from — give it
        // Integer.MAX_VALUE ticks. The loader rejects such entries; this is the backstop
        // for a synergy that reached the map some other way (the API, an older save).
        if (requirements.isEmpty()) return false;

        for (String req : requirements) {
            boolean met = false;
            for (ActiveFoodBuff buff : activeBuffs) {
                if (satisfiesRequirement(buff, req)) { met = true; break; }
            }
            if (!met) return false;
        }
        return true;
    }

    /**
     * Re-evaluates every synergy against the buffs now active.
     *
     * @param fresh the buff that was just gained or refreshed, or null when this runs
     *              because something ended. Only a fresh buff that feeds a synergy may
     *              lengthen it or restart one that has already run out — see
     *              {@link #spentSynergies} and {@link #refreshWindow}.
     */
    private boolean updateSynergies(ActiveFoodBuff fresh) {
        if (player.getWorld().isClient) return false;

        if (!FlorafareConfig.enableSynergies) {
            spentSynergies.clear();
            boolean changed = !activeSynergies.isEmpty();
            if (changed) {
                List<ActiveFoodBuff> ended = new ArrayList<>(activeSynergies);
                activeSynergies.clear();
                activeSynergyMap.clear();
                for (ActiveFoodBuff synergyBuff : ended) endBuff(synergyBuff);
                markDirty();
            }
            return changed;
        }

        boolean changed = false;

        // Remove synergies whose requirements are no longer met or that have expired.
        // Collected first and ended after the loop: ending one queues a listener event,
        // and nothing should run while the list is mid-iteration.
        List<ActiveFoodBuff> ended = null;
        for (int i = activeSynergies.size() - 1; i >= 0; i--) {
            ActiveFoodBuff synergyBuff = activeSynergies.get(i);
            FoodSynergyData synergyData = FoodSynergyManager.getSynergy(synergyBuff.getTarget());

            if (synergyData == null
                    || !requirementsMet(synergyData.requirements())
                    || synergyBuff.isExpired()) {
                activeSynergies.remove(i);
                activeSynergyMap.remove(synergyBuff.getTarget()); // #11
                if (ended == null) ended = new ArrayList<>();
                ended.add(synergyBuff);
                changed = true;
            }
        }
        if (ended != null) {
            for (ActiveFoodBuff synergyBuff : ended) endBuff(synergyBuff);
        }

        // A spent synergy whose requirements have lapsed is no longer spent: the next time
        // they come together it is a new combination and starts normally.
        spentSynergies.removeIf(id -> {
            FoodSynergyData synergy = FoodSynergyManager.getSynergy(id);
            return synergy == null || !requirementsMet(synergy.requirements());
        });

        // Add or refresh synergies whose requirements are now met.
        for (FoodSynergyData synergy : FoodSynergyManager.getAllSynergies()) {
            if (!requirementsMet(synergy.requirements())) continue;

            // Synergy duration mirrors the shortest remaining buff among its requirements.
            int minDuration = Integer.MAX_VALUE;
            int minInitial  = Integer.MAX_VALUE;
            for (String req : synergy.requirements()) {
                for (ActiveFoodBuff buff : activeBuffs) {
                    if (satisfiesRequirement(buff, req)) {
                        if (buff.getDurationRemaining() < minDuration) {
                            minDuration = buff.getDurationRemaining();
                            minInitial  = buff.getInitialDuration();
                        }
                        break;
                    }
                }
            }

            SynergyWindow window = synergyWindow(minDuration, minInitial, synergy.duration());
            boolean fed = fresh != null && feedsSynergy(fresh, synergy);

            // #11 — O(1) lookup via map instead of stream().filter()
            ActiveFoodBuff existing = activeSynergyMap.get(synergy.id());

            if (existing != null) {
                SynergyWindow next = refreshWindow(existing.getDurationRemaining(),
                        existing.getInitialDuration(), window, fed);
                if (next != null) {
                    existing.resetDuration(next.remaining(), next.initial());
                    // Through the full apply path, not just the effects.
                    //
                    // This branch used to re-grant the status effects and stop there, so
                    // a synergy's attribute modifiers were written exactly once, when it
                    // first activated. A /reload that changed them left the running
                    // synergy applying the old numbers until it expired — and a modifier
                    // whose attribute had meanwhile been un-registered was never taken
                    // off at all. applyBuffEffects strips every recorded modifier before
                    // re-adding the current ones, so this converges on whatever the
                    // synergy currently says rather than drifting away from it.
                    applyBuffEffects(existing, synergyData(synergy, next.remaining()), true);
                    changed = true;
                }
            } else {
                // Ran its window out with its ingredients still going: it waits for one of
                // them to be eaten again rather than restarting on any bite at all.
                if (spentSynergies.contains(synergy.id())) {
                    if (!fed) continue;
                    spentSynergies.remove(synergy.id());
                }

                // Determine the HUD icon item from the first satisfied requirement.
                String iconItem = "minecraft:apple";
                if (!synergy.requirements().isEmpty()) {
                    String firstReq = synergy.requirements().get(0);
                    for (ActiveFoodBuff buff : activeBuffs) {
                        if (satisfiesRequirement(buff, firstReq)) {
                            iconItem = buff.getConsumedItemId();
                            break;
                        }
                    }
                }

                ActiveFoodBuff synergyBuff = ActiveFoodBuff.synergy(
                        synergy.id(), iconItem, window.remaining(), window.initial());

                applyBuffEffects(synergyBuff, synergyData(synergy, window.remaining()), true);
                activeSynergies.add(synergyBuff);
                activeSynergyMap.put(synergy.id(), synergyBuff); // #11

                logSynergyActivation(synergy, window.remaining());
                queueEvent(() -> FlorafareEvents.SYNERGY_ACTIVATED.invoker()
                        .onSynergyActivated(player, synergy, synergyBuff));

                if (discoveredSynergies.add(synergy.id())) {
                    markDiscoveryPending();
                    if (player instanceof ServerPlayerEntity serverPlayer) {
                        Florafare.sendTo(serverPlayer, new SynergyUnlockedPayload(synergy.id()));
                    }
                }
                changed = true;
            }
        }
        if (changed) markDirty();
        return changed;
    }

    /** Whether a buff satisfies at least one of a synergy's requirements. */
    private static boolean feedsSynergy(ActiveFoodBuff buff, FoodSynergyData synergy) {
        for (String req : synergy.requirements()) {
            if (satisfiesRequirement(buff, req)) return true;
        }
        return false;
    }

    /**
     * How far a running synergy's duration may drift from its target before it is
     * re-synced. Also the slack {@code removeSynergyEffects} has to allow for.
     */
    static final int SYNERGY_DRIFT_TOLERANCE = 10;

    /**
     * What a synergy that is already running should become, or null to leave it alone.
     *
     * <p>Only a requirement buff that was just gained or refreshed ({@code fed}) may move
     * a synergy to a <em>later</em> end — restarting a capped one at its full cap, or
     * following an uncapped one's ingredient forward. Any other re-evaluation (an
     * unrelated bite, some other buff running out) can only bring it forward, to the end
     * of its shortest ingredient. That second rule is the fix: those re-evaluations used
     * to re-sync every running synergy to its full window, which reset a capped synergy to
     * its whole cap on every bite of anything.
     *
     * <p>Package-private and static so the rule can be driven from a unit test.
     */
    static SynergyWindow refreshWindow(int remaining, int initial, SynergyWindow window,
                                       boolean fed) {
        int targetRemaining;
        int targetInitial;
        if (fed) {
            targetRemaining = window.remaining();
            targetInitial   = window.initial();
        } else if (window.remaining() < remaining) {
            targetRemaining = window.remaining();
            // Brought forward, not restarted: the bar keeps counting from its own start.
            targetInitial   = Math.max(initial, targetRemaining);
        } else {
            return null;
        }
        if (Math.abs(remaining - targetRemaining) <= SYNERGY_DRIFT_TOLERANCE) return null;
        return new SynergyWindow(targetRemaining, targetInitial);
    }

    /**
     * A synergy expressed as the {@link FoodBuffData} the apply path understands, with
     * every effect rewritten to last exactly as long as the synergy itself.
     *
     * <p>That rewrite is what lets {@code removeSynergyEffects} tell a synergy's own
     * Regeneration apart from a beacon's later on: it knows the effect it granted cannot
     * outlive the synergy.
     */
    private static FoodBuffData synergyData(FoodSynergyData synergy, int duration) {
        List<FoodBuffData.EffectData> scoped = new ArrayList<>(synergy.effects().size());
        for (FoodBuffData.EffectData eff : synergy.effects()) {
            scoped.add(new FoodBuffData.EffectData(eff.id(), duration, eff.amplifier()));
        }
        return new FoodBuffData(synergy.id(), duration, 0, 0f,
                synergy.healthBonus(), scoped, synergy.attributes(), 0, false);
    }

    /** How long a synergy runs for: {@code remaining} now, out of {@code initial} total. */
    record SynergyWindow(int remaining, int initial) {}

    /**
     * The window a synergy gets, given the shortest of the buffs feeding it and the
     * synergy's own {@code duration} field.
     *
     * <p>A synergy mirrors the shortest remaining buff among its requirements, and is
     * then capped by its own {@code duration}, which the datapack format documents as a
     * ceiling. Nothing read that field for a long time: it was parsed, serialized and
     * shipped to clients, and every bundled synergy sets it, but the value had no effect
     * whatsoever — a pack asking for a 10-second window on a powerful combination got the
     * full five minutes of its ingredients.
     *
     * <p>A ceiling can only ever shorten, never lengthen. That is applied identically on
     * the create and the refresh path, so a capped synergy is at every instant no longer
     * than the uncapped one would have been — which is what makes it safe to re-apply on
     * refresh without opening a way to extend a synergy for free.
     *
     * <p>Zero or negative means "no ceiling", which is also what an older sync payload
     * (where the field was absent) decodes to.
     *
     * <p>This is the window a synergy is <em>entitled</em> to. Whether a running one is
     * actually moved to it is {@link #refreshWindow}'s call, which only lets a bite that
     * feeds the synergy push its end later.
     *
     * <p>Package-private and static so the rule can be driven from a unit test; it needs
     * no player and no world.
     */
    static SynergyWindow synergyWindow(int minDuration, int minInitial, int synergyDuration) {
        if (synergyDuration > 0) {
            int ceiling = ActiveFoodBuff.clampDuration(synergyDuration);
            if (minDuration > ceiling) return new SynergyWindow(ceiling, ceiling);
        }
        return new SynergyWindow(minDuration, Math.max(minInitial, minDuration));
    }

    private void logSynergyActivation(FoodSynergyData synergy, int duration) {
        if (player.getWorld().isClient()
                || FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.NONE) {
            return;
        }
        String playerName = player.getName().getString();
        if (FlorafareConfig.consumptionLogging == FlorafareConfig.LogLevel.ALL) {
            Florafare.LOGGER.info(
                    "[Florafare Synergy] Player {} activated synergy '{}' (foods: {}) "
                            + "| Health: +{} | Duration: {}t",
                    playerName, synergy.id(),
                    String.join(", ", synergy.requirements()),
                    synergy.healthBonus(), duration);
        } else {
            Florafare.LOGGER.info(
                    "[Florafare Synergy] Player {} activated synergy '{}'",
                    playerName, synergy.id());
        }
    }

    // -------------------------------------------------------------------------
    // TICK
    // -------------------------------------------------------------------------

    /**
     * Counts every buff and synergy down, and — <b>on the server only</b> — drops the
     * ones that have run out.
     *
     * <p>Both sides run the countdown, because the overlay has to show a bar that moves
     * smoothly between packets rather than one that jumps once a second. Only one side
     * gets to decide that a buff is <em>over</em>, and it is not the client.
     *
     * <p>That split used to not exist: the client removed its own expired entries too.
     * Client and server tick the same buff at different real-world rates — the client is
     * pinned near 20 TPS by its own game loop while a busy modded server runs below it,
     * and the gap widens for as long as the buff lasts. A five-minute buff on a server
     * holding 15 TPS therefore vanished from the overlay something like a minute before
     * the server had finished with it.
     *
     * <p>Which produced the mod's worst symptom. The slot looked free, so the player ate
     * something to fill it; the server still held all {@link #maxBuffSlots} and refused
     * the new buff; and the bite did nothing at all, with the overlay showing a free slot
     * the whole time. It reads as "buffs randomly stop working", and it is at its worst
     * right after a restart or a busy join — exactly when the server's tick rate is
     * furthest from the client's.
     *
     * <p>So an expired entry now stays in the client's list, its bar sitting at empty,
     * until the server's own expiry sends the {@link BuffStateSyncPayload} that takes it
     * away. The slot count the player sees is then the slot count the server is enforcing.
     */
    public void tick() {
        boolean isServer = !player.getWorld().isClient;

        // Expired entries are taken out of the list inside the loop and ended after it,
        // so the listener events that ending queues never run mid-iteration.
        List<ActiveFoodBuff> expired = null;
        for (int i = activeBuffs.size() - 1; i >= 0; i--) {
            ActiveFoodBuff buff = activeBuffs.get(i);
            buff.tick();
            if (buff.isExpired() && isServer) {
                activeBuffs.remove(i);
                if (expired == null) expired = new ArrayList<>();
                expired.add(buff);
            }
        }

        if (expired != null) {
            for (ActiveFoodBuff buff : expired) endBuff(buff);
            markDirty();
            updateSynergies(null);
        }

        List<ActiveFoodBuff> ranOut = null;
        for (int i = activeSynergies.size() - 1; i >= 0; i--) {
            ActiveFoodBuff synergyBuff = activeSynergies.get(i);
            synergyBuff.tick();
            if (synergyBuff.isExpired() && isServer) {
                activeSynergies.remove(i);
                activeSynergyMap.remove(synergyBuff.getTarget()); // #11
                // Its requirements are still met (a lapsed requirement would have ended it
                // in updateSynergies above), so it ran its own window out: it stays off
                // until one of its ingredients is eaten again.
                spentSynergies.add(synergyBuff.getTarget());
                if (ranOut == null) ranOut = new ArrayList<>();
                ranOut.add(synergyBuff);
            }
        }

        if (ranOut != null) {
            for (ActiveFoodBuff synergyBuff : ranOut) endBuff(synergyBuff);
            markDirty();
        }

        fireQueuedEvents();

        // One flush per tick, at the end, covering everything that changed during it.
        flushIfDirty();
    }

    // -------------------------------------------------------------------------
    // ATTRIBUTE REMOVAL
    // -------------------------------------------------------------------------

    /**
     * Takes the modifiers recorded on a buff off the player. No health clamp: the callers
     * that end a buff clamp afterwards, and the refresh path must not clamp in between
     * taking a max-health bonus off and putting it back.
     */
    private void removeRecordedModifiers(ActiveFoodBuff buff) {
        buff.getAppliedModifiers().forEach((modId, modifier) -> {
            Registries.ATTRIBUTE.getEntry(modifier.attributeId()).ifPresent(entry -> {
                EntityAttributeInstance instance = player.getAttributeInstance(entry);
                if (instance != null) instance.removeModifier(modId);
            });
        });
    }

    /**
     * Ends a buff or synergy that has already been taken out of its list: strips what it
     * applied and queues the matching listener event. The records stay on the entry, so a
     * {@code BUFF_REMOVED} listener can still see what the buff had been granting.
     */
    private void endBuff(ActiveFoodBuff buff) {
        removeRecordedModifiers(buff);

        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }

        // Whether this is a synergy is read off the entry itself. Asking the synergy
        // manager "is anything registered under this target" answered the wrong question
        // twice over: a food buff whose datapack target collided with a synergy id was
        // treated as a synergy, and a synergy whose definition a /reload had removed was
        // treated as a food buff — so its status effects were never taken back.
        if (buff.isSynergy()) {
            FoodSynergyData synergy = FoodSynergyManager.getSynergy(buff.getTarget());
            if (synergy != null) removeSynergyEffects(buff, synergy);
        }

        // Single choke point for every removal path (expiry, Forgotten Mead, /florafare
        // clear, death, requirement-no-longer-met), so listeners see a consistent signal
        // regardless of why the buff/synergy ended. Queued, not fired: see pendingEvents.
        if (buff.isSynergy()) {
            queueEvent(() -> FlorafareEvents.SYNERGY_ENDED.invoker().onSynergyEnded(player, buff));
        } else {
            queueEvent(() -> FlorafareEvents.BUFF_REMOVED.invoker().onBuffRemoved(player, buff));
        }
    }

    /**
     * Takes off the status effects a synergy granted, and only those.
     *
     * <p>A synergy's effects are applied for exactly its own remaining duration, so when
     * it ends by running out they expire on their own — this matters for the other exit,
     * a requirement being lost early, where the effect would otherwise outlive the
     * synergy that justified it.
     *
     * <p>{@link PlayerEntity#removeStatusEffect} is indiscriminate: it takes the effect
     * off whatever granted it. Called unconditionally, ending a Resistance synergy
     * stripped the Resistance a player was getting from a beacon or a potion. So each
     * candidate is checked against what this synergy could plausibly have granted — same
     * amplifier, and not outlasting the synergy itself — and anything stronger or
     * longer-lived is left to its real owner.
     */
    private void removeSynergyEffects(ActiveFoodBuff buff, FoodSynergyData synergy) {
        // The refresh in updateSynergies() tolerates SYNERGY_DRIFT_TOLERANCE ticks of
        // drift before it re-applies, so our own instance can legitimately read slightly
        // longer than the buff. A second of slack covers that without reaching a beacon's
        // minutes.
        final int slack = 20;

        for (FoodBuffData.EffectData eff : synergy.effects()) {
            Registries.STATUS_EFFECT.getEntry(eff.id()).ifPresent(entry -> {
                StatusEffectInstance active = player.getStatusEffect(entry);
                if (active == null) return;
                if (active.isInfinite()) return;
                if (active.getAmplifier() != clampAmplifier(eff.amplifier())) return;
                if (active.getDuration() > buff.getDurationRemaining() + slack) return;
                player.removeStatusEffect(entry);
            });
        }
    }

    // -------------------------------------------------------------------------
    // SYNC
    //
    // Two packet shapes, because the two halves of this component change at wildly
    // different rates:
    //
    //   * the volatile half — active buffs and active synergies — changes constantly
    //     (a buff expiring is a change every few seconds) and is small, so it goes out
    //     on its own as BuffStateSyncPayload;
    //   * the durable half — every food and synergy the player has ever discovered —
    //     is by far the largest part of the state (hundreds of item ids in a big
    //     modpack) yet only ever grows by one entry at a time, so it rides on the
    //     full FoodBuffSyncPayload at join/respawn and on single-entry discovery
    //     packets after that.
    //
    // Before the split, a buff ticking down to zero re-sent the entire discovery list
    // to that player — kilobytes per expiry, per player.
    // -------------------------------------------------------------------------

    /** Flags the volatile half as changed; {@link #tick()} sends it once at tick end. */
    private void markDirty() {
        dirty = true;
    }

    /**
     * A buff was deliberately gained or lost, so the change is owed to disk as well as to
     * the client.
     *
     * <p>Vanilla writes player NBT on a clean disconnect and on the world autosave, five
     * minutes apart. A server that is killed rather than stopped therefore used to roll
     * every player back to the last autosave — and what a player notices about that is not
     * the coordinates, it is the buff they ate for two minutes ago being gone, which reads
     * as the buff having silently failed to apply.
     *
     * <p>Kept off natural expiry on purpose. That path fires for every player with a buff
     * running and would put every one of them on the save schedule permanently, to protect
     * a change that corrects itself anyway: a buff restored from before a crash simply
     * finishes counting down after it.
     */
    private void markBuffChanged() {
        markDirty();
        requestPersist(BUFF_PERSIST_INTERVAL_TICKS);
    }

    /** Sends the volatile half if anything changed this tick. */
    private void flushIfDirty() {
        if (!dirty) return;
        dirty = false;
        if (player instanceof ServerPlayerEntity serverPlayer) {
            Florafare.sendTo(serverPlayer,
                    new BuffStateSyncPayload(writeBuffStateToNbt(new NbtCompound())));
        }
    }

    /** Sends the complete state — used on join, respawn and dimension change. */
    public void sync() {
        dirty = false;
        if (player instanceof ServerPlayerEntity serverPlayer) {
            Florafare.sendTo(serverPlayer,
                    new FoodBuffSyncPayload(writeToNbt(new NbtCompound())));
        }
    }

    // -------------------------------------------------------------------------
    // NBT SERIALIZATION
    // -------------------------------------------------------------------------

    /** The volatile half only: the active buffs and synergies. */
    public NbtCompound writeBuffStateToNbt(NbtCompound nbt) {
        NbtList buffs = new NbtList();
        for (ActiveFoodBuff buff : activeBuffs) buffs.add(buff.toNbt());
        nbt.put(NBT_BUFFS_KEY, buffs);

        NbtList activeSyn = new NbtList();
        for (ActiveFoodBuff b : activeSynergies) activeSyn.add(b.toNbt());
        nbt.put(NBT_ACTIVE_SYNERGIES_KEY, activeSyn);
        return nbt;
    }

    /** Counterpart of {@link #writeBuffStateToNbt}; leaves the discovery lists alone. */
    public void readBuffStateFromNbt(NbtCompound nbt) {
        readActiveBuffs(nbt);
        readActiveSynergies(nbt);
    }

    public NbtCompound writeToNbt(NbtCompound nbt) {
        writeBuffStateToNbt(nbt);

        NbtList discovered = new NbtList();
        for (String id : discoveredFoods) discovered.add(NbtString.of(id));
        nbt.put(NBT_DISCOVERED_KEY, discovered);

        NbtList discoveredSyn = new NbtList();
        for (String synId : discoveredSynergies) discoveredSyn.add(NbtString.of(synId));
        nbt.put(NBT_DISCOVERED_SYNERGIES_KEY, discoveredSyn);

        nbt.putBoolean(NBT_JOURNAL_KEY, hasReceivedJournal);

        // Saved so a relog does not hand every spent synergy a free restart. Server
        // bookkeeping only; the client reads it back along with the rest and ignores it.
        NbtList spent = new NbtList();
        for (String synId : spentSynergies) spent.add(NbtString.of(synId));
        nbt.put(NBT_SPENT_SYNERGIES_KEY, spent);
        return nbt;
    }

    private void readActiveBuffs(NbtCompound nbt) {
        activeBuffs.clear();
        if (nbt.contains(NBT_BUFFS_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_BUFFS_KEY, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                activeBuffs.add(ActiveFoodBuff.fromNbt(list.getCompound(i)));
            }
        }
    }

    private void readActiveSynergies(NbtCompound nbt) {
        activeSynergies.clear();
        activeSynergyMap.clear(); // #11
        if (nbt.contains(NBT_ACTIVE_SYNERGIES_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_ACTIVE_SYNERGIES_KEY, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                ActiveFoodBuff b = ActiveFoodBuff.fromNbt(list.getCompound(i), true);
                activeSynergies.add(b);
                activeSynergyMap.put(b.getTarget(), b); // #11
            }
        }
    }

    public void readFromNbt(NbtCompound nbt) {
        readActiveBuffs(nbt);
        readActiveSynergies(nbt);

        discoveredFoods.clear();
        if (nbt.contains(NBT_DISCOVERED_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_DISCOVERED_KEY, NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) discoveredFoods.add(list.getString(i));
        }

        discoveredSynergies.clear();
        if (nbt.contains(NBT_DISCOVERED_SYNERGIES_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_DISCOVERED_SYNERGIES_KEY, NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) discoveredSynergies.add(list.getString(i));
        }

        spentSynergies.clear();
        if (nbt.contains(NBT_SPENT_SYNERGIES_KEY, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_SPENT_SYNERGIES_KEY, NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) spentSynergies.add(list.getString(i));
        }

        hasReceivedJournal = nbt.getBoolean(NBT_JOURNAL_KEY);
        dirty = false;          // freshly loaded — not dirty
        persistPending = false; // ...and identical to what is on disk
    }

    /**
     * Carries over only what survives dying: the journal, not the buffs.
     *
     * <p>The respawn handler used to do this by reaching through
     * {@code getDiscoveredFoods().addAll(...)} on the live sets, which writes the
     * progress in but steps over the bookkeeping {@link #unlockFood} does — so the new
     * component came up with {@code persistPending} still false and was owed to disk
     * without knowing it. Every discovery made since the world's last autosave was then
     * lost if the server went down before the next one, and dying is exactly when a
     * player has just been eating.
     */
    public void copyProgressFrom(PlayerFoodComponent old) {
        this.discoveredFoods.addAll(old.discoveredFoods);
        this.discoveredSynergies.addAll(old.discoveredSynergies);
        this.hasReceivedJournal = old.hasReceivedJournal;

        // Same reasoning as in copyFrom: a brand-new entity that has never been written.
        this.persistPending = true;
        this.lastPersistTick = old.lastPersistTick;
    }

    public void copyFrom(PlayerFoodComponent old) {
        this.activeBuffs.clear();
        this.activeBuffs.addAll(old.activeBuffs);

        this.discoveredFoods.clear();
        this.discoveredFoods.addAll(old.discoveredFoods);

        this.discoveredSynergies.clear();
        this.discoveredSynergies.addAll(old.discoveredSynergies);

        this.activeSynergies.clear();
        this.activeSynergyMap.clear();
        for (ActiveFoodBuff b : old.activeSynergies) {
            this.activeSynergies.add(b);
            this.activeSynergyMap.put(b.getTarget(), b); // #11
        }

        this.spentSynergies.clear();
        this.spentSynergies.addAll(old.spentSynergies);

        this.hasReceivedJournal = old.hasReceivedJournal;
        this.dirty = false;

        // Same server, same tick counter, so the absolute tick carries over as is: a hit
        // taken just before the portal still holds regeneration back on the far side.
        this.regenDelayUntilTick = old.regenDelayUntilTick;

        // The progress came across on a brand-new entity that has never been written, so
        // it is owed to disk even though nothing was discovered just now.
        this.persistPending = true;
        this.lastPersistTick = old.lastPersistTick;
    }
}
