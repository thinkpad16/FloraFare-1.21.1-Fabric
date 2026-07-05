package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

public record SynergyConfigSyncPayload(NbtCompound nbt) implements CustomPayload {

    public static final CustomPayload.Id<SynergyConfigSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "synergy_config_sync"));

    public static final PacketCodec<RegistryByteBuf, SynergyConfigSyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, SynergyConfigSyncPayload::nbt,
            SynergyConfigSyncPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}