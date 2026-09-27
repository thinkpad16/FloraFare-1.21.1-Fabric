package net.tend1tnuy.florafare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * The client asking for its Florafare state back. The mod's only C2S packet.
 *
 * <p>Everything the overlay, the journal and the food tooltips read lives on the client's
 * {@code PlayerFoodComponent}, which hangs off the {@code ClientPlayerEntity} — and
 * vanilla throws that entity away and builds a new one every time it handles a
 * {@code PlayerRespawnS2CPacket}. Dying is one of those; so is every dimension change,
 * every {@code /execute in}, and every return through an end portal. The replacement
 * component comes up empty, so until something refills it the player has no buffs in the
 * overlay, an entirely locked journal and placeholder tooltips — while the server still
 * holds all of it and is still enforcing the full buff slots.
 *
 * <p>The server pushes a full sync on each of those paths ({@code AFTER_RESPAWN} and
 * {@code AFTER_PLAYER_CHANGE_WORLD}), which is the actual fix; this packet is the safety
 * net under it. The client watches for its own player entity being swapped and asks for
 * the state back if no sync has arrived shortly after — so a path nobody anticipated, a
 * push that raced a reconnect, or an initial sync that was dropped because the channel
 * handshake had not finished, all repair themselves instead of leaving the player looking
 * at state that silently stopped matching the server.
 *
 * <p>Carries nothing: the request <em>is</em> the message, and the only state a player may
 * ask for is their own, which the server reads off the connection rather than the packet.
 */
public record RequestStateSyncPayload() implements CustomPayload {

    public static final CustomPayload.Id<RequestStateSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "request_state_sync"));

    public static final PacketCodec<RegistryByteBuf, RequestStateSyncPayload> CODEC =
            PacketCodec.unit(new RequestStateSyncPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
