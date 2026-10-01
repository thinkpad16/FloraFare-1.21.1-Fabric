package net.tend1tnuy.florafare.food;

import net.tend1tnuy.florafare.Florafare;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the last datapack load had to say about the {@code food_buffs} and
 * {@code food_synergies} files, kept in memory for {@code /florafare validate}.
 *
 * <p>Load-time problems used to exist only as log lines, and a log line is the wrong
 * place for most of them. A pack author debugging "my entry does nothing" is in game,
 * not in {@code latest.log}; the line that explains it scrolled past at startup, among
 * hundreds of others; and some of the most common causes — an entry that lost on
 * priority, a field spelled {@code healthBonus} — were never logged at all. So every
 * listener reports through a {@link Collector} here, which both logs (rate-limited, so a
 * 700-entry pack with one systematic typo produces a handful of lines and a summary,
 * not 700) and keeps the full list.
 *
 * <p>The report is split into {@link Phase phases}, each rebuilt on its own: the two
 * reload listeners run in no guaranteed order, and the tag checks can only run once the
 * reload has bound the new tags, after both of them. Each phase's list is replaced
 * wholesale when that phase publishes, so a fixed problem disappears on the next
 * {@code /reload} and nothing accumulates across reloads.
 *
 * <p><b>For command code:</b> {@link #issues()} is the whole report, in phase order and
 * within a phase in the order the problems were found. It is an immutable snapshot and
 * safe to read from any thread.
 */
public final class DatapackDiagnostics {

    private DatapackDiagnostics() {}

    /** How serious an issue is. */
    public enum Severity {
        /** Worth knowing, nothing is broken: e.g. an entry that lost on priority, as designed. */
        INFO,
        /** Something loaded, but probably not the way the author meant. */
        WARNING,
        /** Something was skipped: an entry, a line of one, or a whole file. */
        ERROR
    }

    /** Which part of the reload found the issue; each is rebuilt independently. */
    public enum Phase {
        /** The {@code food_buffs} listener, {@code config_generation.json} included. */
        FOOD_BUFFS,
        /** The {@code food_synergies} listener. */
        SYNERGIES,
        /** Checks that need the reloaded item tags, run once they are bound. */
        TAGS
    }

    /** What kind of problem an issue is, for filtering and grouping. */
    public enum Category {
        /** A file that is not JSON of a shape the loader understands; nothing in it loaded. */
        MALFORMED_FILE,
        /** One entry that could not be read; the rest of its file is unaffected. */
        MALFORMED_ENTRY,
        /** A field present with the wrong JSON type, e.g. {@code "nutrition": "eight"}. */
        WRONG_TYPE,
        /** A key the loader does not read, usually a typo ({@code healthBonus}). */
        UNKNOWN_KEY,
        /** A target naming an item or potion that does not exist. */
        MISSING_TARGET,
        /** A target naming an item that exists but is not food, so the entry never applies. */
        NOT_FOOD,
        /** An effect id that is not a registered status effect; that line was skipped. */
        INVALID_EFFECT,
        /** An attribute id that is not a registered attribute; that line was skipped. */
        INVALID_ATTRIBUTE,
        /** An attribute operation the parser does not know; that line was skipped. */
        UNKNOWN_OPERATION,
        /** An entry that lost to a higher-priority definition of the same target. */
        PRIORITY_LOSS,
        /** Two files defining one target at the same priority; the tie-break picked one. */
        AMBIGUOUS_TIE,
        /** One file defining a target twice; its later entry wins. */
        DUPLICATE_ENTRY,
        /** Two definitions of one synergy id; one replaced the other. */
        DUPLICATE_SYNERGY,
        /** A synergy requirement naming an item that does not exist. */
        INVALID_REQUIREMENT,
        /** A {@code #tag} target or requirement whose tag does not exist. */
        MISSING_TAG,
        /** A {@code #tag} target or requirement whose tag exists but holds no items. */
        EMPTY_TAG,
        /** A problem with {@code config_generation.json}: out-of-range values, several files. */
        CONFIG_GENERATION
    }

    /**
     * One problem.
     *
     * @param file    the datapack file it was found in, as its resource id
     *                ({@code mypack:meats}), or null when it is not about one file
     * @param target  the entry's target or synergy id, or null for a file-level problem
     * @param message one human-readable sentence, already naming the field or value at fault
     */
    public record Issue(Phase phase, Severity severity, Category category,
                        @Nullable String file, @Nullable String target, String message) {

        /** One line, the way the log prints it: {@code file: target: message}. */
        public String describe() {
            StringBuilder line = new StringBuilder();
            if (file != null) line.append(file).append(": ");
            if (target != null) line.append('\'').append(target).append("': ");
            return line.append(message).toString();
        }
    }

    /** How many issues of one category a collector logs before it only counts them. */
    static final int LOG_LIMIT_PER_CATEGORY = 8;

    /**
     * The published report, per phase. Replaced, never mutated, so a reader on another
     * thread always sees one complete phase list or the previous one.
     */
    private static volatile Map<Phase, List<Issue>> published = emptyReport();

    private static Map<Phase, List<Issue>> emptyReport() {
        Map<Phase, List<Issue>> empty = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) empty.put(phase, List.of());
        return Collections.unmodifiableMap(empty);
    }

    // -------------------------------------------------------------------------
    // READING THE REPORT
    // -------------------------------------------------------------------------

    /**
     * Every issue the last load found, food buffs first, then synergies, then tag checks.
     * Immutable; empty when the datapacks loaded cleanly.
     */
    public static List<Issue> issues() {
        Map<Phase, List<Issue>> snapshot = published;
        List<Issue> all = new ArrayList<>();
        for (Phase phase : Phase.values()) all.addAll(snapshot.get(phase));
        return List.copyOf(all);
    }

    /** The issues of one category, in the order {@link #issues()} gives them. */
    public static List<Issue> issues(Category category) {
        List<Issue> matching = new ArrayList<>();
        for (Issue issue : issues()) {
            if (issue.category() == category) matching.add(issue);
        }
        return List.copyOf(matching);
    }

    /** The issues at or above a severity — {@code WARNING} drops the purely informational ones. */
    public static List<Issue> issuesAtLeast(Severity minimum) {
        List<Issue> matching = new ArrayList<>();
        for (Issue issue : issues()) {
            if (issue.severity().compareTo(minimum) >= 0) matching.add(issue);
        }
        return List.copyOf(matching);
    }

    /** How many issues each category holds; categories with none are left out. */
    public static Map<Category, Integer> countsByCategory() {
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (Issue issue : issues()) counts.merge(issue.category(), 1, Integer::sum);
        return Collections.unmodifiableMap(counts);
    }

    /** Starts collecting a fresh report for one phase. Nothing is visible until {@link Collector#publish()}. */
    public static Collector begin(Phase phase) {
        return new Collector(phase);
    }

    /** Drops the whole report. For tests, and for a server shutting down in single-player. */
    public static void reset() {
        published = emptyReport();
    }

    private static synchronized void publish(Phase phase, List<Issue> issues) {
        Map<Phase, List<Issue>> next = new EnumMap<>(published);
        next.put(phase, List.copyOf(issues));
        published = Collections.unmodifiableMap(next);
    }

    // -------------------------------------------------------------------------
    // COLLECTING
    // -------------------------------------------------------------------------

    /**
     * Gathers one phase's issues during a load, logging as it goes.
     *
     * <p>Logging is rate-limited per category: the first {@value #LOG_LIMIT_PER_CATEGORY}
     * of each are printed in full, the rest only counted and summed up in one line at
     * {@link #publish()}. Priority losses and ties are the exception — losing on priority
     * is the mechanism working, so each is logged at DEBUG only and the reload prints its
     * own one-line summary of them.
     *
     * <p>Not thread-safe; a collector belongs to the one reload that created it.
     */
    public static final class Collector {

        private final Phase phase;
        private final List<Issue> issues = new ArrayList<>();
        private final Map<Category, Integer> seen = new EnumMap<>(Category.class);
        private boolean published;

        private Collector(Phase phase) {
            this.phase = phase;
        }

        /** Records one issue and logs it, subject to the per-category limit. */
        public Issue report(Severity severity, Category category,
                            @Nullable String file, @Nullable String target, String message) {
            Issue issue = new Issue(phase, severity, category, file, target, message);
            issues.add(issue);
            int count = seen.merge(category, 1, Integer::sum);

            if (category == Category.PRIORITY_LOSS) {
                Florafare.LOGGER.debug("[Florafare datapack] {}", issue.describe());
            } else if (count <= LOG_LIMIT_PER_CATEGORY) {
                log(severity, issue.describe());
            }
            return issue;
        }

        /** {@link #report} at {@link Severity#WARNING}. */
        public Issue warn(Category category, @Nullable String file, @Nullable String target,
                          String message) {
            return report(Severity.WARNING, category, file, target, message);
        }

        /** {@link #report} at {@link Severity#ERROR}. */
        public Issue error(Category category, @Nullable String file, @Nullable String target,
                           String message) {
            return report(Severity.ERROR, category, file, target, message);
        }

        /** The issues collected so far, in order. */
        public List<Issue> issues() {
            return Collections.unmodifiableList(issues);
        }

        /** How many issues of one category have been collected so far. */
        public int count(Category category) {
            return seen.getOrDefault(category, 0);
        }

        /**
         * Makes this phase's issues the published ones, replacing the previous load's, and
         * logs a one-line count for every category whose lines were cut short. Calling it
         * twice does nothing the second time.
         */
        public void publish() {
            if (published) return;
            published = true;
            for (Map.Entry<Category, Integer> entry : seen.entrySet()) {
                Category category = entry.getKey();
                int suppressed = entry.getValue() - LOG_LIMIT_PER_CATEGORY;
                if (suppressed <= 0 || category == Category.PRIORITY_LOSS) continue;
                Florafare.LOGGER.warn(
                        "[Florafare datapack] ...and {} more {} issue(s) not shown; "
                                + "run /florafare validate for the full list.",
                        suppressed, category.name().toLowerCase(java.util.Locale.ROOT));
            }
            DatapackDiagnostics.publish(phase, issues);
        }

        private static void log(Severity severity, String line) {
            switch (severity) {
                case ERROR   -> Florafare.LOGGER.error("[Florafare datapack] {}", line);
                case WARNING -> Florafare.LOGGER.warn("[Florafare datapack] {}", line);
                case INFO    -> Florafare.LOGGER.info("[Florafare datapack] {}", line);
            }
        }
    }
}
