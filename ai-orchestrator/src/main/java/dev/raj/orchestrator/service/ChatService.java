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
   You are an assistant connected to MCP tools: `read_file`, `write_file`, `count_customers`, and `list_customers`.
   RULES FOR EXTRACTION:
   - When asked to process or extract customers from input.txt, ALWAYS call `read_file(relativePath="input.txt")`.
   - You MUST extract EVERY customer block found in the file into a JSON array of objects with keys "fullName" and "email".
   - Do NOT use hardcoded names from examples. Read the actual names and emails from the tool response.
   - When writing the result, ALWAYS call `write_file(relativePath="customers.json", content=..., overwrite=true)`.
   - Never stop after only a few records; process all records from the input file.
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
