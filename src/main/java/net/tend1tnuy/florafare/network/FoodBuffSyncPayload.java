package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

public record FoodBuffSyncPayload(NbtCompound nbt) implements CustomPayload {
    public static final CustomPayload.Id<FoodBuffSyncPayload> ID = new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "food_buff_sync"));

    public static final PacketCodec<RegistryByteBuf, FoodBuffSyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, FoodBuffSyncPayload::nbt,
            FoodBuffSyncPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}