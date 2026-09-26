package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.config.FlorafareConfig;

import java.util.List;
import java.util.Locale;

/**
 * {@code /florafarehelp} — the guide, in game.
 *
 * <p>Florafare's rules are mostly invisible while playing: how many buffs fit at once,
 * what a synergy needs, why a bite sometimes grants nothing. All of it is in the README,
 * which is exactly where a player on someone else's server is not looking. So it is here
 * too, short enough to read in chat and split into topics rather than printed as one wall.
 *
 * <h2>Two entry points, deliberately</h2>
 * The whole {@code /florafare} tree sits behind {@code commandPermissionLevel} (op by
 * default), which is right for everything that changes state and wrong for a help text.
 * A Brigadier child cannot loosen its parent's requirement, so the guide is registered
 * twice: as {@code /florafare help} inside the gated tree, where an operator will look for
 * it, and as {@code /florafarehelp} at the root with no requirement at all, which is the
 * one an ordinary player can actually run.
 *
 * <h2>What each side sees</h2>
 * The player-facing topics are listed for everybody. The three that only an operator can
 * act on — {@code edit}, {@code ignore}, {@code admin} — are listed, suggested and
 * printed only above {@code commandPermissionLevel}, so a player's topic list contains
 * nothing they cannot use.
 */
public final class HelpCommand {

    private HelpCommand() {}

    /** A topic, and who is allowed to read it. */
    private record Topic(String name, boolean operatorOnly, int lines) {}

    private static final List<Topic> TOPICS = List.of(
            new Topic("buffs",        false, 6),
            new Topic("journal",      false, 4),
            new Topic("synergies",    false, 4),
            new Topic("troubleshoot", false, 6),
            new Topic("edit",         true,  7),
            new Topic("ignore",       true,  5),
            new Topic("admin",        true,  6)
    );

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        // No .requires: the guide is text, and the player who most needs it is the one
        // who cannot run anything else in this mod.
        dispatcher.register(CommandManager.literal("florafarehelp")
                .executes(HelpCommand::executeOverview)
                .then(topicArgument()));
    }

    /** The {@code help} subtree, for {@code SetBuffCommand} to hang off {@code /florafare}. */
    public static ArgumentBuilder<ServerCommandSource, ?> branch() {
        return CommandManager.literal("help")
                .executes(HelpCommand::executeOverview)
                .then(topicArgument());
    }

    private static ArgumentBuilder<ServerCommandSource, ?> topicArgument() {
        return CommandManager.argument("topic",
                        com.mojang.brigadier.arguments.StringArgumentType.word())
                .suggests((context, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                    for (Topic topic : TOPICS) {
                        if (topic.operatorOnly() && !isOperator(context.getSource())) continue;
                        if (net.minecraft.command.CommandSource.shouldSuggest(remaining, topic.name())) {
                            builder.suggest(topic.name());
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(HelpCommand::executeTopic);
    }

    private static boolean isOperator(ServerCommandSource source) {
        return source.hasPermissionLevel(FlorafareConfig.commandPermissionLevel);
    }

    // -------------------------------------------------------------------------
    // OUTPUT
    // -------------------------------------------------------------------------

    private static int executeOverview(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        source.sendFeedback(() -> Text.translatable("command.florafare.help.title")
                .formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> Text.translatable("command.florafare.help.intro",
                PlayerFoodComponent.getMaxBuffSlots()).formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.empty(), false);

        for (Topic topic : TOPICS) {
            if (topic.operatorOnly() && !isOperator(source)) continue;
            source.sendFeedback(() -> topicLink(topic), false);
        }
        return 1;
    }

    /**
     * One line of the topic list: the name, what it covers, and a click that types the
     * command out. Clickable because the alternative is asking a player to retype
     * "/florafarehelp troubleshoot" from memory, which is how a guide goes unread.
     */
    private static MutableText topicLink(Topic topic) {
        MutableText name = Text.literal(topic.name())
                .formatted(Formatting.YELLOW)
                .styled(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/florafarehelp " + topic.name()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.translatable("command.florafare.help.click"))));

        return Text.literal(" • ").formatted(Formatting.DARK_GRAY)
                .append(name)
                .append(Text.literal(" — ").formatted(Formatting.DARK_GRAY))
                .append(Text.translatable("command.florafare.help." + topic.name() + ".short")
                        .formatted(Formatting.GRAY));
    }

    private static int executeTopic(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String requested = com.mojang.brigadier.arguments.StringArgumentType
                .getString(context, "topic").toLowerCase(Locale.ROOT);

        Topic topic = null;
        for (Topic candidate : TOPICS) {
            if (candidate.name().equals(requested)) topic = candidate;
        }

        // An operator-only topic is reported as unknown rather than as forbidden: the
        // topic list a player was shown did not contain it, so "no such topic" is both
        // true from where they are standing and the less confusing of the two answers.
        if (topic == null || (topic.operatorOnly() && !isOperator(source))) {
            source.sendError(Text.translatable("command.florafare.help.unknown_topic", requested));
            return 0;
        }

        Topic shown = topic;
        source.sendFeedback(() -> Text.translatable(
                "command.florafare.help." + shown.name() + ".title").formatted(Formatting.GOLD), false);
        for (int i = 1; i <= shown.lines(); i++) {
            final int line = i;
            source.sendFeedback(() -> Text.translatable(
                            "command.florafare.help." + shown.name() + ".l" + line)
                    .formatted(Formatting.GRAY), false);
        }
        return 1;
    }
}
