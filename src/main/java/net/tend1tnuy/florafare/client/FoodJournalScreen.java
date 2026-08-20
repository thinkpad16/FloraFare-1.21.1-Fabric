package net.tend1tnuy.florafare.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.PageTurnWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FoodJournalScreen extends Screen {

    // -------------------------------------------------------------------------
    // TEXTURE & LAYOUT CONSTANTS
    // -------------------------------------------------------------------------

    private static final Identifier BOOK_TEXTURE =
            Identifier.of("minecraft", "textures/gui/book.png");

    private static final int BOOK_WIDTH  = 192;
    private static final int BOOK_HEIGHT = 192;
    private static final int BOOK_X_SHIFT = 20; // nudges the whole panel right of dead-center

    // Grid layout — one row shorter than the book could fit, to make room for the search bar.
    private static final int ITEMS_PER_ROW  = 5;
    private static final int ROWS_PER_PAGE  = 4;
    private static final int ITEMS_PER_PAGE = ITEMS_PER_ROW * ROWS_PER_PAGE;
    private static final int ITEM_SPACING   = 23;

    // Named pixel offsets — all relative to bookX / bookY
    private static final int GRID_START_X_OFFSET   = 37;
    private static final int GRID_START_Y_OFFSET   = 60;

    // Search bar — a single parchment "card" spanning the *same* left/right edges as
    // the item grid below it (GRID_START_X_OFFSET - 2, out to the last column's right
    // edge), so the two visually line up instead of the card floating narrower/offset
    // from the grid. TextFieldWidget with drawsBackground(false) applies NONE of
    // vanilla's usual padding (no 4px left inset, no vertical centering of the text
    // row within its height), so both are computed by hand below via the search*()
    // helpers — that omission is what previously left the placeholder text flush
    // against the card's border and jammed against its own icon.
    private static final int SEARCH_BAR_Y_OFFSET    = 42;
    private static final int SEARCH_BAR_HEIGHT      = 13;
    private static final int SEARCH_BAR_LEFT_OFFSET = GRID_START_X_OFFSET - 2;
    private static final int SEARCH_BAR_WIDTH       = (ITEMS_PER_ROW - 1) * ITEM_SPACING + 20;
    private static final int SEARCH_BAR_PAD_X       = 4;
    private static final int SEARCH_ICON_SIZE       = 7;
    private static final int SEARCH_ICON_TEXT_GAP   = 4;
    private static final int SEARCH_FIELD_HEIGHT    = 8;
    private static final int TITLE_Y_OFFSET         = 12;
    private static final int TITLE_UNDERLINE_Y      = 22;
    private static final int PROGRESS_Y_OFFSET      = 26;
    private static final int PAGE_COUNTER_Y_OFFSET  = 157;
    private static final int PAGE_COUNTER_BG_TOP    = 155;
    private static final int PAGE_COUNTER_BG_BOTTOM = 165;
    private static final int PREV_BTN_X_OFFSET      = 43;
    private static final int NEXT_BTN_X_OFFSET      = 116;
    private static final int BTN_Y_OFFSET           = 157;
    private static final int INDEX_TITLE_Y          = 20;
    private static final int INDEX_DIVIDER_TOP_Y    = 32;
    private static final int INDEX_SUBTITLE_Y       = 44;
    private static final int INDEX_ICON_Y           = 78;
    private static final int INDEX_DIVIDER_BOT_Y    = 138;
    private static final int BOOK_CENTER_X_OFFSET   = 93; // half of BOOK_WIDTH

    // Detail view offsets
    private static final int DETAIL_ICON_Y_OFFSET      = 20;
    private static final int DETAIL_NAME_Y_OFFSET      = 56;
    private static final int DETAIL_LINES_Y_PAGE0      = 74;
    private static final int DETAIL_LINES_Y_SUBSEQUENT = 22;
    private static final int SYNERGY_LINES_Y_PAGE0     = 64;
    private static final int PAPER_LEFT_OFFSET         = 36;
    private static final int PAPER_WIDTH               = 114;

    // -------------------------------------------------------------------------
    // COLOUR PALETTE
    // -------------------------------------------------------------------------

    private static final int COLOR_INK_DARK       = 0x1A1008;
    private static final int COLOR_INK_MID        = 0x3B2A14;
    private static final int COLOR_INK_LIGHT      = 0x5C4A2A;
    private static final int COLOR_INK_FAINT      = 0x7A6040;
    private static final int COLOR_GOLD           = 0xAA8800;
    private static final int COLOR_GOLD_BRIGHT    = 0xFFDD00;
    private static final int COLOR_RED_DARK       = 0x7A1010;
    private static final int COLOR_DIVIDER        = 0x55503010;
    private static final int COLOR_LOCKED_OVERLAY = 0x99000000;

    // Search field — a light card so typed/placeholder text has clean contrast against
    // the vanilla book page, with a soft translucent border (not a hard opaque outline,
    // which read as too heavy/"fat" next to the rest of the book's thin ink lines).
    private static final int COLOR_SEARCH_BOX_BG     = 0xFFF3E6C6;
    private static final int COLOR_SEARCH_BOX_BORDER = 0x66503010;
    // Translucent ink wash for selected text — vanilla's selection is a hard inverted
    // block, which looks wrong on parchment.
    private static final int COLOR_SEARCH_SELECTION  = 0x553B2A14;

    // -------------------------------------------------------------------------
    // SCREEN STATE
    // -------------------------------------------------------------------------

    private enum ScreenState {
        INDEX, FOOD_GRID, SYNERGY_GRID, FOOD_DETAIL, SYNERGY_DETAIL
    }

    private ScreenState currentState = ScreenState.INDEX;
    private int currentPage = 0;
    private int maxPages    = 1;

    // Animation timing — content slides/pops into place on open and on every state change.
    private long screenOpenTimeMs = System.currentTimeMillis();
    private long stateEnterTimeMs = System.currentTimeMillis();
    private static final long OPEN_ANIM_MS  = 220L;
    private static final long STATE_ANIM_MS = 180L;

    /** Switches screen state and restarts the content entrance animation. */
    private void setState(ScreenState newState) {
        this.currentState    = newState;
        this.stateEnterTimeMs = System.currentTimeMillis();
    }

    private final List<List<RenderLine>> detailPages = new ArrayList<>();
    private int detailCurrentPage = 0;

    private FoodEntry    selectedEntry   = null;
    private SynergyEntry selectedSynergy = null;
    private FoodEntry    hoveredEntry    = null;
    private SynergyEntry hoveredSynergy  = null;
    private boolean hoverFoodsIndex     = false;
    private boolean hoverSynergiesIndex = false;

    private PageTurnWidget nextPageButton;
    private PageTurnWidget previousPageButton;
    private FlatTextFieldWidget searchField;
    private String searchQuery = "";

    /** Filtered views of {@link JournalDataCache#foods}/{@code synergies}, recomputed on search change. */
    private List<FoodEntry>    filteredFoods     = new ArrayList<>();
    private List<SynergyEntry> filteredSynergies = new ArrayList<>();

    // -------------------------------------------------------------------------
    // DATA CACHE
    // -------------------------------------------------------------------------

    /**
     * Shared cache populated once per config-sync and reused across screen opens.
     * Invalidated by {@link #invalidateCache()} which is called from
     * {@code FlorafareClient} whenever a {@code FoodConfigSyncPayload} or
     * {@code FoodSynergySyncPayload} arrives.
     */
    private static final class JournalDataCache {
        List<FoodEntry>    foods     = new ArrayList<>();
        List<SynergyEntry> synergies = new ArrayList<>();
        int unlockedFoods     = 0;
        int unlockedSynergies = 0;
        boolean valid = false;
    }

    private static final JournalDataCache CACHE = new JournalDataCache();

    /** Call this whenever food configs or synergy configs are re-synced from the server. */
    public static void invalidateCache() {
        CACHE.valid = false;
    }

    // Convenience accessors — the grid/pagination work off the search-filtered view,
    // while progress counters always reflect the full unfiltered set.
    private List<FoodEntry>    displayItems        () { return filteredFoods; }
    private List<SynergyEntry> synergyDisplayItems () { return filteredSynergies; }
    private List<FoodEntry>    allFoodItems        () { return CACHE.foods; }
    private List<SynergyEntry> allSynergyItems     () { return CACHE.synergies; }
    private int unlockedCount        () { return CACHE.unlockedFoods; }
    private int unlockedSynergyCount () { return CACHE.unlockedSynergies; }

    // -------------------------------------------------------------------------
    // CONSTRUCTOR & INIT
    // -------------------------------------------------------------------------

    public FoodJournalScreen() {
        super(Text.translatable("gui.florafare.journal.title"));
    }

    @Override
    protected void init() {
        super.init();

        // Rebuild cache only when stale (config re-synced or first open)
        if (!CACHE.valid) {
            rebuildCache();
        }
        // Re-applies the current search query (empty on a fresh screen instance —
        // search resets each time the journal is reopened) to the freshly built cache.
        rebuildFilteredLists();

        int bookX = bookX();
        int bookY = bookY();

        // Plain, unstyled placeholder — FlatTextFieldWidget draws it with an explicit
        // colour argument, and a Text's own style colour would override that argument.
        Text placeholderText =
                Text.translatable("gui.florafare.journal.search.placeholder");

        // FlatTextFieldWidget instead of a plain TextFieldWidget: vanilla always draws
        // field text via drawTextWithShadow with no public toggle, and that shadow was
        // what made the search text look bold/traced against the parchment while every
        // other line in the book is drawn flat. See the class at the bottom of this file.
        this.searchField = new FlatTextFieldWidget(this.textRenderer,
                searchTextX(bookX), searchTextY(bookY),
                searchTextWidth(bookX), SEARCH_FIELD_HEIGHT,
                placeholderText,
                COLOR_INK_MID, COLOR_INK_FAINT, COLOR_INK_DARK, COLOR_SEARCH_SELECTION);
        this.searchField.setMaxLength(50);
        // Keeps getInnerWidth() equal to the widget width (vanilla subtracts 8 for its
        // own background inset) — our card and padding are drawn by hand instead.
        this.searchField.setDrawsBackground(false);
        this.searchField.setText(searchQuery);
        this.searchField.setChangedListener(this::onSearchChanged);
        // addSelectableChild (not addDrawableChild): keeps click/focus/typing wired up
        // through Screen's normal input dispatch, but takes it out of Screen#render's
        // automatic drawable pass — that pass runs after this screen's own state-enter
        // slide transform is popped, so the field's text used to snap into place
        // instantly while the search bar's hand-drawn card/icon slid in around it.
        // It's rendered manually instead, inside that same transform, from
        // drawGridContent().
        this.addSelectableChild(this.searchField);

        this.previousPageButton = this.addDrawableChild(new PageTurnWidget(
                bookX + PREV_BTN_X_OFFSET, bookY + BTN_Y_OFFSET, false, btn -> {
            if (currentState == ScreenState.FOOD_DETAIL
                    || currentState == ScreenState.SYNERGY_DETAIL) {
                if (detailCurrentPage > 0) {
                    detailCurrentPage--;
                } else {
                    setState((currentState == ScreenState.FOOD_DETAIL)
                            ? ScreenState.FOOD_GRID : ScreenState.SYNERGY_GRID);
                }
            } else if (currentState == ScreenState.FOOD_GRID
                    || currentState == ScreenState.SYNERGY_GRID) {
                if (currentPage > 0) {
                    currentPage--;
                } else {
                    setState(ScreenState.INDEX);
                }
            }
            updatePageButtons();
            playPageTurnSound();
        }, true));

        this.nextPageButton = this.addDrawableChild(new PageTurnWidget(
                bookX + NEXT_BTN_X_OFFSET, bookY + BTN_Y_OFFSET, true, btn -> {
            if (currentState == ScreenState.FOOD_DETAIL
                    || currentState == ScreenState.SYNERGY_DETAIL) {
                if (detailCurrentPage < detailPages.size() - 1) {
                    detailCurrentPage++;
                }
            } else if (currentState == ScreenState.FOOD_GRID
                    || currentState == ScreenState.SYNERGY_GRID) {
                if (currentPage < maxPages - 1) {
                    currentPage++;
                }
            }
            updatePageButtons();
            playPageTurnSound();
        }, true));

        updatePageButtons();
    }

    // -------------------------------------------------------------------------
    // CACHE POPULATION
    // -------------------------------------------------------------------------

    private void rebuildCache() {
        CACHE.foods.clear();
        CACHE.synergies.clear();
        CACHE.unlockedFoods     = 0;
        CACHE.unlockedSynergies = 0;

        if (this.client == null || this.client.player == null) return;

        Set<String> discoveredFoods =
                ((IFoodComponentProvider) this.client.player)
                        .florafare$getFoodComponent().getDiscoveredFoods();

        Set<String> datapackTargets = new HashSet<>();
        for (FoodBuffData data : FoodBuffManager.getAllConfigs()) {
            datapackTargets.add(data.target());
        }

        // Potion-target entries
        for (FoodBuffData data : FoodBuffManager.getAllConfigs()) {
            String target = data.target();
            if (!target.startsWith("potion:")) continue;
            Identifier id = Identifier.tryParse(target.substring("potion:".length()));
            if (id == null) continue;
            RegistryEntry.Reference<Potion> potionEntry =
                    Registries.POTION.getEntry(id).orElse(null);
            if (potionEntry == null) continue;
            ItemStack displayStack = new ItemStack(Items.POTION);
            displayStack.set(DataComponentTypes.POTION_CONTENTS,
                    new PotionContentsComponent(potionEntry));
            boolean isUnlocked = discoveredFoods.contains(target);
            if (isUnlocked) CACHE.unlockedFoods++;
            CACHE.foods.add(new FoodEntry(displayStack, data, isUnlocked));
        }

        // Item-target entries — single registry walk
        for (net.minecraft.item.Item item : Registries.ITEM) {
            ItemStack stack = item.getDefaultStack();
            FoodBuffData data = FoodBuffManager.getConfig(stack);

            if (data == null || data.target().startsWith("potion:")) continue;

            // Skip auto-generated entries — only show foods with an explicit datapack config.
            if (!datapackTargets.contains(data.target())) continue;

            String itemId = Registries.ITEM.getId(item).toString();
            boolean isUnlocked =
                    discoveredFoods.contains(itemId) || discoveredFoods.contains(data.target());
            if (isUnlocked) CACHE.unlockedFoods++;
            CACHE.foods.add(new FoodEntry(stack, data, isUnlocked));
        }

        // Synergy entries
        if (FlorafareConfig.enableSynergies && this.client.player != null) {
            Set<String> discoveredSynergies =
                    ((IFoodComponentProvider) this.client.player)
                            .florafare$getFoodComponent().getDiscoveredSynergies();

            for (FoodSynergyData syn : FoodSynergyManager.getAllSynergies()) {
                boolean isUnlocked = discoveredSynergies.contains(syn.id());
                if (isUnlocked) CACHE.unlockedSynergies++;
                List<List<ItemStack>> reqStacks = new ArrayList<>();
                for (String req : syn.requirements()) {
                    List<ItemStack> stacks = FoodBuffManager.resolveRequirementStacks(req);
                    if (!stacks.isEmpty()) reqStacks.add(stacks);
                }
                CACHE.synergies.add(new SynergyEntry(syn, reqStacks, isUnlocked));
            }
        }

        CACHE.valid = true;
    }

    // -------------------------------------------------------------------------
    // SEARCH
    // -------------------------------------------------------------------------

    private void onSearchChanged(String query) {
        this.searchQuery = query;
        rebuildFilteredLists();
        this.currentPage = 0;
        updatePageButtons();
    }

    /** Recomputes {@link #filteredFoods}/{@link #filteredSynergies} from the current query. */
    private void rebuildFilteredLists() {
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);

        if (query.isEmpty()) {
            filteredFoods     = CACHE.foods;
            filteredSynergies = CACHE.synergies;
            return;
        }

        filteredFoods = new ArrayList<>();
        for (FoodEntry entry : CACHE.foods) {
            if (matchesQuery(entry.stack.getName().getString(), entry.data.effects(),
                    entry.data.attributes(), query)) {
                filteredFoods.add(entry);
            }
        }

        filteredSynergies = new ArrayList<>();
        for (SynergyEntry entry : CACHE.synergies) {
            String name = Text.translatable(
                    "synergy.florafare." + entry.data.id().replace(":", ".")).getString();
            if (matchesQuery(name, entry.data.effects(), entry.data.attributes(), query)) {
                filteredSynergies.add(entry);
            }
        }
    }

    /**
     * Matches a display name and its granted effects/attributes against a lowercase
     * search query. Locked entries are matched the same as unlocked ones — they still
     * render as {@code ?} in the grid, only whether they appear in the filtered list
     * changes — consistent with the journal's "browse everything, unlock the detail
     * view" model.
     */
    private boolean matchesQuery(String displayName, List<FoodBuffData.EffectData> effects,
                                 List<FoodBuffData.AttributeData> attributes, String query) {
        if (displayName.toLowerCase(Locale.ROOT).contains(query)) return true;

        for (FoodBuffData.EffectData effect : effects) {
            String effectName = Text.translatable(
                    "effect." + effect.id().getNamespace() + "." + effect.id().getPath()).getString();
            if (effectName.toLowerCase(Locale.ROOT).contains(query)) return true;
        }

        for (FoodBuffData.AttributeData attr : attributes) {
            String attrKey = "florafare.attribute." + attr.attributeId().getPath().replace("generic.", "");
            String attrName = Text.translatable(attrKey).getString();
            if (attrName.toLowerCase(Locale.ROOT).contains(query)) return true;
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // PAGE BUTTON MANAGEMENT
    // -------------------------------------------------------------------------

    private void updatePageButtons() {
        recalculateMaxPages();

        if (currentState == ScreenState.INDEX) {
            this.previousPageButton.visible = false;
            this.nextPageButton.visible     = false;
        } else if (currentState == ScreenState.FOOD_DETAIL
                || currentState == ScreenState.SYNERGY_DETAIL) {
            this.previousPageButton.visible = true;
            this.nextPageButton.visible     = this.detailCurrentPage < this.detailPages.size() - 1;
        } else {
            this.previousPageButton.visible = true;
            this.nextPageButton.visible     = this.currentPage < this.maxPages - 1;
        }

        boolean showSearch = currentState == ScreenState.FOOD_GRID
                || currentState == ScreenState.SYNERGY_GRID;
        this.searchField.setVisible(showSearch);
        if (!showSearch) {
            this.searchField.setFocused(false);
        }
    }

    private void recalculateMaxPages() {
        if (currentState == ScreenState.FOOD_GRID) {
            this.maxPages = Math.max(1,
                    (int) Math.ceil((double) displayItems().size() / ITEMS_PER_PAGE));
        } else if (currentState == ScreenState.SYNERGY_GRID) {
            this.maxPages = Math.max(1,
                    (int) Math.ceil((double) synergyDisplayItems().size() / ITEMS_PER_PAGE));
        } else {
            this.maxPages = 1;
        }
    }

    // -------------------------------------------------------------------------
    // DETAIL PAGE BUILDING
    // -------------------------------------------------------------------------

    private void buildDetailPages(FoodBuffData data) {
        detailPages.clear();
        detailCurrentPage = 0;
        List<RenderLine> allLines = new ArrayList<>();

        int seconds = data.duration() / 20;
        String timeStr = String.format("%02d:%02d", seconds / 60, seconds % 60);
        allLines.add(new RenderLine(
                Text.translatable("gui.florafare.journal.duration", timeStr).getString(),
                COLOR_INK_MID, 12, 0));
        allLines.add(new RenderLine(
                Text.translatable("gui.florafare.journal.nutrition", data.nutrition()).getString(),
                COLOR_INK_MID, 12, 0));
        allLines.add(new RenderLine(
                Text.translatable("gui.florafare.journal.saturation", data.saturation()).getString(),
                COLOR_INK_MID, 12, 0));

        buildCommonDetailLines(allLines, data.healthBonus(), data.attributes(), data.effects());
        paginateLines(allLines, false);
    }

    private void buildDetailPages(FoodSynergyData data) {
        detailPages.clear();
        detailCurrentPage = 0;
        List<RenderLine> allLines = new ArrayList<>();

        allLines.add(new RenderLine(
                Text.translatable("gui.florafare.journal.duration",
                                Text.translatable("gui.florafare.journal.duration_dynamic").getString())
                        .getString(),
                COLOR_INK_MID, 12, 0));

        buildCommonDetailLines(allLines, data.healthBonus(), data.attributes(), data.effects());
        paginateLines(allLines, true);
    }

    private void buildCommonDetailLines(List<RenderLine> allLines, double healthBonus,
                                        List<FoodBuffData.AttributeData> attributes,
                                        List<FoodBuffData.EffectData> effects) {
        if (healthBonus != 0) {
            String sign = healthBonus > 0 ? "+" : "";
            String val  = healthBonus % 1 == 0
                    ? String.valueOf((int) healthBonus) : String.valueOf(healthBonus);
            allLines.add(new RenderLine(
                    Text.translatable("gui.florafare.journal.health", sign + val).getString(),
                    COLOR_INK_MID, 12, 0));
        }

        if (attributes != null && !attributes.isEmpty()) {
            allLines.add(new RenderLine("", COLOR_INK_DARK, 6, 0));
            allLines.add(new RenderLine(
                    Text.translatable("gui.florafare.journal.attributes_title").getString(),
                    COLOR_INK_DARK, 14, 0, true));
            for (FoodBuffData.AttributeData attr : attributes) {
                String attrKey  = "florafare.attribute."
                        + attr.attributeId().getPath().replace("generic.", "");
                String attrName = Text.translatable(attrKey).getString();
                String sign     = attr.amount() > 0 ? "+" : "";
                String val      = attr.operation().contains("multiplied")
                        ? (int) (attr.amount() * 100) + "%"
                        : (attr.amount() % 1 == 0
                        ? String.valueOf((int) attr.amount())
                        : String.valueOf(attr.amount()));
                allLines.add(new RenderLine(
                        "• " + attrName + ": " + sign + val, COLOR_INK_LIGHT, 11, 4));
            }
        }

        if (effects != null && !effects.isEmpty()) {
            allLines.add(new RenderLine("", COLOR_INK_DARK, 6, 0));
            allLines.add(new RenderLine(
                    Text.translatable("gui.florafare.journal.effects_title").getString(),
                    COLOR_INK_DARK, 14, 0, true));
            for (var effect : effects) {
                String effectKey  = "effect." + effect.id().getNamespace()
                        + "." + effect.id().getPath();
                String effectName = Text.translatable(effectKey).getString();
                String lvl        = getAmplifierNumeral(effect.amplifier());
                int    effectSecs = effect.duration() / 20;
                String effectTime = String.format(" (%02d:%02d)",
                        effectSecs / 60, effectSecs % 60);
                allLines.add(new RenderLine(
                        "• " + effectName + lvl + effectTime, COLOR_INK_LIGHT, 11, 4));
            }
        }
    }

    private void paginateLines(List<RenderLine> allLines, boolean isSynergy) {
        List<RenderLine> currentPageLines = new ArrayList<>();
        int currentY = isSynergy ? 66 : 76;
        int maxY     = 150;

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

    // -------------------------------------------------------------------------
    // RENDERING — TOP LEVEL
    // -------------------------------------------------------------------------

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // No background dim — keep the vanilla book feel.
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int bookX = bookX();
        int bookY = bookY();

        // Opening pop: the whole book scales in from ~97% to 100%, eased out — a light
        // settle rather than a big springy pop. Only affects drawing, not widget
        // hit-boxes — harmless since page buttons/search are invisible during the
        // INDEX state, which is always what's showing when this first plays.
        long  sinceOpen  = System.currentTimeMillis() - screenOpenTimeMs;
        float openT      = MathHelper.clamp(sinceOpen / (float) OPEN_ANIM_MS, 0.0f, 1.0f);
        float openEased  = 1.0f - (1.0f - openT) * (1.0f - openT) * (1.0f - openT);
        float openScale  = 0.97f + 0.03f * openEased;
        float pivotX     = bookX + BOOK_WIDTH  / 2.0f;
        float pivotY     = bookY + BOOK_HEIGHT / 2.0f;

        context.getMatrices().push();
        context.getMatrices().translate(pivotX, pivotY, 0);
        context.getMatrices().scale(openScale, openScale, 1.0f);
        context.getMatrices().translate(-pivotX, -pivotY, 0);

        // Draw the vanilla book texture — no overlays or vignettes on top of it.
        context.drawTexture(BOOK_TEXTURE, bookX, bookY, 0, 0,
                BOOK_WIDTH, BOOK_HEIGHT, 256, 256);

        hoveredEntry        = null;
        hoveredSynergy      = null;
        hoverFoodsIndex     = false;
        hoverSynergiesIndex = false;

        // Content slide: each time the state changes (index -> grid -> detail, and
        // back), the new content eases up into place instead of snapping in instantly.
        long  sinceState   = System.currentTimeMillis() - stateEnterTimeMs;
        float stateT       = MathHelper.clamp(sinceState / (float) STATE_ANIM_MS, 0.0f, 1.0f);
        float stateEased   = 1.0f - (1.0f - stateT) * (1.0f - stateT);
        float slideOffset  = (1.0f - stateEased) * 6.0f;

        context.getMatrices().push();
        context.getMatrices().translate(0, slideOffset, 0);
        switch (currentState) {
            case INDEX          -> drawIndexPage(context, mouseX, mouseY, bookX, bookY);
            case FOOD_GRID      -> drawGridContent(context, mouseX, mouseY, bookX, bookY, false, delta);
            case SYNERGY_GRID   -> drawGridContent(context, mouseX, mouseY, bookX, bookY, true,  delta);
            case FOOD_DETAIL    -> drawFoodDetailView(context, bookX, bookY);
            case SYNERGY_DETAIL -> drawSynergyDetailView(context, bookX, bookY);
        }
        context.getMatrices().pop();

        context.getMatrices().pop();

        super.render(context, mouseX, mouseY, delta);

        // Tooltips rendered last so they appear on top of everything.
        if (currentState == ScreenState.INDEX) {
            if (hoverFoodsIndex) {
                context.drawTooltip(this.textRenderer,
                        Text.translatable("gui.florafare.journal.tab.foods"), mouseX, mouseY);
            } else if (hoverSynergiesIndex) {
                context.drawTooltip(this.textRenderer,
                        Text.translatable("gui.florafare.journal.tab.synergies"), mouseX, mouseY);
            }
        } else if (currentState == ScreenState.FOOD_GRID
                || currentState == ScreenState.SYNERGY_GRID) {
            if (hoveredSynergy != null) {
                if (!hoveredSynergy.isUnlocked) {
                    context.drawTooltip(this.textRenderer,
                            Text.literal("???").formatted(Formatting.GRAY), mouseX, mouseY);
                } else {
                    context.drawTooltip(this.textRenderer,
                            Text.translatable("synergy.florafare."
                                    + hoveredSynergy.data.id().replace(":", ".")),
                            mouseX, mouseY);
                }
            } else if (hoveredEntry != null) {
                if (!hoveredEntry.isUnlocked) {
                    context.drawTooltip(this.textRenderer,
                            Text.literal("???").formatted(Formatting.GRAY), mouseX, mouseY);
                } else {
                    context.drawTooltip(this.textRenderer,
                            hoveredEntry.stack.getName(), mouseX, mouseY);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // INDEX PAGE
    // -------------------------------------------------------------------------

    private void drawIndexPage(DrawContext context, int mouseX, int mouseY,
                               int bookX, int bookY) {
        int centerX = bookX + BOOK_CENTER_X_OFFSET;

        // Title
        Text mainTitle  = Text.translatable("gui.florafare.journal.title");
        int  titleWidth = this.textRenderer.getWidth(mainTitle);
        context.drawText(this.textRenderer, mainTitle,
                centerX - titleWidth / 2, bookY + INDEX_TITLE_Y, COLOR_INK_DARK, false);

        // Decorative top rule
        drawDecorativeDivider(context, centerX, bookY + INDEX_DIVIDER_TOP_Y);

        // Subtitle
        Text subtitle = Text.translatable("gui.florafare.journal.subtitle",
                unlockedCount(), allFoodItems().size());
        int subWidth = this.textRenderer.getWidth(subtitle);
        context.getMatrices().push();
        context.getMatrices().translate(centerX, bookY + INDEX_SUBTITLE_Y, 0);
        context.getMatrices().scale(0.8f, 0.8f, 1.0f);
        context.drawText(this.textRenderer, subtitle, -subWidth / 2, 0, COLOR_INK_FAINT, false);
        context.getMatrices().pop();

        // Category icons
        int iconAreaY = bookY + INDEX_ICON_Y;

        if (FlorafareConfig.enableSynergies) {
            int foodX = centerX - 32;
            int synX  = centerX + 16;

            hoverFoodsIndex     = isOverIcon(mouseX, mouseY, foodX, iconAreaY);
            hoverSynergiesIndex = isOverIcon(mouseX, mouseY, synX,  iconAreaY);

            drawCategoryIcon(context, foodX, iconAreaY, Items.APPLE, hoverFoodsIndex);
            drawCategoryLabel(context, foodX + 8, iconAreaY + 20,
                    Text.translatable("gui.florafare.journal.tab.foods_short"), hoverFoodsIndex);

            drawCategoryIcon(context, synX, iconAreaY,
                    Items.ENCHANTED_GOLDEN_APPLE, hoverSynergiesIndex);
            drawCategoryLabel(context, synX + 8, iconAreaY + 20,
                    Text.translatable("gui.florafare.journal.tab.synergies_short"),
                    hoverSynergiesIndex);
        } else {
            int foodX = centerX - 8;
            hoverFoodsIndex = isOverIcon(mouseX, mouseY, foodX, iconAreaY);
            drawCategoryIcon(context, foodX, iconAreaY, Items.APPLE, hoverFoodsIndex);
            drawCategoryLabel(context, foodX + 8, iconAreaY + 20,
                    Text.translatable("gui.florafare.journal.tab.foods_short"), hoverFoodsIndex);
        }

        // Decorative bottom rule
        drawDecorativeDivider(context, centerX, bookY + INDEX_DIVIDER_BOT_Y);
    }

    private boolean isOverIcon(int mouseX, int mouseY, int iconX, int iconY) {
        return mouseX >= iconX - 2 && mouseX < iconX + 18
                && mouseY >= iconY - 2 && mouseY < iconY + 18;
    }

    private void drawCategoryIcon(DrawContext context, int x, int y,
                                  net.minecraft.item.Item item, boolean isHovered) {
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

    private void drawCategoryLabel(DrawContext context, int centerX, int y,
                                   Text label, boolean isHovered) {
        int color = isHovered ? COLOR_GOLD : COLOR_INK_FAINT;
        int w     = this.textRenderer.getWidth(label);
        context.getMatrices().push();
        context.getMatrices().translate(centerX, y, 0);
        context.getMatrices().scale(0.75f, 0.75f, 1.0f);
        context.drawText(this.textRenderer, label, -w / 2, 0, color, false);
        context.getMatrices().pop();
    }

    private void drawDecorativeDivider(DrawContext context, int centerX, int y) {
        String gem  = "✦";
        int    gemW = this.textRenderer.getWidth(gem);
        int    midY = y + 4;

        context.fill(centerX - 50, midY, centerX - gemW / 2 - 4, midY + 1, COLOR_DIVIDER);
        context.fill(centerX + gemW / 2 + 4, midY, centerX + 50, midY + 1, COLOR_DIVIDER);
        context.drawText(this.textRenderer, gem, centerX - gemW / 2, y, COLOR_INK_FAINT, false);
    }

    /** Small hand-drawn magnifying glass (lens ring + diagonal handle) — no texture asset needed. */
    private void drawMagnifyingGlass(DrawContext context, int x, int y) {
        int color = 0xFF5C4A2A; // COLOR_INK_LIGHT, made opaque for fill/drawBorder use
        context.drawBorder(x, y, 5, 5, color);
        context.fill(x + 4, y + 5, x + 6, y + 6, color);
        context.fill(x + 5, y + 6, x + 7, y + 7, color);
    }

    // -------------------------------------------------------------------------
    // GRID PAGE
    // -------------------------------------------------------------------------

    /**
     * @param delta partial tick — used for frame-rate-independent hover scale lerp.
     */
    private void drawGridContent(DrawContext context, int mouseX, int mouseY,
                                 int bookX, int bookY, boolean isSynergy, float delta) {
        int centerX = bookX + BOOK_CENTER_X_OFFSET;

        // Section title
        Text titleText = Text.translatable(isSynergy
                ? "gui.florafare.journal.synergies_title"
                : "gui.florafare.journal.dishes_title");
        int titleW = this.textRenderer.getWidth(titleText);
        context.drawText(this.textRenderer, titleText,
                centerX - titleW / 2, bookY + TITLE_Y_OFFSET, COLOR_INK_DARK, false);

        context.fill(centerX - 40, bookY + TITLE_UNDERLINE_Y,
                centerX + 40, bookY + TITLE_UNDERLINE_Y + 1, COLOR_DIVIDER);

        // Progress tracker — always against the full (unfiltered) set.
        int  unlocked     = isSynergy ? unlockedSynergyCount() : unlockedCount();
        int  total        = isSynergy ? allSynergyItems().size() : allFoodItems().size();
        Text trackerText  = Text.translatable("gui.florafare.journal.progress", unlocked, total);
        int  trackerColor = (unlocked == total && total > 0) ? COLOR_GOLD : COLOR_INK_FAINT;

        context.getMatrices().push();
        context.getMatrices().translate(centerX, bookY + PROGRESS_Y_OFFSET, 0);
        context.getMatrices().scale(0.8f, 0.8f, 1.0f);
        context.drawText(this.textRenderer, trackerText,
                -this.textRenderer.getWidth(trackerText) / 2, 0, trackerColor, false);
        context.getMatrices().pop();

        // Page counter pill
        String pageStr  = (currentPage + 1) + " / " + maxPages;
        int    pageStrW = this.textRenderer.getWidth(pageStr);
        context.fill(centerX - pageStrW / 2 - 3, bookY + PAGE_COUNTER_BG_TOP,
                centerX + pageStrW / 2 + 3, bookY + PAGE_COUNTER_BG_BOTTOM, 0x22100800);
        context.drawText(this.textRenderer, pageStr,
                centerX - pageStrW / 2, bookY + PAGE_COUNTER_Y_OFFSET, COLOR_INK_FAINT, false);

        // Search bar — a single parchment-styled card the full width of the grid below
        // it, with a hand-drawn magnifying glass inset on the left and the
        // (background-less) vanilla text field inset further in past it; no extra
        // texture assets. Geometry comes from the search*() helpers so this card can
        // never drift out of alignment with the real TextFieldWidget built in init().
        int barLeft = searchBarLeft(bookX);
        int barTop  = searchBarTop(bookY);
        context.fill(barLeft, barTop,
                barLeft + SEARCH_BAR_WIDTH, barTop + SEARCH_BAR_HEIGHT, COLOR_SEARCH_BOX_BG);
        context.drawBorder(barLeft, barTop,
                SEARCH_BAR_WIDTH, SEARCH_BAR_HEIGHT, COLOR_SEARCH_BOX_BORDER);
        drawMagnifyingGlass(context, searchIconX(bookX), searchIconY(bookY));

        // Rendered manually (not by Screen's automatic drawable pass — see the
        // addSelectableChild comment in init()) so the field's own text/caret/
        // placeholder slides in with the rest of this state's content instead of
        // popping into place a frame after the transform below it finishes.
        this.searchField.render(context, mouseX, mouseY, delta);

        // Item grid
        int startX     = bookX + GRID_START_X_OFFSET;
        int startY     = bookY + GRID_START_Y_OFFSET;
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int totalItems = isSynergy ? synergyDisplayItems().size() : displayItems().size();
        int endIndex   = Math.min(startIndex + ITEMS_PER_PAGE, totalItems);

        // Frame-rate-independent lerp factor
        float lerpFactor = 1.0f - (float) Math.pow(0.05, delta);

        for (int i = startIndex; i < endIndex; i++) {
            int localIndex = i - startIndex;
            int col = localIndex % ITEMS_PER_ROW;
            int row = localIndex / ITEMS_PER_ROW;
            int x   = startX + col * ITEM_SPACING;
            int y   = startY + row * ITEM_SPACING;

            boolean isHovered = (mouseX >= x && mouseX < x + 16
                    && mouseY >= y && mouseY < y + 16);

            // Alternating slot background
            int slotBg = (row % 2 == col % 2) ? 0x14100800 : 0x0C100800;
            context.fill(x - 2, y - 2, x + 18, y + 18, slotBg);

            if (isHovered) {
                context.fill(x - 2, y - 2, x + 18, y + 18, 0x33FFDD88);
                context.drawBorder(x - 2, y - 2, 20, 20, 0x88AA8800);
            } else {
                context.drawBorder(x - 2, y - 2, 20, 20, 0x1A503010);
            }

            ItemStack stackToDraw;
            boolean   isUnlocked;
            float     scale;

            if (isSynergy) {
                SynergyEntry entry = synergyDisplayItems().get(i);
                // Undiscovered entries never animate — they keep a flat 1.0 scale (and
                // therefore zero lift) so only the slot highlight reacts to hover. The
                // grow-and-lift motion on a locked slot pushed the item and its "?"
                // overlay past the slot's drawn border into the row above.
                entry.hoverScale = entry.isUnlocked
                        ? MathHelper.lerp(lerpFactor, entry.hoverScale, isHovered ? 1.25f : 1.0f)
                        : 1.0f;
                scale       = entry.hoverScale;
                isUnlocked  = entry.isUnlocked;
                stackToDraw = entry.reqStacks.isEmpty()
                        ? new ItemStack(Items.APPLE)
                        : cycledStack(entry.reqStacks.get(0));
                if (isHovered) hoveredSynergy = entry;
            } else {
                FoodEntry entry = displayItems().get(i);
                entry.hoverScale = entry.isUnlocked
                        ? MathHelper.lerp(lerpFactor, entry.hoverScale, isHovered ? 1.25f : 1.0f)
                        : 1.0f;
                scale       = entry.hoverScale;
                isUnlocked  = entry.isUnlocked;
                stackToDraw = entry.stack;
                if (isHovered) hoveredEntry = entry;
            }

            // Small extra "lift" riding the same hover lerp as the scale, so hovered
            // items feel like they tilt up off the page rather than just growing in place.
            float lift = (scale - 1.0f) * 8.0f;

            context.getMatrices().push();
            context.getMatrices().translate(x + 8, y + 8 - lift, 0);
            context.getMatrices().scale(scale, scale, 1.0f);
            context.getMatrices().translate(-(x + 8), -(y + 8), 0);
            context.drawItem(stackToDraw, x, y);
            context.getMatrices().pop();

            // Locked-dish darkening + "?" (and the synergy "✦" badge) are drawn in
            // their own transform, separate from the item's hover scale/lift above.
            // They used to share that transform, so on hover the darkened overlay
            // grew and slid upward right along with the item and spilled past the
            // slot's drawn border into the row above; pinning them to the plain
            // 16x16 slot bounds keeps them contained regardless of hover state.
            if (!isUnlocked) {
                context.getMatrices().push();
                context.getMatrices().translate(0, 0, 200);
                context.fill(x, y, x + 16, y + 16, COLOR_LOCKED_OVERLAY);
                context.drawText(this.textRenderer, "?", x + 5, y + 4, 0xCCCCCC, false);
                context.getMatrices().pop();
            } else if (isSynergy) {
                context.getMatrices().push();
                context.getMatrices().translate(0, 0, 200);
                context.drawText(this.textRenderer, "✦", x - 1, y - 2,
                        COLOR_GOLD_BRIGHT, false);
                context.getMatrices().pop();
            }
        }
    }

    // -------------------------------------------------------------------------
    // FOOD DETAIL VIEW
    // -------------------------------------------------------------------------

    private void drawFoodDetailView(DrawContext context, int bookX, int bookY) {
        int paperLeft = bookX + PAPER_LEFT_OFFSET;
        int px        = paperLeft;

        if (detailCurrentPage == 0) {
            int iconCenterX = paperLeft + PAPER_WIDTH / 2;
            int iconY       = bookY + DETAIL_ICON_Y_OFFSET;

            // Drop-shadow
            context.fill(iconCenterX - 14, iconY + 2,
                    iconCenterX + 18, iconY + 34, 0x22000000);

            context.getMatrices().push();
            context.getMatrices().translate(iconCenterX - 16, iconY, 0);
            context.getMatrices().scale(2.0f, 2.0f, 1.0f);
            context.drawItem(selectedEntry.stack, 0, 0);
            context.getMatrices().pop();

            int    nameY     = bookY + DETAIL_NAME_Y_OFFSET;
            String name      = selectedEntry.stack.getName().getString();
            int    nameWidth = this.textRenderer.getWidth(name);
            if (nameWidth > PAPER_WIDTH - 4) {
                name      = this.textRenderer.trimToWidth(name, PAPER_WIDTH - 14) + "…";
                nameWidth = this.textRenderer.getWidth(name);
            }
            context.drawText(this.textRenderer, name,
                    paperLeft + (PAPER_WIDTH - nameWidth) / 2, nameY, COLOR_INK_DARK, false);

            context.fill(paperLeft + 8, nameY + 11,
                    paperLeft + PAPER_WIDTH - 8, nameY + 12, COLOR_DIVIDER);
        }

        int py = bookY + (detailCurrentPage == 0
                ? DETAIL_LINES_Y_PAGE0 : DETAIL_LINES_Y_SUBSEQUENT);
        drawDetailLines(context, px, py, PAPER_WIDTH);
        drawDetailPageCounter(context, bookX, bookY);
    }

    // -------------------------------------------------------------------------
    // SYNERGY DETAIL VIEW
    // -------------------------------------------------------------------------

    private void drawSynergyDetailView(DrawContext context, int bookX, int bookY) {
        int paperLeft = bookX + PAPER_LEFT_OFFSET;
        int px        = paperLeft;

        if (detailCurrentPage == 0) {
            int py       = bookY + DETAIL_ICON_Y_OFFSET;
            int reqCount = selectedSynergy.reqStacks.size();

            int separatorW    = 6;
            int totalReqWidth = reqCount * 16 + (reqCount - 1) * (4 + separatorW);
            int startX        = paperLeft + (PAPER_WIDTH - totalReqWidth) / 2;

            for (int i = 0; i < reqCount; i++) {
                int ix = startX + i * (16 + 4 + separatorW);

                context.fill(ix - 1, py - 1, ix + 17, py + 17, 0x14100800);
                context.drawBorder(ix - 1, py - 1, 18, 18, 0x22503010);
                context.drawItem(cycledStack(selectedSynergy.reqStacks.get(i)), ix, py);

                if (i < reqCount - 1) {
                    context.drawText(this.textRenderer, "+",
                            ix + 18, py + 4, COLOR_INK_FAINT, false);
                }
            }

            py += 24;

            String name      = Text.translatable("synergy.florafare."
                    + selectedSynergy.data.id().replace(":", ".")).getString();
            int    nameWidth = this.textRenderer.getWidth(name);
            if (nameWidth > PAPER_WIDTH - 4) {
                name      = this.textRenderer.trimToWidth(name, PAPER_WIDTH - 14) + "…";
                nameWidth = this.textRenderer.getWidth(name);
            }
            context.drawText(this.textRenderer, name,
                    paperLeft + (PAPER_WIDTH - nameWidth) / 2, py, COLOR_RED_DARK, false);

            context.fill(paperLeft + 8, py + 11,
                    paperLeft + PAPER_WIDTH - 8, py + 12, COLOR_DIVIDER);
        }

        int py = bookY + (detailCurrentPage == 0
                ? SYNERGY_LINES_Y_PAGE0 : DETAIL_LINES_Y_SUBSEQUENT);
        drawDetailLines(context, px, py, PAPER_WIDTH);
        drawDetailPageCounter(context, bookX, bookY);
    }

    // -------------------------------------------------------------------------
    // SHARED DETAIL HELPERS
    // -------------------------------------------------------------------------

    private void drawDetailLines(DrawContext context, int px, int startY, int paperWidth) {
        int py = startY;
        for (RenderLine line : detailPages.get(detailCurrentPage)) {
            if (line.text.isEmpty()) {
                py += line.height;
                continue;
            }

            if (line.isHeader) {
                // Headers are set apart by ink tone and a rule only. They used to be
                // faux-bolded by drawing the same string again 1 px to the right, but
                // Minecraft's font leaves just 1 px between glyphs, so the second pass
                // filled every gap and ran the letters together into one solid slab.
                int drawY = py + 2;
                context.drawText(this.textRenderer, line.text,
                        px + line.offsetX, drawY, line.color, false);
                int headerW = Math.min(this.textRenderer.getWidth(line.text), paperWidth - 8);
                context.fill(px + line.offsetX, drawY + 10,
                        px + line.offsetX + headerW, drawY + 11, COLOR_DIVIDER);
            } else {
                context.drawText(this.textRenderer, line.text,
                        px + line.offsetX, py, line.color, false);
            }
            py += line.height;
        }
    }

    private void drawDetailPageCounter(DrawContext context, int bookX, int bookY) {
        int    centerX  = bookX + BOOK_CENTER_X_OFFSET;
        String pageStr  = (detailCurrentPage + 1) + " / " + detailPages.size();
        int    pageStrW = this.textRenderer.getWidth(pageStr);
        context.fill(centerX - pageStrW / 2 - 3, bookY + PAGE_COUNTER_BG_TOP,
                centerX + pageStrW / 2 + 3, bookY + PAGE_COUNTER_BG_BOTTOM, 0x22100800);
        context.drawText(this.textRenderer, pageStr,
                centerX - pageStrW / 2, bookY + PAGE_COUNTER_Y_OFFSET,
                COLOR_INK_FAINT, false);
    }

    // -------------------------------------------------------------------------
    // UTILITIES
    // -------------------------------------------------------------------------

    private int bookX() { return (this.width - BOOK_WIDTH) / 2 + BOOK_X_SHIFT; }
    private int bookY() { return (this.height - BOOK_HEIGHT) / 2; }

    // -------------------------------------------------------------------------
    // SEARCH BAR GEOMETRY
    // -------------------------------------------------------------------------
    // Shared by init() (which positions the real TextFieldWidget) and
    // drawGridContent() (which paints the card/icon behind it) so the two can never
    // drift out of sync with each other.

    private int searchBarLeft(int bookX) { return bookX + SEARCH_BAR_LEFT_OFFSET; }
    private int searchBarTop(int bookY)  { return bookY + SEARCH_BAR_Y_OFFSET; }

    private int searchIconX(int bookX) { return searchBarLeft(bookX) + SEARCH_BAR_PAD_X; }
    private int searchIconY(int bookY) {
        return searchBarTop(bookY) + (SEARCH_BAR_HEIGHT - SEARCH_ICON_SIZE) / 2;
    }

    private int searchTextX(int bookX) {
        return searchIconX(bookX) + SEARCH_ICON_SIZE + SEARCH_ICON_TEXT_GAP;
    }
    private int searchTextY(int bookY) {
        return searchBarTop(bookY) + (SEARCH_BAR_HEIGHT - SEARCH_FIELD_HEIGHT) / 2;
    }
    private int searchTextWidth(int bookX) {
        return searchBarLeft(bookX) + SEARCH_BAR_WIDTH - SEARCH_BAR_PAD_X - searchTextX(bookX);
    }

    /**
     * Returns the Roman-numeral suffix for a potion amplifier value.
     * Amplifier 0 = level I — vanilla omits "I" for level-1 effects, matching
     * vanilla behaviour. Amplifier 1 = " II", etc.
     * Uses proper Roman numerals up to VIII; falls back to Arabic for higher values.
     */
    private String getAmplifierNumeral(int amplifier) {
        return switch (amplifier) {
            case 0 -> "";
            case 1 -> " II";
            case 2 -> " III";
            case 3 -> " IV";
            case 4 -> " V";
            case 5 -> " VI";
            case 6 -> " VII";
            case 7 -> " VIII";
            default -> " " + (amplifier + 1);
        };
    }

    /**
     * Cycles through a list of ItemStacks once per ~1.2 seconds.
     */
    private static ItemStack cycledStack(List<ItemStack> stacks) {
        if (stacks.isEmpty()) return new ItemStack(Items.APPLE);
        if (stacks.size() == 1) return stacks.get(0);
        int index = (int) ((System.currentTimeMillis() / 1200L) % stacks.size());
        return stacks.get(index);
    }

    // -------------------------------------------------------------------------
    // INPUT
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (currentState == ScreenState.INDEX) {
                if (hoverFoodsIndex) {
                    setState(ScreenState.FOOD_GRID);
                    currentPage  = 0;
                    updatePageButtons();
                    playPageTurnSound();
                    return true;
                } else if (FlorafareConfig.enableSynergies && hoverSynergiesIndex) {
                    setState(ScreenState.SYNERGY_GRID);
                    currentPage  = 0;
                    updatePageButtons();
                    playPageTurnSound();
                    return true;
                }
            } else if (currentState == ScreenState.FOOD_GRID
                    || currentState == ScreenState.SYNERGY_GRID) {
                int bookX  = bookX();
                int bookY  = bookY();
                int startX = bookX + GRID_START_X_OFFSET;
                int startY = bookY + GRID_START_Y_OFFSET;

                int startIndex = currentPage * ITEMS_PER_PAGE;
                int totalItems = currentState == ScreenState.SYNERGY_GRID
                        ? synergyDisplayItems().size() : displayItems().size();
                int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalItems);

                for (int i = startIndex; i < endIndex; i++) {
                    int localIndex = i - startIndex;
                    int col = localIndex % ITEMS_PER_ROW;
                    int row = localIndex / ITEMS_PER_ROW;
                    int x   = startX + col * ITEM_SPACING;
                    int y   = startY + row * ITEM_SPACING;

                    if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                        if (currentState == ScreenState.SYNERGY_GRID) {
                            SynergyEntry entry = synergyDisplayItems().get(i);
                            if (entry.isUnlocked) {
                                selectedSynergy = entry;
                                buildDetailPages(entry.data);
                                setState(ScreenState.SYNERGY_DETAIL);
                                updatePageButtons();
                                playPageTurnSound();
                            } else if (this.client != null) {
                                this.client.getSoundManager().play(
                                        PositionedSoundInstance.master(
                                                SoundEvents.BLOCK_CHEST_LOCKED, 1.5F));
                            }
                        } else {
                            FoodEntry entry = displayItems().get(i);
                            if (entry.isUnlocked) {
                                selectedEntry = entry;
                                buildDetailPages(entry.data);
                                setState(ScreenState.FOOD_DETAIL);
                                updatePageButtons();
                                playPageTurnSound();
                            } else if (this.client != null) {
                                this.client.getSoundManager().play(
                                        PositionedSoundInstance.master(
                                                SoundEvents.BLOCK_CHEST_LOCKED, 1.5F));
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
        final String  text;
        final int     color;
        final int     height;
        final int     offsetX;
        /** Section headers get the rule underneath; body lines are drawn plain. */
        final boolean isHeader;

        RenderLine(String text, int color, int height, int offsetX) {
            this(text, color, height, offsetX, false);
        }

        RenderLine(String text, int color, int height, int offsetX, boolean isHeader) {
            this.text     = text;
            this.color    = color;
            this.height   = height;
            this.offsetX  = offsetX;
            this.isHeader = isHeader;
        }
    }

    private static class FoodEntry {
        final ItemStack    stack;
        final FoodBuffData data;
        final boolean      isUnlocked;
        float hoverScale = 1.0f;

        FoodEntry(ItemStack stack, FoodBuffData data, boolean isUnlocked) {
            this.stack      = stack;
            this.data       = data;
            this.isUnlocked = isUnlocked;
        }
    }

    private static class SynergyEntry {
        final FoodSynergyData       data;
        final List<List<ItemStack>> reqStacks;
        final boolean               isUnlocked;
        float hoverScale = 1.0f;

        SynergyEntry(FoodSynergyData data, List<List<ItemStack>> reqStacks, boolean isUnlocked) {
            this.data       = data;
            this.reqStacks  = reqStacks;
            this.isUnlocked = isUnlocked;
        }
    }

    // -------------------------------------------------------------------------
    // FLAT (SHADOW-LESS) TEXT FIELD
    // -------------------------------------------------------------------------

    /**
     * A {@link TextFieldWidget} that draws its contents without a drop shadow.
     *
     * <p>Vanilla's {@code renderWidget} always paints field text through
     * {@code drawTextWithShadow}, and exposes no switch for it. Against the journal's
     * parchment that shadow made the search text look bold and traced-over, while every
     * other line in the book is drawn flat via {@code drawText(..., false)}. So this
     * subclass replaces {@code renderWidget} wholesale with a flat equivalent.
     *
     * <p>Vanilla tracks the horizontal scroll position in a private
     * {@code firstCharacterIndex} with no accessor, so this class keeps its own
     * {@link #scrollIndex} and overrides {@link #onClick} to map clicks through the same
     * value — that keeps rendering and click-to-place-cursor consistent with each other
     * without needing a mixin or access widener. (Vanilla's own copy of the index is
     * then only read by code paths this class overrides.) Likewise the selection anchor
     * is captured by overriding {@link #setSelectionEnd}, since {@code selectionEnd} is
     * private too.
     */
    private static class FlatTextFieldWidget extends TextFieldWidget {

        private final TextRenderer font;
        private final Text placeholderText;
        private final int  textColor;
        private final int  placeholderColor;
        private final int  cursorColor;
        private final int  selectionColor;

        /** Index of the leftmost rendered character — our copy of vanilla's private one. */
        private int scrollIndex = 0;
        /** The fixed end of a selection; the moving end is {@link #getCursor()}. */
        private int selectionAnchor = 0;

        FlatTextFieldWidget(TextRenderer font, int x, int y, int width, int height,
                            Text placeholderText, int textColor, int placeholderColor,
                            int cursorColor, int selectionColor) {
            super(font, x, y, width, height, placeholderText);
            this.font             = font;
            this.placeholderText  = placeholderText;
            this.textColor        = textColor;
            this.placeholderColor = placeholderColor;
            this.cursorColor      = cursorColor;
            this.selectionColor   = selectionColor;
        }

        @Override
        public void setSelectionEnd(int index) {
            super.setSelectionEnd(index);
            // Called from the superclass constructor before our fields are assigned,
            // so getText() can still be null at that point.
            String text = this.getText();
            this.selectionAnchor = MathHelper.clamp(index, 0, text == null ? 0 : text.length());
        }

        /**
         * Keeps {@link #scrollIndex} such that the cursor stays visible and no blank gap
         * is left at the right edge while text remains to the left — the same invariant
         * vanilla maintains on its private index.
         */
        private void updateScrollIndex(String text, int cursor) {
            int width = this.getInnerWidth();

            if (this.scrollIndex > text.length()) this.scrollIndex = text.length();
            if (cursor < this.scrollIndex)        this.scrollIndex = cursor;

            while (this.scrollIndex < cursor
                    && this.font.getWidth(text.substring(this.scrollIndex, cursor)) > width) {
                this.scrollIndex++;
            }
            while (this.scrollIndex > 0
                    && this.font.getWidth(text.substring(this.scrollIndex - 1)) <= width) {
                this.scrollIndex--;
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            String text  = this.getText();
            int    start = MathHelper.clamp(this.scrollIndex, 0, text.length());
            String shown = this.font.trimToWidth(text.substring(start), this.getInnerWidth());
            int    click = MathHelper.floor(mouseX) - this.getX();
            this.setCursor(this.font.trimToWidth(shown, click).length() + start,
                    Screen.hasShiftDown());
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            if (!this.isVisible()) return;

            String text   = this.getText();
            int    cursor = MathHelper.clamp(this.getCursor(), 0, text.length());
            int    anchor = MathHelper.clamp(this.selectionAnchor, 0, text.length());
            updateScrollIndex(text, cursor);

            int x = this.getX();
            int y = this.getY();

            // Placeholder — shown only while empty and unfocused, so it never sits
            // underneath the caret.
            if (text.isEmpty() && !this.isFocused()) {
                if (this.placeholderText != null) {
                    context.drawText(this.font, this.placeholderText, x, y,
                            this.placeholderColor, false);
                }
                return;
            }

            String shown      = this.font.trimToWidth(text.substring(this.scrollIndex),
                    this.getInnerWidth());
            int    shownEnd   = this.scrollIndex + shown.length();

            // Selection wash, painted under the glyphs.
            if (anchor != cursor) {
                int selStart = Math.max(Math.min(cursor, anchor), this.scrollIndex);
                int selEnd   = Math.min(Math.max(cursor, anchor), shownEnd);
                if (selEnd > selStart) {
                    int sx = x + this.font.getWidth(text.substring(this.scrollIndex, selStart));
                    int ex = x + this.font.getWidth(text.substring(this.scrollIndex, selEnd));
                    context.fill(sx, y - 1, ex, y + this.font.fontHeight, this.selectionColor);
                }
            }

            if (!shown.isEmpty()) {
                context.drawText(this.font, shown, x, y, this.textColor, false);
            }

            // Blinking caret as a thin rule rather than vanilla's shadowed "_" glyph.
            if (this.isFocused() && (System.currentTimeMillis() / 300L) % 2L == 0L
                    && cursor >= this.scrollIndex && cursor <= shownEnd) {
                int cx = x + this.font.getWidth(text.substring(this.scrollIndex, cursor));
                context.fill(cx, y - 1, cx + 1, y + this.font.fontHeight, this.cursorColor);
            }
        }
    }
}