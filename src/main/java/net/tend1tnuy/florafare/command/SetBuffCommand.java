package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.component.PlayerFoodComponent;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Registers and handles all Florafare server commands.
 */
public final class SetBuffCommand {

    private static final String UNIQUE_BUFF_NBT_KEY = "FlorafareBuffId";

    private SetBuffCommand() {
    }

    /**
     * Registers all Florafare commands.
     *
     * @param dispatcher command dispatcher
     * @param registryAccess registry access instance
     * @param environment command registration environment
     */
    public static void register(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        dispatcher.register(
                CommandManager.literal("florafare")

                        .then(CommandManager.literal("setbuff")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.argument(
                                                        "duration",
                                                        IntegerArgumentType.integer(1)
                                                )
                                                .then(CommandManager.argument(
                                                                        "nutrition",
                                                                        IntegerArgumentType.integer(0)
                                                                )
                                                                .then(CommandManager.argument(
                                                                                        "saturation",
                                                                                        DoubleArgumentType.doubleArg(0.0D)
                                                                                )
                                                                                .then(CommandManager.argument(
                                                                                                        "health",
                                                                                                        DoubleArgumentType.doubleArg(0.0D)
                                                                                                )
                                                                                                .executes(context ->
                                                                                                        executeSetBuff(
                                                                                                                context,
                                                                                                                null,
                                                                                                                0.0D,
                                                                                                                null
                                                                                                        )
                                                                                                )
                                                                                                .then(CommandManager.argument(
                                                                                                                        "attr_id",
                                                                                                                        IdentifierArgumentType.identifier()
                                                                                                                )
                                                                                                                .then(CommandManager.argument(
                                                                                                                                        "attr_amount",
                                                                                                                                        DoubleArgumentType.doubleArg()
                                                                                                                                )
                                                                                                                                .then(CommandManager.argument(
                                                                                                                                                        "attr_op",
                                                                                                                                                        StringArgumentType.word()
                                                                                                                                                )
                                                                                                                                                .executes(context ->
                                                                                                                                                        executeSetBuff(
                                                                                                                                                                context,
                                                                                                                                                                IdentifierArgumentType.getIdentifier(
                                                                                                                                                                        context,
                                                                                                                                                                        "attr_id"
                                                                                                                                                                ),
                                                                                                                                                                DoubleArgumentType.getDouble(
                                                                                                                                                                        context,
                                                                                                                                                                        "attr_amount"
                                                                                                                                                                ),
                                                                                                                                                                StringArgumentType.getString(
                                                                                                                                                                        context,
                                                                                                                                                                        "attr_op"
                                                                                                                                                                )
                                                                                                                                                        )
                                                                                                                                                )
                                                                                                                                )
                                                                                                                )
                                                                                                )
                                                                                )
                                                                )
                                                )
                                )
                        )

                        .then(CommandManager.literal("buff")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.literal("give")
                                        .then(CommandManager.argument(
                                                                "player",
                                                                EntityArgumentType.player()
                                                        )
                                                        .then(CommandManager.argument(
                                                                                "targetId",
                                                                                StringArgumentType.string()
                                                                        )
                                                                        .executes(
                                                                                SetBuffCommand::executeBuffGive
                                                                        )
                                                        )
                                        )
                                )
                        )
        );
    }

    /**
     * Applies a unique runtime buff to the item held in the player's main hand.
     *
     * @param context command context
     * @param attributeId optional attribute identifier
     * @param attributeAmount attribute value
     * @param attributeOperation attribute operation
     * @return command result
     */
    private static int executeSetBuff(
            CommandContext<ServerCommandSource> context,
            Identifier attributeId,
            Double attributeAmount,
            String attributeOperation
    ) {
        ServerCommandSource source = context.getSource();

        try {
            ItemStack stack = source.getPlayerOrThrow().getMainHandStack();

            if (stack.isEmpty()) {
                source.sendError(
                        Text.literal(
                                "You must hold an item in your main hand."
                        )
                );

                return 0;
            }

            String uniqueBuffId = UUID.randomUUID().toString();

            NbtCompound customData = stack.getOrDefault(
                    DataComponentTypes.CUSTOM_DATA,
                    NbtComponent.DEFAULT
            ).copyNbt();

            customData.putString(
                    UNIQUE_BUFF_NBT_KEY,
                    uniqueBuffId
            );

            stack.set(
                    DataComponentTypes.CUSTOM_DATA,
                    NbtComponent.of(customData)
            );

            List<FoodBuffData.AttributeData> attributes =
                    new ArrayList<>();

            if (attributeId != null) {
                attributes.add(
                        new FoodBuffData.AttributeData(
                                attributeId,
                                attributeAmount,
                                attributeOperation
                        )
                );
            }

            FoodBuffData buffData = new FoodBuffData(
                    "stack:" + uniqueBuffId,
                    IntegerArgumentType.getInteger(context, "duration"),
                    IntegerArgumentType.getInteger(context, "nutrition"),
                    (float) DoubleArgumentType.getDouble(
                            context,
                            "saturation"
                    ),
                    DoubleArgumentType.getDouble(context, "health"),
                    new ArrayList<>(),
                    attributes
            );

            FoodBuffManager.setRuntimeStackConfig(
                    uniqueBuffId,
                    buffData
            );

            FoodBuffManager.saveRuntimeConfigs();

            String feedbackMessage =
                    attributeId != null
                            ? "Successfully applied a unique buff with attribute '"
                            + attributeId + "' to the held item."
                            : "Successfully applied a unique buff to the held item.";

            source.sendFeedback(
                    () -> Text.literal(feedbackMessage),
                    true
            );

            return 1;

        } catch (CommandSyntaxException exception) {
            source.sendError(
                    Text.literal("Only players can execute this command.")
            );

            return 0;
        }
    }

    /**
     * Grants a configured buff to the specified player.
     *
     * @param context command context
     * @return command result
     * @throws CommandSyntaxException if player lookup fails
     */
    private static int executeBuffGive(
            CommandContext<ServerCommandSource> context
    ) throws CommandSyntaxException {

        ServerPlayerEntity player =
                EntityArgumentType.getPlayer(context, "player");

        String targetId =
                StringArgumentType.getString(context, "targetId");

        FoodBuffData buffData = FoodBuffManager.getAllConfigs()
                .stream()
                .filter(config -> config.target().equals(targetId))
                .findFirst()
                .orElse(null);

        if (buffData == null) {
            context.getSource().sendError(
                    Text.literal(
                            "No buff configuration found for ID: "
                                    + targetId
                    )
            );

            return 0;
        }

        PlayerFoodComponent component =
                ((IFoodComponentProvider) player)
                        .florafare$getFoodComponent();

        component.unlockFood(targetId);

        context.getSource().sendFeedback(
                () -> Text.literal(
                        "Successfully granted buff '"
                                + targetId
                                + "' to player "
                                + player.getName().getString()
                                + "."
                ),
                true
        );

        return 1;
    }
}