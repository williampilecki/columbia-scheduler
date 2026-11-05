package scheduler;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

final class Planner {
    record Plan(List<Section> sections, int gaps) {
        List<String> ids() { return sections.stream().map(Section::id).sorted().toList(); }
    }
    record Result(long count, List<Plan> best, List<String> blockingCourses) {}
    private static final Comparator<Plan> ORDER = Comparator.comparingInt(Plan::gaps)
            .thenComparing(Plan::ids, Planner::compareIds);
    // Lexicographic section IDs make equal-scoring plans reproducible.
    private static int compareIds(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int cmp = a.get(i).compareTo(b.get(i));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.size(), b.size());
    }

    static Result solve(List<Section> catalog, List<String> courses) {
        if (courses.isEmpty() || courses.stream().distinct().count() != courses.size())
            throw new IllegalArgumentException("Select each course/component exactly once.");
        var groups = new ArrayList<List<Section>>();
        for (String course : courses) {
            var matches = catalog.stream().filter(s -> s.course().equals(course)).toList();
            if (matches.isEmpty()) throw new IllegalArgumentException("No catalog sections for " + course);
            var verified = matches.stream().filter(s -> !s.unknown()).toList();
            if (verified.isEmpty()) throw new IllegalArgumentException("All sections of " + course + " have unverified times.");
            groups.add(verified);
        }
        groups.sort(Comparator.comparingInt(List::size));
        var best = new ArrayList<Plan>();
        long count = search(groups, 0, new ArrayList<>(), best);
        return new Result(count, List.copyOf(best), count == 0 ? blockingSet(groups) : List.of());
    }

    // Removing any one course from the returned set makes that subset feasible.
    private static List<String> blockingSet(List<List<Section>> groups) {
        var necessary = new ArrayList<>(groups);
        for (var group : groups) {
            var trial = new ArrayList<>(necessary);
            trial.remove(group);
            if (search(trial, 0, new ArrayList<>(), null) == 0) necessary = trial;
        }
        return necessary.stream().map(group -> group.get(0).course()).toList();
    }

    private static long search(List<List<Section>> groups, int depth, List<Section> chosen, List<Plan> best) {
        if (depth == groups.size()) {
            if (best == null) return 1;
            best.add(new Plan(List.copyOf(chosen), weeklyGap(chosen)));
            best.sort(ORDER);
            if (best.size() > 3) best.remove(3);
            return 1;
        }
        long count = 0;
        for (Section section : groups.get(depth)) {
            if (chosen.stream().anyMatch(section::conflicts)) continue;
            chosen.add(section);
            count += search(groups, depth + 1, chosen, best);
            chosen.remove(chosen.size() - 1);
            if (best == null && count > 0) break;
        }
        return count;
    }

    // Score the recurring weekly timetable. Date windows affect feasibility separately.
    static int weeklyGap(List<Section> sections) {
        var slots = sections.stream().flatMap(s -> s.slots().stream())
                .sorted(Comparator.comparing(Slot::day).thenComparing(Slot::start)).toList();
        int minutes = 0;
        DayOfWeek day = null;
        LocalTime end = null;
        for (Slot s : slots) {
            if (s.day() != day) end = null;
            if (end != null && s.start().isAfter(end)) minutes += (int) ChronoUnit.MINUTES.between(end, s.start());
            if (end == null || s.end().isAfter(end)) end = s.end();
            day = s.day();
        }
        return minutes;
    }
}
