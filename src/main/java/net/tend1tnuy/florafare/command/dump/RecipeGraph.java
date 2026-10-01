package net.tend1tnuy.florafare.command.dump;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Every recipe in the game as plain ids, indexed by what it makes, with the analysis
 * {@code /florafare dumpfoods} needs: which recipes merely pack or unpack an item, which
 * one is the "real" way to make it, and the crafting tree that follows from that.
 *
 * <p>Deliberately free of game types so the rules are unit-tested on made-up recipe sets.
 * {@link FoodDumpCollector} turns the live recipe manager into {@link Node}s.
 *
 * <h2>Why the analysis exists</h2>
 * The old dump kept one recipe per item, the one whose recipe <em>type</em> scored best,
 * with ties going to whichever loaded first. In a modpack that picked the wrong one all
 * the time: an apple came out "crafted from {@code cratedelight:apple_crate}", a pie slice
 * "from the whole pie", a bucket "from {@code eternal_starlight:deepsilver_ingot}" — and
 * the tree then dutifully walked crate → apples → crate, or down into ores. The flags
 * below recognise those shapes, and {@link #primary} never picks a storage recipe.
 */
public final class RecipeGraph {

    /**
     * One ingredient slot, merged: identical slots of one recipe are folded into one with
     * their counts added, so "3 wheat" is one slot of count 3, not three slots.
     *
     * @param choices the items that satisfy it, in the game's order, without duplicates
     * @param tag     the item tag it was written as, when that could be recovered
     * @param count   how many it takes
     */
    public record Slot(List<String> choices, @Nullable String tag, int count) {
        public Slot {
            choices = List.copyOf(new LinkedHashSet<>(choices));
            count = Math.max(1, count);
        }

        /** The one item this slot takes, or null when several items (or a tag) satisfy it. */
        public @Nullable String single() {
            return tag == null && choices.size() == 1 ? choices.get(0) : null;
        }
    }

    /**
     * One way to make one item. A recipe with several outputs (a cutting board's main
     * result plus a chance of bone meal, say) becomes one node per output.
     *
     * @param typeScore how strongly this recipe type suggests "the way it is made"; see
     *                  {@code FoodDumpCollector#scoreRecipeType}
     */
    public record Node(String id, String type, String output, int outputCount, @Nullable Float chance,
                       List<Slot> slots, @Nullable String container, @Nullable String tool,
                       int typeScore) {
        public Node {
            slots = List.copyOf(slots);
            outputCount = Math.max(1, outputCount);
        }

        /** Unique per node: a recipe id alone repeats across a multi-output recipe. */
        public String key() {
            return id + " -> " + output;
        }

        /** The namespace of the recipe's own id ({@code minecraft} for {@code minecraft:bread}). */
        public String recipeNamespace() {
            int colon = id.indexOf(':');
            return colon < 0 ? "minecraft" : id.substring(0, colon);
        }
    }

    /** What a recipe was recognised as. */
    public enum Flag {
        /**
         * Packing or unpacking: a single-item recipe whose input is itself made, by another
         * single-item recipe, from this one's output — crate ↔ apples, block ↔ ingots,
         * whole pie ↔ slices — with at least one side multiplying. Never primary.
         */
        STORAGE,
        /** Its output is an ingredient of a recipe for one of its own ingredients: a 2-cycle. */
        REVERSIBLE,
        /**
         * One item in, several of the output out, and the output is an ingredient of some
         * recipe for that input: the whole pie cut into the slices it can also be made of.
         * Ranked after every other candidate.
         */
        DECOMPOSITION,
        /** The output is one of its own ingredients (dyeing, repairing, refilling). Never primary. */
        SELF_REFERENCE
    }

    /** Hard ceiling for food chains followed past {@code maxDepth}; see {@link #tree}. */
    public static final int EXTRA_FOOD_DEPTH = 4;

    private final Map<String, List<Node>> byOutput = new HashMap<>();
    private final Map<String, Set<Flag>> flags = new HashMap<>();
    private final Map<String, Optional<Node>> primaryCache = new HashMap<>();
    private final int size;

    public RecipeGraph(Collection<Node> nodes) {
        for (Node node : nodes) {
            byOutput.computeIfAbsent(node.output(), k -> new ArrayList<>()).add(node);
        }
        // Recipe id order, never load order: the same pack must dump the same file.
        Comparator<Node> byId = Comparator.comparing(Node::id).thenComparing(Node::output);
        for (List<Node> list : byOutput.values()) list.sort(byId);
        this.size = nodes.size();

        for (List<Node> list : byOutput.values()) {
            for (Node node : list) flags.put(node.key(), computeFlags(node));
        }
    }

    /** How many nodes the graph was built from. */
    public int size() {
        return size;
    }

    /** Every recipe making this item, in recipe id order. */
    public List<Node> recipesFor(String item) {
        return Collections.unmodifiableList(byOutput.getOrDefault(item, List.of()));
    }

    /** What a node was recognised as; empty for an ordinary recipe. */
    public Set<Flag> flags(Node node) {
        Set<Flag> found = flags.get(node.key());
        return found == null ? Set.of() : Collections.unmodifiableSet(found);
    }

    // -------------------------------------------------------------------------
    // FLAGS
    // -------------------------------------------------------------------------

    /** The one item every slot takes, or null if the recipe takes more than one kind of item. */
    static @Nullable String singleInput(Node node) {
        String only = null;
        for (Slot slot : node.slots()) {
            String item = slot.single();
            if (item == null) return null;
            if (only == null) only = item;
            else if (!only.equals(item)) return null;
        }
        return only;
    }

    /** How many items the recipe consumes in total. */
    static int inputCount(Node node) {
        int total = 0;
        for (Slot slot : node.slots()) total += slot.count();
        return total;
    }

    /**
     * The items the recipe certainly consumes: the slots with exactly one choice. A slot
     * satisfied by a tag ({@code #c:foods}) is left out — counting it would make nearly
     * every recipe in a modpack part of a cycle with nearly every other.
     */
    static Set<String> exactInputs(Node node) {
        Set<String> items = new HashSet<>();
        for (Slot slot : node.slots()) {
            String item = slot.single();
            if (item != null) items.add(item);
        }
        return items;
    }

    private Set<Flag> computeFlags(Node node) {
        Set<Flag> found = EnumSet.noneOf(Flag.class);

        for (Slot slot : node.slots()) {
            if (slot.choices().contains(node.output())) {
                found.add(Flag.SELF_REFERENCE);
                break;
            }
        }

        Set<String> inputs = exactInputs(node);
        for (String input : inputs) {
            if (input.equals(node.output())) continue;
            for (Node back : byOutput.getOrDefault(input, List.of())) {
                if (exactInputs(back).contains(node.output())) {
                    found.add(Flag.REVERSIBLE);
                    break;
                }
            }
        }

        String single = singleInput(node);
        if (single != null && !single.equals(node.output())) {
            for (Node back : byOutput.getOrDefault(single, List.of())) {
                if (!node.output().equals(singleInput(back))) continue;
                // Packing multiplies on one side or the other; a 1:1 pair (smelt and
                // un-smelt) is reversible but stores nothing.
                if (inputCount(node) >= 2 || node.outputCount() >= 2
                        || inputCount(back) >= 2 || back.outputCount() >= 2) {
                    found.add(Flag.STORAGE);
                    break;
                }
            }
            if (inputCount(node) == 1 && node.outputCount() >= 2) {
                for (Node back : byOutput.getOrDefault(single, List.of())) {
                    if (exactInputs(back).contains(node.output())) {
                        found.add(Flag.DECOMPOSITION);
                        break;
                    }
                }
            }
        }
        return found;
    }

    // -------------------------------------------------------------------------
    // PRIMARY RECIPE
    // -------------------------------------------------------------------------

    /**
     * The recipe that best answers "how is this made", or null if it is only ever
     * unpacked from storage (or has no recipe at all).
     *
     * <p>Storage and self-referencing recipes are never chosen. Of the rest: a recipe from
     * the item's own namespace first (a bucket is {@code minecraft:bucket}, not another
     * mod's alternative), then the higher type score, then anything but a decomposition,
     * then the recipe id — so the answer never depends on load order.
     */
    public @Nullable Node primary(String item) {
        return primaryCache.computeIfAbsent(item, this::choosePrimary).orElse(null);
    }

    private Optional<Node> choosePrimary(String item) {
        String namespace = namespaceOf(item);
        Node best = null;
        for (Node node : byOutput.getOrDefault(item, List.of())) {
            Set<Flag> nodeFlags = flags(node);
            if (nodeFlags.contains(Flag.STORAGE) || nodeFlags.contains(Flag.SELF_REFERENCE)) continue;
            if (best == null || isBetterPrimary(node, best, namespace)) best = node;
        }
        return Optional.ofNullable(best);
    }

    private boolean isBetterPrimary(Node candidate, Node current, String namespace) {
        boolean candidateOwn = candidate.recipeNamespace().equals(namespace);
        boolean currentOwn = current.recipeNamespace().equals(namespace);
        if (candidateOwn != currentOwn) return candidateOwn;
        if (candidate.typeScore() != current.typeScore()) return candidate.typeScore() > current.typeScore();
        boolean candidateSplits = flags(candidate).contains(Flag.DECOMPOSITION);
        boolean currentSplits = flags(current).contains(Flag.DECOMPOSITION);
        if (candidateSplits != currentSplits) return !candidateSplits;
        int byId = candidate.id().compareTo(current.id());
        return byId != 0 ? byId < 0 : candidate.output().compareTo(current.output()) < 0;
    }

    static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    // -------------------------------------------------------------------------
    // TREE
    // -------------------------------------------------------------------------

    /**
     * The crafting tree below one item, following primary recipes only.
     *
     * <p>The root's own ingredients are level 1. An ingredient at a level below
     * {@code maxDepth} is expanded; past it only food is, up to {@link #EXTRA_FOOD_DEPTH}
     * further levels, so a chain of dishes is shown whole while a non-food intermediate —
     * an ingot, a plank — stops being a branch and becomes a leaf instead of a walk down
     * to the ore. An ingredient that is one of its own ancestors is marked as a cycle and
     * not followed; one already expanded elsewhere in the same tree is marked and not
     * repeated. Slots satisfied by a tag or by several items are leaves.
     *
     * @return the lines, empty when the item has no primary recipe or {@code maxDepth} is 0
     */
    public List<String> tree(String root, int maxDepth, Predicate<String> isFood) {
        List<String> lines = new ArrayList<>();
        if (maxDepth <= 0 || primary(root) == null) return lines;
        Set<String> path = new HashSet<>();
        path.add(root);
        expand(root, 1, Math.max(0, maxDepth), isFood, path, new HashSet<>(), "", lines);
        return lines;
    }

    private void expand(String item, int level, int maxDepth, Predicate<String> isFood,
                        Set<String> path, Set<String> expanded, String indent, List<String> lines) {
        Node recipe = primary(item);
        if (recipe == null) return;
        List<Slot> slots = recipe.slots();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            boolean last = i == slots.size() - 1;
            String child = slot.single();
            Node childRecipe = child == null ? null : primary(child);
            String note = "";
            boolean follow = childRecipe != null;

            if (follow && path.contains(child)) {
                note = " (cycle, not followed)";
                follow = false;
            } else if (follow && level >= maxDepth
                    && (!isFood.test(child) || level >= maxDepth + EXTRA_FOOD_DEPTH)) {
                follow = false;
            } else if (follow && expanded.contains(child)) {
                note = " (expanded above)";
                follow = false;
            }

            StringBuilder line = new StringBuilder(indent).append(last ? "└─ " : "├─ ")
                    .append(slotLabel(slot));
            if (follow) {
                line.append(" [").append(shortType(childRecipe.type())).append(']');
                if (childRecipe.outputCount() > 1) line.append(" makes ").append(childRecipe.outputCount());
            }
            lines.add(line.append(note).toString());

            if (follow) {
                expanded.add(child);
                path.add(child);
                expand(child, level + 1, maxDepth, isFood, path, expanded,
                        indent + (last ? "   " : "│  "), lines);
                path.remove(child);
            }
        }
    }

    /** {@code 2x minecraft:wheat}, {@code #c:grains}, {@code a | b | c (+4 more)}. */
    public static String slotLabel(Slot slot) {
        String prefix = slot.count() > 1 ? slot.count() + "x " : "";
        if (slot.tag() != null) return prefix + "#" + slot.tag();
        if (slot.choices().size() <= 3) return prefix + String.join(" | ", slot.choices());
        return prefix + String.join(" | ", slot.choices().subList(0, 3))
                + " (+" + (slot.choices().size() - 3) + " more)";
    }

    /** The path of a recipe type id: {@code crafting} for {@code minecraft:crafting}. */
    public static String shortType(String type) {
        int colon = type.indexOf(':');
        return colon < 0 ? type : type.substring(colon + 1);
    }
}
