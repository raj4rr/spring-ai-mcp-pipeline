# Spring AI MCP Pipeline — STDIO + Ollama

A Java 21 multi-module Maven sample with three applications:

- **AI Orchestrator** — REST API, Ollama `ChatClient`, MCP client.
- **File MCP Server** — `read_file` and `write_file`, sandboxed to `./workspace`.
- **PostgreSQL MCP Server** — read-only `list_customers`, `find_customer_by_email`, `count_customers`.

Both MCP servers are launched as child Java processes and communicate over **STDIO**. The LLM only receives read-only database tools. Database imports require a separate explicit approval request.

## Version baseline

- Java 21+
- Spring Boot 4.0.3
- Spring AI 2.0.2
- PostgreSQL 17
- Ollama model default: `qwen2.5:0.5b-instruct-fp16`

Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x. Confirm the official compatibility matrix before upgrading.

## Architecture

```text
Client -> POST /api/chat -> Spring Boot Orchestrator -> Ollama
                                  |
                                  +-- MCP Client (STDIO) -> File MCP Server -> ./workspace
                                  |
                                  +-- MCP Client (STDIO) -> PostgreSQL MCP Server -> PostgreSQL

Human review:
GET /api/imports/preview
POST /api/imports/approve  (requires X-Approval-Confirm: yes)
   -> JDBC transaction -> PostgreSQL
```

## Prerequisites

Install JDK 21+, Maven 3.9+, Docker, and Ollama. Then:

```bash
ollama pull qwen2.5:0.5b-instruct-fp16
ollama serve
```

Keep Ollama running in a separate terminal.

## Build and start

Run from the repository root:

```bash
docker compose up -d postgres
mvn clean package
mkdir -p workspace
java -jar ai-orchestrator/target/ai-orchestrator-1.0.0.jar
```

The orchestrator launches the two MCP server JARs automatically. Run from the project root because the default server JAR and workspace paths are relative.

Optional environment overrides:

```bash
export OLLAMA_MODEL=qwen2.5:0.5b-instruct-fp16
export OLLAMA_BASE_URL=http://localhost:11434
export MCP_FILES_ROOT="$PWD/workspace"
export DB_URL=jdbc:postgresql://localhost:5432/mcp_demo
export DB_USERNAME=mcp_user
export DB_PASSWORD=mcp_password
java -jar ai-orchestrator/target/ai-orchestrator-1.0.0.jar
```

On Windows PowerShell, use `$env:NAME="value"` for each environment variable. The configured MCP child executable is `java`, not `npm`/`.cmd`, so the batch-file wrapper is not needed.

## Run the end-to-end example

### 1. Ask the agent to read the source file and create JSON

```bash
curl -X POST http://localhost:8080/api/chat \
 -H 'Content-Type: application/json' \
 -d '{"message":"Read input.txt, extract every customer full name and email, then create customers.json as a JSON array. Do not overwrite an existing file."}'
```

Expected JSON structure:

```json
[
  {"fullName":"Alice Johnson","email":"alice@example.com"},
  {"fullName":"Bob Smith","email":"bob@example.com"},
  {"fullName":"Carol Taylor","email":"carol@example.com"}
]
```

### 2. Preview the proposed database import

```bash
curl http://localhost:8080/api/imports/preview
```

Review the record count, duplicates, and data. This request does not modify the database.

### 3. Explicitly approve the import

Only after reviewing the preview:

```bash
curl -X POST http://localhost:8080/api/imports/approve \
 -H 'X-Approval-Confirm: yes'
```

The endpoint inserts within a transaction and uses `ON CONFLICT(email) DO NOTHING` to avoid duplicate email rows.

### 4. Ask the agent to read database records through MCP

```bash
curl -X POST http://localhost:8080/api/chat \
 -H 'Content-Type: application/json' \
 -d '{"message":"Use count_customers and list_customers to report the current customer records."}'
```

## MCP tool inventory

| MCP server | Tool | Permission |
|---|---|---|
| file-mcp-server | `read_file` | Read workspace text |
| file-mcp-server | `write_file` | Write workspace text; overwrite off by default |
| postgres-mcp-server | `list_customers` | Read-only |
| postgres-mcp-server | `find_customer_by_email` | Read-only |
| postgres-mcp-server | `count_customers` | Read-only |

## Project structure

```text
spring-ai-mcp-pipeline/
├── pom.xml
├── docker-compose.yml
├── workspace/input.txt
├── file-mcp-server/
│   └── src/main/java/dev/raj/filemcp/
├── postgres-mcp-server/
│   └── src/main/java/dev/raj/postgresmcp/
└── ai-orchestrator/
    └── src/main/java/dev/raj/orchestrator/
        ├── api/
        └── service/
```

## Important security notes

- **STDIO integrity:** MCP protocol uses stdout. Both MCP servers route application logs to stderr. Do not add `System.out.println()` to MCP server code.
- **File sandbox:** File tools reject absolute paths and traversal outside the configured workspace. Keep the workspace owned by the application user.
- **Human approval:** The LLM has no database write tool. Writes occur only after the separate approval endpoint is called.
- **Local development only:** `X-Approval-Confirm: yes` is an explicit confirmation gate, not authentication. Before deployment, add Spring Security, authenticated user/role authorization, CSRF protection as appropriate, audit records, rate limits, and an immutable import preview/hash bound to the approval.
- Replace demo DB credentials and use environment variables or a secret manager. Never commit production secrets.
- Treat file contents and tool outputs as untrusted data. Validate every value before persistence.

## Troubleshooting

**MCP child process does not start**
- Run `mvn clean package` at the root.
- Check that both `*-mcp-server/target/*-1.0.0.jar` files exist.
- Keep the current directory at the repository root.
- Start the child JAR manually to inspect stderr output.

**Ollama connection refused**
- Start `ollama serve`.
- Check `curl http://localhost:11434/api/tags`.
- Set `OLLAMA_BASE_URL` if Ollama runs elsewhere.

**Model does not call tools**
- Confirm the selected model supports tool calling.
- Try `qwen3:8b` or another tool-capable model.
- Inspect orchestrator logs and MCP child stderr output.

**PostgreSQL connection failure**
- Check `docker compose ps`.
- Verify `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`.

## Official documentation

- MCP overview: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html
- MCP client / STDIO: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html
- MCP server / annotations: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
- Ollama chat: https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html
- Spring AI tool calling: https://docs.spring.io/spring-ai/reference/api/tools.html
