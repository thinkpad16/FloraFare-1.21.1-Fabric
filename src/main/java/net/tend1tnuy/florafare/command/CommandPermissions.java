package net.tend1tnuy.florafare.command;

import net.minecraft.server.command.ServerCommandSource;
import net.tend1tnuy.florafare.config.FlorafareConfig;

/**
 * Who may run which part of {@code /florafare}.
 *
 * <p>There used to be no such split, and not even the one gate there was worked as
 * written: {@code DumpFoodsCommand} and {@code SetBuffCommand} each registered their own
 * {@code florafare} literal with its own {@code requires}, and Brigadier merges a second
 * same-named literal into the first one's node while keeping the <em>first</em> one's
 * requirement. Which of the two that was depended on registration order. There is now one
 * tree ({@link FlorafareCommands}) whose root admits anyone holding either level, and each
 * subcommand carries the level it actually needs:
 *
 * <ul>
 *   <li>{@link #canRead} — {@code commandPermissionLevel}, default 2: the commands that
 *       look at things (help, buffs, explain, validate) and the per-player gameplay
 *       tools command blocks are commonly built around (journal, buff give/remove,
 *       clear).</li>
 *   <li>{@link #canWrite} — {@code writeCommandPermissionLevel}, default 3: the ones that
 *       change the rules for everybody or write files on the server (setbuff, edit, ignore
 *       add/remove, repair, dumpfoods, validate file).</li>
 * </ul>
 *
 * <p>Both levels are read from the config on every check, so a {@code /reload} that
 * re-reads {@code florafare.json} applies a new level at once; vanilla re-sends every
 * player's command tree at the end of a reload, so their tab-completion follows too.
 */
public final class CommandPermissions {

    private CommandPermissions() {}

    /** The read level, as configured. */
    public static int readLevel() {
        return FlorafareConfig.commandPermissionLevel;
    }

    /** The write level, as configured. */
    public static int writeLevel() {
        return FlorafareConfig.writeCommandPermissionLevel;
    }

    /** May run the read-only subcommands. */
    public static boolean canRead(ServerCommandSource source) {
        return source.hasPermissionLevel(readLevel());
    }

    /** May run the subcommands that change shared state or write files. */
    public static boolean canWrite(ServerCommandSource source) {
        return source.hasPermissionLevel(writeLevel());
    }

    /**
     * The root's own requirement: holding either level. A Brigadier child can only ever
     * narrow its parent's requirement, never widen it, so the root has to admit the
     * looser of the two and leave the rest to the children.
     */
    public static boolean canEnterRoot(ServerCommandSource source) {
        return canRead(source) || canWrite(source);
    }
}
