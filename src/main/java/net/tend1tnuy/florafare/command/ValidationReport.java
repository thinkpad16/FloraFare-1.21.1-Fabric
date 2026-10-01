package net.tend1tnuy.florafare.command;

import net.tend1tnuy.florafare.food.DatapackDiagnostics.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What {@code /florafare validate} found, grouped into sections, with the paging and
 * capping the chat output and the report file share.
 *
 * <p>Plain data, no game types: the command fills it on the server thread (it has to —
 * the checks ask the registries), then renders it to chat or hands it to the IO pool to
 * be written, and the paging rules are unit-tested here.
 */
public final class ValidationReport {

    /**
     * One group of findings.
     *
     * @param name     what the player types to open it ({@code missing_target}), lowercase
     * @param severity the worst severity among its lines, for the header color
     * @param lines    every finding, in the order found
     */
    public record Section(String name, Severity severity, List<String> lines) {
        public Section {
            lines = List.copyOf(lines);
        }

        public int size() {
            return lines.size();
        }
    }

    /**
     * One page of a section.
     *
     * @param page  1-based, already clamped into range
     * @param pages how many pages the section has, at least 1
     */
    public record Page(List<String> lines, int page, int pages) {}

    private final List<Section> sections = new ArrayList<>();

    /** Adds a section unless it has no lines; an empty section is not a finding. */
    public ValidationReport add(String name, Severity severity, List<String> lines) {
        if (!lines.isEmpty()) sections.add(new Section(name.toLowerCase(Locale.ROOT), severity, lines));
        return this;
    }

    public List<Section> sections() {
        return Collections.unmodifiableList(sections);
    }

    public Section section(String name) {
        for (Section section : sections) {
            if (section.name().equals(name)) return section;
        }
        return null;
    }

    /** Every finding across all sections. */
    public int total() {
        int total = 0;
        for (Section section : sections) total += section.size();
        return total;
    }

    /** Whether the report holds anything at or above this severity. */
    public boolean hasAtLeast(Severity severity) {
        for (Section section : sections) {
            if (section.severity().compareTo(severity) >= 0) return true;
        }
        return false;
    }

    /**
     * One page of {@code size} lines. A page past the end shows the last one and a page
     * before the start the first, so a stale "next" link never answers with nothing.
     */
    public static Page page(List<String> lines, int page, int size) {
        int pageSize = Math.max(1, size);
        int pages = Math.max(1, (lines.size() + pageSize - 1) / pageSize);
        int clamped = Math.min(Math.max(page, 1), pages);
        int from = (clamped - 1) * pageSize;
        int to = Math.min(from + pageSize, lines.size());
        return new Page(from >= to ? List.of() : lines.subList(from, to), clamped, pages);
    }

    /** How many lines are left out when only the first {@code shown} are printed. */
    public static int hidden(int total, int shown) {
        return Math.max(0, total - Math.max(0, shown));
    }

    /** The whole report as plain text, for the file {@code validate file} writes. */
    public String toText(List<String> header) {
        StringBuilder out = new StringBuilder(1 << 12);
        for (String line : header) out.append(line).append('\n');
        out.append('\n');
        if (sections.isEmpty()) {
            out.append("No issues found.\n");
            return out.toString();
        }
        for (Section section : sections) {
            out.append("== ").append(section.name()).append(" (").append(section.size())
               .append(", ").append(section.severity().name().toLowerCase(Locale.ROOT)).append(") ==\n");
            for (String line : section.lines()) out.append(" - ").append(line).append('\n');
            out.append('\n');
        }
        return out.toString();
    }
}
