# AI Coding Agent Demo

A minimal AI Coding Agent demo in Java demonstrating the fundamental architecture of an AI agent that can execute coding tasks using tools.

## Project Overview

This project implements a basic AI Coding Agent with:

- **Agent Loop**: Think-decide-execute-observe cycle
- **LLM Integration**: Interface-based design for communication with Large Language Models
- **Tool System**: Extensible tool framework for file operations and code execution
- **Inversion of Control**: Clean separation between interfaces, implementations, and dummy implementations for testing

## Requirements

- **Java**: JDK 17 or later
- **Gradle**: 8.10.2 (managed by Gradle Wrapper)

## Project Structure

```
CodingAgentDemo/
├── src/main/java/com/demo/
│   ├── agent/           # Agent orchestration
│   ├── llm/             # LLM client interface
│   │   └── impl/        # LLM implementations
│   ├── model/           # Data models
│   └── tools/           # Tool interface
│       └── impl/        # Tool implementations
├── build.gradle.kts     # Gradle build configuration
├── settings.gradle.kts  # Gradle settings
└── gradle/              # Gradle wrapper
```

## How to Build

```bash
./gradlew build
```

For Windows:

```gradle
gradlew.bat build
```

## How to Run

```bash
./gradlew run
```

For Windows:

```bash
gradlew.bat run
```

You can also pass a custom task as a command-line argument:

```bash
./gradlew run --args="create a hello world file"
```

## How to Run Tests

```bash
./gradlew test
```

## Architecture

### Packages

- **agent**: Contains `Main`, `CodingAgent`, and `AgentLoop` - the core orchestration logic
- **llm**: Contains `LLMClient` interface and `LLMResponse` class for LLM communication
- **llm.impl**: Contains abstract `LLMClientImpl` and `DummyLLMClientImpl` for testing
- **model**: Contains `Message`, `ToolCall`, and `ToolResult` data models
- **tools**: Contains the `Tool` interface for extensibility
- **tools.impl**: Contains abstract tool implementations and dummy implementations for testing

### Design Patterns

- **Interface-based Design**: LLM and Tool components use interfaces for flexibility
- **Inversion of Control**: Real implementations can be swapped with dummy implementations for testing
- **Template Method**: Abstract classes provide common functionality while allowing specific implementations
