package net.tend1tnuy.florafare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * Payload used to notify the client that a new food entry
 * has been unlocked in the culinary journal. Carries the discovered item's
 * id so the client can show which food it was in the discovery toast.
 */
public record FoodUnlockedPayload(String itemId) implements CustomPayload {

    public static final CustomPayload.Id<FoodUnlockedPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "food_unlocked"));

    public static final PacketCodec<RegistryByteBuf, FoodUnlockedPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, FoodUnlockedPayload::itemId,
            FoodUnlockedPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}