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
import net.minecraft.util.math.MathHelper;
import net.tend1tnuy.florafare.component.IFoodComponentProvider;
import net.tend1tnuy.florafare.config.FlorafareConfig;
import net.tend1tnuy.florafare.food.FoodBuffData;
import net.tend1tnuy.florafare.food.FoodBuffManager;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.FoodSynergyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FoodJournalScreen extends Screen {

    private static final Identifier BOOK_TEXTURE = Identifier.of("minecraft", "textures/gui/book.png");
    private static final int BOOK_WIDTH = 192, BOOK_HEIGHT = 192;
    private static final int ITEMS_PER_ROW = 5, ROWS_PER_PAGE = 5;
    private static final int ITEMS_PER_PAGE = ITEMS_PER_ROW * ROWS_PER_PAGE;
    private static final int ITEM_SPACING = 23;

    private enum ScreenState {
        INDEX, FOOD_GRID, SYNERGY_GRID, FOOD_DETAIL, SYNERGY_DETAIL
    }

    private ScreenState currentState = ScreenState.INDEX;
    private int currentPage = 0, maxPages = 1;
    private int unlockedCount = 0, unlockedSynergyCount = 0;

    // Пагінація для детального опису страв
    private final List<List<RenderLine>> detailPages = new ArrayList<>();
    private int detailCurrentPage = 0;

    private final List<FoodEntry> displayItems = new ArrayList<>();
    private final List<SynergyEntry> synergyDisplayItems = new ArrayList<>();

    private FoodEntry selectedEntry = null;
    private SynergyEntry selectedSynergy = null;
    private FoodEntry hoveredEntry = null;
    private SynergyEntry hoveredSynergy = null;
    private boolean hoverFoodsIndex = false;
    private boolean hoverSynergiesIndex = false;

    private PageTurnWidget nextPageButton;
    private PageTurnWidget previousPageButton;

    public FoodJournalScreen() {
        super(Text.translatable("gui.florafare.journal.title"));
    }

    @Override
    protected void init() {
        super.init();
        loadFoodData();
        loadSynergyData();

        int bookX = (this.width - BOOK_WIDTH) / 2;
        int bookY = (this.height - BOOK_HEIGHT) / 2;

        this.previousPageButton = this.addDrawableChild(new PageTurnWidget(bookX + 43, bookY + 157, false, btn -> {
            if (currentState == ScreenState.FOOD_DETAIL || currentState == ScreenState.SYNERGY_DETAIL) {
                if (detailCurrentPage > 0) {
                    detailCurrentPage--;
                } else {
                    currentState = (currentState == ScreenState.FOOD_DETAIL) ? ScreenState.FOOD_GRID : ScreenState.SYNERGY_GRID;
                }
            } else if (currentState == ScreenState.FOOD_GRID || currentState == ScreenState.SYNERGY_GRID) {
                if (currentPage > 0) {
                    currentPage--;
                } else {
                    currentState = ScreenState.INDEX;
                }
            }
            updatePageButtons();
            playPageTurnSound();
        }, true));

        this.nextPageButton = this.addDrawableChild(new PageTurnWidget(bookX + 116, bookY + 157, true, btn -> {
            if (currentState == ScreenState.FOOD_DETAIL || currentState == ScreenState.SYNERGY_DETAIL) {
                if (detailCurrentPage < detailPages.size() - 1) {
                    detailCurrentPage++;
                }
            } else if (currentState == ScreenState.FOOD_GRID || currentState == ScreenState.SYNERGY_GRID) {
                if (currentPage < maxPages - 1) {
                    currentPage++;
                }
            }
            updatePageButtons();
            playPageTurnSound();
        }, true));

        updatePageButtons();
    }

    private void updatePageButtons() {
        recalculateMaxPages();

        if (currentState == ScreenState.INDEX) {
            this.previousPageButton.visible = false;
            this.nextPageButton.visible = false;
        } else if (currentState == ScreenState.FOOD_DETAIL || currentState == ScreenState.SYNERGY_DETAIL) {
            this.previousPageButton.visible = true;
            this.nextPageButton.visible = this.detailCurrentPage < this.detailPages.size() - 1;
        } else {
            this.previousPageButton.visible = true;
            this.nextPageButton.visible = this.currentPage < this.maxPages - 1;
        }
    }

    private void recalculateMaxPages() {
        if (currentState == ScreenState.FOOD_GRID) {
            this.maxPages = Math.max(1, (int) Math.ceil((double) displayItems.size() / ITEMS_PER_PAGE));
        } else if (currentState == ScreenState.SYNERGY_GRID) {
            this.maxPages = Math.max(1, (int) Math.ceil((double) synergyDisplayItems.size() / ITEMS_PER_PAGE));
        } else {
            this.maxPages = 1;
        }
    }

    private void loadFoodData() {
        displayItems.clear();
        unlockedCount = 0;
        if (this.client == null || this.client.player == null) return;
        Set<String> discovered = ((IFoodComponentProvider) this.client.player).florafare$getFoodComponent().getDiscoveredFoods();

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

    private void loadSynergyData() {
        synergyDisplayItems.clear();
        unlockedSynergyCount = 0;
        if (!FlorafareConfig.enableSynergies || this.client == null || this.client.player == null) return;
        Set<String> discovered = ((IFoodComponentProvider) this.client.player).florafare$getFoodComponent().getDiscoveredSynergies();

        for (FoodSynergyData syn : FoodSynergyManager.getAllSynergies()) {
            boolean isUnlocked = discovered.contains(syn.id());
            if (isUnlocked) unlockedSynergyCount++;
            List<ItemStack> reqStacks = new ArrayList<>();
            for (String req : syn.requirements()) {
                if (req.startsWith("item:")) {
                    Identifier id = Identifier.tryParse(req.replace("item:", ""));
                    if (id != null && Registries.ITEM.containsId(id)) {
                        reqStacks.add(Registries.ITEM.get(id).getDefaultStack());
                    }
                }
            }
            synergyDisplayItems.add(new SynergyEntry(syn, reqStacks, isUnlocked));
        }
    }

    // === ПЕРЕВАНТАЖЕННЯ ДЛЯ ЗВИЧАЙНОЇ ЇЖІ ===
    private void buildDetailPages(FoodBuffData data) {
        detailPages.clear();
        detailCurrentPage = 0;
        List<RenderLine> allLines = new ArrayList<>();

        int seconds = data.duration() / 20;
        String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);
        allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.duration", timeStr).getString(), 0x222222, 12, 0));
        allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.nutrition", data.nutrition()).getString(), 0x222222, 12, 0));
        allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.saturation", data.saturation()).getString(), 0x222222, 12, 0));

        buildCommonDetailLines(allLines, data.healthBonus(), data.attributes(), data.effects());
        paginateLines(allLines, false);
    }

    // === ПЕРЕВАНТАЖЕННЯ ДЛЯ СИНЕРГІЙ ===
    private void buildDetailPages(FoodSynergyData data) {
        detailPages.clear();
        detailCurrentPage = 0;
        List<RenderLine> allLines = new ArrayList<>();

        allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.duration", "Динамічний").getString(), 0x222222, 12, 0));

        buildCommonDetailLines(allLines, data.healthBonus(), data.attributes(), data.effects());
        paginateLines(allLines, true);
    }

    // === СПІЛЬНА ЛОГІКА ФОРМУВАННЯ АТРИБУТІВ І ЕФЕКТІВ ===
    private void buildCommonDetailLines(List<RenderLine> allLines, double healthBonus, List<FoodBuffData.AttributeData> attributes, List<FoodBuffData.EffectData> effects) {
        if (healthBonus != 0) {
            String sign = healthBonus > 0 ? "+" : "";
            String val = healthBonus % 1 == 0 ? String.valueOf((int)healthBonus) : String.valueOf(healthBonus);
            allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.health", sign + val).getString(), 0x222222, 12, 0));
        }

        if (attributes != null && !attributes.isEmpty()) {
            allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.attributes_title").getString(), 0x000000, 16, 0));
            for (FoodBuffData.AttributeData attr : attributes) {
                String attrKey = "florafare.attribute." + attr.attributeId().getPath().replace("generic.", "");
                String attrName = Text.translatable(attrKey).getString();
                String sign = attr.amount() > 0 ? "+" : "";
                String val = attr.operation().contains("multiplied") ? (int)(attr.amount() * 100) + "%" : (attr.amount() % 1 == 0 ? String.valueOf((int)attr.amount()) : String.valueOf(attr.amount()));
                allLines.add(new RenderLine("• " + attrName + ": " + sign + val, 0x444444, 10, 6));
            }
        }

        if (effects != null && !effects.isEmpty()) {
            allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.effects_title").getString(), 0x000000, 16, 0));
            for (var effect : effects) {
                String effectKey = "effect." + effect.id().getNamespace() + "." + effect.id().getPath();
                String effectName = Text.translatable(effectKey).getString();
                String lvl = getAmplifierNumeral(effect.amplifier());
                int effectSeconds = effect.duration() / 20;
                String effectTime = String.format(" (%02d:%02d)", effectSeconds / 60, effectSeconds % 60);
                allLines.add(new RenderLine("• " + effectName + lvl + effectTime, 0x444444, 10, 6));
            }
        }
    }

    // === ЛОГІКА РОЗБИТТЯ НА СТОРІНКИ ===
    private void paginateLines(List<RenderLine> allLines, boolean isSynergy) {
        List<RenderLine> currentPageLines = new ArrayList<>();
        int currentY = isSynergy ? 66 : 76;
        int maxY = 150;

        for (RenderLine line : allLines) {
            if (currentY + line.height > maxY) {
                detailPages.add(currentPageLines);
                currentPageLines = new ArrayList<>();
                currentY = 24;
            }
            currentPageLines.add(line);
            currentY += line.height;
        }

        if (!currentPageLines.isEmpty() || detailPages.isEmpty()) {
            detailPages.add(currentPageLines);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Прозорий фон
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int bookX = (this.width - BOOK_WIDTH) / 2;
        int bookY = (this.height - BOOK_HEIGHT) / 2;

        context.drawTexture(BOOK_TEXTURE, bookX, bookY, 0, 0, BOOK_WIDTH, BOOK_HEIGHT, 256, 256);
        hoveredEntry = null;
        hoveredSynergy = null;
        hoverFoodsIndex = false;
        hoverSynergiesIndex = false;

        switch (currentState) {
            case INDEX -> drawIndexPage(context, mouseX, mouseY, bookX, bookY);
            case FOOD_GRID -> drawGridContent(context, mouseX, mouseY, bookX, bookY, false);
            case SYNERGY_GRID -> drawGridContent(context, mouseX, mouseY, bookX, bookY, true);
            case FOOD_DETAIL -> drawFoodDetailView(context, bookX, bookY);
            case SYNERGY_DETAIL -> drawSynergyDetailView(context, bookX, bookY);
        }

        super.render(context, mouseX, mouseY, delta);

        if (currentState == ScreenState.INDEX) {
            if (hoverFoodsIndex) {
                context.drawTooltip(this.textRenderer, Text.translatable("gui.florafare.journal.tab.foods"), mouseX, mouseY);
            } else if (hoverSynergiesIndex) {
                context.drawTooltip(this.textRenderer, Text.translatable("gui.florafare.journal.tab.synergies"), mouseX, mouseY);
            }
        } else if (currentState == ScreenState.FOOD_GRID || currentState == ScreenState.SYNERGY_GRID) {
            if (hoveredSynergy != null) {
                if (!hoveredSynergy.isUnlocked) {
                    context.drawTooltip(this.textRenderer, Text.literal("???").formatted(Formatting.GRAY), mouseX, mouseY);
                } else {
                    context.drawTooltip(this.textRenderer, Text.translatable("synergy.florafare." + hoveredSynergy.data.id().replace(":", ".")), mouseX, mouseY);
                }
            } else if (hoveredEntry != null) {
                if (!hoveredEntry.isUnlocked) {
                    context.drawTooltip(this.textRenderer, Text.literal("???").formatted(Formatting.GRAY), mouseX, mouseY);
                } else {
                    context.drawTooltip(this.textRenderer, hoveredEntry.stack.getName(), mouseX, mouseY);
                }
            }
        }
    }

    private void drawIndexPage(DrawContext context, int mouseX, int mouseY, int bookX, int bookY) {
        int centerX = bookX + 93;

        Text mainTitle = Text.translatable("gui.florafare.journal.title");
        int titleWidth = this.textRenderer.getWidth(mainTitle);
        context.drawText(this.textRenderer, mainTitle, centerX - titleWidth / 2, bookY + 22, 0x333333, false);

        int starWidth = this.textRenderer.getWidth("✦");

        int topY = bookY + 36;
        context.drawText(this.textRenderer, "✦", centerX - starWidth / 2, topY, 0x666666, false);
        context.fill(centerX - 45, topY + 4, centerX - starWidth / 2 - 4, topY + 5, 0x44000000);
        context.fill(centerX + starWidth / 2 + 4, topY + 4, centerX + 45, topY + 5, 0x44000000);

        int startY = bookY + 70;

        if (FlorafareConfig.enableSynergies) {
            int foodX = centerX - 30;
            hoverFoodsIndex = (mouseX >= foodX - 4 && mouseX < foodX + 20 && mouseY >= startY - 4 && mouseY < startY + 20);
            drawCategoryIcon(context, foodX, startY, Items.APPLE, hoverFoodsIndex);

            int synX = centerX + 14;
            hoverSynergiesIndex = (mouseX >= synX - 4 && mouseX < synX + 20 && mouseY >= startY - 4 && mouseY < startY + 20);
            drawCategoryIcon(context, synX, startY, Items.ENCHANTED_GOLDEN_APPLE, hoverSynergiesIndex);
        } else {
            int foodX = centerX - 8;
            hoverFoodsIndex = (mouseX >= foodX - 4 && mouseX < foodX + 20 && mouseY >= startY - 4 && mouseY < startY + 20);
            drawCategoryIcon(context, foodX, startY, Items.APPLE, hoverFoodsIndex);
        }

        int botY = bookY + 140;
        context.drawText(this.textRenderer, "✦", centerX - starWidth / 2, botY, 0x666666, false);
        context.fill(centerX - 45, botY + 4, centerX - starWidth / 2 - 4, botY + 5, 0x44000000);
        context.fill(centerX + starWidth / 2 + 4, botY + 4, centerX + 45, botY + 5, 0x44000000);
    }

    private void drawCategoryIcon(DrawContext context, int x, int y, net.minecraft.item.Item item, boolean isHovered) {
        context.getMatrices().push();
        context.getMatrices().translate(x + 8, y + 8, 0);
        context.getMatrices().scale(isHovered ? 1.4f : 1.1f, isHovered ? 1.4f : 1.1f, 1.0f);
        context.drawItem(new ItemStack(item), -8, -8);
        context.getMatrices().pop();
    }

    private void drawGridContent(DrawContext context, int mouseX, int mouseY, int bookX, int bookY, boolean isSynergy) {
        int centerX = bookX + 93;

        Text titleText = Text.translatable(isSynergy ? "gui.florafare.journal.synergies_title" : "gui.florafare.journal.tab.foods");
        context.drawText(this.textRenderer, titleText, centerX - this.textRenderer.getWidth(titleText) / 2, bookY + 14, 0x000000, false);

        Text trackerText = Text.translatable("gui.florafare.journal.progress", isSynergy ? unlockedSynergyCount : unlockedCount, isSynergy ? synergyDisplayItems.size() : displayItems.size());
        int trackerColor = ((isSynergy ? unlockedSynergyCount : unlockedCount) == (isSynergy ? synergyDisplayItems.size() : displayItems.size()) && (isSynergy ? synergyDisplayItems.size() : displayItems.size()) > 0) ? 0xAA8800 : 0x555555;
        context.getMatrices().push();
        context.getMatrices().translate(centerX, bookY + 25, 0);
        context.getMatrices().scale(0.85f, 0.85f, 1.0f);
        context.drawText(this.textRenderer, trackerText, -this.textRenderer.getWidth(trackerText) / 2, 0, trackerColor, false);
        context.getMatrices().pop();

        String pageStr = (currentPage + 1) + " / " + maxPages;
        context.drawText(this.textRenderer, pageStr, centerX - this.textRenderer.getWidth(pageStr) / 2, bookY + 158, 0x555555, false);

        int startX = bookX + 37;
        int startY = bookY + 38;
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int totalItems = isSynergy ? synergyDisplayItems.size() : displayItems.size();
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalItems);

        for (int i = startIndex; i < endIndex; i++) {
            int localIndex = i - startIndex;
            int col = localIndex % ITEMS_PER_ROW;
            int row = localIndex / ITEMS_PER_ROW;
            int x = startX + col * ITEM_SPACING;
            int y = startY + row * ITEM_SPACING;

            context.fill(x - 2, y - 2, x + 18, y + 18, 0x11000000);
            context.drawBorder(x - 2, y - 2, 20, 20, 0x1A000000);

            boolean isHovered = (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16);
            ItemStack stackToDraw;
            boolean isUnlocked;
            float scale;

            if (isSynergy) {
                SynergyEntry entry = synergyDisplayItems.get(i);
                entry.hoverScale = MathHelper.lerp(0.25f, entry.hoverScale, isHovered ? 1.25f : 1.0f);
                scale = entry.hoverScale;
                isUnlocked = entry.isUnlocked;
                stackToDraw = entry.reqStacks.isEmpty() ? new ItemStack(Items.APPLE) : entry.reqStacks.get(0);
                if (isHovered) hoveredSynergy = entry;
            } else {
                FoodEntry entry = displayItems.get(i);
                entry.hoverScale = MathHelper.lerp(0.25f, entry.hoverScale, isHovered ? 1.25f : 1.0f);
                scale = entry.hoverScale;
                isUnlocked = entry.isUnlocked;
                stackToDraw = entry.stack;
                if (isHovered) hoveredEntry = entry;
            }

            context.getMatrices().push();
            context.getMatrices().translate(x + 8, y + 8, 0);
            context.getMatrices().scale(scale, scale, 1.0f);
            context.getMatrices().translate(-(x + 8), -(y + 8), 0);

            context.drawItem(stackToDraw, x, y);

            if (!isUnlocked) {
                context.getMatrices().translate(0, 0, 200);
                context.fill(x, y, x + 16, y + 16, 0x88000000);
                context.drawText(this.textRenderer, "?", x + 5, y + 4, 0xDDDDDD, false);
            } else if (isSynergy) {
                context.getMatrices().translate(0, 0, 200);
                context.drawText(this.textRenderer, "✦", x - 1, y - 1, 0xFFDD00, false);
            }
            context.getMatrices().pop();
        }
    }

    private void drawFoodDetailView(DrawContext context, int bookX, int bookY) {
        int paperLeft = bookX + 36;
        int paperWidth = 114;
        int px = paperLeft;

        if (detailCurrentPage == 0) {
            context.getMatrices().push();
            context.getMatrices().translate(paperLeft + (paperWidth / 2f) - 16, bookY + 22, 0);
            context.getMatrices().scale(2.0f, 2.0f, 1.0f);
            context.drawItem(selectedEntry.stack, 0, 0);
            context.getMatrices().pop();

            int py = bookY + 58;
            String name = selectedEntry.stack.getName().getString();
            int nameWidth = this.textRenderer.getWidth(name);
            if (nameWidth > paperWidth - 4) {
                name = this.textRenderer.trimToWidth(name, paperWidth - 14) + "...";
                nameWidth = this.textRenderer.getWidth(name);
            }
            context.drawText(this.textRenderer, name, paperLeft + (paperWidth - nameWidth) / 2, py, 0x000000, false);
            context.fill(paperLeft + 6, py + 12, paperLeft + paperWidth - 6, py + 13, 0x44000000);
        }

        int py = bookY + (detailCurrentPage == 0 ? 76 : 24);
        for (RenderLine line : detailPages.get(detailCurrentPage)) {
            int drawY = py;
            if (line.height == 16) drawY += 4;
            context.drawText(this.textRenderer, line.text, px + line.offsetX, drawY, line.color, false);
            py += line.height;
        }

        String pageStr = (detailCurrentPage + 1) + " / " + detailPages.size();
        context.drawText(this.textRenderer, pageStr, bookX + 93 - this.textRenderer.getWidth(pageStr) / 2, bookY + 158, 0x555555, false);
    }

    private void drawSynergyDetailView(DrawContext context, int bookX, int bookY) {
        int paperLeft = bookX + 36;
        int paperWidth = 114;
        int px = paperLeft;

        if (detailCurrentPage == 0) {
            int py = bookY + 24;
            int reqCount = selectedSynergy.reqStacks.size();
            int totalReqWidth = reqCount * 16 + (reqCount - 1) * 4;
            int startX = paperLeft + (paperWidth - totalReqWidth) / 2;

            for (int i = 0; i < reqCount; i++) {
                context.drawItem(selectedSynergy.reqStacks.get(i), startX + i * 20, py);
            }
            py += 24;

            String name = Text.translatable("synergy.florafare." + selectedSynergy.data.id().replace(":", ".")).getString();
            int nameWidth = this.textRenderer.getWidth(name);
            if (nameWidth > paperWidth - 4) {
                name = this.textRenderer.trimToWidth(name, paperWidth - 14) + "...";
                nameWidth = this.textRenderer.getWidth(name);
            }
            context.drawText(this.textRenderer, name, paperLeft + (paperWidth - nameWidth) / 2, py, 0x880000, false);
            context.fill(paperLeft + 6, py + 12, paperLeft + paperWidth - 6, py + 13, 0x44000000);
        }

        int py = bookY + (detailCurrentPage == 0 ? 66 : 24);
        for (RenderLine line : detailPages.get(detailCurrentPage)) {
            int drawY = py;
            if (line.height == 16) drawY += 4;
            context.drawText(this.textRenderer, line.text, px + line.offsetX, drawY, line.color, false);
            py += line.height;
        }

        String pageStr = (detailCurrentPage + 1) + " / " + detailPages.size();
        context.drawText(this.textRenderer, pageStr, bookX + 93 - this.textRenderer.getWidth(pageStr) / 2, bookY + 158, 0x555555, false);
    }

    private String getAmplifierNumeral(int amplifier) {
        return switch (amplifier) {
            case 0 -> "";
            case 1 -> " II";
            case 2 -> " III";
            case 3 -> " IV";
            default -> " " + (amplifier + 1);
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (currentState == ScreenState.INDEX) {
                if (hoverFoodsIndex) {
                    currentState = ScreenState.FOOD_GRID;
                    currentPage = 0;
                    updatePageButtons();
                    playPageTurnSound();
                    return true;
                } else if (FlorafareConfig.enableSynergies && hoverSynergiesIndex) {
                    currentState = ScreenState.SYNERGY_GRID;
                    currentPage = 0;
                    updatePageButtons();
                    playPageTurnSound();
                    return true;
                }
            } else if (currentState == ScreenState.FOOD_GRID || currentState == ScreenState.SYNERGY_GRID) {
                int bookX = (this.width - BOOK_WIDTH) / 2;
                int startX = bookX + 37;
                int bookY = (this.height - BOOK_HEIGHT) / 2;
                int startY = bookY + 38;

                int startIndex = currentPage * ITEMS_PER_PAGE;
                int totalItems = currentState == ScreenState.SYNERGY_GRID ? synergyDisplayItems.size() : displayItems.size();
                int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalItems);

                for (int i = startIndex; i < endIndex; i++) {
                    int localIndex = i - startIndex;
                    int col = localIndex % ITEMS_PER_ROW;
                    int row = localIndex / ITEMS_PER_ROW;

                    int x = startX + col * ITEM_SPACING;
                    int y = startY + row * ITEM_SPACING;

                    if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                        if (currentState == ScreenState.SYNERGY_GRID) {
                            SynergyEntry entry = synergyDisplayItems.get(i);
                            if (entry.isUnlocked) {
                                selectedSynergy = entry;
                                buildDetailPages(entry.data);
                                currentState = ScreenState.SYNERGY_DETAIL;
                                updatePageButtons();
                                playPageTurnSound();
                            } else if (this.client != null) {
                                this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_CHEST_LOCKED, 1.5F));
                            }
                        } else {
                            FoodEntry entry = displayItems.get(i);
                            if (entry.isUnlocked) {
                                selectedEntry = entry;
                                buildDetailPages(entry.data);
                                currentState = ScreenState.FOOD_DETAIL;
                                updatePageButtons();
                                playPageTurnSound();
                            } else if (this.client != null) {
                                this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_CHEST_LOCKED, 1.5F));
                            }
                        }
                        return true;
                    }
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

    private static class RenderLine {
        final String text;
        final int color;
        final int height;
        final int offsetX;

        RenderLine(String text, int color, int height, int offsetX) {
            this.text = text;
            this.color = color;
            this.height = height;
            this.offsetX = offsetX;
        }
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

    private static class SynergyEntry {
        final FoodSynergyData data;
        final List<ItemStack> reqStacks;
        final boolean isUnlocked;
        float hoverScale = 1.0f;

        SynergyEntry(FoodSynergyData data, List<ItemStack> reqStacks, boolean isUnlocked) {
            this.data = data;
            this.reqStacks = reqStacks;
            this.isUnlocked = isUnlocked;
        }
    }
}