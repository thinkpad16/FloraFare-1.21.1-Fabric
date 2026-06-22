package net.tend1tnuy.florafare.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.PageTurnWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Graphical interface for the Food Journal.
 * Displays discovered foods and detailed information about their effects.
 */
public class FoodJournalScreen extends Screen {

    private static final Identifier BOOK_TEXTURE =
            Identifier.of("minecraft", "textures/gui/book.png");

    private static final int BOOK_WIDTH = 192;
    private static final int BOOK_HEIGHT = 192;

    private static final int ITEMS_PER_ROW = 5;
    private static final int ROWS_PER_PAGE = 5;
    private static final int ITEMS_PER_PAGE = ITEMS_PER_ROW * ROWS_PER_PAGE;
    private static final int ITEM_SPACING = 23;

    private static final int GRID_START_X = 37;
    private static final int GRID_START_Y = 38;

    private static final float HOVER_ANIMATION_SPEED = 0.25F;
    private static final float HOVER_SCALE = 1.25F;
    private static final float DEFAULT_SCALE = 1.0F;

    private int currentPage = 0;
    private int maxPages = 1;
    private int unlockedCount = 0;

    private final List<FoodEntry> displayItems = new ArrayList<>();

    private FoodEntry selectedEntry;
    private FoodEntry hoveredEntry;

    private boolean showingDetails = false;

    private PageTurnWidget nextPageButton;
    private PageTurnWidget previousPageButton;

    /**
     * Creates a new Food Journal screen.
     */
    public FoodJournalScreen() {
        super(Text.literal("Culinary Journal"));
    }

    @Override
    protected void init() {
        super.init();

        loadFoodData();

        maxPages = (int) Math.ceil((double) displayItems.size() / ITEMS_PER_PAGE);
        maxPages = Math.max(1, maxPages);

        int bookX = (width - BOOK_WIDTH) / 2;
        int bookY = (height - BOOK_HEIGHT) / 2;

        previousPageButton = addDrawableChild(new PageTurnWidget(
                bookX + 43,
                bookY + 157,
                false,
                button -> {
                    if (showingDetails) {
                        showingDetails = false;
                        selectedEntry = null;
                    } else if (currentPage > 0) {
                        currentPage--;
                    }

                    updatePageButtons();
                    playPageTurnSound();
                },
                true
        ));

        nextPageButton = addDrawableChild(new PageTurnWidget(
                bookX + 116,
                bookY + 157,
                true,
                button -> {
                    if (!showingDetails && currentPage < maxPages - 1) {
                        currentPage++;
                        updatePageButtons();
                        playPageTurnSound();
                    }
                },
                true
        ));

        updatePageButtons();
    }

    /**
     * Updates page button visibility based on the current screen state.
     */
    private void updatePageButtons() {
        if (showingDetails) {
            previousPageButton.visible = true;
            nextPageButton.visible = false;
            return;
        }

        previousPageButton.visible = currentPage > 0;
        nextPageButton.visible = currentPage < maxPages - 1;
    }

    /**
     * Loads all available food entries and marks discovered foods.
     */
    private void loadFoodData() {
        displayItems.clear();
        unlockedCount = 0;

        if (client == null || client.player == null) {
            return;
        }

        Set<String> discoveredFoods =
                ((IFoodComponentProvider) client.player)
                        .florafare$getFoodComponent()
                        .getDiscoveredFoods();

        for (FoodBuffData data : FoodBuffManager.getAllConfigs()) {

            if (!data.target().startsWith("item:")) {
                continue;
            }

            Identifier itemId =
                    Identifier.tryParse(data.target().replace("item:", ""));

            if (itemId == null || !Registries.ITEM.containsId(itemId)) {
                continue;
            }

            ItemStack stack =
                    Registries.ITEM.get(itemId).getDefaultStack();

            boolean unlocked =
                    discoveredFoods.contains(itemId.toString());

            if (unlocked) {
                unlockedCount++;
            }

            displayItems.add(
                    new FoodEntry(stack, data, unlocked)
            );
        }
    }

    @Override
    public void renderBackground(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta
    ) {
    }

