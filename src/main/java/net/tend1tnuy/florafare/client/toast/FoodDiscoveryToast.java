package net.tend1tnuy.florafare.client.toast;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.tend1tnuy.registry.ItemRegistry;

public class FoodDiscoveryToast implements Toast {

    // Правильний ідентифікатор спрайту для фону тоста у нових версіях
    private static final Identifier BACKGROUND_SPRITE = Identifier.of("minecraft", "toast/recipe");
    private final ItemStack icon = new ItemStack(ItemRegistry.FOOD_JOURNAL);

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
        // 1. Малюємо правильний ванільний фон через метод drawGuiTexture
        context.drawGuiTexture(BACKGROUND_SPRITE, 0, 0, this.getWidth(), this.getHeight());

        // 2. Малюємо Заголовок (Колір: Темно-оранжевий/Золотий)
        context.drawText(manager.getClient().textRenderer, Text.literal("Кулінарна Книга"), 30, 7, 0xAA6600, false);

        // 3. Малюємо Опис (Колір: Чорний)
        context.drawText(manager.getClient().textRenderer, Text.literal("Нову страву досліджено!"), 30, 18, 0x000000, false);

        // 4. Малюємо іконку книги
        context.drawItem(icon, 8, 8);

        // Показуємо тост 5 секунд (5000 мілісекунд)
        return startTime >= 5000L ? Visibility.HIDE : Visibility.SHOW;
    }
}