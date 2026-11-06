#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
docker compose up -d postgres
ollama pull "${OLLAMA_MODEL:-qwen2.5:0.5b-instruct-fp16}"
mvn clean package
mkdir -p workspace
exec java -jar ai-orchestrator/target/ai-orchestrator-1.0.0.jar
