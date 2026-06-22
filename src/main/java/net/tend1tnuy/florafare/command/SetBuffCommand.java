package net.tend1tnuy.florafare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles the registration and execution of the /florafare setbuff command.
 * Allows admins to apply custom, unique food buffs to items.
 */
public class SetBuffCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {

        dispatcher.register(
                CommandManager.literal("florafare")
                        .then(CommandManager.literal("setbuff")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.argument("duration", IntegerArgumentType.integer(1))
                                        .then(CommandManager.argument("nutrition", IntegerArgumentType.integer(0))
                                                .then(CommandManager.argument("saturation", DoubleArgumentType.doubleArg(0.0))
                                                        .then(CommandManager.argument("health", DoubleArgumentType.doubleArg(0.0))
                                                                .executes(context -> execute(context, null, 0.0, null))
                                                                .then(CommandManager.argument("attr_id", IdentifierArgumentType.identifier())
                                                                        .then(CommandManager.argument("attr_amount", DoubleArgumentType.doubleArg())
                                                                                .then(CommandManager.argument("attr_op", StringArgumentType.word())
                                                                                        .executes(context -> execute(
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
        );
    }

    private static int execute(CommandContext<ServerCommandSource> context, Identifier attrId, Double amount, String op) {
        ServerCommandSource source = context.getSource();
        ItemStack stack = source.getPlayer().getMainHandStack();

        if (stack.isEmpty()) {
            source.sendFeedback(() -> Text.literal("You must be holding an item in your main hand!"), false);
            return 0;
        }

        // 1. Create a unique ID for this specific item instance
        String uniqueId = UUID.randomUUID().toString();

        // 2. Write the unique ID to the item's NBT to prevent stacking with identical items
        NbtCompound customData = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
        customData.putString("FlorafareBuffId", uniqueId);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(customData));

        // 3. Construct the buff data
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
                attrs
        );

        // 4. Save the unique buff to the Manager and persist to file
        FoodBuffManager.setRuntimeStackConfig(uniqueId, data);
        FoodBuffManager.saveRuntimeConfigs();

        String message = "Unique buff" + (attrId != null ? " and attribute (" + attrId.toString() + ")" : "") + " successfully applied to this item!";
        source.sendFeedback(() -> Text.literal(message), true);
        return 1;
    }
}