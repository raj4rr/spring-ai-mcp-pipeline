package dev.raj.orchestrator.service;

import dev.raj.orchestrator.logging.AppLogBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpTools;
    private final AppLogBuffer logBuffer;

    public ChatService(ChatClient.Builder builder, ToolCallbackProvider mcpTools, AppLogBuffer logBuffer) {
        this.chatClient = builder.defaultSystem("""
   You are an intelligent orchestrator with access to MCP tools:
   - `convert_input_to_customers_json(sourcePath, targetPath)`: DIRECTLY extracts all customer records from an input file (handles 100, 1,000, or any large size instantly) and saves to JSON.
   - `read_file(relativePath)`: Reads a text file.
   - `write_file(relativePath, content, overwrite)`: Writes text to a file.
   - `count_customers()`, `list_customers()`: Queries PostgreSQL.

   RULES:
   1. When the user asks to read/process an input file (e.g. input.txt or input1.txt) and write/save to customers.json:
      - ALWAYS invoke `convert_input_to_customers_json(sourcePath="<source>", targetPath="<target>")`.
      - This tool processes all records in the source file without token limits or truncation.
   2. After the tool returns success, summarize the result to the user.
   """).build();
        this.mcpTools = mcpTools;
        this.logBuffer = logBuffer;
    }

    public String chat(String message) {
        log.info("Incoming chat query: {}", message);
        logBuffer.add("INFO", "AI Orchestrator", "Received chat message: \"" + message + "\"");
        logBuffer.add("DEBUG", "MCP Client", "Invoking Ollama with discovered MCP tools...");

        try {
            String response = chatClient.prompt().user(message).tools(mcpTools).call().content();
            log.info("Agent responded (length: {} chars)", response != null ? response.length() : 0);
            logBuffer.add("INFO", "AI Orchestrator", "Agent call completed successfully.");
            return response;
        } catch (Exception e) {
            log.error("Error during agent execution: {}", e.getMessage(), e);
            logBuffer.add("ERROR", "AI Orchestrator", "Execution error: " + e.getMessage());
            throw e;
        }
    }
}
