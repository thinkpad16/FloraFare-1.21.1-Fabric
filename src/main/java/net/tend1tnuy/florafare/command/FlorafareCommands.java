package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

/**
 * Builds the one {@code /florafare} command tree.
 *
 * <p>The tree used to be registered twice — once by {@code DumpFoodsCommand}, once by
 * {@code SetBuffCommand} — each with its own {@code .requires}. Brigadier merges a second
 * literal of the same name into the node the first one created and keeps the first one's
 * requirement, so the second registration's gate silently did nothing, and which gate was
 * in force depended on the order two callbacks happened to run in. Now there is a single
 * root, admitting anyone who holds either permission level, and every subcommand states
 * the level it needs on its own node; see {@link CommandPermissions}.
 *
 * <p>{@code /florafarehelp} is still registered on its own by {@link HelpCommand}, with no
 * requirement at all, because a child can never loosen its parent's gate.
 */
public final class FlorafareCommands {

    private FlorafareCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("florafare")
                .requires(CommandPermissions::canEnterRoot);

        SetBuffCommand.attach(root, registryAccess);
        root.then(ValidateCommand.branch());
        root.then(DumpFoodsCommand.branch());

        dispatcher.register(root);
    }
}
