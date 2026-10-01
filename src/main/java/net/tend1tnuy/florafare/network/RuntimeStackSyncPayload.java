package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * The {@code /florafare setbuff} stack buffs, as {@code uuid -> FoodBuffData} NBT.
 *
 * <p>These were the one kind of config never sent to clients, so on a dedicated server
 * every stamped stack's tooltip, journal row and hunger prediction showed the item's
 * ordinary buff — the client resolved the stack's id against an empty map and fell
 * through to the default.
 *
 * <p>Two shapes on one channel:
 *
 * <ul>
 *   <li>{@code full = true}: the whole map, as a numbered chunk sequence exactly like
 *       {@link FoodConfigSyncPayload} (a world can hold thousands of these). Sent on join
 *       and after a prune; the last chunk replaces the client's map.</li>
 *   <li>{@code full = false}: one or a few entries just created by {@code setbuff}, merged
 *       into the map as they arrive. {@code index}/{@code total} are 0 and 1.</li>
 * </ul>
 */
public record RuntimeStackSyncPayload(NbtCompound nbt, int index, int total, boolean full)
        implements CustomPayload {

    public static final CustomPayload.Id<RuntimeStackSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "runtime_stack_sync"));

    public static final PacketCodec<RegistryByteBuf, RuntimeStackSyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, RuntimeStackSyncPayload::nbt,
            PacketCodecs.VAR_INT,      RuntimeStackSyncPayload::index,
            PacketCodecs.VAR_INT,      RuntimeStackSyncPayload::total,
            PacketCodecs.BOOL,         RuntimeStackSyncPayload::full,
            RuntimeStackSyncPayload::new
    );

    /** Entries to merge into what the client already has. */
    public static RuntimeStackSyncPayload partial(NbtCompound entries) {
        return new RuntimeStackSyncPayload(entries, 0, 1, false);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
