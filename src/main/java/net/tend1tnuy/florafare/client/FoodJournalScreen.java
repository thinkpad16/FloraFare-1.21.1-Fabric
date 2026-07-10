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
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
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

    // Palette constants for consistent coloring
    private static final int COLOR_INK_DARK    = 0x1A1008;
    private static final int COLOR_INK_MID     = 0x3B2A14;
    private static final int COLOR_INK_LIGHT   = 0x5C4A2A;
    private static final int COLOR_INK_FAINT   = 0x7A6040;
    private static final int COLOR_GOLD        = 0xAA8800;
    private static final int COLOR_GOLD_BRIGHT = 0xFFDD00;
    private static final int COLOR_RED_DARK    = 0x7A1010;
    private static final int COLOR_SLOT_BG     = 0x18100800;  // warm dark tint
    private static final int COLOR_SLOT_BORDER = 0x22503010;
    private static final int COLOR_DIVIDER     = 0x55503010;
    private static final int COLOR_LOCKED_OVERLAY = 0x99000000;

    private enum ScreenState {
        INDEX, FOOD_GRID, SYNERGY_GRID, FOOD_DETAIL, SYNERGY_DETAIL
    }

    private ScreenState currentState = ScreenState.INDEX;
    private int currentPage = 0, maxPages = 1;
    private int unlockedCount = 0, unlockedSynergyCount = 0;

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
            String target = data.target();
            if (!target.startsWith("potion:")) continue;
            Identifier id = Identifier.tryParse(target.substring("potion:".length()));
            if (id == null) continue;
            RegistryEntry.Reference<Potion> potionEntry = Registries.POTION.getEntry(id).orElse(null);
            if (potionEntry == null) continue;
            ItemStack displayStack = new ItemStack(Items.POTION);
            displayStack.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(potionEntry));
            boolean isUnlocked = discovered.contains(target);
            if (isUnlocked) unlockedCount++;
            displayItems.add(new FoodEntry(displayStack, data, isUnlocked));
        }

        for (net.minecraft.item.Item item : Registries.ITEM) {
            ItemStack stack = item.getDefaultStack();
            FoodBuffData data = FoodBuffManager.getConfig(stack);
            if (data == null || data.target().startsWith("potion:")) continue;
            String itemId = Registries.ITEM.getId(item).toString();
            boolean isUnlocked = discovered.contains(itemId) || discovered.contains(data.target());
            if (isUnlocked) unlockedCount++;
            displayItems.add(new FoodEntry(stack, data, isUnlocked));
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
            List<List<ItemStack>> reqStacks = new ArrayList<>();
            for (String req : syn.requirements()) {
                List<ItemStack> stacks = resolveRequirementStacks(req);
                if (!stacks.isEmpty()) {
                    reqStacks.add(stacks);
                }
            }
            synergyDisplayItems.add(new SynergyEntry(syn, reqStacks, isUnlocked));
        }
    }

    private static List<ItemStack> resolveRequirementStacks(String req) {
        List<ItemStack> stacks = new ArrayList<>();
        if (req.startsWith("#")) {
            Identifier tagId = Identifier.tryParse(req.substring(1));
            if (tagId != null) {
                for (RegistryEntry<net.minecraft.item.Item> entry
                        : Registries.ITEM.iterateEntries(TagKey.of(RegistryKeys.ITEM, tagId))) {
                    stacks.add(entry.value().getDefaultStack());
                }
            }
        } else {
            Identifier id = Identifier.tryParse(req);
            if (id != null && Registries.ITEM.containsId(id)) {
                stacks.add(Registries.ITEM.get(id).getDefaultStack());
            }
        }
        return stacks;
    }

    private static ItemStack cycledStack(List<ItemStack> stacks) {
        if (stacks.isEmpty()) return new ItemStack(Items.APPLE);
        if (stacks.size() == 1) return stacks.get(0);
        int index = (int) ((Util.getMeasuringTimeMs() / 1000L) % stacks.size());
        return stacks.get(index);
    }

    private void buildDetailPages(FoodBuffData data) {
        detailPages.clear();
        detailCurrentPage = 0;
        List<RenderLine> allLines = new ArrayList<>();

        int seconds = data.duration() / 20;
        String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);
        allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.duration", timeStr).getString(), COLOR_INK_MID, 12, 0));
        allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.nutrition", data.nutrition()).getString(), COLOR_INK_MID, 12, 0));
        allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.saturation", data.saturation()).getString(), COLOR_INK_MID, 12, 0));

        buildCommonDetailLines(allLines, data.healthBonus(), data.attributes(), data.effects());
        paginateLines(allLines, false);
    }

    private void buildDetailPages(FoodSynergyData data) {
        detailPages.clear();
        detailCurrentPage = 0;
        List<RenderLine> allLines = new ArrayList<>();

        allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.duration",
                Text.translatable("gui.florafare.journal.duration_dynamic").getString()).getString(), COLOR_INK_MID, 12, 0));

        buildCommonDetailLines(allLines, data.healthBonus(), data.attributes(), data.effects());
        paginateLines(allLines, true);
    }

    private void buildCommonDetailLines(List<RenderLine> allLines, double healthBonus,
                                        List<FoodBuffData.AttributeData> attributes,
                                        List<FoodBuffData.EffectData> effects) {
        if (healthBonus != 0) {
            String sign = healthBonus > 0 ? "+" : "";
            String val = healthBonus % 1 == 0 ? String.valueOf((int) healthBonus) : String.valueOf(healthBonus);
            allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.health", sign + val).getString(), COLOR_INK_MID, 12, 0));
        }

        if (attributes != null && !attributes.isEmpty()) {
            allLines.add(new RenderLine("", COLOR_INK_DARK, 6, 0)); // spacer
            allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.attributes_title").getString(), COLOR_INK_DARK, 14, 0));
            for (FoodBuffData.AttributeData attr : attributes) {
                String attrKey = "florafare.attribute." + attr.attributeId().getPath().replace("generic.", "");
                String attrName = Text.translatable(attrKey).getString();
                String sign = attr.amount() > 0 ? "+" : "";
                String val = attr.operation().contains("multiplied")
                        ? (int) (attr.amount() * 100) + "%"
                        : (attr.amount() % 1 == 0 ? String.valueOf((int) attr.amount()) : String.valueOf(attr.amount()));
                allLines.add(new RenderLine("• " + attrName + ": " + sign + val, COLOR_INK_LIGHT, 11, 4));
            }
        }

        if (effects != null && !effects.isEmpty()) {
            allLines.add(new RenderLine("", COLOR_INK_DARK, 6, 0)); // spacer
            allLines.add(new RenderLine(Text.translatable("gui.florafare.journal.effects_title").getString(), COLOR_INK_DARK, 14, 0));
            for (var effect : effects) {
                String effectKey = "effect." + effect.id().getNamespace() + "." + effect.id().getPath();
                String effectName = Text.translatable(effectKey).getString();
                String lvl = getAmplifierNumeral(effect.amplifier());
                int effectSeconds = effect.duration() / 20;
                String effectTime = String.format(" (%02d:%02d)", effectSeconds / 60, effectSeconds % 60);
                allLines.add(new RenderLine("• " + effectName + lvl + effectTime, COLOR_INK_LIGHT, 11, 4));
            }
        }
    }

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
        // No background dim — keep the vanilla book feel
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int bookX = (this.width - BOOK_WIDTH) / 2;
        int bookY = (this.height - BOOK_HEIGHT) / 2;

        // Draw the book texture
        context.drawTexture(BOOK_TEXTURE, bookX, bookY, 0, 0, BOOK_WIDTH, BOOK_HEIGHT, 256, 256);

        // Draw a very subtle warm vignette inside the book content area
        drawBookVignette(context, bookX, bookY);

        hoveredEntry = null;
        hoveredSynergy = null;
        hoverFoodsIndex = false;
        hoverSynergiesIndex = false;

        switch (currentState) {
            case INDEX        -> drawIndexPage(context, mouseX, mouseY, bookX, bookY);
            case FOOD_GRID    -> drawGridContent(context, mouseX, mouseY, bookX, bookY, false);
            case SYNERGY_GRID -> drawGridContent(context, mouseX, mouseY, bookX, bookY, true);
            case FOOD_DETAIL  -> drawFoodDetailView(context, bookX, bookY);
            case SYNERGY_DETAIL -> drawSynergyDetailView(context, bookX, bookY);
        }

        super.render(context, mouseX, mouseY, delta);

        // Tooltips rendered last so they appear on top
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
                    context.drawTooltip(this.textRenderer,
                            Text.translatable("synergy.florafare." + hoveredSynergy.data.id().replace(":", ".")),
                            mouseX, mouseY);
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

    /**
     * Draws a very faint warm inner shadow around the book's paper area to give
     * it a slight depth / aged-paper feel without obscuring content.
     */
    private void drawBookVignette(DrawContext context, int bookX, int bookY) {
        int left   = bookX + 18;
        int top    = bookY + 10;
        int right  = bookX + BOOK_WIDTH - 18;
        int bottom = bookY + BOOK_HEIGHT - 14;
        int shadow = 0x18200800;

        // top edge
        context.fill(left, top, right, top + 3, shadow);
        // bottom edge
        context.fill(left, bottom - 3, right, bottom, shadow);
        // left edge
        context.fill(left, top, left + 3, bottom, shadow);
        // right edge
        context.fill(right - 3, top, right, bottom, shadow);
    }

    // -------------------------------------------------------------------------
    // INDEX PAGE
    // -------------------------------------------------------------------------

    private void drawIndexPage(DrawContext context, int mouseX, int mouseY, int bookX, int bookY) {
        int centerX = bookX + 93;

        // ── Title ──
        Text mainTitle = Text.translatable("gui.florafare.journal.title");
        int titleWidth = this.textRenderer.getWidth(mainTitle);
        context.drawText(this.textRenderer, mainTitle, centerX - titleWidth / 2, bookY + 20, COLOR_INK_DARK, false);

        // ── Decorative top rule ──
        drawDecorativeDivider(context, centerX, bookY + 32);

        // ── Subtitle ──
        Text subtitle = Text.translatable("gui.florafare.journal.subtitle",
                unlockedCount, displayItems.size());
        int subWidth = this.textRenderer.getWidth(subtitle);
        context.getMatrices().push();
        context.getMatrices().translate(centerX, bookY + 44, 0);
        context.getMatrices().scale(0.8f, 0.8f, 1.0f);
        context.drawText(this.textRenderer, subtitle, -subWidth / 2, 0, COLOR_INK_FAINT, false);
        context.getMatrices().pop();

        // ── Category icons ──
        int iconAreaY = bookY + 78;

        if (FlorafareConfig.enableSynergies) {
            // Two icons side by side
            int foodX  = centerX - 32;
            int synX   = centerX + 16;

            hoverFoodsIndex     = isOverIcon(mouseX, mouseY, foodX, iconAreaY);
            hoverSynergiesIndex = isOverIcon(mouseX, mouseY, synX,  iconAreaY);

            drawCategoryIcon(context, foodX, iconAreaY, Items.APPLE, hoverFoodsIndex);
            drawCategoryLabel(context, foodX + 8, iconAreaY + 20,
                    Text.translatable("gui.florafare.journal.tab.foods_short"), hoverFoodsIndex);

            drawCategoryIcon(context, synX, iconAreaY, Items.ENCHANTED_GOLDEN_APPLE, hoverSynergiesIndex);
            drawCategoryLabel(context, synX + 8, iconAreaY + 20,
                    Text.translatable("gui.florafare.journal.tab.synergies_short"), hoverSynergiesIndex);
        } else {
            // Single centered icon
            int foodX = centerX - 8;
            hoverFoodsIndex = isOverIcon(mouseX, mouseY, foodX, iconAreaY);
            drawCategoryIcon(context, foodX, iconAreaY, Items.APPLE, hoverFoodsIndex);
            drawCategoryLabel(context, foodX + 8, iconAreaY + 20,
                    Text.translatable("gui.florafare.journal.tab.foods_short"), hoverFoodsIndex);
        }

        // ── Decorative bottom rule ──
        drawDecorativeDivider(context, centerX, bookY + 138);
    }

    /** Returns true if the mouse is within the 20×20 hit-box of a category icon. */
    private boolean isOverIcon(int mouseX, int mouseY, int iconX, int iconY) {
        return mouseX >= iconX - 2 && mouseX < iconX + 18
                && mouseY >= iconY - 2 && mouseY < iconY + 18;
    }

    /**
     * Draws a category icon with a subtle highlight box when hovered.
     * The icon is scaled up slightly when hovered to give a "lift" effect.
     */
    private void drawCategoryIcon(DrawContext context, int x, int y,
                                  net.minecraft.item.Item item, boolean isHovered) {
        // Hover highlight box
        if (isHovered) {
            context.fill(x - 3, y - 3, x + 19, y + 19, 0x22FFDD88);
            context.drawBorder(x - 3, y - 3, 22, 22, 0x55AA8800);
        } else {
            context.fill(x - 2, y - 2, x + 18, y + 18, 0x11100800);
            context.drawBorder(x - 2, y - 2, 20, 20, 0x22503010);
        }

        float scale = isHovered ? 1.35f : 1.1f;
        context.getMatrices().push();
        context.getMatrices().translate(x + 8, y + 8, 0);
        context.getMatrices().scale(scale, scale, 1.0f);
        context.drawItem(new ItemStack(item), -8, -8);
        context.getMatrices().pop();
    }

    /**
     * Draws a small centered label beneath a category icon.
     * The label is gold-colored when hovered.
     */
    private void drawCategoryLabel(DrawContext context, int centerX, int y, Text label, boolean isHovered) {
        int color = isHovered ? COLOR_GOLD : COLOR_INK_FAINT;
        int w = this.textRenderer.getWidth(label);
        context.getMatrices().push();
        context.getMatrices().translate(centerX, y, 0);
        context.getMatrices().scale(0.75f, 0.75f, 1.0f);
        context.drawText(this.textRenderer, label, -w / 2, 0, color, false);
        context.getMatrices().pop();
    }

    /**
     * Draws a "✦ ─── ✦" style decorative horizontal rule centered at (centerX, y).
     */
    private void drawDecorativeDivider(DrawContext context, int centerX, int y) {
        String gem = "✦";
        int gemW = this.textRenderer.getWidth(gem);
        int midY = y + 4;

        // Left line
        context.fill(centerX - 50, midY, centerX - gemW / 2 - 4, midY + 1, COLOR_DIVIDER);
        // Right line
        context.fill(centerX + gemW / 2 + 4, midY, centerX + 50, midY + 1, COLOR_DIVIDER);
        // Center gem
        context.drawText(this.textRenderer, gem, centerX - gemW / 2, y, COLOR_INK_FAINT, false);
    }

    // -------------------------------------------------------------------------
    // GRID PAGE
    // -------------------------------------------------------------------------

    private void drawGridContent(DrawContext context, int mouseX, int mouseY,
                                 int bookX, int bookY, boolean isSynergy) {
        int centerX = bookX + 93;

        // ── Section title ──
        Text titleText = Text.translatable(isSynergy
                ? "gui.florafare.journal.synergies_title"
                : "gui.florafare.journal.tab.foods");
        int titleW = this.textRenderer.getWidth(titleText);
        context.drawText(this.textRenderer, titleText,
                centerX - titleW / 2, bookY + 12, COLOR_INK_DARK, false);

        // Thin underline beneath title
        context.fill(centerX - 40, bookY + 22, centerX + 40, bookY + 23, COLOR_DIVIDER);

        // ── Progress tracker ──
        int unlocked = isSynergy ? unlockedSynergyCount : unlockedCount;
        int total    = isSynergy ? synergyDisplayItems.size() : displayItems.size();
        Text trackerText = Text.translatable("gui.florafare.journal.progress", unlocked, total);
        int trackerColor = (unlocked == total && total > 0) ? COLOR_GOLD : COLOR_INK_FAINT;

        context.getMatrices().push();
        context.getMatrices().translate(centerX, bookY + 26, 0);
        context.getMatrices().scale(0.8f, 0.8f, 1.0f);
        context.drawText(this.textRenderer, trackerText,
                -this.textRenderer.getWidth(trackerText) / 2, 0, trackerColor, false);
        context.getMatrices().pop();

        // ── Page counter ──
        String pageStr = (currentPage + 1) + " / " + maxPages;
        int pageStrW = this.textRenderer.getWidth(pageStr);
        // Small pill background
        context.fill(centerX - pageStrW / 2 - 3, bookY + 155,
                     centerX + pageStrW / 2 + 3, bookY + 165, 0x22100800);
        context.drawText(this.textRenderer, pageStr,
                centerX - pageStrW / 2, bookY + 157, COLOR_INK_FAINT, false);

        // ── Item grid ──
        int startX = bookX + 37;
        int startY = bookY + 36;
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int totalItems = isSynergy ? synergyDisplayItems.size() : displayItems.size();
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalItems);

        for (int i = startIndex; i < endIndex; i++) {
            int localIndex = i - startIndex;
            int col = localIndex % ITEMS_PER_ROW;
            int row = localIndex / ITEMS_PER_ROW;
            int x = startX + col * ITEM_SPACING;
            int y = startY + row * ITEM_SPACING;

            boolean isHovered = (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16);

            // Slot background — alternate rows for subtle checkerboard warmth
            int slotBg = (row % 2 == col % 2) ? 0x14100800 : 0x0C100800;
            context.fill(x - 2, y - 2, x + 18, y + 18, slotBg);

            if (isHovered) {
                // Bright hover highlight
                context.fill(x - 2, y - 2, x + 18, y + 18, 0x33FFDD88);
                context.drawBorder(x - 2, y - 2, 20, 20, 0x88AA8800);
            } else {
                context.drawBorder(x - 2, y - 2, 20, 20, 0x1A503010);
            }

            ItemStack stackToDraw;
            boolean isUnlocked;
            float scale;

            if (isSynergy) {
                SynergyEntry entry = synergyDisplayItems.get(i);
                entry.hoverScale = MathHelper.lerp(0.25f, entry.hoverScale, isHovered ? 1.25f : 1.0f);
                scale = entry.hoverScale;
                isUnlocked = entry.isUnlocked;
                stackToDraw = entry.reqStacks.isEmpty()
                        ? new ItemStack(Items.APPLE)
                        : cycledStack(entry.reqStacks.get(0));
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
                context.fill(x, y, x + 16, y + 16, COLOR_LOCKED_OVERLAY);
                // Centered "?" glyph
                context.drawText(this.textRenderer, "?", x + 5, y + 4, 0xCCCCCC, false);
            } else if (isSynergy) {
                // Small gold star badge in the top-left corner
                context.getMatrices().translate(0, 0, 200);
                context.drawText(this.textRenderer, "✦", x - 1, y - 2, COLOR_GOLD_BRIGHT, false);
            }

            context.getMatrices().pop();
        }
    }

    // -------------------------------------------------------------------------
    // FOOD DETAIL VIEW
    // -------------------------------------------------------------------------

    private void drawFoodDetailView(DrawContext context, int bookX, int bookY) {
        int paperLeft  = bookX + 36;
        int paperWidth = 114;
        int px = paperLeft;

        if (detailCurrentPage == 0) {
            // ── Large item icon with drop-shadow ──
            int iconCenterX = paperLeft + paperWidth / 2;
            int iconY = bookY + 20;

            // Shadow
            context.fill(iconCenterX - 14, iconY + 2, iconCenterX + 18, iconY + 34, 0x22000000);

            context.getMatrices().push();
            context.getMatrices().translate(iconCenterX - 16, iconY, 0);
            context.getMatrices().scale(2.0f, 2.0f, 1.0f);
            context.drawItem(selectedEntry.stack, 0, 0);
            context.getMatrices().pop();

            // ── Item name ──
            int nameY = bookY + 56;
            String name = selectedEntry.stack.getName().getString();
            int nameWidth = this.textRenderer.getWidth(name);
            if (nameWidth > paperWidth - 4) {
                name = this.textRenderer.trimToWidth(name, paperWidth - 14) + "…";
                nameWidth = this.textRenderer.getWidth(name);
            }
            context.drawText(this.textRenderer, name,
                    paperLeft + (paperWidth - nameWidth) / 2, nameY, COLOR_INK_DARK, false);

            // Underline beneath name
            context.fill(paperLeft + 8, nameY + 11, paperLeft + paperWidth - 8, nameY + 12, COLOR_DIVIDER);
        }

        // ── Detail lines ──
        int py = bookY + (detailCurrentPage == 0 ? 74 : 22);
        drawDetailLines(context, px, py, paperWidth);

        // ── Page counter ──
        drawDetailPageCounter(context, bookX, bookY);
    }

    // -------------------------------------------------------------------------
    // SYNERGY DETAIL VIEW
    // -------------------------------------------------------------------------

    private void drawSynergyDetailView(DrawContext context, int bookX, int bookY) {
        int paperLeft  = bookX + 36;
        int paperWidth = 114;
        int px = paperLeft;

        if (detailCurrentPage == 0) {
            int py = bookY + 20;
            int reqCount = selectedSynergy.reqStacks.size();

            // ── Ingredient icons with "+" separators ──
            // Each icon is 16px wide; separators are ~6px wide; total width is computed
            // so the row is centered on the paper.
            int separatorW = 6;
            int totalReqWidth = reqCount * 16 + (reqCount - 1) * (4 + separatorW);
            int startX = paperLeft + (paperWidth - totalReqWidth) / 2;

            for (int i = 0; i < reqCount; i++) {
                int ix = startX + i * (16 + 4 + separatorW);

                // Subtle slot box
                context.fill(ix - 1, py - 1, ix + 17, py + 17, 0x14100800);
                context.drawBorder(ix - 1, py - 1, 18, 18, 0x22503010);

                context.drawItem(cycledStack(selectedSynergy.reqStacks.get(i)), ix, py);

                // "+" between icons (not after the last one)
                if (i < reqCount - 1) {
                    int plusX = ix + 18;
                    context.drawText(this.textRenderer, "+", plusX, py + 4, COLOR_INK_FAINT, false);
                }
            }

            py += 24;

            // ── Synergy name ──
            String name = Text.translatable("synergy.florafare."
                    + selectedSynergy.data.id().replace(":", ".")).getString();
            int nameWidth = this.textRenderer.getWidth(name);
            if (nameWidth > paperWidth - 4) {
                name = this.textRenderer.trimToWidth(name, paperWidth - 14) + "…";
                nameWidth = this.textRenderer.getWidth(name);
            }
            context.drawText(this.textRenderer, name,
                    paperLeft + (paperWidth - nameWidth) / 2, py, COLOR_RED_DARK, false);

            // Underline beneath name
            context.fill(paperLeft + 8, py + 11, paperLeft + paperWidth - 8, py + 12, COLOR_DIVIDER);
        }

        // ── Detail lines ──
        int py = bookY + (detailCurrentPage == 0 ? 64 : 22);
        drawDetailLines(context, px, py, paperWidth);

        // ── Page counter ──
        drawDetailPageCounter(context, bookX, bookY);
    }

    // -------------------------------------------------------------------------
    // SHARED DETAIL HELPERS
    // -------------------------------------------------------------------------

    /**
     * Renders the current detail page's lines starting at (px, startY).
     * Section-header lines (height == 14) are drawn slightly bolder by rendering
     * them twice with a 1-pixel offset for a faux-bold effect.
     */
    private void drawDetailLines(DrawContext context, int px, int startY, int paperWidth) {
        int py = startY;
        for (RenderLine line : detailPages.get(detailCurrentPage)) {
            if (line.text.isEmpty()) {
                py += line.height;
                continue;
            }

            int drawY = py;
            if (line.height == 14) {
                // Section header — draw a subtle underline and use slightly larger spacing
                drawY += 2;
                context.drawText(this.textRenderer, line.text, px + line.offsetX, drawY, line.color, false);
                // Faux-bold: render again 1px to the right
                context.drawText(this.textRenderer, line.text, px + line.offsetX + 1, drawY, line.color, false);
                // Thin rule beneath header
                int headerW = this.textRenderer.getWidth(line.text);
                context.fill(px + line.offsetX, drawY + 10,
                             px + line.offsetX + Math.min(headerW, paperWidth - 8), drawY + 11,
                             0x33503010);
            } else {
                context.drawText(this.textRenderer, line.text, px + line.offsetX, drawY, line.color, false);
            }
            py += line.height;
        }
    }

    /** Draws the "X / Y" page counter centered at the bottom of the book. */
    private void drawDetailPageCounter(DrawContext context, int bookX, int bookY) {
        int centerX = bookX + 93;
        String pageStr = (detailCurrentPage + 1) + " / " + detailPages.size();
        int pageStrW = this.textRenderer.getWidth(pageStr);
        context.fill(centerX - pageStrW / 2 - 3, bookY + 155,
                     centerX + pageStrW / 2 + 3, bookY + 165, 0x22100800);
        context.drawText(this.textRenderer, pageStr,
                centerX - pageStrW / 2, bookY + 157, COLOR_INK_FAINT, false);
    }

    // -------------------------------------------------------------------------
    // UTILITIES
    // -------------------------------------------------------------------------

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
                int bookX  = (this.width - BOOK_WIDTH) / 2;
                int bookY  = (this.height - BOOK_HEIGHT) / 2;
                int startX = bookX + 37;
                int startY = bookY + 36;

                int startIndex = currentPage * ITEMS_PER_PAGE;
                int totalItems = currentState == ScreenState.SYNERGY_GRID
                        ? synergyDisplayItems.size() : displayItems.size();
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
                                this.client.getSoundManager().play(
                                        PositionedSoundInstance.master(SoundEvents.BLOCK_CHEST_LOCKED, 1.5F));
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
                                this.client.getSoundManager().play(
                                        PositionedSoundInstance.master(SoundEvents.BLOCK_CHEST_LOCKED, 1.5F));
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
            this.client.getSoundManager().play(
                    PositionedSoundInstance.master(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.0F));
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // -------------------------------------------------------------------------
    // DATA CLASSES
    // -------------------------------------------------------------------------

    private static class RenderLine {
        final String text;
        final int color;
        final int height;
        final int offsetX;

        RenderLine(String text, int color, int height, int offsetX) {
            this.text    = text;
            this.color   = color;
            this.height  = height;
            this.offsetX = offsetX;
        }
    }

    private static class FoodEntry {
        final ItemStack stack;
        final FoodBuffData data;
        final boolean isUnlocked;
        float hoverScale = 1.0f;

        FoodEntry(ItemStack stack, FoodBuffData data, boolean isUnlocked) {
            this.stack      = stack;
            this.data       = data;
            this.isUnlocked = isUnlocked;
        }
    }

    private static class SynergyEntry {
        final FoodSynergyData data;
        /** One list per requirement; tag requirements hold every item in the tag. */
        final List<List<ItemStack>> reqStacks;
        final boolean isUnlocked;
        float hoverScale = 1.0f;

        SynergyEntry(FoodSynergyData data, List<List<ItemStack>> reqStacks, boolean isUnlocked) {
            this.data       = data;
            this.reqStacks  = reqStacks;
            this.isUnlocked = isUnlocked;
        }
    }
}
