package net.tend1tnuy.florafare.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal's detail pagination. The page is small (rows from y 76 to 150 on the first
 * page), so a wrapped line costs real room, and getting the arithmetic wrong either runs
 * text through the page counter or strands a heading at the foot of a page.
 */
class JournalLayoutTest {

    private static final int FIRST = 76;
    private static final int LATER = 24;
    private static final int BOTTOM = 150;

    private static JournalLayout.Row body(int height) {
        return new JournalLayout.Row(height, false, false);
    }

    private static JournalLayout.Row header() {
        return new JournalLayout.Row(14, false, true);
    }

    private static JournalLayout.Row spacer() {
        return new JournalLayout.Row(6, true, false);
    }

    @Test
    @DisplayName("rows that fit stay on one page")
    void fitsOnOnePage() {
        List<List<Integer>> pages = JournalLayout.paginate(
                List.of(body(12), body(12), body(12)), FIRST, LATER, BOTTOM);
        assertEquals(List.of(List.of(0, 1, 2)), pages);
    }

    @Test
    @DisplayName("no page runs past the bottom, measured by real row heights")
    void neverOverflows() {
        List<JournalLayout.Row> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(body(11));
        List<List<Integer>> pages = JournalLayout.paginate(rows, FIRST, LATER, BOTTOM);

        int count = 0;
        for (int p = 0; p < pages.size(); p++) {
            int y = p == 0 ? FIRST : LATER;
            for (int index : pages.get(p)) {
                y += rows.get(index).height();
                count++;
            }
            assertTrue(y <= BOTTOM, "page " + p + " ends at " + y);
        }
        assertEquals(40, count, "every row lands on exactly one page");
        // 74 px on the first page is six 11-px rows; 126 px later is eleven.
        assertEquals(6, pages.get(0).size());
        assertEquals(11, pages.get(1).size());
    }

    @Test
    @DisplayName("a heading moves to the next page with its first row")
    void headingKeptWithNext() {
        // Five rows of 12 take the first page to y 136; a 14-px heading would still fit
        // (150), but its first entry would not, so the heading goes over with it.
        List<JournalLayout.Row> rows = List.of(
                body(12), body(12), body(12), body(12), body(12), header(), body(11));
        List<List<Integer>> pages = JournalLayout.paginate(rows, FIRST, LATER, BOTTOM);
        assertEquals(List.of(0, 1, 2, 3, 4), pages.get(0));
        assertEquals(List.of(5, 6), pages.get(1));
    }

    @Test
    @DisplayName("a spacer never opens a page")
    void spacerDroppedAtTop() {
        List<JournalLayout.Row> rows = List.of(
                body(12), body(12), body(12), body(12), body(12), body(12), spacer(), header(), body(11));
        List<List<Integer>> pages = JournalLayout.paginate(rows, FIRST, LATER, BOTTOM);
        assertEquals(2, pages.size());
        assertEquals(List.of(7, 8), pages.get(1), "the spacer (6) is dropped, not carried over");
    }

    @Test
    @DisplayName("nothing to show is still one, empty page")
    void emptyIsOnePage() {
        assertEquals(List.of(List.of()), JournalLayout.paginate(List.of(), FIRST, LATER, BOTTOM));
    }
}
