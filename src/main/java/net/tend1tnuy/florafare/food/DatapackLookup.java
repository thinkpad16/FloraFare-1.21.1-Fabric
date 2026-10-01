package net.tend1tnuy.florafare.food;

import net.minecraft.util.Identifier;

/**
 * The questions the datapack listeners ask the game while validating a file: does this
 * item, potion, effect or attribute exist, is the item food, is its mod installed, what
 * is in this tag.
 *
 * <p>An interface rather than direct {@code Registries} calls so the parsing and
 * conflict rules can be unit-tested. Touching {@code Registries} outside a bootstrapped
 * game throws, and that used to put every rule in the listeners — the file shapes, the
 * priority and tie handling, the diagnostics — out of reach of a test. {@link #LIVE} is
 * the real thing; tests hand in a stub.
 */
public interface DatapackLookup {

    boolean itemExists(Identifier id);

    /** Whether the item's default components include {@code minecraft:food}. Only asked of items that exist. */
    boolean itemIsFood(Identifier id);

    boolean potionExists(Identifier id);

    boolean effectExists(Identifier id);

    boolean attributeExists(Identifier id);

    /** Whether a mod with this id is loaded, so a target from an absent mod is not reported as a typo. */
    boolean modLoaded(String namespace);

    /**
     * Whether anything in the game uses this namespace: a loaded mod with that id, or at
     * least one registered item under it. Both are asked because a mod's id and the
     * namespace of its items do not have to match — and a rule naming "the items of mod
     * X" is about the namespace, not the mod id.
     */
    default boolean namespaceInUse(String namespace) {
        return modLoaded(namespace);
    }

    /** What an item tag holds right now, as far as {@link #tagState} can tell. */
    enum TagState { MISSING, EMPTY, PRESENT }

    /** Only meaningful once the reload has bound tags; see {@code FoodReloadListener#validateTags}. */
    TagState tagState(Identifier tagId);

    /**
     * The running game. Every method resolves {@code Registries} only when called, so
     * merely loading this interface in a test never bootstraps anything.
     */
    DatapackLookup LIVE = new DatapackLookup() {
        @Override
        public boolean itemExists(Identifier id) {
            return net.minecraft.registry.Registries.ITEM.containsId(id);
        }

        @Override
        public boolean itemIsFood(Identifier id) {
            return net.minecraft.registry.Registries.ITEM.get(id).getComponents()
                    .contains(net.minecraft.component.DataComponentTypes.FOOD);
        }

        @Override
        public boolean potionExists(Identifier id) {
            return net.minecraft.registry.Registries.POTION.containsId(id);
        }

        @Override
        public boolean effectExists(Identifier id) {
            return net.minecraft.registry.Registries.STATUS_EFFECT.containsId(id);
        }

        @Override
        public boolean attributeExists(Identifier id) {
            return net.minecraft.registry.Registries.ATTRIBUTE.containsId(id);
        }

        @Override
        public boolean modLoaded(String namespace) {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(namespace);
        }

        @Override
        public boolean namespaceInUse(String namespace) {
            if (modLoaded(namespace)) return true;
            for (Identifier id : net.minecraft.registry.Registries.ITEM.getIds()) {
                if (id.getNamespace().equals(namespace)) return true;
            }
            return false;
        }

        @Override
        public TagState tagState(Identifier tagId) {
            var list = net.minecraft.registry.Registries.ITEM.getEntryList(
                    net.minecraft.registry.tag.TagKey.of(
                            net.minecraft.registry.RegistryKeys.ITEM, tagId));
            if (list.isEmpty()) return TagState.MISSING;
            return list.get().size() == 0 ? TagState.EMPTY : TagState.PRESENT;
        }
    };
}
