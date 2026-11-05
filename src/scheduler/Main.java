package scheduler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("--help")) {
                System.out.println("Demo: java -jar build/scheduler.jar\nCustom: java -jar build/scheduler.jar CATALOG.tsv TERM COURSE...\nList: java -jar build/scheduler.jar CATALOG.tsv TERM --list");
                return;
            }
            boolean demo = args.length == 0;
            if (!demo && args.length < 3) throw new IllegalArgumentException("Expected catalog, term, and course code(s). Try --help.");
            List<Section> catalog;
            try (BufferedReader reader = demo ? new BufferedReader(new InputStreamReader(
                    Objects.requireNonNull(Main.class.getResourceAsStream("/demo.tsv")), StandardCharsets.UTF_8))
                    : Files.newBufferedReader(Path.of(args[0]))) {
                catalog = Catalog.read(reader, demo ? "DEMO" : args[1]);
            }
            if (catalog.isEmpty()) throw new IllegalArgumentException("No sections for this term.");
            if (demo) System.out.println("Columbia Scheduler | Fictional demo catalog");
            if (!demo && args.length == 3 && args[2].equals("--list")) {
                catalog.stream().map(s -> s.course() + "  " + s.title()).distinct().sorted().forEach(System.out::println);
                return;
            }
            var requested = demo ? List.of("COMSW3134", "MATHUN1201", "HUMACC1001") :
                    java.util.Arrays.stream(args).skip(2).map(s -> s.toUpperCase(Locale.ROOT)).toList();
            Planner.Result result = Planner.solve(catalog, requested);
            System.out.printf("%d valid combination%s for %s%n", result.count(), result.count() == 1 ? "" : "s", String.join(", ", requested));
            if (result.count() == 0) System.out.println("These courses cannot fit together with the supplied sections: " + String.join(", ", result.blockingCourses()));
            int rank = 0;
            for (Planner.Plan plan : result.best()) {
                System.out.printf("%nOption %d | %d weekly gap minutes%n", ++rank, plan.gaps());
                record Entry(Slot slot, Section section) {}
                var entries = new ArrayList<Entry>();
                for (Section s : plan.sections()) {
                    if (s.slots().isEmpty()) System.out.printf("ASYNC  %s [%s] %s%n", s.course(), s.id(), s.title());
                    for (Slot slot : s.slots()) entries.add(new Entry(slot, s));
                }
                entries.sort(Comparator.comparing((Entry e) -> e.slot.day()).thenComparing(e -> e.slot.start()));
                for (Entry e : entries) {
                    var s = e.slot;
                    System.out.printf("%-3s  %s-%s  %-12s [%s] %s%s%n", s.day().name().substring(0,3),
                            s.start(), s.end(), e.section.course(), e.section.id(), e.section.title(),
                            s.first().getYear() == 1 && s.last().getYear() == 9999 ? "" : "  " + s.first() + " to " + s.last());
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("Scheduler: " + ex.getMessage());
            System.exit(2);
        }
    }
}
