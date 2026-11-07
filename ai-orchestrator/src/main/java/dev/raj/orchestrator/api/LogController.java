package dev.raj.orchestrator.api;

import dev.raj.orchestrator.logging.AppLogBuffer;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class LogController {
    private final AppLogBuffer logBuffer;

    public LogController(AppLogBuffer logBuffer) {
        this.logBuffer = logBuffer;
    }

    @GetMapping
    public List<AppLogBuffer.LogEntry> getLogs() {
        return logBuffer.getLogs();
    }

    @DeleteMapping
    public void clearLogs() {
        logBuffer.clear();
    }
}

