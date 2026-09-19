package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * Synchronizes the datapack-loaded food buff configurations from the server to the
 * client. The configs are normally only resolved server-side, but client features
 * (the food journal and the AppleSkin tooltip/HUD integration) need to know each
 * food's configured nutrition/saturation, so the whole config map is replicated as NBT.
 *
 * <p><b>Chunked.</b> A {@code CustomPayloadS2CPacket} is hard-capped at 1 MiB and the
 * failure mode is a dropped connection mid-encode, so a map too big for one packet used
 * to be refused outright — every client on a large pack then fell back to its own view,
 * with wrong tooltips and an empty journal. The map now travels as a numbered sequence
 * of packets instead: {@code index} counts from 0 and {@code total} says how many to
 * expect. Chunk 0 starts a fresh sequence and the last one applies it, so the client's
 * map is still replaced atomically. See {@link ChunkedNbtSync}.
 */
public record FoodConfigSyncPayload(NbtCompound nbt, int index, int total) implements CustomPayload {

    public static final CustomPayload.Id<FoodConfigSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "food_config_sync"));

    public static final PacketCodec<RegistryByteBuf, FoodConfigSyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, FoodConfigSyncPayload::nbt,
            PacketCodecs.VAR_INT,      FoodConfigSyncPayload::index,
            PacketCodecs.VAR_INT,      FoodConfigSyncPayload::total,
            FoodConfigSyncPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
