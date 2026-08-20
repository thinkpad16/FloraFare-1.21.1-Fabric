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

import java.util.HashSet;
import java.util.Set;

/**
 * Synchronizes the set of item ids Florafare has been told to fully ignore
 * (see {@code FlorafareAPI#excludeFood}) from server to client, so client-side
 * hooks (tooltip stripping, hunger prediction) stay consistent with the
 * server's authoritative decision to leave these items untouched.
 */
public record FoodExclusionSyncPayload(NbtCompound nbt) implements CustomPayload {

    private static final String KEY_ITEMS = "ExcludedItems";

    public static final CustomPayload.Id<FoodExclusionSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "food_exclusion_sync"));

    public static final PacketCodec<RegistryByteBuf, FoodExclusionSyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, FoodExclusionSyncPayload::nbt,
            FoodExclusionSyncPayload::new
    );

    public static FoodExclusionSyncPayload of(Set<String> excludedItems) {
        NbtCompound nbt = new NbtCompound();
        NbtList list = new NbtList();
        for (String id : excludedItems) list.add(NbtString.of(id));
        nbt.put(KEY_ITEMS, list);
        return new FoodExclusionSyncPayload(nbt);
    }

    public Set<String> toSet() {
        Set<String> result = new HashSet<>();
        if (nbt.contains(KEY_ITEMS, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(KEY_ITEMS, NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) result.add(list.getString(i));
        }
        return result;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
