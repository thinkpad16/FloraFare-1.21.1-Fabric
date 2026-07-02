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
 */
public record FoodConfigSyncPayload(NbtCompound nbt) implements CustomPayload {

    public static final CustomPayload.Id<FoodConfigSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "food_config_sync"));

    public static final PacketCodec<RegistryByteBuf, FoodConfigSyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, FoodConfigSyncPayload::nbt,
            FoodConfigSyncPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
