package dev.raj.postgresmcp;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseTools {
 private static final Logger log = LoggerFactory.getLogger(DatabaseTools.class);
 private final JdbcTemplate jdbc;

 public DatabaseTools(JdbcTemplate jdbc) {
  this.jdbc = jdbc;
  log.info("PostgreSQL MCP Server initialized and ready.");
 }

 @McpTool(name="list_customers", description="List customers from PostgreSQL. Read-only. Limit capped at 100.")
 public List<Map<String,Object>> listCustomers(
  @McpToolParam(description="Maximum rows, 1 to 100", required=false) Integer limit) {
  int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
  log.info("[Postgres MCP] list_customers called with limit: {}", safeLimit);
  List<Map<String,Object>> rows = jdbc.queryForList("SELECT id, full_name, email, created_at FROM customers ORDER BY id DESC LIMIT ?", safeLimit);
  log.info("[Postgres MCP] list_customers returned {} row(s)", rows.size());
  return rows;
 }

 @McpTool(name="find_customer_by_email", description="Find a customer by exact email address. Read-only.")
 public List<Map<String,Object>> findCustomerByEmail(
  @McpToolParam(description="Exact customer email", required=true) String email) {
  log.info("[Postgres MCP] find_customer_by_email called for: {}", email);
  List<Map<String,Object>> rows = jdbc.queryForList("SELECT id, full_name, email, created_at FROM customers WHERE lower(email)=lower(?)", email);
  log.info("[Postgres MCP] find_customer_by_email found {} row(s)", rows.size());
  return rows;
 }

 @McpTool(name="count_customers", description="Return the number of customer records in PostgreSQL. Read-only.")
 public long countCustomers() {
  log.info("[Postgres MCP] count_customers called");
  Long result = jdbc.queryForObject("SELECT COUNT(*) FROM customers", Long.class);
  long count = result == null ? 0L : result;
  log.info("[Postgres MCP] count_customers returned: {}", count);
  return count;
 }
}
