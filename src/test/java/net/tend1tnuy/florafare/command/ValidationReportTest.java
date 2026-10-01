package net.tend1tnuy.florafare.command;

import net.tend1tnuy.florafare.food.DatapackDiagnostics.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The grouping, paging and capping {@code /florafare validate} prints with. */
class ValidationReportTest {

    private static List<String> lines(int n) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= n; i++) lines.add("finding " + i);
        return lines;
    }

    @Test
    @DisplayName("empty sections are left out; totals and the worst severity add up")
    void sections() {
        ValidationReport report = new ValidationReport()
                .add("MISSING_TARGET", Severity.WARNING, lines(4))
                .add("sync", Severity.ERROR, List.of())
                .add("priority_loss", Severity.INFO, lines(2));
        assertEquals(2, report.sections().size());
        assertEquals(6, report.total());
        assertNotNull(report.section("missing_target"), "names are lowercased");
        assertNull(report.section("sync"));
        assertTrue(report.hasAtLeast(Severity.WARNING));
        assertFalse(report.hasAtLeast(Severity.ERROR));
    }

    @Test
    @DisplayName("pages clamp into range and the last one is short")
    void paging() {
        List<String> all = lines(23);
        ValidationReport.Page first = ValidationReport.page(all, 1, 10);
        assertEquals(10, first.lines().size());
        assertEquals(3, first.pages());
        ValidationReport.Page last = ValidationReport.page(all, 3, 10);
        assertEquals(List.of("finding 21", "finding 22", "finding 23"), last.lines());
        assertEquals(3, ValidationReport.page(all, 99, 10).page(), "past the end shows the last page");
        assertEquals(1, ValidationReport.page(all, 0, 10).page());
        assertEquals(1, ValidationReport.page(List.of(), 1, 10).pages());
    }

    @Test
    @DisplayName("\"and N more\" counts only what was not shown")
    void hidden() {
        assertEquals(7, ValidationReport.hidden(10, 3));
        assertEquals(0, ValidationReport.hidden(2, 3));
    }

    @Test
    @DisplayName("the file form lists every finding under its section")
    void text() {
        String text = new ValidationReport().add("sync", Severity.ERROR, lines(12))
                .toText(List.of("header"));
        assertTrue(text.startsWith("header\n"));
        assertTrue(text.contains("== sync (12, error) =="));
        assertTrue(text.contains(" - finding 12"));
        assertTrue(new ValidationReport().toText(List.of()).contains("No issues found."));
    }
}
