package net.tend1tnuy.florafare.client;

import java.util.ArrayList;
import java.util.List;

/**
 * How the journal's detail lines are split into pages, kept apart from the screen so the
 * arithmetic can be tested without one.
 *
 * <p>The screen wraps every line to the page width first (a translated attribute name
 * is easily wider than the 114-pixel page), then hands the resulting rows here. Rows are
 * measured by their real height, so a wrapped entry takes the room it actually needs and
 * a page never runs past its bottom edge — which is what the journal did while lines
 * were neither wrapped nor measured.
 *
 * <p>Two typesetting rules on top of "fill the page":
 * <ul>
 *   <li>a section heading is kept with the row after it, so a page never ends on a
 *       lonely "Effects:" whose effects are all overleaf;</li>
 *   <li>a spacer that would open a page is dropped — the space is there to separate two
 *       sections, and at the top of a page there is nothing above it to separate from.</li>
 * </ul>
 */
public final class JournalLayout {

    private JournalLayout() {}

    /**
     * One row on a page.
     *
     * @param height       pixels it advances the pen by
     * @param spacer       a blank separator row
     * @param keepWithNext whether it must share a page with the row after it (a heading)
     */
    public record Row(int height, boolean spacer, boolean keepWithNext) {}

    /**
     * Splits rows into pages.
     *
     * @param firstTop where the first page's rows start (below the icon and name)
     * @param laterTop where every later page's rows start
     * @param bottom   the lowest y a row may reach
     * @return the row indices on each page, in order; always at least one page
     */
    public static List<List<Integer>> paginate(List<Row> rows, int firstTop, int laterTop, int bottom) {
        List<List<Integer>> pages = new ArrayList<>();
        List<Integer> page = new ArrayList<>();
        int y = firstTop;

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.spacer() && page.isEmpty() && !pages.isEmpty()) continue;

            int needed = row.height();
            if (row.keepWithNext() && i + 1 < rows.size()) needed += rows.get(i + 1).height();

            if (y + needed > bottom && !page.isEmpty()) {
                pages.add(page);
                page = new ArrayList<>();
                y = laterTop;
                if (row.spacer()) continue;
            }
            page.add(i);
            y += row.height();
        }

        if (!page.isEmpty() || pages.isEmpty()) pages.add(page);
        return pages;
    }
}
