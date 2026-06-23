package net.tend1tnuy.florafare.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * Payload used to instruct the client to open the Food Journal graphical user interface.
 */
public record OpenFoodJournalPayload() implements CustomPayload {

    public static final CustomPayload.Id<OpenFoodJournalPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "open_food_journal"));

    public static final PacketCodec<RegistryByteBuf, OpenFoodJournalPayload> CODEC =
            PacketCodec.unit(new OpenFoodJournalPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}