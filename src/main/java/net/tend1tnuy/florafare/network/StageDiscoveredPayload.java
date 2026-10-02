package net.tend1tnuy.florafare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * Tells the client that its player has just learned one more thing about a synergy's
 * stages: reached a stage ({@code "<synergyId>#<stage>"}) or suffered a stage's aftermath
 * ({@code "<synergyId>#<stage>/aftermath"}) — see {@code FoodSynergyData#stageKey}.
 *
 * <p>One string, for the same reason as {@link SynergyUnlockedPayload}: the discovered set
 * is durable state that only ever grows by one entry at a time, so it travels whole on the
 * full sync at join and as single entries after that, never on the per-tick buff state. No
 * toast — a drawback revealing itself is announced by the drawback.
 */
public record StageDiscoveredPayload(String key) implements CustomPayload {
    public static final CustomPayload.Id<StageDiscoveredPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "stage_discovered"));

    public static final PacketCodec<RegistryByteBuf, StageDiscoveredPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, StageDiscoveredPayload::key,
            StageDiscoveredPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
