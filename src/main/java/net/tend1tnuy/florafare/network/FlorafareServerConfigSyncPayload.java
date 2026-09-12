package net.tend1tnuy.florafare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * Synchronizes the server-authoritative subset of {@code FlorafareConfig} to clients,
 * so a dedicated server stays authoritative over gameplay-affecting settings instead of
 * clients silently falling back to their own local {@code florafare.json}. Purely
 * cosmetic client-local settings (HUD layout, toasts, tooltip stripping) are not
 * included here — they stay per-client.
 */
public record FlorafareServerConfigSyncPayload(
        int maxBuffSlots,
        int autoGenDurationMultiplier,
        double autoGenHealthMultiplier,
        boolean enableSynergies,
        boolean enableAlwaysEdibleOverride,
        boolean allowEatingWhenFull,
        boolean respectVanillaFoodEffects,
        boolean enableForgottenMead
) implements CustomPayload {

    public static final CustomPayload.Id<FlorafareServerConfigSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "server_config_sync"));

    // More than 6 fields, so PacketCodec.tuple's arity limit doesn't apply — encode/decode manually.
    public static final PacketCodec<RegistryByteBuf, FlorafareServerConfigSyncPayload> CODEC = PacketCodec.ofStatic(
            (buf, payload) -> {
                buf.writeVarInt(payload.maxBuffSlots());
                buf.writeVarInt(payload.autoGenDurationMultiplier());
                buf.writeDouble(payload.autoGenHealthMultiplier());
                buf.writeBoolean(payload.enableSynergies());
                buf.writeBoolean(payload.enableAlwaysEdibleOverride());
                buf.writeBoolean(payload.allowEatingWhenFull());
                buf.writeBoolean(payload.respectVanillaFoodEffects());
                buf.writeBoolean(payload.enableForgottenMead());
            },
            buf -> new FlorafareServerConfigSyncPayload(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readDouble(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean()
            )
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
