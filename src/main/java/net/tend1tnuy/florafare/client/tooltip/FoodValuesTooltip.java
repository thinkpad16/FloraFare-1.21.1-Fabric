package net.tend1tnuy.florafare.client.tooltip;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.text.CharacterVisitor;
import net.minecraft.text.OrderedText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TextVisitFactory;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.food.FoodBuffData;

import java.util.List;

/**
 * The hunger and saturation a food restores, drawn as icons instead of spelled out.
 *
 * <p>Replaces the line that used to read "Restores 3 hunger, 3.6 saturation" — by some
 * distance the longest thing Florafare put on a tooltip, and the reason a tooltip could
 * grow wider than the screen and spill past its own frame. Two short rows of icons say
 * the same thing in about a fifth of the width, and say it the way every player already
 * reads food values.
 *
 * <p>The hunger row uses vanilla's own drumstick sprites at their HUD size, so it is
 * pixel-identical to what AppleSkin draws. The saturation row underneath is drawn rather
 * than textured — AppleSkin's saturation icons live in its own texture file, which is not
 * ours to depend on — but keeps the same 7-pixel segment pitch, so the two line up.
 *
 * <p><b>Not used at all when AppleSkin is installed.</b> AppleSkin already puts exactly
 * this row on every food tooltip, and Florafare's integration feeds it the configured
 * values, so drawing our own would simply say the same thing twice. See
 * {@link #appleSkinOwnsFoodValues()}.
 */
public final class FoodValuesTooltip implements TooltipComponent, TooltipData {

    // Vanilla HUD sprites — the same three AppleSkin uses, so the rows match exactly.
    private static final Identifier FOOD_EMPTY = Identifier.ofVanilla("hud/food_empty");
    private static final Identifier FOOD_HALF  = Identifier.ofVanilla("hud/food_half");
    private static final Identifier FOOD_FULL  = Identifier.ofVanilla("hud/food_full");

    /** One drumstick, in pixels. */
    private static final int ICON = 9;
    /** One saturation segment: AppleSkin's pitch, so the two rows line up under each other. */
    private static final int SEGMENT_WIDTH = 7;
    private static final int SEGMENT_HEIGHT = 3;
    private static final int ROW_GAP = 2;

    /**
     * Past this many icons the row stops being readable and starts being a wall, which is
     * the very thing this component exists to avoid. Anything longer collapses to a single
     * icon plus a count, the way AppleSkin does it.
     */
    private static final int MAX_ICONS = 10;

    private static final int COLOR_SATURATION = 0xFFFFD24A;
    private static final int COLOR_SATURATION_EMPTY = 0x55000000;

    private final int nutrition;
    private final float saturation;

    private final int hungerIcons;
    private final String hungerOverflow;
    private final int saturationSegments;
    private final String saturationOverflow;

    /**
     * Builds a row straight from a hunger point count and a saturation point count.
     *
     * <p>Separate from {@link #of} so the layout arithmetic — how many icons, where the
     * half goes, when a count replaces the row — can be driven from a unit test. It needs
     * neither a game nor a mod loader.
     */
    public static FoodValuesTooltip forValues(int nutrition, float saturationPoints) {
        return new FoodValuesTooltip(nutrition, saturationPoints);
    }

    int hungerIcons()          { return hungerIcons; }
    int saturationSegments()   { return saturationSegments; }
    String hungerOverflow()    { return hungerOverflow; }
    String saturationOverflow() { return saturationOverflow; }

    private FoodValuesTooltip(int nutrition, float saturation) {
        this.nutrition = Math.max(0, nutrition);
        this.saturation = Math.max(0f, saturation);

        int icons = (int) Math.ceil(this.nutrition / 2.0);
        if (icons > MAX_ICONS) {
            this.hungerIcons = 1;
            this.hungerOverflow = "x" + icons;
        } else {
            this.hungerIcons = icons;
            this.hungerOverflow = null;
        }

        int segments = (int) Math.ceil(this.saturation / 2.0);
        if (segments > MAX_ICONS) {
            this.saturationSegments = 1;
            this.saturationOverflow = "x" + segments;
        } else {
            this.saturationSegments = segments;
            this.saturationOverflow = null;
        }
    }

    /**
     * The row for a config, or null when there is nothing to draw or AppleSkin is already
     * drawing it.
     *
     * <p>{@code saturation} on a {@link FoodBuffData} is a vanilla saturation
     * <em>modifier</em>, not a point count — the same meaning the datapack field and
     * {@code HungerManager#add} carry — so it is converted here, once, rather than at each
     * of the places that display it.
     */
    public static FoodValuesTooltip of(FoodBuffData data) {
        if (appleSkinOwnsFoodValues()) return null;
        if (data.nutrition() <= 0) return null;
        return new FoodValuesTooltip(data.nutrition(), data.nutrition() * data.saturation() * 2.0f);
    }

