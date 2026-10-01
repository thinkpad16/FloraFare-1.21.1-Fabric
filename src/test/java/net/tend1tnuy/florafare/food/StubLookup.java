package net.tend1tnuy.florafare.food;

import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A pretend game for the listener tests: a handful of items (food or not), one effect,
 * one attribute, one potion, and whichever tags a test declares. The real registries
 * cannot be touched without bootstrapping Minecraft.
 */
final class StubLookup implements DatapackLookup {

    final Map<String, Boolean> items = new HashMap<>(Map.of(
            "minecraft:bread", true,
            "minecraft:apple", true,
            "minecraft:beef", true,
            "mypack:beef", true,
            "minecraft:stone", false,
            "minecraft:potion", false));
    final Set<String> effects    = new HashSet<>(Set.of("minecraft:speed"));
    final Set<String> attributes = new HashSet<>(Set.of("minecraft:generic.max_health"));
    final Set<String> potions    = new HashSet<>(Set.of("minecraft:strength"));
    final Set<String> mods       = new HashSet<>(Set.of("minecraft", "florafare", "mypack", "other"));
    final Map<String, TagState> tags = new HashMap<>();

    @Override public boolean itemExists(Identifier id)      { return items.containsKey(id.toString()); }
    @Override public boolean itemIsFood(Identifier id)      { return items.getOrDefault(id.toString(), false); }
    @Override public boolean potionExists(Identifier id)    { return potions.contains(id.toString()); }
    @Override public boolean effectExists(Identifier id)    { return effects.contains(id.toString()); }
    @Override public boolean attributeExists(Identifier id) { return attributes.contains(id.toString()); }
    @Override public boolean modLoaded(String namespace)    { return mods.contains(namespace); }

    @Override
    public TagState tagState(Identifier tagId) {
        return tags.getOrDefault(tagId.toString(), TagState.MISSING);
    }
}
