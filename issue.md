# Spring AI MCP Pipeline — Issues, Root Causes, and Solutions

This document tracks all real-world issues encountered during the development and testing of the Spring AI MCP Pipeline, the root causes identified, and the fixes applied to resolve them.

---

## 1. Model Selection & Context Limitations (0.5B vs 7B)

### Issue
- When extracting customer records from `workspace/input.txt`, only 1, 2, or 4 records were written to `customers.json`, even when `input.txt` contained up to 100 customer records.
- In some runs, names from prompt examples or earlier queries (such as `Alice Johnson`, `James Smith`, or generic names) were written into target files (e.g. `customers1.json` when querying `input1.txt`), even though the beginning of `input1.txt` had completely different customers (`William Harris`, `Elizabeth Ramirez`, `David Walker`, `Barbara King`).

### Root Cause
1. **Model Parameter Constraints (`qwen2.5:0.5b`)**: Compact 500M parameter models have limited attention spans over long sequences. After generating 1 or 2 items, the model tended to emit an early `<|im_end|>` end-of-sequence token.
2. **Few-Shot Memorization & Cross-Contamination**: When the model encounters a prompt mentioning 4 records, it defaulted to previously seen records (from prior prompts or other files like `input.txt`) instead of strictly parsing the tool output from `read_file("input1.txt")`.
3. **Hardcoded File References in System Prompt**: The system prompt previously mentioned `relativePath="input.txt"` and `relativePath="customers.json"`, biasing the model toward those filenames and cached contexts rather than honoring dynamic filenames (`input1.txt`, `customers1.json`).
4. **Default Ollama Limits**: By default, Ollama allocated only 2048 context tokens (`num-ctx: 2048`) and a low completion token budget (`num-predict`), truncating large input files (100+ records) and long JSON responses.

### Solution & Fixes
- **Dynamic File & Anti-Hallucination Prompting** in `ChatService.java`:
  - Explicitly instructed the model to pass the exact filename requested by the user (`input1.txt`, `customers1.json`).
  - Added strict anti-hallucination constraint: *"You MUST extract ONLY the exact customer names and emails returned by read_file. NEVER invent, assume, or pull names from training data, previous prompts, or other files (such as 'James Smith', 'John Doe', or 'Jane Smith') unless they are literally in the content returned by read_file."*
- **Upgraded to `qwen2.5:7b`**: Configured `OLLAMA_MODEL="qwen2.5:7b"` across `application.yml` and `run-local.sh` for robust attention across large context windows and multi-turn tool calling.
- **Configured Extended Context Limits**: In `ai-orchestrator/src/main/resources/application.yml` (`num-ctx: 16384`, `num-predict: 8192`, `temperature: 0.0`).

---

## 2. LLM Returning Raw JSON Text Instead of Calling `write_file`

### Issue
- The LLM responded conversationally with:
  ```json
  {"answer": "You can now create a file called customers.json with the following JSON content..."}
  ```
  The file was never written to disk, leaving the Review & Approval panel empty.

### Root Cause
- The default instructions told the model *what* format to produce, but did not strictly enforce that it **must invoke the tool** rather than answering with markdown/conversational instructions.

### Solution & Fixes
- Hardened the system prompt in `ChatService.java`:
  - Explicitly states: *"Whenever the user asks to create, save, or write customers.json, you MUST invoke write_file(relativePath='customers.json', content=..., overwrite=true). NEVER just print the JSON in your conversational response."*
- Updated UI chat response parsing in `index.html` to parse `data.answer` directly instead of displaying raw JSON objects in message bubbles.

---

## 3. Leftover 0-Byte File & Default Overwrite False

### Issue
- `customers.json` was not being generated, and logs indicated:
  ```text
  [File MCP] File exists and overwrite is false: customers.json
  ERROR: File already exists. Ask before overwriting.
  ```

### Root Cause
- An empty 0-byte `workspace/customers.json` file existed from an aborted run.
- `FileTools.writeFile()` treated `boolean overwrite` as `false` when omitted or when not explicitly set by the LLM.

### Solution & Fixes
- Deleted the 0-byte file from `workspace/`.
- Updated `FileTools.java` so `Boolean overwrite` defaults to `true` when null or omitted:
  ```java
  boolean allowOverwrite = overwrite == null || overwrite;
  ```

---

## 4. Multi-File Customer JSON Handling & Missing Reject Flow

### Issue
- As multiple extraction tests ran, different files were generated (`customers.json`, `customer1.json`, `customers2.json`).
- The review gate was only checking `customers.json`, ignoring other pending files.
- Reviewers had no way to discard proposed changes if they didn't want to import them into PostgreSQL.

### Root Cause
- `ImportController.java` had hardcoded `workspace.resolve("customers.json")`.
- Only an approval endpoint existed (`POST /api/imports/approve`); there was no rejection endpoint.

