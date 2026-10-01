package net.tend1tnuy.florafare.command.dump;

import net.tend1tnuy.florafare.command.dump.RecipeGraph.Flag;
import net.tend1tnuy.florafare.command.dump.RecipeGraph.Node;
import net.tend1tnuy.florafare.command.dump.RecipeGraph.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The recipe shapes the old dump got wrong, on made-up recipe sets: storage crates, pies
 * cut into slices, another mod's alternative recipe, load-order ties, cycles and trees
 * that walked down into ores.
 */
class RecipeGraphTest {

    private static Slot item(String id, int count) {
        return new Slot(List.of(id), null, count);
    }

    private static Slot tag(String tag, String... choices) {
        return new Slot(List.of(choices), tag, 1);
    }

    private static Node recipe(String id, String type, String output, int count, Slot... slots) {
        return new Node(id, type, output, count, null, List.of(slots), null, null, score(type));
    }

    private static int score(String type) {
        return switch (RecipeGraph.shortType(type)) {
            case "crafting" -> 100;
            case "cooking" -> 90;
            case "smelting" -> 50;
            case "cutting" -> 25;
            default -> 10;
        };
    }

    @Test
    @DisplayName("a crate that unpacks into apples is storage, and never the apple's primary recipe")
    void storageCrate() {
        Node unpack = recipe("cratedelight:apple_from_crate", "minecraft:crafting", "minecraft:apple", 9,
                item("cratedelight:apple_crate", 1));
        Node pack = recipe("cratedelight:apple_crate", "minecraft:crafting", "cratedelight:apple_crate", 1,
                item("minecraft:apple", 9));
        RecipeGraph graph = new RecipeGraph(List.of(unpack, pack));

        assertTrue(graph.flags(unpack).contains(Flag.STORAGE));
        assertTrue(graph.flags(pack).contains(Flag.STORAGE));
        assertTrue(graph.flags(unpack).contains(Flag.REVERSIBLE));
        assertNull(graph.primary("minecraft:apple"), "an apple is not crafted; it is only unpacked");
        assertTrue(graph.tree("minecraft:apple", 3, id -> true).isEmpty());
    }

    @Test
    @DisplayName("a pie cut into slices it can also be made from is storage and decomposition; the pie keeps its real recipe")
    void pieSlices() {
        Node cut = recipe("farmersdelight:cutting/apple_pie", "farmersdelight:cutting",
                "farmersdelight:apple_pie_slice", 4, item("farmersdelight:apple_pie", 1));
        Node fromSlices = recipe("farmersdelight:apple_pie_from_slices", "minecraft:crafting",
                "farmersdelight:apple_pie", 1, item("farmersdelight:apple_pie_slice", 4));
        Node baked = recipe("farmersdelight:apple_pie", "minecraft:crafting", "farmersdelight:apple_pie", 1,
                item("minecraft:wheat", 3), item("minecraft:apple", 3), item("farmersdelight:pie_crust", 1));
        RecipeGraph graph = new RecipeGraph(List.of(cut, fromSlices, baked));

        assertTrue(graph.flags(cut).containsAll(Set.of(Flag.STORAGE, Flag.DECOMPOSITION)));
        assertTrue(graph.flags(fromSlices).contains(Flag.STORAGE));
        assertSame(baked, graph.primary("farmersdelight:apple_pie"));
        assertNull(graph.primary("farmersdelight:apple_pie_slice"));
    }

    @Test
    @DisplayName("1→N with no way back is a decomposition only, ranked after a real recipe of the same rank")
    void decompositionRanksLast() {
        Node cut = recipe("a:cut", "minecraft:crafting", "a:slice", 4, item("a:loaf", 1));
        Node loaf = recipe("a:loaf", "minecraft:crafting", "a:loaf", 1, item("a:slice", 1), item("a:egg", 1));
        Node direct = recipe("a:slice_direct", "minecraft:crafting", "a:slice", 1, item("a:dough", 2));
        RecipeGraph graph = new RecipeGraph(List.of(cut, loaf, direct));

        assertTrue(graph.flags(cut).contains(Flag.DECOMPOSITION));
        assertFalse(graph.flags(cut).contains(Flag.STORAGE), "the loaf takes more than slices");
        assertSame(direct, graph.primary("a:slice"));
    }

    @Test
    @DisplayName("the item's own namespace wins over another mod's alternative, whatever the type score")
    void ownNamespaceFirst() {
        Node vanilla = recipe("minecraft:bucket", "minecraft:smelting", "minecraft:bucket", 1,
                item("minecraft:iron_ingot", 3));
        Node modded = recipe("eternal_starlight:bucket_from_deepsilver", "minecraft:crafting",
                "minecraft:bucket", 1, item("eternal_starlight:deepsilver_ingot", 3));
        RecipeGraph graph = new RecipeGraph(List.of(modded, vanilla));
        assertSame(vanilla, graph.primary("minecraft:bucket"));
    }

