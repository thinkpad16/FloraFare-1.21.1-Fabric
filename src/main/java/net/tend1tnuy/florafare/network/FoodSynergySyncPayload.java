package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * Synchronizes the datapack-loaded food synergies from the server to the client.
 * Synergies are loaded from SERVER_DATA, so on a dedicated server the client's
 * {@code FoodSynergyManager} would otherwise stay empty and the HUD/journal
 * could not display synergy names, requirements, or effects.
 */
public record FoodSynergySyncPayload(NbtCompound nbt) implements CustomPayload {

    public static final CustomPayload.Id<FoodSynergySyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "food_synergy_sync"));

    public static final PacketCodec<RegistryByteBuf, FoodSynergySyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, FoodSynergySyncPayload::nbt,
            FoodSynergySyncPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
