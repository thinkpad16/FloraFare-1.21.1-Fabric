package net.tend1tnuy.florafare.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens up vanilla's per-player save so journal progress can be flushed on its own
 * schedule instead of waiting for the world autosave.
 *
 * <p>Vanilla persists players only every autosave interval — five minutes by default —
 * and on a clean disconnect. A server that is killed, OOMs, or hard-crashes therefore
 * takes every discovery made since the last autosave with it, which on a busy server is
 * the difference between "we crashed" and "we crashed and everyone lost an evening of
 * journal progress".
 *
 * <p>{@code savePlayerData} is protected, so an invoker is the way to reach it. It is the
 * right method to call rather than the save handler underneath it: it also flushes the
 * player's statistics and advancements, which is exactly what should be durable too.
 */
@Mixin(PlayerManager.class)
public interface PlayerManagerInvoker {

    @Invoker("savePlayerData")
    void florafare$savePlayerData(ServerPlayerEntity player);
}