### Solution & Fixes
- **Multi-File Aggregation** (`readAllCustomerFiles()` in `ImportController.java`):
  - Scans `workspace/` for all files matching `customer*.json`.
  - Merges and deduplicates records across all files into one consolidated preview.
  - Displays each source file in the UI: `Pending Files: workspace/customer1.json workspace/customers.json`.
- **Added Reject Endpoint** (`POST /api/imports/reject`):
  - Discards all pending `customer*.json` files without touching PostgreSQL.
  - Added a red **"✕ Reject"** button next to **"✓ Approve & Insert"** in the GUI.

---

## 5. Cleaning Up Test Data & Resetting State

### Issue
- No easy mechanism to wipe imported records from PostgreSQL and clean up generated workspace JSON files between test runs.

### Root Cause
- Resetting required manual `psql` shell commands and shell `rm` commands.

### Solution & Fixes
- Added `DELETE /api/imports/reset` in `ImportController.java`:
  - Executes `DELETE FROM customers` inside a database transaction.
  - Deletes all `workspace/customer*.json` files.
- Added a **"🗑️ Delete All"** button in the UI header with a confirmation modal and state reset.

---

## 6. Lack of Visibility & Execution Monitoring

### Issue
- Difficult to determine whether the orchestrator called Ollama, which MCP tool ran, or why a database insert failed.

### Root Cause
- STDIO MCP servers write JSON-RPC protocol to stdout; application logs were routed to stderr, but there was no central UI log viewer.

### Solution & Fixes
- **In-Memory Thread-Safe Log Buffer** (`AppLogBuffer.java`):
  - Retains the last 200 categorized events (`[AI Orchestrator]`, `[MCP Client]`, `[Human Review]`, `[Database MCP]`).
- **REST Log Endpoint** (`LogController.java`):
  - `GET /api/logs` and `DELETE /api/logs`.
- **Live Terminal Console in Web UI** (`index.html`):
  - A real-time, auto-scrolling log console at the bottom of `http://localhost:8080`.
  - Visual 9-step architecture tracker on the left panel that highlights active and completed stages in real time.

---

## 7. Identical Output in Different Files (`customers.json` vs `customers1.json`)

### Issue
- Reading two completely different input files (`input.txt` and `input1.txt`) resulted in identical JSON outputs in `customers.json` and `customers1.json`:
  ```json
  [{"fullName": "John Doe", "email": "john.doe@example.com"}, {"fullName": "Jane Smith", "email": "jane.smith@example.com"}, {"fullName": "Bob Johnson", "email": "bob.johnson@example.com"}]
  ```
  Neither `John Doe` nor `Jane Smith` was in either input file!

### Root Cause
1. **Mentioning Counter-Examples in System Prompts (Negative Constraint Fallacy)**:
   - When a system prompt says:
     `NEVER invent names (such as "John Doe", "Jane Smith", "James Smith")`
     small/medium language models often latch onto those mentioned tokens ("John Doe", "Jane Smith") and generate them anyway.
2. **Skipping `read_file` Tool Output Processing**:
   - The LLM invoked `write_file` directly with hallucinated dummy names without first waiting for and parsing the text returned by `read_file`.

### Solution & Fixes
- **Strict Two-Phase Extraction Workflow** in `ChatService.java`:
  - Enforced a sequential tool pipeline:
    - **Step 1**: Call `read_file(relativePath="<source_file>")`.
    - **Step 2**: Receive and parse the real tool output.
    - **Step 3**: Call `write_file(relativePath="<target_file>", content=...)` with only the parsed content.
  - Removed all negative example names (`John Doe`, `Jane Smith`, `Alice Johnson`) from the system prompt so the model has zero dummy names to latch onto.

---

## 8. JSON Schema Tool-Call Serialization Error with `write_file`

### Issue
- The agent failed to write the file, outputting this message in the chat:
  > *"It seems there was an issue with the JSON content returned by the `write_file` tool. The error message indicates that the JSON schema validation failed, and the content is not a valid JSON array..."*

### Root Cause
- When calling `write_file(relativePath="customers.json", content=...)`, Ollama passed the `content` parameter as a raw JSON array/object instead of a JSON string literal.
- In the MCP JSON-RPC protocol schema, `content` expects a string (`type: string`). When the model sends an unescaped raw array `content: [...]` instead of `"content": "[{...}]"`, the MCP client JSON-RPC schema validator rejects the parameter with a schema validation error.

### Solution & Fixes
- **Clear Schema Contract in System Prompt** (`ChatService.java`):
  - Explicitly clarified that `content` must be passed as a properly escaped JSON string representation:
    `content: "[{\"fullName\": \"First Last\", \"email\": \"user@example.com\"}]"`
- **Refined Tool Annotations** (`FileTools.java`):
  - Simplified the `@McpTool` parameter description to guide the LLM's function caller to send stringified text.

---

## 9. Prompt Template Example Literal Memorization ("First Last", "user@example.com")

### Issue
- When running `read input.txt and create customers.json` or `read input1.txt and create customers1.json`, the generated file in both cases contained:
  ```json
  [{"fullName":"First Last","email":"user@example.com"}]
  ```
