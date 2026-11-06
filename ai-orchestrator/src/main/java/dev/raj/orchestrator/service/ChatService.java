package dev.raj.orchestrator.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpTools;

    public ChatService(ChatClient.Builder builder, ToolCallbackProvider mcpTools) {
        this.chatClient = builder.defaultSystem("""
   You are an assistant connected to MCP tools for files and PostgreSQL.
   When asked to perform tasks, you MUST call the appropriate MCP tool:
   - To read a workspace file, call the `read_file` tool.
   - When asked to extract customers from input.txt, you MUST read the ENTIRE file, extract ALL customers (do not stop at 1, extract every single record), and write ALL of them into `customers.json`.
   - To create or update files, call the `write_file` tool with relativePath="customers.json" and overwrite=true.
   - When generating customers.json, format it strictly as a JSON array of all extracted objects with keys "fullName" and "email":
     [{"fullName":"Alice Johnson","email":"alice@example.com"},{"fullName":"Bob Smith","email":"bob@example.com"},...]
   - Do not merely print text asking the user to create the file if you can call write_file yourself.
   - PostgreSQL tools (count_customers, list_customers, find_customer_by_email) are read-only.
   """).build();
        this.mcpTools = mcpTools;
    }

    public String chat(String message) {
        return chatClient.prompt().user(message).tools(mcpTools).call().content();
    }
}
