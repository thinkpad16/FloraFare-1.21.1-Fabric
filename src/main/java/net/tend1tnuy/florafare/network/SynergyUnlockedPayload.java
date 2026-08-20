package net.tend1tnuy.florafare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * Notifies the client that a synergy has just been discovered, carrying its
 * id so the client can show which synergy it was in the discovery toast.
 */
public record SynergyUnlockedPayload(String synergyId) implements CustomPayload {
    public static final CustomPayload.Id<SynergyUnlockedPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "synergy_unlocked"));

    public static final PacketCodec<RegistryByteBuf, SynergyUnlockedPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SynergyUnlockedPayload::synergyId,
            SynergyUnlockedPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}