    /**
     * Whether AppleSkin is installed and therefore responsible for the hunger/saturation
     * row on food tooltips.
     *
     * <p>A flat "is it installed" check, not a peek at AppleSkin's settings. Its display
     * is on by default, and a player who has deliberately turned it off has said they do
     * not want food values on their tooltips — quietly substituting ours would be
     * overriding that, not honouring it.
     */
    public static boolean appleSkinOwnsFoodValues() {
        Boolean known = appleSkinPresent;
        if (known != null) return known;
        // Resolved on demand rather than in a static initializer, and defensively: the
        // layout arithmetic below is unit-tested, and those tests run with no mod loader
        // for FabricLoader.getInstance() to return.
        boolean present;
        try {
            present = FabricLoader.getInstance().isModLoaded("appleskin");
        } catch (Throwable notInAGame) {
            present = false;
        }
        appleSkinPresent = present;
        return present;
    }

    private static volatile Boolean appleSkinPresent;

    // -------------------------------------------------------------------------
    // TooltipComponent
    // -------------------------------------------------------------------------

    @Override
    public int getWidth(TextRenderer textRenderer) {
        int hunger = hungerIcons * ICON;
        if (hungerOverflow != null) hunger += 2 + textRenderer.getWidth(hungerOverflow);

        int sat = saturationSegments * SEGMENT_WIDTH;
        if (saturationOverflow != null) sat += 2 + textRenderer.getWidth(saturationOverflow);

        return Math.max(hunger, sat);
    }

    @Override
    public int getHeight() {
        return saturationSegments > 0 ? ICON + ROW_GAP + SEGMENT_HEIGHT + 1 : ICON + 1;
    }

    @Override
    public void drawItems(TextRenderer textRenderer, int x, int y, DrawContext context) {
        drawHungerRow(textRenderer, x, y, context);
        if (saturationSegments > 0) {
            drawSaturationRow(textRenderer, x, y + ICON + ROW_GAP, context);
        }
    }

    private void drawHungerRow(TextRenderer textRenderer, int x, int y, DrawContext context) {
        // The empty drumstick goes down first and the filled one on top of it, which is
        // what gives a half portion its unfilled other half — the same two-pass draw the
        // vanilla HUD and AppleSkin both use.
        for (int i = 0; i < hungerIcons; i++) {
            int iconX = x + i * ICON;
            context.drawGuiTexture(FOOD_EMPTY, iconX, y, ICON, ICON);

            int pointsBefore = i * 2;
            if (nutrition > pointsBefore) {
                boolean half = hungerOverflow == null && nutrition - pointsBefore == 1;
                context.drawGuiTexture(half ? FOOD_HALF : FOOD_FULL, iconX, y, ICON, ICON);
            }
        }
        if (hungerOverflow != null) {
            context.drawTextWithShadow(textRenderer, hungerOverflow,
                    x + hungerIcons * ICON + 2, y + 1, 0xAAAAAA);
        }
    }

    private void drawSaturationRow(TextRenderer textRenderer, int x, int y, DrawContext context) {
        for (int i = 0; i < saturationSegments; i++) {
            int segX = x + i * SEGMENT_WIDTH;
            context.fill(segX, y, segX + SEGMENT_WIDTH - 1, y + SEGMENT_HEIGHT,
                    COLOR_SATURATION_EMPTY);

            // How much of this particular segment the saturation actually reaches into,
            // so a value like 3.6 ends on a partly-filled segment instead of rounding
            // itself up to a whole one.
            float filled = saturationOverflow != null
                    ? 1.0f
                    : Math.min(1.0f, Math.max(0.0f, (saturation - i * 2.0f) / 2.0f));
            int width = Math.round((SEGMENT_WIDTH - 1) * filled);
            if (width > 0) {
                context.fill(segX, y, segX + width, y + SEGMENT_HEIGHT, COLOR_SATURATION);
            }
        }
        if (saturationOverflow != null) {
            context.drawTextWithShadow(textRenderer, saturationOverflow,
                    x + saturationSegments * SEGMENT_WIDTH + 2, y - 2, 0xAAAAAA);
        }
    }

    // -------------------------------------------------------------------------
    // CARRIER
    //
    // ItemStack#getTooltip deals in Text, and a tooltip only becomes a TooltipComponent
    // later, inside TooltipComponent#of. So the component travels as a Text that carries
    // itself, and FlorafareTooltipComponentMixin unwraps it at that conversion. This is
    // the same route AppleSkin takes for its own overlay, which is a good sign it is the
    // only one there is.
    // -------------------------------------------------------------------------

    /** Wraps this component in something {@code List<Text>} will accept. */
    public Text asText() {
        return new Carrier(this);
    }

    public static final class Carrier implements Text, OrderedText {

        public final FoodValuesTooltip component;

        private Carrier(FoodValuesTooltip component) {
            this.component = component;
        }

        // An empty piece of text: it contributes no characters, and every measurement of
        // it that is not routed through the component comes out as zero rather than as
        // something that would push the tooltip wider.
        @Override public Style getStyle()        { return Style.EMPTY; }
        @Override public TextContent getContent() { return PlainTextContent.EMPTY; }
        @Override public List<Text> getSiblings() { return List.of(); }

        @Override public OrderedText asOrderedText() { return this; }

        @Override
        public boolean accept(CharacterVisitor visitor) {
            return TextVisitFactory.visitFormatted(this, getStyle(), visitor);
        }
    }
}
