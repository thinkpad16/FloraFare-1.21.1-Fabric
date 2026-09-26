package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * One food config entry, changed on a live server by {@code /florafare edit}.
 *
 * <p>The counterpart to {@link FoodConfigSyncPayload}, which carries the whole map and is
 * what a client gets on join and after a {@code /reload}. Re-using that for a single edit
 * is what this exists to avoid: on a large modpack the map runs to hundreds of kilobytes
 * and is chunked across several packets, so one operator retuning one food re-sent all of
 * it to every player online — and an evening of balancing on a full server is the same
 * few hundred kilobytes again per edit, per player. An entry is a few dozen bytes.
 *
 * <p>{@code removed} is the other half of an edit: {@code /florafare edit … reset} can
 * leave a target with no entry at all, and a client that was only ever told about
 * additions would keep resolving foods against a definition the server has dropped.
 */
public record FoodConfigEntrySyncPayload(String target, NbtCompound data, boolean removed)
        implements CustomPayload {

    public static final CustomPayload.Id<FoodConfigEntrySyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "food_config_entry_sync"));

    public static final PacketCodec<RegistryByteBuf, FoodConfigEntrySyncPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING,       FoodConfigEntrySyncPayload::target,
                    PacketCodecs.NBT_COMPOUND, FoodConfigEntrySyncPayload::data,
                    PacketCodecs.BOOL,         FoodConfigEntrySyncPayload::removed,
                    FoodConfigEntrySyncPayload::new
            );

    /** The entry as it now stands on the server. */
    public static FoodConfigEntrySyncPayload of(String target, NbtCompound data) {
        return new FoodConfigEntrySyncPayload(target, data, false);
    }

    /** This target is no longer defined at all. */
    public static FoodConfigEntrySyncPayload removal(String target) {
        return new FoodConfigEntrySyncPayload(target, new NbtCompound(), true);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
