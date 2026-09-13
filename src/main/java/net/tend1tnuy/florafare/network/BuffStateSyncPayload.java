package net.tend1tnuy.florafare.network;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.Florafare;

/**
 * The volatile half of a player's Florafare state — active buffs and active
 * synergies — sent whenever either of them changes.
 *
 * <p>Deliberately does <em>not</em> carry the journal discovery lists, which is the
 * whole point of it existing: those lists are the bulk of the state and change only
 * one entry at a time, so they travel on {@link FoodBuffSyncPayload} at join and on
 * {@link FoodUnlockedPayload} / {@link SynergyUnlockedPayload} afterwards. A buff
 * expiring therefore costs a few dozen bytes instead of the player's entire cookbook.
 */
public record BuffStateSyncPayload(NbtCompound nbt) implements CustomPayload {

    public static final CustomPayload.Id<BuffStateSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Florafare.MOD_ID, "buff_state_sync"));

    public static final PacketCodec<RegistryByteBuf, BuffStateSyncPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.NBT_COMPOUND, BuffStateSyncPayload::nbt,
            BuffStateSyncPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