    @Override
    public void render(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta
    ) {
        renderBackground(context, mouseX, mouseY, delta);

        int bookX = (width - BOOK_WIDTH) / 2;
        int bookY = (height - BOOK_HEIGHT) / 2;

        context.drawTexture(
                BOOK_TEXTURE,
                bookX,
                bookY,
                0,
                0,
                BOOK_WIDTH,
                BOOK_HEIGHT,
                256,
                256
        );

        hoveredEntry = null;

        if (showingDetails) {
            drawDetailView(context, bookX, bookY);
        } else {
            drawItemGrid(context, mouseX, mouseY, bookX, bookY);
        }

        super.render(context, mouseX, mouseY, delta);

        if (!showingDetails && hoveredEntry != null) {
            Text tooltip = hoveredEntry.isUnlocked
                    ? hoveredEntry.stack.getName()
                    : Text.literal("???").formatted(Formatting.GRAY);

            context.drawTooltip(
                    textRenderer,
                    tooltip,
                    mouseX,
                    mouseY
            );
        }
    }

    /**
     * Renders the grid containing all food entries.
     */
    private void drawItemGrid(
            DrawContext context,
            int mouseX,
            int mouseY,
            int bookX,
            int bookY
    ) {
        int titleWidth = textRenderer.getWidth(title);

        context.drawText(
                textRenderer,
                title,
                bookX + (BOOK_WIDTH - titleWidth) / 2,
                bookY + 12,
                0x000000,
                false
        );

        String progressText =
                "Discovered Foods: "
                        + unlockedCount
                        + " / "
                        + displayItems.size();

        int progressColor =
                unlockedCount == displayItems.size()
                        && !displayItems.isEmpty()
                        ? 0xAA8800
                        : 0x555555;

        int progressWidth = textRenderer.getWidth(progressText);

        context.getMatrices().push();
        context.getMatrices().translate(
                bookX + BOOK_WIDTH / 2F,
                bookY + 25,
                0
        );
        context.getMatrices().scale(0.85F, 0.85F, 1.0F);

        context.drawText(
                textRenderer,
                progressText,
                -progressWidth / 2,
                0,
                progressColor,
                false
        );

        context.getMatrices().pop();

        String pageText =
                (currentPage + 1) + " / " + maxPages;

        int pageWidth = textRenderer.getWidth(pageText);

        context.drawText(
                textRenderer,
                pageText,
                bookX + (BOOK_WIDTH - pageWidth) / 2,
                bookY + 158,
                0x555555,
                false
        );

        int startX = bookX + GRID_START_X;
        int startY = bookY + GRID_START_Y;

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex =
                Math.min(startIndex + ITEMS_PER_PAGE, displayItems.size());

        for (int i = startIndex; i < endIndex; i++) {

            FoodEntry entry = displayItems.get(i);

            int localIndex = i - startIndex;
            int row = localIndex / ITEMS_PER_ROW;
            int column = localIndex % ITEMS_PER_ROW;

            int x = startX + column * ITEM_SPACING;
            int y = startY + row * ITEM_SPACING;

            context.fill(
                    x - 2,
                    y - 2,
                    x + 18,
                    y + 18,
                    0x11000000
            );

            context.drawBorder(
                    x - 2,
                    y - 2,
                    20,
                    20,
                    0x1A000000
            );

            boolean hovered =
                    mouseX >= x && mouseX < x + 16
                            && mouseY >= y && mouseY < y + 16;

            entry.hoverScale = MathHelper.lerp(
                    HOVER_ANIMATION_SPEED,
                    entry.hoverScale,
                    hovered ? HOVER_SCALE : DEFAULT_SCALE
            );

            if (hovered) {
                hoveredEntry = entry;
            }

            context.getMatrices().push();

            context.getMatrices().translate(x + 8, y + 8, 0);
            context.getMatrices().scale(
                    entry.hoverScale,
                    entry.hoverScale,
                    1.0F
            );
            context.getMatrices().translate(-(x + 8), -(y + 8), 0);

            context.drawItem(entry.stack, x, y);

            if (!entry.isUnlocked) {
                context.getMatrices().translate(0, 0, 200);

                context.fill(
                        x,
                        y,
                        x + 16,
                        y + 16,
                        0x88000000
                );

                context.drawText(
                        textRenderer,
                        "?",
                        x + 5,
                        y + 4,
                        0xDDDDDD,
                        false
                );
            }

            context.getMatrices().pop();
        }
    }

