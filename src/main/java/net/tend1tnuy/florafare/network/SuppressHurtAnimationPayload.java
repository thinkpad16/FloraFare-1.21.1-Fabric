package net.tend1tnuy.florafare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * Sent right before the server clamps a player's health down to a lowered
 * max health (buff expiry or a negative health_bonus). The health drop is
 * administrative, not damage, so the client should skip the hurt
 * flash/camera-tilt animation for the health update that follows.
 */
public record SuppressHurtAnimationPayload() implements CustomPayload {

    public static final CustomPayload.Id<SuppressHurtAnimationPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "suppress_hurt_animation"));

    public static final PacketCodec<RegistryByteBuf, SuppressHurtAnimationPayload> CODEC =
            PacketCodec.unit(new SuppressHurtAnimationPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
