package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Sends the server's exclusion list — everything Florafare has been told to leave alone
 * — to the client, so client-side hooks (tooltip decoration and stripping, the journal,
 * AppleSkin's hunger preview, EMI's category) agree with the server about which items
 * the mod manages at all.
 *
 * <p>The list travels in canonical form: {@code "modid:item"}, {@code "modid:*"},
 * {@code "#namespace:tag"}. Sending the expanded item ids instead was not an option once
 * mod-wide rules existed — a client cannot expand {@code "modid:*"} back out of a list of
 * ids, and expanding it server-side would mean naming every food in a mod on every join.
 *
 * <p>Entries are written under a new key, and the old {@code ExcludedItems} key is still
 * written alongside it with just the item-id rules. A 1.4 client connecting to a 1.5
 * server therefore still gets its per-item exclusions rather than none at all, which is
 * the failure that would otherwise look like "the server and my tooltips disagree".
 */
public record FoodExclusionSyncPayload(NbtCompound nbt) implements CustomPayload {

    /** Pre-1.5: bare item ids only. Still written, for clients that only read this. */
    private static final String KEY_LEGACY_ITEMS = "ExcludedItems";

    /** Canonical rules — items, {@code modid:*} and {@code #tags} together. */
    private static final String KEY_RULES = "ExclusionRules";

    /**
     * Rules pointing the other way: manage this anyway. Needed client-side because the
     * {@code #florafare:ignored} tag is synced by vanilla and applies on both ends — so
     * a server that has claimed one item back out of it has to say so, or every client
     * keeps drawing that item as unmanaged.
     */
    private static final String KEY_MANAGED = "ManagedRules";

    public static final CustomPayload.Id<FoodExclusionSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "food_exclusion_sync"));

    public static final PacketCodec<RegistryByteBuf, FoodExclusionSyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, FoodExclusionSyncPayload::nbt,
            FoodExclusionSyncPayload::new
    );

    /**
     * @param excluded canonical exclusion rules, as
     *                 {@code FoodExclusions#canonicalEntries(Effect)} returns them
     * @param managed  canonical "manage this anyway" rules, which beat the exclusions
     */
    public static FoodExclusionSyncPayload of(Collection<String> excluded,
                                              Collection<String> managed) {
        NbtCompound nbt = new NbtCompound();

        NbtList all = new NbtList();
        NbtList legacyItems = new NbtList();
        for (String rule : excluded) {
            if (rule == null || rule.isBlank()) continue;
            all.add(NbtString.of(rule));
            if (!rule.startsWith("#") && !rule.endsWith(":*")) legacyItems.add(NbtString.of(rule));
        }

        NbtList managedList = new NbtList();
        for (String rule : managed) {
            if (rule == null || rule.isBlank()) continue;
            managedList.add(NbtString.of(rule));
        }

        nbt.put(KEY_RULES, all);
        nbt.put(KEY_MANAGED, managedList);
        nbt.put(KEY_LEGACY_ITEMS, legacyItems);
        return new FoodExclusionSyncPayload(nbt);
    }

    /**
     * The rules this packet carries, newest key preferred.
     *
     * <p>Falling back to the legacy key matters in the other direction: a 1.5 client on a
     * 1.4 server reads the only list that server knows how to send, instead of deciding
     * the server excludes nothing.
     */
    public List<String> rules() {
        String key = nbt.contains(KEY_RULES, NbtElement.LIST_TYPE) ? KEY_RULES : KEY_LEGACY_ITEMS;
        return readList(key);
    }

    /**
     * The "manage this anyway" rules. Empty for a packet from a server that predates
     * them, which is the right reading: such a server has no way to claim anything back,
     * so its exclusions stand exactly as sent.
     */
    public List<String> managedRules() {
        return readList(KEY_MANAGED);
    }

    private List<String> readList(String key) {
        List<String> result = new ArrayList<>();
        if (!nbt.contains(key, NbtElement.LIST_TYPE)) return result;
        NbtList list = nbt.getList(key, NbtElement.STRING_TYPE);
        for (int i = 0; i < list.size(); i++) result.add(list.getString(i));
        return result;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
