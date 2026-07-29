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
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
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
 * Handles the registration and execution of all /florafare administration commands.
 */
public class SetBuffCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {

        dispatcher.register(
                CommandManager.literal("florafare")
                        .requires(source -> source.hasPermissionLevel(2))

                        // Branch: /florafare clear <player> (Clears all active buffs and synergies)
                        .then(CommandManager.literal("clear")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(SetBuffCommand::executeClearBuffs)
                                )
                        )

                        // Branch: /florafare setbuff (Dynamic buff on item in hand)
                        .then(CommandManager.literal("setbuff")
                                .then(CommandManager.argument("duration", IntegerArgumentType.integer(1))
                                        .then(CommandManager.argument("nutrition", IntegerArgumentType.integer(0))
                                                .then(CommandManager.argument("saturation", DoubleArgumentType.doubleArg(0.0))
                                                        .then(CommandManager.argument("health", DoubleArgumentType.doubleArg(0.0))
                                                                .executes(context -> executeSetBuff(context, null, 0.0, null))
                                                                .then(CommandManager.argument("attr_id", IdentifierArgumentType.identifier())
                                                                        .then(CommandManager.argument("attr_amount", DoubleArgumentType.doubleArg())
                                                                                .then(CommandManager.argument("attr_op", StringArgumentType.word())
                                                                                        .executes(context -> executeSetBuff(
                                                                                                context,
                                                                                                IdentifierArgumentType.getIdentifier(context, "attr_id"),
                                                                                                DoubleArgumentType.getDouble(context, "attr_amount"),
                                                                                                StringArgumentType.getString(context, "attr_op")
                                                                                        ))
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )

                        // Branch: /florafare buff give (Grant buff from datapack to player)
                        .then(CommandManager.literal("buff")
                                .then(CommandManager.literal("give")
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .then(CommandManager.argument("targetId", StringArgumentType.string())
                                                        .executes(SetBuffCommand::executeBuffGive)
                                                )
                                        )
                                )
                        )
        );
    }

    private static int executeClearBuffs(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");
        PlayerFoodComponent component = ((IFoodComponentProvider) targetPlayer).florafare$getFoodComponent();

        component.clearAllBuffs();

        context.getSource().sendFeedback(() -> Text.translatable("command.florafare.clear.success", targetPlayer.getName().getString()), true);
        return 1;
    }

    private static int executeSetBuff(CommandContext<ServerCommandSource> context, Identifier attrId, Double amount, String op) {
        ServerCommandSource source = context.getSource();
        if (source.getPlayer() == null) return 0;

        ItemStack stack = source.getPlayer().getMainHandStack();

        if (stack.isEmpty()) {
            source.sendError(Text.translatable("command.florafare.error.empty_hand"));
            return 0;
        }

        String uniqueId = UUID.randomUUID().toString();

        NbtCompound customData = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        customData.putString("FlorafareBuffId", uniqueId);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(customData));

        List<FoodBuffData.AttributeData> attrs = new ArrayList<>();
        if (attrId != null) {
            attrs.add(new FoodBuffData.AttributeData(attrId, amount, op));
        }

        FoodBuffData data = new FoodBuffData(
                "stack:" + uniqueId,
                IntegerArgumentType.getInteger(context, "duration"),
                IntegerArgumentType.getInteger(context, "nutrition"),
                (float) DoubleArgumentType.getDouble(context, "saturation"),
                DoubleArgumentType.getDouble(context, "health"),
                new ArrayList<>(),
                attrs,
                0,
                false
        );

        FoodBuffManager.setRuntimeStackConfig(uniqueId, data);
        FoodBuffManager.saveRuntimeConfigs();

        source.sendFeedback(() -> Text.translatable("command.florafare.setbuff.success"), true);
        return 1;
    }

    private static int executeBuffGive(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        // Normalize (trim, default namespace) so the input matches the registered config keys.
        String targetId = FoodBuffManager.normalizeTarget(StringArgumentType.getString(context, "targetId"));

        FoodBuffData data = FoodBuffManager.getAllConfigs().stream()
                .filter(config -> config.target().equals(targetId))
                .findFirst()
                .orElse(null);

        if (data != null) {
            PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();
            component.unlockFood(targetId);

            // Actually apply the buff (previously this only unlocked the journal entry).
            // Resolve a display stack for plain item targets so the HUD shows the right icon.
            ItemStack displayStack = Items.APPLE.getDefaultStack();
            if (!targetId.startsWith("#")) {
                Identifier itemId = Identifier.tryParse(targetId);
                if (itemId != null && Registries.ITEM.containsId(itemId)) {
                    displayStack = Registries.ITEM.get(itemId).getDefaultStack();
                }
            }

            if (!component.tryAddBuff(displayStack, data)) {
                context.getSource().sendError(Text.translatable("command.florafare.error.slots_full", player.getName().getString()));
                return 0;
            }

            context.getSource().sendFeedback(() -> Text.translatable("command.florafare.buff_give.success", targetId, player.getName().getString()), true);
            return 1;
        } else {
            context.getSource().sendError(Text.translatable("command.florafare.error.buff_not_found", targetId));
            return 0;
        }
    }
}