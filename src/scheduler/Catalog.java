package scheduler;

import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

record Slot(DayOfWeek day, LocalTime start, LocalTime end, LocalDate first, LocalDate last) {
    Slot {
        if (!start.isBefore(end) || last.isBefore(first) || start.getSecond() != 0 ||
                end.getSecond() != 0 || start.getNano() != 0 || end.getNano() != 0)
            throw new IllegalArgumentException("Invalid meeting interval");
    }

    boolean conflicts(Slot other) {
        if (day != other.day || !start.isBefore(other.end) || !other.start.isBefore(end)) return false;
        LocalDate from = first.isAfter(other.first) ? first : other.first;
        LocalDate to = last.isBefore(other.last) ? last : other.last;
        return !from.with(TemporalAdjusters.nextOrSame(day)).isAfter(to);
    }
}

record Section(String course, String id, String title, List<Slot> slots, boolean unknown) {
    boolean conflicts(Section other) {
        return slots.stream().anyMatch(a -> other.slots.stream().anyMatch(a::conflicts));
    }
}

final class Catalog {
    static final String HEADER = "term\tcourse\tid\ttitle\tday\tstart\tend\tfirst\tlast";
    private record Key(String term, String id) {}
    private static final class Rows {
        final String course, id, title;
        final Set<Slot> slots = new LinkedHashSet<>();
        boolean unknown;
        Rows(String course, String id, String title) {
            this.course = course; this.id = id; this.title = title;
        }
    }

    static List<Section> read(BufferedReader reader, String term) throws IOException {
        if (!HEADER.equals(reader.readLine())) throw new IOException("Unexpected catalog header");
        var groups = new LinkedHashMap<Key, Rows>();
        String line;
        int number = 1;
        while ((line = reader.readLine()) != null) {
            number++;
            if (line.isBlank()) continue;
            String[] v = line.split("\t", -1);
            try {
                if (v.length != 9) throw new IllegalArgumentException("Expected 9 tab-separated fields");
                for (int i = 0; i < 5; i++) if (v[i].isBlank()) throw new IllegalArgumentException("Missing required field");
                Rows row = groups.computeIfAbsent(new Key(v[0], v[2]), k -> new Rows(v[1].toUpperCase(Locale.ROOT), v[2], v[3]));
                if (!row.course.equalsIgnoreCase(v[1]) || !row.title.equals(v[3])) throw new IllegalArgumentException("Inconsistent section ID");
                if (v[4].equals("TBA") || v[4].equals("ASYNC")) {
                    for (int i = 5; i < 9; i++) if (!v[i].isEmpty()) throw new IllegalArgumentException("Unexpected times on " + v[4]);
                    row.unknown |= v[4].equals("TBA");
                } else {
                    if (v[7].isEmpty() != v[8].isEmpty()) throw new IllegalArgumentException("Provide both dates");
                    row.slots.add(new Slot(DayOfWeek.valueOf(v[4]), LocalTime.parse(v[5]), LocalTime.parse(v[6]),
                        v[7].isEmpty() ? LocalDate.of(1,1,1) : LocalDate.parse(v[7]),
                        v[8].isEmpty() ? LocalDate.of(9999,12,31) : LocalDate.parse(v[8])));
                }
            } catch (RuntimeException ex) {
                throw new IOException("Catalog line " + number + ": " + ex.getMessage(), ex);
            }
        }
        return groups.entrySet().stream().filter(e -> e.getKey().term().equals(term)).map(e -> {
            Rows r = e.getValue();
            return new Section(r.course, r.id, r.title, List.copyOf(r.slots), r.unknown);
        }).toList();
    }
}
