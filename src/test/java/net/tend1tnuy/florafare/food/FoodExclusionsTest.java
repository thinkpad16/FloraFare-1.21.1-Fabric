package net.tend1tnuy.florafare.food;

import net.tend1tnuy.florafare.food.FoodExclusions.Effect;
import net.tend1tnuy.florafare.food.FoodExclusions.Entry;
import net.tend1tnuy.florafare.food.FoodExclusions.Kind;
import net.tend1tnuy.florafare.food.FoodExclusions.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The exclusion rules, driven directly — no game, no registry, no tags.
 *
 * <p>Everything here is about the half of the feature that decides what a written rule
 * <em>means</em>, because that is the half with the failure mode worth catching: a rule
 * that silently parses as something other than what was typed excludes the wrong items,
 * and nothing in game says so — the food simply stops (or fails to stop) being managed.
 */
class FoodExclusionsTest {

    @BeforeEach
    void reset() {
        FoodExclusions.resetAll();
    }

    @Test
    @DisplayName("an id with a namespace is one item")
    void parsesItemIds() {
        assertEquals(new Entry(Kind.ITEM, "loot_n_explore:espresso"),
                FoodExclusions.parse("loot_n_explore:espresso"));
        assertEquals(new Entry(Kind.ITEM, "minecraft:bread"),
                FoodExclusions.parse("  minecraft:bread  "));
    }

    @Test
    @DisplayName("all four mod-wide spellings mean the same rule")
    void parsesModWideForms() {
        Entry expected = new Entry(Kind.MOD, "loot_n_explore");
        for (String written : List.of("loot_n_explore:*", "namespace:loot_n_explore",
                                      "@loot_n_explore", "loot_n_explore")) {
            assertEquals(expected, FoodExclusions.parse(written), written);
        }
        assertEquals("loot_n_explore:*", expected.canonical());
    }

    @Test
    @DisplayName("a bare word is a mod, never minecraft:<word>")
    void bareWordIsNeverAnItem() {
        // Reading "bread" as "minecraft:bread" would silently exclude a vanilla item
        // nobody named, which is the worst possible reading of a typo.
        assertEquals(new Entry(Kind.MOD, "bread"), FoodExclusions.parse("bread"));
    }

    @Test
    @DisplayName("a #-prefixed id is an item tag")
    void parsesTags() {
        assertEquals(new Entry(Kind.TAG, "c:drinks"), FoodExclusions.parse("#c:drinks"));
        assertEquals("#c:drinks", FoodExclusions.parse("#c:drinks").canonical());
    }

    @Test
    @DisplayName("unusable text is rejected rather than guessed at")
    void rejectsGarbage() {
        for (String written : List.of("", "   ", "Loot N Explore", "mod:id:extra", "#", "@")) {
            assertNull(FoodExclusions.parse(written), written);
        }
        assertNull(FoodExclusions.parse(null));
    }

    @Test
    @DisplayName("the config lists are replaced, not merged, on every load")
    void configLoadReplaces() {
        FoodExclusions.loadFromConfig(
                List.of("minecraft:bread"), List.of("loot_n_explore"), List.of());
        assertEquals(List.of("loot_n_explore:*", "minecraft:bread"),
                FoodExclusions.canonicalEntries(Source.CONFIG));

        // An entry deleted from florafare.json has to actually stop applying.
        FoodExclusions.loadFromConfig(List.of("minecraft:bread"), List.of(), List.of());
        assertEquals(List.of("minecraft:bread"),
                FoodExclusions.canonicalEntries(Source.CONFIG));
    }

    @Test
    @DisplayName("re-reading the config leaves another mod's API entries alone")
    void configLoadKeepsApiEntries() {
        FoodExclusions.add(Source.API, "somemod:stew");
        FoodExclusions.loadFromConfig(List.of("minecraft:bread"), List.of(), List.of());
        try {
            assertTrue(FoodExclusions.canonicalEntries().contains("somemod:stew"));
            assertTrue(FoodExclusions.canonicalEntries().contains("minecraft:bread"));
        } finally {
            FoodExclusions.remove(Source.API, "somemod:stew");
        }
    }

    @Test
    @DisplayName("adding the same rule twice reports the second as a no-op")
    void addIsIdempotent() {
        assertNotNull(FoodExclusions.add(Source.CONFIG, "loot_n_explore:*"));
        assertNull(FoodExclusions.add(Source.CONFIG, "loot_n_explore:*"));
        // And so does the same rule written another way.
        assertNull(FoodExclusions.add(Source.CONFIG, "@loot_n_explore"));
    }

