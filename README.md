# AI Coding Agent Demo

A minimal AI Coding Agent demo in Java demonstrating the fundamental architecture of an AI agent that can execute coding tasks using tools.

## Project Overview

This project implements a basic AI Coding Agent with:

- **Agent Loop**: Think-decide-execute-observe cycle
- **LLM Integration**: Interface-based design for communication with Large Language Models
- **Tool System**: Extensible tool framework for file operations and optional command execution
- **Inversion of Control**: LLM and tools are injected through interfaces so tests can use dummy implementations
- **Structured Tool Messages**: Tool calls and tool results are preserved in conversation history for testing and replay-style regression checks

## Requirements

- **Java**: JDK 17 or later
- **Gradle**: 8.10.2 (managed by Gradle Wrapper)

## Project Structure

```text
CodingAgentDemo/
|-- src/main/java/com/demo/
|   |-- agent/           # Agent orchestration, context, observers and conversation storage
|   |-- config/          # YAML and environment configuration
|   |-- llm/             # LLM client interface and factory
|   |   `-- impl/        # OpenAI-compatible client adapter/parser implementations
|   |-- model/           # Message, tool call and tool result models
|   `-- tools/           # Tool interface, registry, specs and implementations
|-- build.gradle.kts     # Gradle build configuration
|-- config.yaml          # Default non-secret configuration
|-- settings.gradle.kts  # Gradle settings
`-- gradle/              # Gradle wrapper
```

## How to Build

```bash
./gradlew build
```

For Windows:

```bat
gradlew.bat build
```

## How to Run

```bash
./gradlew run
```

For Windows:

```bat
gradlew.bat run
```

You can also pass a custom task as a command-line argument:

```bash
./gradlew run --args="create a hello world file"
```

## Configuration

`config.yaml` contains non-secret defaults. Set `LLM_API_KEY` in the environment for real LLM access. The shell tool is disabled by default. If `run_shell` is enabled, it still applies only a narrow in-process policy: platform shell names are allowlisted, unknown shell names are rejected instead of falling back, command names must be on a small allowlist, and shell metacharacters, pipes, redirects, and background execution are blocked. This reduces accidental risk but is not an OS-level sandbox, permission boundary, or human approval flow.

## How to Run Tests

```bash
./gradlew test
```

## Architecture

### Packages

- **agent**: Contains `Main`, `CodingAgent`, `AgentLoop`, `Context`, and `Memory`
- **config**: Contains YAML and environment configuration loading
- **llm**: Contains `LLMClient` and `LLMResponse`
- **llm.impl**: Contains `LLMClientImpl` and `DummyLLMClientImpl`
- **model**: Contains `Message`, `ToolCall`, and `ToolResult`
- **tools**: Contains the `Tool` interface and concrete file/shell tool implementations

### Design Patterns

- **Interface-based Design**: LLM, Tool, conversation storage, and observer components use interfaces for flexibility
- **Inversion of Control**: Real implementations can be swapped with dummy/fake implementations for testing
- **Command-style Tools**: Each tool exposes a stable name, description, argument schema, and execution method
- **Factory/Registry Boundaries**: LLM selection and tool registration live outside the main `CodingAgent`
- **Structured Conversation Messages**: Tool calls and tool results are stored as structured messages; this supports focused regression tests, not full production trace/recovery

## Current Limitations

- There is no production sandbox for shell execution.
- Context windowing uses approximate character counts rather than model tokenizer counts.
- The saved conversation transcript is useful for regression tests, but this demo does not implement full run checkpointing, cancellation, or durable event replay.