    /**
     * Renders the detailed information page for a selected food.
     */
    private void drawDetailView(
            DrawContext context,
            int bookX,
            int bookY
    ) {
        if (selectedEntry == null) {
            return;
        }

        int paperLeft = bookX + 42;
        int paperWidth = 122;

        context.getMatrices().push();
        context.getMatrices().translate(
                paperLeft + paperWidth / 2F - 16,
                bookY + 22,
                0
        );
        context.getMatrices().scale(2.0F, 2.0F, 1.0F);

        context.drawItem(selectedEntry.stack, 0, 0);

        context.getMatrices().pop();

        int currentY = bookY + 58;

        String itemName = selectedEntry.stack.getName().getString();

        if (textRenderer.getWidth(itemName) > paperWidth - 4) {
            itemName =
                    textRenderer.trimToWidth(itemName, paperWidth - 14)
                            + "...";
        }

        int nameWidth = textRenderer.getWidth(itemName);

        context.drawText(
                textRenderer,
                itemName,
                paperLeft + (paperWidth - nameWidth) / 2,
                currentY,
                0x000000,
                false
        );

        context.fill(
                paperLeft + 6,
                currentY + 12,
                paperLeft + paperWidth - 6,
                currentY + 13,
                0x44000000
        );

        currentY += 18;

        FoodBuffData data = selectedEntry.data;

        int durationSeconds = data.duration() / 20;

        context.drawText(
                textRenderer,
                "Duration: "
                        + String.format(
                        "%02d:%02d",
                        durationSeconds / 60,
                        durationSeconds % 60
                ),
                paperLeft,
                currentY,
                0x222222,
                false
        );

        currentY += 12;

        context.drawText(
                textRenderer,
                "Nutrition: " + data.nutrition(),
                paperLeft,
                currentY,
                0x222222,
                false
        );

        currentY += 12;

        context.drawText(
                textRenderer,
                "Saturation: " + data.saturation(),
                paperLeft,
                currentY,
                0x222222,
                false
        );
    }

    @Override
    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button
    ) {
        if (button == 0 && !showingDetails) {

            int bookX = (width - BOOK_WIDTH) / 2;
            int bookY = (height - BOOK_HEIGHT) / 2;

            int startX = bookX + GRID_START_X;
            int startY = bookY + GRID_START_Y;

            int startIndex = currentPage * ITEMS_PER_PAGE;
            int endIndex =
                    Math.min(startIndex + ITEMS_PER_PAGE, displayItems.size());

            for (int i = startIndex; i < endIndex; i++) {

                int localIndex = i - startIndex;
                int row = localIndex / ITEMS_PER_ROW;
                int column = localIndex % ITEMS_PER_ROW;

                int x = startX + column * ITEM_SPACING;
                int y = startY + row * ITEM_SPACING;

                if (mouseX >= x && mouseX < x + 16
                        && mouseY >= y && mouseY < y + 16) {

                    FoodEntry entry = displayItems.get(i);

                    if (entry.isUnlocked) {
                        selectedEntry = entry;
                        showingDetails = true;

                        updatePageButtons();
                        playPageTurnSound();
                    } else if (client != null) {
                        client.getSoundManager().play(
                                PositionedSoundInstance.master(
                                        SoundEvents.BLOCK_CHEST_LOCKED,
                                        1.5F
                                )
                        );
                    }

                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Plays the page turning sound.
     */
    private void playPageTurnSound() {
        if (client != null) {
            client.getSoundManager().play(
                    PositionedSoundInstance.master(
                            SoundEvents.ITEM_BOOK_PAGE_TURN,
                            1.0F
                    )
            );
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /**
     * Represents a food entry displayed in the journal.
     */
    private static class FoodEntry {

        final ItemStack stack;
        final FoodBuffData data;
        final boolean isUnlocked;

        float hoverScale = DEFAULT_SCALE;

        FoodEntry(
                ItemStack stack,
                FoodBuffData data,
                boolean isUnlocked
        ) {
            this.stack = stack;
            this.data = data;
            this.isUnlocked = isUnlocked;
        }
    }
}