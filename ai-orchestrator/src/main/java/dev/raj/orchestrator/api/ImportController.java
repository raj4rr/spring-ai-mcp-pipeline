package dev.raj.orchestrator.api;

import dev.raj.orchestrator.logging.AppLogBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
    private static final Logger log = LoggerFactory.getLogger(ImportController.class);
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Path workspace;
    private final AppLogBuffer logBuffer;

    public ImportController(JdbcTemplate jdbc, JsonMapper mapper,
                            @Value("${app.files.root:./workspace}") String workspace,
                            AppLogBuffer logBuffer) throws Exception {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.logBuffer = logBuffer;
        Path configured = Path.of(workspace).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        this.workspace = configured.toRealPath();
    }

    @GetMapping("/preview")
    public Preview preview() {
        ParsedResult result = readAllCustomerFiles();
        List<Customer> customers = result.customers();
        long present = customers.stream().filter(c -> !jdbc.queryForList(
                "SELECT id FROM customers WHERE lower(email)=lower(?)", c.email()).isEmpty()).count();
        log.info("Preview generated: files={}, total={}, present={}, wouldInsert={}",
                result.sourceFiles().size(), customers.size(), present, customers.size() - present);
        logBuffer.add("INFO", "Human Review", "Preview loaded from " + result.sourceFiles().size() +
                " file(s) [" + String.join(", ", result.sourceFiles()) + "]: " + customers.size() +
                " records (" + (customers.size() - present) + " new, " + present + " duplicates)");
        return new Preview(customers.size(), present, customers.size() - present, customers, result.sourceFiles());
    }

    @PostMapping("/approve")
    @Transactional
    public ImportResult approve(@RequestHeader(name = "X-Approval-Confirm", required = false) String confirm) {
        if (!"yes".equalsIgnoreCase(confirm)) {
            logBuffer.add("WARN", "Human Review", "Approval rejected: missing X-Approval-Confirm header");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Explicit approval required: send X-Approval-Confirm: yes");
        }
        ParsedResult result = readAllCustomerFiles();
        List<Customer> customers = result.customers();
        int inserted = 0, skipped = 0;
        for (Customer c : customers) {
            int rows = jdbc.update("INSERT INTO customers(full_name,email) VALUES (?,?) ON CONFLICT(email) DO NOTHING",
                    c.fullName().trim(), c.email().trim().toLowerCase(Locale.ROOT));
            if (rows == 1) inserted++; else skipped++;
        }
        log.info("Approval import executed: inserted={}, skipped={}, total={}", inserted, skipped, customers.size());
        logBuffer.add("INFO", "Database MCP", "Approved & inserted into PostgreSQL: " + inserted +
                " row(s) inserted, " + skipped + " duplicate(s) skipped from " + result.sourceFiles().size() + " file(s)");
        return new ImportResult(inserted, skipped, customers.size());
    }

    @PostMapping("/reject")
    public RejectResult reject() {
        ParsedResult result = readAllCustomerFiles();
        int deletedFiles = 0;
        for (String filename : result.sourceFiles()) {
            try {
                Path file = workspace.resolve(filename).normalize();
                if (file.startsWith(workspace) && Files.deleteIfExists(file)) {
                    deletedFiles++;
                }
            } catch (Exception e) {
                log.warn("Failed to delete rejected file {}: {}", filename, e.getMessage());
            }
        }
        log.info("Import rejected: removed {} pending customer file(s)", deletedFiles);
        logBuffer.add("WARN", "Human Review", "Import REJECTED by human reviewer. Deleted " +
                deletedFiles + " pending file(s). No database changes made.");
        return new RejectResult(deletedFiles, "Rejected: deleted " + deletedFiles + " pending customer file(s).");
    }

    @DeleteMapping("/reset")
    @Transactional
    public ResetResult resetAll() {
        int deletedDbRows = jdbc.update("DELETE FROM customers");
        int deletedFiles = 0;
        try (var stream = Files.list(workspace)) {
            for (Path file : stream.toList()) {
                String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (filename.startsWith("customer") && filename.endsWith(".json")) {
                    if (Files.deleteIfExists(file)) {
                        deletedFiles++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not delete customer json files: {}", e.getMessage());
        }
        log.info("Reset complete: deletedDbRows={}, deletedFiles={}", deletedDbRows, deletedFiles);
        logBuffer.add("WARN", "Database MCP", "Reset executed: wiped " + deletedDbRows + " customer rows and deleted " + deletedFiles + " customer json file(s)");
        return new ResetResult(deletedDbRows, deletedFiles > 0, "Reset complete: deleted " + deletedDbRows + " database rows and " + deletedFiles + " customer json file(s).");
    }

    private ParsedResult readAllCustomerFiles() {
        List<Path> matchingFiles = new ArrayList<>();
        try (var stream = Files.list(workspace)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith("customer") && name.endsWith(".json") && Files.isRegularFile(file)) {
                    matchingFiles.add(file);
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot scan workspace: " + e.getMessage());
        }

        if (matchingFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No customer JSON files found in workspace.");
        }

        List<Customer> combined = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        List<String> sourceFileNames = new ArrayList<>();

        for (Path file : matchingFiles) {
            sourceFileNames.add(file.getFileName().toString());
            try {
                if (Files.size(file) > 1_000_000L) continue;
                List<Customer> list = mapper.readValue(Files.readString(file), new TypeReference<List<Customer>>() {});
                for (Customer c : list) {
                    if (c.fullName() != null && !c.fullName().isBlank() &&
                        c.email() != null && c.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                        String emailLower = c.email().toLowerCase(Locale.ROOT);
                        if (seenEmails.add(emailLower)) {
                            combined.add(new Customer(c.fullName().trim(), emailLower));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not parse file {}: {}", file.getFileName(), e.getMessage());
            }
        }

        if (combined.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No valid customer records found in pending files.");
        }

        return new ParsedResult(combined, sourceFileNames);
    }

    public record Customer(String fullName, String email) {}
    public record Preview(int total, long alreadyPresent, long wouldInsert, List<Customer> customers, List<String> sourceFiles) {}
    public record ImportResult(int inserted, int skippedAsDuplicates, int totalProcessed) {}
    public record RejectResult(int deletedFiles, String message) {}
    public record ResetResult(int deletedDbRows, boolean deletedCustomersJson, String message) {}
    private record ParsedResult(List<Customer> customers, List<String> sourceFiles) {}
}
