# AI Coding Agent Demo

A minimal AI Coding Agent demo in Java demonstrating the fundamental architecture of an AI agent that can execute coding tasks using tools.

## Project Overview

This project implements a basic AI Coding Agent with:

- **Agent Loop**: Think-decide-execute-observe cycle
- **LLM Integration**: Interface-based design for communication with Large Language Models
- **Tool System**: Extensible tool framework for file operations and optional command execution
- **Inversion of Control**: LLM and tools are injected through interfaces so tests can use dummy implementations
- **Structured Tool Events**: Tool calls and tool results are preserved as structured conversation messages

## Requirements

- **Java**: JDK 17 or later
- **Gradle**: 8.10.2 (managed by Gradle Wrapper)

## Project Structure

```text
CodingAgentDemo/
|-- src/main/java/com/demo/
|   |-- agent/           # Agent orchestration, context and memory
|   |-- config/          # YAML and environment configuration
|   |-- llm/             # LLM client interface
|   |   `-- impl/        # LLM client implementations
|   |-- model/           # Message, tool call and tool result models
|   `-- tools/           # Tool interface and tool implementations
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

`config.yaml` contains non-secret defaults. Set `LLM_API_KEY` in the environment for real LLM access. The shell tool is disabled by default; only enable `run_shell` after adding the approval and policy controls appropriate for your environment.

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

- **Interface-based Design**: LLM and Tool components use interfaces for flexibility
- **Inversion of Control**: Real implementations can be swapped with dummy implementations for testing
- **Command-style Tools**: Each tool exposes a stable name, description, argument schema, and execution method
- **Structured Conversation Events**: Tool calls and tool results are stored as structured messages for replay/debuggability
