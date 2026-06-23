package net.tend1tnuy.florafare.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.PageTurnWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.potion.Potion;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FoodJournalScreen extends Screen {

    private static final Identifier BOOK_TEXTURE = Identifier.of("minecraft", "textures/gui/book.png");
    private static final int BOOK_WIDTH = 192;
    private static final int BOOK_HEIGHT = 192;

    private static final int ITEMS_PER_ROW = 5;
    private static final int ROWS_PER_PAGE = 5;
    private static final int ITEMS_PER_PAGE = ITEMS_PER_ROW * ROWS_PER_PAGE;
    private static final int ITEM_SPACING = 23;

    private int currentPage = 0;
    private int maxPages = 1;
    private int unlockedCount = 0;

    private final List<FoodEntry> displayItems = new ArrayList<>();
    private FoodEntry selectedEntry = null;
    private FoodEntry hoveredEntry = null;
    private boolean showingDetails = false;

    private PageTurnWidget nextPageButton;
    private PageTurnWidget previousPageButton;

    public FoodJournalScreen() {
        super(Text.literal("Кулінарна Книга"));
    }

    @Override
    protected void init() {
        super.init();
        loadFoodData();

        maxPages = (int) Math.ceil((double) displayItems.size() / ITEMS_PER_PAGE);
        if (maxPages == 0) maxPages = 1;

        int bookX = (this.width - BOOK_WIDTH) / 2;
        int bookY = (this.height - BOOK_HEIGHT) / 2;

        this.previousPageButton = this.addDrawableChild(new PageTurnWidget(bookX + 43, bookY + 157, false, btn -> {
            if (showingDetails) {
                showingDetails = false;
                selectedEntry = null;
                this.updatePageButtons();
                playPageTurnSound();
            } else if (currentPage > 0) {
                currentPage--;
                this.updatePageButtons();
                playPageTurnSound();
            }
        }, true));

        this.nextPageButton = this.addDrawableChild(new PageTurnWidget(bookX + 116, bookY + 157, true, btn -> {
            if (!showingDetails && currentPage < maxPages - 1) {
                currentPage++;
                this.updatePageButtons();
                playPageTurnSound();
            }
        }, true));

        this.updatePageButtons();
    }

    private void updatePageButtons() {
        if (showingDetails) {
            this.previousPageButton.visible = true;
            this.nextPageButton.visible = false;
        } else {
            this.previousPageButton.visible = this.currentPage > 0;
            this.nextPageButton.visible = this.currentPage < this.maxPages - 1;
        }
    }

    private void loadFoodData() {
        displayItems.clear();
        unlockedCount = 0;

        if (this.client == null || this.client.player == null) return;

        Set<String> discovered = ((IFoodComponentProvider) this.client.player)
                .florafare$getFoodComponent().getDiscoveredFoods();

        for (FoodBuffData data : FoodBuffManager.getAllConfigs()) {
            ItemStack displayStack = null;

            if (data.target().startsWith("item:")) {
                Identifier id = Identifier.tryParse(data.target().replace("item:", ""));
                if (id != null && Registries.ITEM.containsId(id)) {
                    displayStack = Registries.ITEM.get(id).getDefaultStack();
                }
            } else if (data.target().startsWith("potion:")) {
                Identifier id = Identifier.tryParse(data.target().replace("potion:", ""));
                if (id != null) {
                    RegistryEntry.Reference<Potion> potionEntry = Registries.POTION.getEntry(id).orElse(null);
                    if (potionEntry != null) {
                        displayStack = new ItemStack(Items.POTION);
                        displayStack.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(potionEntry));
                    }
                }
            }

            if (displayStack != null) {
                boolean isUnlocked = discovered.contains(data.target());
                if (isUnlocked) unlockedCount++;
                displayItems.add(new FoodEntry(displayStack, data, isUnlocked));
            }
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int bookX = (this.width - BOOK_WIDTH) / 2;
        int bookY = (this.height - BOOK_HEIGHT) / 2;

        context.drawTexture(BOOK_TEXTURE, bookX, bookY, 0, 0, BOOK_WIDTH, BOOK_HEIGHT, 256, 256);

        hoveredEntry = null;

        if (showingDetails) {
            drawDetailView(context, bookX, bookY);
        } else {
            drawItemGrid(context, mouseX, mouseY, bookX, bookY);
        }

        super.render(context, mouseX, mouseY, delta);

        if (!showingDetails && hoveredEntry != null) {
            if (!hoveredEntry.isUnlocked) {
                context.drawTooltip(this.textRenderer, Text.literal("???").formatted(Formatting.GRAY), mouseX, mouseY);
            } else {
                context.drawTooltip(this.textRenderer, hoveredEntry.stack.getName(), mouseX, mouseY);
            }
        }
    }

    private void drawItemGrid(DrawContext context, int mouseX, int mouseY, int bookX, int bookY) {
        int titleWidth = this.textRenderer.getWidth(this.title);
        context.drawText(this.textRenderer, this.title, bookX + (BOOK_WIDTH - titleWidth) / 2, bookY + 12, 0x000000, false);

        String trackerStr = "Відкрито страв: " + unlockedCount + " / " + displayItems.size();
        int trackerColor = (unlockedCount == displayItems.size() && displayItems.size() > 0) ? 0xAA8800 : 0x555555;
        int trackerWidth = this.textRenderer.getWidth(trackerStr);

        context.getMatrices().push();
        context.getMatrices().translate(bookX + (BOOK_WIDTH / 2f), bookY + 25, 0);
        context.getMatrices().scale(0.85f, 0.85f, 1.0f);
        context.drawText(this.textRenderer, trackerStr, -trackerWidth / 2, 0, trackerColor, false);
        context.getMatrices().pop();

        String pageStr = (currentPage + 1) + " / " + maxPages;
        int pageStrWidth = this.textRenderer.getWidth(pageStr);
        context.drawText(this.textRenderer, pageStr, bookX + (BOOK_WIDTH - pageStrWidth) / 2, bookY + 158, 0x555555, false);

        int startX = bookX + 37;
        int startY = bookY + 38;

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, displayItems.size());

        for (int i = startIndex; i < endIndex; i++) {
            FoodEntry entry = displayItems.get(i);
            int localIndex = i - startIndex;
            int row = localIndex / ITEMS_PER_ROW;
            int col = localIndex % ITEMS_PER_ROW;

            int x = startX + col * ITEM_SPACING;
            int y = startY + row * ITEM_SPACING;

            context.fill(x - 2, y - 2, x + 18, y + 18, 0x11000000);
            context.drawBorder(x - 2, y - 2, 20, 20, 0x1A000000);

            boolean isHovered = (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16);
            if (isHovered) {
                hoveredEntry = entry;
                entry.hoverScale = net.minecraft.util.math.MathHelper.lerp(0.25f, entry.hoverScale, 1.25f);
            } else {
                entry.hoverScale = net.minecraft.util.math.MathHelper.lerp(0.25f, entry.hoverScale, 1.0f);
            }

            context.getMatrices().push();
            context.getMatrices().translate(x + 8, y + 8, 0);
            context.getMatrices().scale(entry.hoverScale, entry.hoverScale, 1.0f);
            context.getMatrices().translate(-(x + 8), -(y + 8), 0);

            context.drawItem(entry.stack, x, y);

            if (!entry.isUnlocked) {
                context.getMatrices().translate(0, 0, 200);
                context.fill(x, y, x + 16, y + 16, 0x88000000);
                context.drawText(this.textRenderer, "?", x + 5, y + 4, 0xDDDDDD, false);
            }
            context.getMatrices().pop();
        }
    }

    private void drawDetailView(DrawContext context, int bookX, int bookY) {
        if (selectedEntry == null) return;

        // Встановлюємо безпечні межі паперу
        int paperLeft = bookX + 36;
        int paperWidth = 114;
        int px = paperLeft;

        // Велика іконка
        context.getMatrices().push();
        context.getMatrices().translate(paperLeft + (paperWidth / 2f) - 16, bookY + 22, 0);
        context.getMatrices().scale(2.0f, 2.0f, 1.0f);
        context.drawItem(selectedEntry.stack, 0, 0);
        context.getMatrices().pop();

        int py = bookY + 58;

        // Назва
        String name = selectedEntry.stack.getName().getString();
        int nameWidth = this.textRenderer.getWidth(name);
        if (nameWidth > paperWidth - 4) {
            name = this.textRenderer.trimToWidth(name, paperWidth - 14) + "...";
            nameWidth = this.textRenderer.getWidth(name);
        }
        context.drawText(this.textRenderer, name, paperLeft + (paperWidth - nameWidth) / 2, py, 0x000000, false);
        context.fill(paperLeft + 6, py + 12, paperLeft + paperWidth - 6, py + 13, 0x44000000);

        py += 18;

        FoodBuffData data = selectedEntry.data;

        int seconds = data.duration() / 20;
        String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);
        context.drawText(this.textRenderer, "⏱ Час дії: " + timeStr, px, py, 0x222222, false);
        py += 12;

        context.drawText(this.textRenderer, "🍖 Ситість: " + data.nutrition(), px, py, 0x222222, false);
        py += 12;

        // Додано Насичення
        context.drawText(this.textRenderer, "✨ Насичення: " + data.saturation(), px, py, 0x222222, false);
        py += 12;

        if (data.healthBonus() != 0) {
            String sign = data.healthBonus() > 0 ? "+" : "";
            context.drawText(this.textRenderer, "❤ Здоров'я: " + sign + data.healthBonus(), px, py, 0x222222, false);
            py += 12;
        }

        // --- ВІДМАЛЬОВКА АТРИБУТІВ ---
        if (data.attributes() != null && !data.attributes().isEmpty()) {
            py += 4;
            context.drawText(this.textRenderer, "Атрибути:", px, py, 0x000000, false);
            py += 12;
            for (FoodBuffData.AttributeData attr : data.attributes()) {
                String attrName = attr.attributeId().getPath().replace("generic.", "");
                if (attrName.length() > 0) {
                    attrName = attrName.substring(0, 1).toUpperCase() + attrName.substring(1).replace("_", " ");
                }
                String sign = attr.amount() > 0 ? "+" : "";
                String val = attr.operation().contains("multiplied") ? (int)(attr.amount() * 100) + "%" : String.valueOf(attr.amount());
                context.drawText(this.textRenderer, "• " + attrName + ": " + sign + val, px + 6, py, 0x444444, false);
                py += 10;
            }
        }

        // --- ВІДМАЛЬОВКА ЕФЕКТІВ ---
        if (data.effects() != null && !data.effects().isEmpty()) {
            py += 4;
            context.drawText(this.textRenderer, "Ефекти:", px, py, 0x000000, false);
            py += 12;
            for (var effect : data.effects()) {
                String rawId = effect.id().getPath();
                String effectName = "";
                if (rawId.length() > 0) {
                    effectName = rawId.substring(0, 1).toUpperCase() + rawId.substring(1).replace("_", " ");
                }
                String lvl = "";
                if (effect.amplifier() == 1) lvl = " II";
                else if (effect.amplifier() == 2) lvl = " III";
                else if (effect.amplifier() == 3) lvl = " IV";
                else if (effect.amplifier() > 3) lvl = " " + (effect.amplifier() + 1);

                int effectSeconds = effect.duration() / 20;
                String effectTime = String.format(" (%02d:%02d)", effectSeconds / 60, effectSeconds % 60);
                context.drawText(this.textRenderer, "• " + effectName + lvl + effectTime, px + 6, py, 0x444444, false);
                py += 10;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !showingDetails) {
            int bookX = (this.width - BOOK_WIDTH) / 2;
            int bookY = (this.height - BOOK_HEIGHT) / 2;
            int startX = bookX + 37;
            int startY = bookY + 38;

            int startIndex = currentPage * ITEMS_PER_PAGE;
            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, displayItems.size());

            for (int i = startIndex; i < endIndex; i++) {
                int localIndex = i - startIndex;
                int row = localIndex / ITEMS_PER_ROW;
                int col = localIndex % ITEMS_PER_ROW;

                int x = startX + col * ITEM_SPACING;
                int y = startY + row * ITEM_SPACING;

                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                    FoodEntry entry = displayItems.get(i);
                    if (entry.isUnlocked) {
                        selectedEntry = entry;
                        showingDetails = true;
                        updatePageButtons();
                        playPageTurnSound();
                    } else {
                        if (this.client != null) {
                            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_CHEST_LOCKED, 1.5F));
                        }
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void playPageTurnSound() {
        if (this.client != null) {
            this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.0F));
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static class FoodEntry {
        final ItemStack stack;
        final FoodBuffData data;
        final boolean isUnlocked;
        float hoverScale = 1.0f;

        FoodEntry(ItemStack stack, FoodBuffData data, boolean isUnlocked) {
            this.stack = stack;
            this.data = data;
            this.isUnlocked = isUnlocked;
        }
    }
}