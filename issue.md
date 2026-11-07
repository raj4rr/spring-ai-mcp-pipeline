# Spring AI MCP Pipeline — Issues, Root Causes, and Solutions

This document tracks all real-world issues encountered during the development and testing of the Spring AI MCP Pipeline, the root causes identified, and the fixes applied to resolve them.

---

## 1. Model Selection & Context Limitations (0.5B vs 7B)

### Issue
- When extracting customer records from `workspace/input.txt`, only 1, 2, or 4 records were written to `customers.json`, even when `input.txt` contained up to 100 customer records.
- In some runs, names from prompt examples (`Alice Johnson`, `Bob Smith`, `Carol Taylor`, `Rajesh Kumar`) were written instead of the actual data inside `input.txt` (`James Smith`, `Mary Brown`, `John Miller`, etc.).

### Root Cause
1. **Model Parameter Constraints (`qwen2.5:0.5b`)**: Compact 500M parameter models have limited attention spans over long sequences. After generating 1 or 2 items, the model tended to emit an early `<|im_end|>` end-of-sequence token.
2. **Few-Shot Memorization**: The model copied hardcoded example names from the system prompt prompt rather than extracting the text returned by the tool.
3. **Default Ollama Limits**: By default, Ollama allocated only 2048 context tokens (`num-ctx: 2048`) and a low completion token budget (`num-predict`), truncating large input files (100+ records) and long JSON responses.

### Solution & Fixes
- **Upgraded to `qwen2.5:7b`**: Configured `OLLAMA_MODEL="qwen2.5:7b"` across `application.yml` and `run-local.sh` for robust attention across large context windows and multi-turn tool calling.
- **Removed Hardcoded Names**: Stripped static name examples from `ChatService.java` so the LLM is forced to extract real data dynamically from `read_file`.
- **Configured Extended Context Limits**: In `ai-orchestrator/src/main/resources/application.yml`:
  ```yaml
  spring:
    ai:
      ollama:
        chat:
          model: ${OLLAMA_MODEL:qwen2.5:7b}
          options:
            temperature: 0.0
            num-ctx: 16384
            num-predict: 8192
  ```

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

## Summary of Active Configurations

| Component | Setting | Value |
|---|---|---|
| **Ollama Model** | `OLLAMA_MODEL` | `qwen2.5:7b` (recommended for full extraction & tool calling) |
| **Context Window** | `num-ctx` | `16384` |
| **Predict Limit** | `num-predict` | `8192` |
| **Temperature** | `temperature` | `0.0` (deterministic outputs) |
| **MCP File Overwrite** | Default | `true` |
| **Pending Files** | Matching Pattern | `workspace/customer*.json` |
| **Human Actions** | UI Controls | `Approve & Insert`, `Reject`, `Delete All`, `Refresh` |

