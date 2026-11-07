package dev.raj.filemcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileTools {
 private static final Logger log = LoggerFactory.getLogger(FileTools.class);
 private static final long MAX_BYTES = 10_000_000L; // 10 MB for larger files
 private static final Pattern CUSTOMER_PATTERN = Pattern.compile("(?i)customer:\\s*(.+?)\\s*\\n\\s*email:\\s*(.+?)(?=\\n\\s*customer:|\\Z)", Pattern.DOTALL);
 private final Path root;

 public FileTools(@Value("${app.files.root:./workspace}") String configuredRoot) throws IOException {
  Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
  Files.createDirectories(configured);
  this.root = configured.toRealPath();
  log.info("File MCP Server initialized with root: {}", this.root);
 }

 @McpTool(name="convert_input_to_customers_json", description="High-performance pipeline tool for lightweight LLMs. Directly parses an input text file (regardless of size: 100, 1,000, or 10,000 records) and writes formatted JSON to target file.")
 public String convertInputToCustomersJson(
  @McpToolParam(description="Source relative path, e.g. input.txt or input1.txt", required=true) String sourcePath,
  @McpToolParam(description="Target relative JSON path, e.g. customers.json", required=true) String targetPath) {
  log.info("[File MCP] convert_input_to_customers_json: source={}, target={}", sourcePath, targetPath);
  try {
   Path src = resolve(sourcePath);
   if (!Files.isRegularFile(src)) {
    return "ERROR: Source file does not exist: " + sourcePath;
   }
   String text = Files.readString(src, StandardCharsets.UTF_8);
   Matcher matcher = CUSTOMER_PATTERN.matcher(text);

   StringBuilder json = new StringBuilder("[");
   int count = 0;
   while (matcher.find()) {
    String name = matcher.group(1).trim().replace("\"", "\\\"");
    String email = matcher.group(2).trim().replace("\"", "\\\"");
    if (count > 0) json.append(",");
    json.append("{\"fullName\":\"").append(name).append("\",\"email\":\"").append(email).append("\"}");
    count++;
   }
   json.append("]");

   if (count == 0) {
    return "ERROR: No 'Customer:' and 'Email:' entries found in " + sourcePath;
   }

   Path dest = resolve(targetPath);
   Files.createDirectories(dest.getParent());
   Files.writeString(dest, json.toString(), StandardCharsets.UTF_8);
   log.info("[File MCP] convert_input_to_customers_json: successfully wrote {} customer records to {}", count, targetPath);
   return "SUCCESS: Extracted all " + count + " customer records from " + sourcePath + " and wrote to " + targetPath + " (" + Files.size(dest) + " bytes).";
  } catch (Exception e) {
   log.error("[File MCP] convert error: {}", e.getMessage(), e);
   return "ERROR: " + e.getMessage();
  }
 }

 @McpTool(name="read_file", description="Read a UTF-8 text file inside workspace.")
 public String readFile(@McpToolParam(description="Relative path inside workspace, e.g. input.txt", required=true) String relativePath) {
  log.info("[File MCP] read_file requested for: {}", relativePath);
  try {
   Path file = resolve(relativePath);
   if (!Files.isRegularFile(file)) return "ERROR: File does not exist: " + relativePath;
   if (Files.size(file) > MAX_BYTES) return "ERROR: File exceeds size limit.";
   return Files.readString(file, StandardCharsets.UTF_8);
  } catch (Exception e) {
   return "ERROR: " + e.getMessage();
  }
 }

 @McpTool(name="write_file", description="Write text content into a workspace file.")
 public String writeFile(
  @McpToolParam(description="Relative path inside workspace", required=true) String relativePath,
  @McpToolParam(description="Text content to write", required=true) String content,
  @McpToolParam(description="Overwrite existing file (default: true)", required=false) Boolean overwrite) {
  boolean allowOverwrite = overwrite == null || overwrite;
  log.info("[File MCP] write_file requested for: {} (overwrite={})", relativePath, allowOverwrite);
  try {
   Path file = resolve(relativePath);
   if (content == null || content.isBlank()) {
    return "ERROR: Content cannot be empty or blank.";
   }
   if (Files.exists(file) && !allowOverwrite) {
    return "ERROR: File already exists. Ask before overwriting.";
   }
   Files.createDirectories(file.getParent());
   Files.writeString(file, content, StandardCharsets.UTF_8);
   log.info("[File MCP] write_file successfully written: {} ({} bytes)", relativePath, Files.size(file));
   return "OK: Wrote " + root.relativize(file) + " (" + Files.size(file) + " bytes).";
  } catch (Exception e) {
   return "ERROR: " + e.getMessage();
  }
 }

 private Path resolve(String value) throws IOException {
  if (value == null || value.isBlank()) throw new IllegalArgumentException("Path is required.");
  Path supplied = Path.of(value);
  if (supplied.isAbsolute()) throw new SecurityException("Absolute paths are not allowed.");
  Path candidate = root.resolve(supplied).normalize();
  if (!candidate.startsWith(root)) throw new SecurityException("Path traversal is not allowed.");
  Path parent = candidate.getParent();
  if (parent != null && Files.exists(parent) && !parent.toRealPath().startsWith(root))
   throw new SecurityException("Symbolic-link escape is not allowed.");
  return candidate;
 }
}