    @Test
    @DisplayName("same namespace and score: the lower recipe id wins, whatever order they were loaded in")
    void deterministicTie() {
        Node b = recipe("mod:b_stew", "minecraft:crafting", "mod:stew", 1, item("mod:carrot", 1));
        Node a = recipe("mod:a_stew", "minecraft:crafting", "mod:stew", 1, item("mod:potato", 1));
        assertSame(a, new RecipeGraph(List.of(b, a)).primary("mod:stew"));
        assertSame(a, new RecipeGraph(List.of(a, b)).primary("mod:stew"));
        assertEquals(List.of(a, b), new RecipeGraph(List.of(b, a)).recipesFor("mod:stew"),
                "recipes are listed in id order");
    }

    @Test
    @DisplayName("a higher type score wins inside one namespace; self-referencing recipes never do")
    void scoreAndSelfReference() {
        Node smelted = recipe("mod:bread_smelt", "minecraft:smelting", "mod:bread", 1, item("mod:dough", 1));
        Node crafted = recipe("mod:bread_craft", "minecraft:crafting", "mod:bread", 1, item("mod:wheat", 3));
        Node refill = recipe("mod:bread_refill", "minecraft:crafting", "mod:bread", 2,
                item("mod:bread", 1), item("mod:wheat", 1));
        RecipeGraph graph = new RecipeGraph(List.of(smelted, refill, crafted));
        assertTrue(graph.flags(refill).contains(Flag.SELF_REFERENCE));
        assertSame(crafted, graph.primary("mod:bread"));
    }

    @Test
    @DisplayName("a 1:1 cook-and-uncook pair is reversible but not storage")
    void reversibleNotStorage() {
        Node cook = recipe("mod:cooked", "minecraft:smelting", "mod:cooked", 1, item("mod:raw", 1));
        Node raw = recipe("mod:raw", "minecraft:crafting", "mod:raw", 1, item("mod:cooked", 1));
        RecipeGraph graph = new RecipeGraph(List.of(cook, raw));
        assertTrue(graph.flags(cook).contains(Flag.REVERSIBLE));
        assertFalse(graph.flags(cook).contains(Flag.STORAGE));
        assertSame(cook, graph.primary("mod:cooked"));
    }

    @Test
    @DisplayName("the tree stops at maxDepth for non-food, follows food further, and marks cycles and repeats")
    void tree() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(recipe("mod:feast", "minecraft:crafting", "mod:feast", 1,
                item("mod:stew", 1), item("mod:bowl", 1), item("mod:stew", 1)));
        nodes.add(recipe("mod:stew", "minecraft:crafting", "mod:stew", 1, item("mod:broth", 1)));
        nodes.add(recipe("mod:broth", "minecraft:crafting", "mod:broth", 1, item("mod:bone", 1)));
        nodes.add(recipe("mod:bone", "minecraft:crafting", "mod:bone", 1, item("mod:ore", 1)));
        nodes.add(recipe("mod:ore", "minecraft:crafting", "mod:ore", 1, item("mod:rock", 1)));
        nodes.add(recipe("mod:bowl", "minecraft:crafting", "mod:bowl", 4, item("mod:plank", 3)));
        nodes.add(recipe("mod:plank", "minecraft:crafting", "mod:plank", 4, item("mod:log", 1)));
        nodes.add(recipe("mod:log", "minecraft:crafting", "mod:log", 1, item("mod:feast", 1)));
        RecipeGraph graph = new RecipeGraph(nodes);
        Set<String> foods = Set.of("mod:feast", "mod:stew", "mod:broth");

        List<String> depthOne = graph.tree("mod:feast", 1, foods::contains);
        assertTrue(depthOne.stream().anyMatch(line -> line.contains("mod:broth")),
                "food keeps being followed past maxDepth");
        assertTrue(depthOne.stream().noneMatch(line -> line.contains("mod:plank")),
                "non-food beyond maxDepth is a leaf");
        assertTrue(depthOne.stream().anyMatch(line -> line.contains("(expanded above)")),
                "the second stew is not expanded again");

        List<String> deep = graph.tree("mod:feast", 5, foods::contains);
        assertTrue(deep.stream().anyMatch(line -> line.contains("mod:feast") && line.contains("cycle")),
                "a log made from the feast is a cycle, not an infinite tree");
        assertTrue(graph.tree("mod:feast", 0, foods::contains).isEmpty());
    }

    @Test
    @DisplayName("slot labels: counts, tags and long choice lists")
    void labels() {
        assertEquals("3x minecraft:wheat", RecipeGraph.slotLabel(item("minecraft:wheat", 3)));
        assertEquals("#c:foods", RecipeGraph.slotLabel(tag("c:foods", "a:x", "a:y")));
        assertEquals("a:1 | a:2 | a:3 (+2 more)", RecipeGraph.slotLabel(
                new Slot(List.of("a:1", "a:2", "a:3", "a:4", "a:5"), null, 1)));
        assertNull(tag("c:foods", "a:x").single(), "a tag slot is never treated as one exact item");
    }
}
