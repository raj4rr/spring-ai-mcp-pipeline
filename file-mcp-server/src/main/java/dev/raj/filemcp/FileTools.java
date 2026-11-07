package dev.raj.filemcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileTools {
 private static final Logger log = LoggerFactory.getLogger(FileTools.class);
 private static final long MAX_BYTES = 1_000_000L;
 private final Path root;

 public FileTools(@Value("${app.files.root:./workspace}") String configuredRoot) throws IOException {
  Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
  Files.createDirectories(configured);
  this.root = configured.toRealPath();
  log.info("File MCP Server initialized with root: {}", this.root);
 }

 @McpTool(name="read_file", description="Read a UTF-8 text file inside the configured workspace. Use a relative path.")
 public String readFile(@McpToolParam(description="Relative path inside workspace, e.g. input.txt", required=true) String relativePath) {
  log.info("[File MCP] read_file requested for: {}", relativePath);
  try {
   Path file = resolve(relativePath);
   if (!Files.isRegularFile(file)) {
    log.warn("[File MCP] File not found: {}", relativePath);
    return "ERROR: File does not exist: " + relativePath;
   }
   if (Files.size(file) > MAX_BYTES) {
    log.warn("[File MCP] File exceeds max size limit: {}", relativePath);
    return "ERROR: File exceeds 1 MB limit.";
   }
   String content = Files.readString(file, StandardCharsets.UTF_8);
   log.info("[File MCP] read_file succeeded ({} bytes)", content.length());
   return content;
  } catch (Exception e) {
   log.error("[File MCP] read_file error: {}", e.getMessage());
   return "ERROR: " + e.getMessage();
  }
 }

 @McpTool(name="write_file", description="Write UTF-8 text inside workspace. Automatically creates or overwrites the file.")
 public String writeFile(
  @McpToolParam(description="Relative path inside workspace", required=true) String relativePath,
  @McpToolParam(description="Text content", required=true) String content,
  @McpToolParam(description="Optional overwrite flag (defaults to true)", required=false) Boolean overwrite) {
  boolean allowOverwrite = overwrite == null || overwrite;
  log.info("[File MCP] write_file requested for: {} (overwrite={})", relativePath, allowOverwrite);
  try {
   Path file = resolve(relativePath);
   if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
    log.warn("[File MCP] Content exceeds limit for: {}", relativePath);
    return "ERROR: Content exceeds 1 MB limit.";
   }
   if (Files.exists(file) && !allowOverwrite) {
    log.warn("[File MCP] File exists and overwrite is false: {}", relativePath);
    return "ERROR: File already exists. Ask before overwriting.";
   }
   Files.createDirectories(file.getParent());
   Files.writeString(file, content, StandardCharsets.UTF_8);
   log.info("[File MCP] write_file successfully written: {} ({} bytes)", relativePath, Files.size(file));
   return "OK: Wrote " + root.relativize(file) + " (" + Files.size(file) + " bytes).";
  } catch (Exception e) {
   log.error("[File MCP] write_file error: {}", e.getMessage());
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
