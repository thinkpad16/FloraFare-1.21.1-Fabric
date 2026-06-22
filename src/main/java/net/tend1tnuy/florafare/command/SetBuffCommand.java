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
 * Handles the registration and execution of all /florafare commands.
 */
public class SetBuffCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {

        dispatcher.register(
                CommandManager.literal("florafare")

                        // =========================================================
                        // ГІЛКА 1: /florafare setbuff (Динамічний баф на предмет у руці)
                        // =========================================================
                        .then(CommandManager.literal("setbuff")
                                .requires(source -> source.hasPermissionLevel(2))
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

                        // =========================================================
                        // ГІЛКА 2: /florafare buff give (Видача бафу з датапаку гравцю)
                        // =========================================================
                        .then(CommandManager.literal("buff")
                                .requires(source -> source.hasPermissionLevel(2))
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

    // Логіка для /florafare setbuff ...
    private static int executeSetBuff(CommandContext<ServerCommandSource> context, Identifier attrId, Double amount, String op) {
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

    // Логіка для /florafare buff give <player> <targetId>
    private static int executeBuffGive(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        String targetId = StringArgumentType.getString(context, "targetId");

        // Шукаємо конфіг
        FoodBuffData data = null;
        for (FoodBuffData config : FoodBuffManager.getAllConfigs()) {
            if (config.target().equals(targetId)) {
                data = config;
                break;
            }
        }

        if (data != null) {
            PlayerFoodComponent component = ((IFoodComponentProvider) player).florafare$getFoodComponent();

            // ТУТ ВАЖЛИВО: Виклич свій метод, який додає баф на екран
            // Наприклад, якщо у тебе є метод addBuff:
            // component.addBuff(new net.tend1tnuy.florafare.component.ActiveFoodBuff(targetId, data));

            // Відкриваємо їжу в книзі гравця (щоб з'явився Toast)
            component.unlockFood(targetId);

            context.getSource().sendFeedback(() -> Text.literal("§aБаф " + targetId + " успішно видано гравцю " + player.getName().getString()), true);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Баф з ID " + targetId + " не знайдено в датапаку!"));
            return 0;
        }
    }
}