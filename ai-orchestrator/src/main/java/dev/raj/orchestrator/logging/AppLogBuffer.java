package dev.raj.orchestrator.logging;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AppLogBuffer {
    private static final int MAX_LOGS = 200;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final List<LogEntry> logs = new CopyOnWriteArrayList<>();

    public void add(String level, String category, String message) {
        String timestamp = LocalTime.now().format(TIME_FORMAT);
        logs.add(new LogEntry(timestamp, level, category, message));
        if (logs.size() > MAX_LOGS) {
            logs.remove(0);
        }
    }

    public List<LogEntry> getLogs() {
        return Collections.unmodifiableList(new ArrayList<>(logs));
    }

    public void clear() {
        logs.clear();
    }

    public record LogEntry(String timestamp, String level, String category, String message) {}
}

