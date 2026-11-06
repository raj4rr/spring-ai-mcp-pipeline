package dev.raj.postgresmcp;

import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseTools {
 private final JdbcTemplate jdbc;
 public DatabaseTools(JdbcTemplate jdbc) { this.jdbc = jdbc; }

 @McpTool(name="list_customers", description="List customers from PostgreSQL. Read-only. Limit capped at 100.")
 public List<Map<String,Object>> listCustomers(
  @McpToolParam(description="Maximum rows, 1 to 100", required=false) Integer limit) {
  int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
  return jdbc.queryForList("SELECT id, full_name, email, created_at FROM customers ORDER BY id DESC LIMIT ?", safeLimit);
 }

 @McpTool(name="find_customer_by_email", description="Find a customer by exact email address. Read-only.")
 public List<Map<String,Object>> findCustomerByEmail(
  @McpToolParam(description="Exact customer email", required=true) String email) {
  return jdbc.queryForList("SELECT id, full_name, email, created_at FROM customers WHERE lower(email)=lower(?)", email);
 }

 @McpTool(name="count_customers", description="Return the number of customer records in PostgreSQL. Read-only.")
 public long countCustomers() {
  Long result = jdbc.queryForObject("SELECT COUNT(*) FROM customers", Long.class);
  return result == null ? 0L : result;
 }
}
