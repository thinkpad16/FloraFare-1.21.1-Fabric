package net.tend1tnuy.florafare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

public record SynergyUnlockedPayload() implements CustomPayload {
    public static final CustomPayload.Id<SynergyUnlockedPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "synergy_unlocked"));

    public static final PacketCodec<RegistryByteBuf, SynergyUnlockedPayload> CODEC =
            PacketCodec.unit(new SynergyUnlockedPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}