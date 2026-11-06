package dev.raj.orchestrator.api;

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
 private final JdbcTemplate jdbc;
 private final JsonMapper mapper;
 private final Path workspace;

 public ImportController(JdbcTemplate jdbc, JsonMapper mapper,
   @Value("${app.files.root:./workspace}") String workspace) throws Exception {
  this.jdbc = jdbc;
  this.mapper = mapper;
  Path configured = Path.of(workspace).toAbsolutePath().normalize();
  Files.createDirectories(configured);
  this.workspace = configured.toRealPath();
 }

 @GetMapping("/preview")
 public Preview preview() {
  List<Customer> customers = readCustomersFile();
  long present = customers.stream().filter(c -> !jdbc.queryForList(
    "SELECT id FROM customers WHERE lower(email)=lower(?)", c.email()).isEmpty()).count();
  return new Preview(customers.size(), present, customers.size()-present, customers);
 }

 // Local-dev approval boundary only. Add real authentication/authorization before deployment.
 @PostMapping("/approve")
 @Transactional
 public ImportResult approve(@RequestHeader(name="X-Approval-Confirm", required=false) String confirm) {
  if (!"yes".equalsIgnoreCase(confirm))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Explicit approval required: send X-Approval-Confirm: yes");
  List<Customer> customers = readCustomersFile();
  int inserted=0, skipped=0;
  for (Customer c : customers) {
   int rows = jdbc.update("INSERT INTO customers(full_name,email) VALUES (?,?) ON CONFLICT(email) DO NOTHING",
     c.fullName().trim(), c.email().trim().toLowerCase(Locale.ROOT));
   if (rows == 1) inserted++; else skipped++;
  }
  return new ImportResult(inserted, skipped, customers.size());
 }

 private List<Customer> readCustomersFile() {
  try {
   Path file = workspace.resolve("customers.json").normalize();
   if (!file.startsWith(workspace) || !Files.isRegularFile(file))
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace/customers.json was not found.");
   if (Files.size(file) > 1_000_000L)
    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "customers.json exceeds 1 MB.");
   List<Customer> customers = mapper.readValue(Files.readString(file), new TypeReference<List<Customer>>() {});
   if (customers.isEmpty() || customers.size() > 500)
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected 1 to 500 customers.");
   Set<String> emails = new HashSet<>();
   for (Customer c : customers) {
    if (c.fullName()==null || c.fullName().isBlank() || c.fullName().length()>200 ||
        c.email()==null || !c.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
     throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each record needs a fullName and valid email.");
    if (!emails.add(c.email().toLowerCase(Locale.ROOT)))
     throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate email: " + c.email());
   }
   return customers;
  } catch (ResponseStatusException e) { throw e; }
    catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid customers.json: " + e.getMessage()); }
 }

 public record Customer(String fullName, String email) {}
 public record Preview(int total, long alreadyPresent, long wouldInsert, List<Customer> customers) {}
 public record ImportResult(int inserted, int skippedAsDuplicates, int totalProcessed) {}
}