    @Test
    @DisplayName("a rule can be removed by any of its spellings")
    void removeAcceptsAnySpelling() {
        FoodExclusions.add(Source.CONFIG, "loot_n_explore:*");
        assertNotNull(FoodExclusions.remove(Source.CONFIG, "loot_n_explore"));
        assertFalse(FoodExclusions.canonicalEntries(Source.CONFIG).contains("loot_n_explore:*"));
    }

    @Test
    @DisplayName("a server's list replaces this client's own, and giving it back restores them")
    void remoteOverridesThenRestores() {
        FoodExclusions.loadFromConfig(List.of("minecraft:bread"), List.of(), List.of());

        assertTrue(FoodExclusions.applyRemote(List.of("othermod:*"), List.of()));
        assertTrue(FoodExclusions.isRemoteInForce());
        List<String> whileConnected = FoodExclusions.canonicalEntries();
        assertTrue(whileConnected.contains("othermod:*"));
        assertFalse(whileConnected.contains("minecraft:bread"),
                "the server decides what Florafare manages there");

        assertTrue(FoodExclusions.clearRemote());
        assertFalse(FoodExclusions.isRemoteInForce());
        assertTrue(FoodExclusions.canonicalEntries().contains("minecraft:bread"));
    }

    @Test
    @DisplayName("clearing an override that was never applied changes nothing")
    void clearRemoteWithoutRemoteIsANoOp() {
        assertFalse(FoodExclusions.clearRemote());
    }

    @Test
    @DisplayName("the built-in ignored tag is always in force and always listed")
    void builtInTagIsAlwaysPresent() {
        assertTrue(FoodExclusions.canonicalEntries().contains("#florafare:ignored"));
        // Including while a server's list is in force: the tag is synced by vanilla, not
        // by Florafare's packet, so it applies on both sides either way.
        FoodExclusions.applyRemote(List.of(), List.of());
        assertTrue(FoodExclusions.canonicalEntries().contains("#florafare:ignored"));
    }

    @Test
    @DisplayName("the allow-list is kept apart from the exclusions and listed on its own")
    void managedRulesAreTheirOwnList() {
        FoodExclusions.loadFromConfig(
                List.of("loot_n_explore:*"), List.of(), List.of("loot_n_explore:espresso"));

        assertEquals(List.of("loot_n_explore:*"),
                FoodExclusions.canonicalEntries(Effect.EXCLUDE, Source.CONFIG));
        assertEquals(List.of("loot_n_explore:espresso"),
                FoodExclusions.canonicalEntries(Effect.MANAGE, Source.CONFIG));
        // The exclusion list must not quietly absorb it — that is what "wins" means.
        assertFalse(FoodExclusions.canonicalEntries(Effect.EXCLUDE)
                .contains("loot_n_explore:espresso"));
    }

    @Test
    @DisplayName("the built-in ignored tag is an exclusion, never an allow-list rule")
    void builtInTagBelongsToTheExcludeSideOnly() {
        // Were it added to both, every item would be claimed back from itself and the
        // shipped defaults would silently do nothing at all.
        assertTrue(FoodExclusions.canonicalEntries(Effect.EXCLUDE).contains("#florafare:ignored"));
        assertFalse(FoodExclusions.canonicalEntries(Effect.MANAGE).contains("#florafare:ignored"));
    }

    @Test
    @DisplayName("a server sends both lists, and both are dropped on disconnect")
    void remoteCarriesBothDirections() {
        FoodExclusions.loadFromConfig(List.of(), List.of(), List.of("minecraft:bread"));

        FoodExclusions.applyRemote(List.of("othermod:*"), List.of("othermod:cake"));
        assertEquals(List.of("othermod:cake"), FoodExclusions.canonicalEntries(Effect.MANAGE));

        FoodExclusions.clearRemote();
        assertEquals(List.of("minecraft:bread"), FoodExclusions.canonicalEntries(Effect.MANAGE));
    }

    @Test
    @DisplayName("a server that sends no allow-list is read as having none, not as an error")
    void remoteWithoutManagedList() {
        // What a pre-1.5 server's packet decodes to. Its exclusions must stand as sent.
        assertTrue(FoodExclusions.applyRemote(List.of("othermod:*"), List.of()));
        assertTrue(FoodExclusions.canonicalEntries(Effect.MANAGE).isEmpty());
        assertTrue(FoodExclusions.canonicalEntries(Effect.EXCLUDE).contains("othermod:*"));
    }

    @Test
    @DisplayName("namespaces follow vanilla's character rule")
    void namespaceValidation() {
        assertTrue(FoodExclusions.isValidNamespace("loot_n_explore"));
        assertTrue(FoodExclusions.isValidNamespace("mod-1.2"));
        assertFalse(FoodExclusions.isValidNamespace("Loot"));
        assertFalse(FoodExclusions.isValidNamespace("mod id"));
        assertFalse(FoodExclusions.isValidNamespace(""));
    }
}
