package dev.raj.filemcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileTools {
 private static final long MAX_BYTES = 1_000_000L;
 private final Path root;

 public FileTools(@Value("${app.files.root:./workspace}") String configuredRoot) throws IOException {
  Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
  Files.createDirectories(configured);
  this.root = configured.toRealPath();
 }

 @McpTool(name="read_file", description="Read a UTF-8 text file inside the configured workspace. Use a relative path.")
 public String readFile(@McpToolParam(description="Relative path inside workspace, e.g. input.txt", required=true) String relativePath) {
  try {
   Path file = resolve(relativePath);
   if (!Files.isRegularFile(file)) return "ERROR: File does not exist: " + relativePath;
   if (Files.size(file) > MAX_BYTES) return "ERROR: File exceeds 1 MB limit.";
   return Files.readString(file, StandardCharsets.UTF_8);
  } catch (Exception e) { return "ERROR: " + e.getMessage(); }
 }

 @McpTool(name="write_file", description="Write UTF-8 text inside workspace. Existing files are not overwritten unless overwrite=true.")
 public String writeFile(
  @McpToolParam(description="Relative path inside workspace", required=true) String relativePath,
  @McpToolParam(description="Text content", required=true) String content,
  @McpToolParam(description="Only true when user explicitly authorizes replacement", required=false) boolean overwrite) {
  try {
   Path file = resolve(relativePath);
   if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return "ERROR: Content exceeds 1 MB limit.";
   if (Files.exists(file) && !overwrite) return "ERROR: File already exists. Ask before overwriting.";
   Files.createDirectories(file.getParent());
   Files.writeString(file, content, StandardCharsets.UTF_8);
   return "OK: Wrote " + root.relativize(file) + " (" + Files.size(file) + " bytes).";
  } catch (Exception e) { return "ERROR: " + e.getMessage(); }
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
