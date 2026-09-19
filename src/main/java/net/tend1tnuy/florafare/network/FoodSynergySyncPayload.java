package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * Synchronizes the datapack-loaded food synergies from the server to the client.
 * Synergies are loaded from SERVER_DATA, so on a dedicated server the client's
 * {@code FoodSynergyManager} would otherwise stay empty and the HUD/journal
 * could not display synergy names, requirements, or effects.
 *
 * <p><b>Chunked.</b> A {@code CustomPayloadS2CPacket} is hard-capped at 1 MiB and the
 * failure mode is a dropped connection mid-encode, so a map too big for one packet used
 * to be refused outright — every client on a large pack then fell back to its own view,
 * with wrong tooltips and an empty journal. The map now travels as a numbered sequence
 * of packets instead: {@code index} counts from 0 and {@code total} says how many to
 * expect. Chunk 0 starts a fresh sequence and the last one applies it, so the client's
 * map is still replaced atomically. See {@link ChunkedNbtSync}.
 */
public record FoodSynergySyncPayload(NbtCompound nbt, int index, int total) implements CustomPayload {

    public static final CustomPayload.Id<FoodSynergySyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "food_synergy_sync"));

    public static final PacketCodec<RegistryByteBuf, FoodSynergySyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, FoodSynergySyncPayload::nbt,
            PacketCodecs.VAR_INT,      FoodSynergySyncPayload::index,
            PacketCodecs.VAR_INT,      FoodSynergySyncPayload::total,
            FoodSynergySyncPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