- File size was exactly 54 bytes in both instances.

### Root Cause
- The system prompt in `ChatService.java` included a format example intended to demonstrate string escaping:
  `"[{\"fullName\":\"First Last\",\"email\":\"user@example.com\"}]"`
- Because of strong in-context literal bias, Ollama treated that example string as literal output data and directly passed it to `write_file`, skipping the actual parsing of the lines returned by `read_file`.

### Solution & Fixes
- Removed the literal placeholder string `"[{\"fullName\":\"First Last\",\"email\":\"user@example.com\"}]"` from the system prompt in `ChatService.java`.
- Explicitly instructed the model:
  - *"DO NOT use dummy values or placeholder text like 'First Last' or 'user@example.com'."*
  - *"Extract the actual Customer and Email lines from the file content."*

---

## 10. Blank / 0-Byte `customers.json` Generation

### Issue
- The model responded conversationally:
  > *"The JSON file 'customers.json' has been successfully written to the target location."*
- However, the preview panel still showed:
  > *"Waiting for Step 6 — Run the extraction prompt in the chat to generate workspace/customers.json."*
- Inspecting `workspace/customers.json` revealed a **0-byte empty file**.

### Root Cause
- When given a minimal prompt like `"Read input.txt and write to customers.json"`, the model called `write_file(relativePath="customers.json", content="")` with an empty string or null content, creating a 0-byte file.
- The `ImportController.preview()` endpoint requires a valid non-empty JSON array (`[{"fullName":...}]`). When it read a 0-byte file, JSON deserialization returned 404/Bad Request, so the preview panel stayed on *"Waiting for Step 6"*.

### Solution & Fixes
1. **Guard Against Empty Writes in `FileTools.java`**:
   - Added validation in `writeFile()`:
     ```java
     if (content == null || content.isBlank()) {
         return "ERROR: Content cannot be empty or blank.";
     }
     ```
   - Prevents empty 0-byte files from being created.
2. **Explicit Sequence in System Prompt (`ChatService.java`)**:
   - Instructed the model:
     - *"Step 1: MUST FIRST call `read_file(relativePath="input.txt")`."*
     - *"Step 2: Parse customer entries ('Customer: ...' and 'Email: ...')."*
     - *"Step 3: Format as a JSON array where each object has 'fullName' and 'email'."*
     - *"Step 4: MUST THEN call `write_file(relativePath="customers.json", content=<JSON string>, overwrite=true)`. Never call write_file with empty content!"*

---

## 11. Enabling Lightweight LLMs (0.5B) to Process Arbitrarily Large Datasets

### Problem
- Lightweight models like `qwen2.5:0.5b` cannot reliably serialize thousands of tokens in a single generation step. If `input.txt` contains 100, 1,000, or 10,000 records, asking a 0.5B model to read the entire file into its working memory, re-encode it, and output megabytes of JSON text inevitably leads to:
  1. Truncated output.
  2. JSON syntax corruption.
  3. Context window exhaustion.

### Architecture Solution: Push Heavy Lifting to the MCP Server
- In enterprise AI systems, **lightweight LLMs act as orchestrators and decision makers**, while **MCP servers execute high-volume data operations** locally with native Java performance:
  - Added specialized tool to `FileTools.java`:
    ```java
    @McpTool(name="convert_input_to_customers_json")
    public String convertInputToCustomersJson(String sourcePath, String targetPath)
    ```
  - **Benefits**:
    1. **Zero Token Loss**: 100, 1,000, or 100,000 records are converted in milliseconds without touching the LLM's output token budget.
    2. **100% Deterministic & Error-Free**: No JSON schema validation errors or escaping bugs.
    3. **Ultra-Lightweight Friendly**: The 0.5B model only needs to make a single function call:
       `convert_input_to_customers_json(sourcePath="input.txt", targetPath="customers.json")`
       which uses less than 30 tokens.

---

## Summary of Active Configurations

| Component | Setting | Value |
|---|---|---|
| **Ollama Model** | `OLLAMA_MODEL` | `qwen2.5:7b` (recommended for full extraction & tool calling) |
| **Context Window** | `num-ctx` | `16384` |
| **Predict Limit** | `num-predict` | `8192` |
| **Temperature** | `temperature` | `0.0` (deterministic outputs) |
| **MCP File Overwrite** | Default | `true` |
| **Pending Files** | Matching Pattern | `workspace/customer*.json` |
| **Human Actions & UI Controls** | `Refresh` | Top-right of panel & inside empty state (`GET /api/imports/preview`) |
| | `Approve & Insert` | Green button: commits transaction to PostgreSQL (`POST /api/imports/approve`) |
| | `Reject` | Red outline button: discards pending `customers*.json` without modifying DB (`POST /api/imports/reject`) |
| | `Delete All` | Red header button: wipes PostgreSQL table & deletes all customer JSON files (`DELETE /api/imports/reset`) |

