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
|   |   `-- impl/        # OpenAI Java SDK client and domain/SDK adapters
|   |-- model/           # Message, tool call and tool result models
|   |-- session/         # Persistent workspace-bound session service (UI-independent)
|   |   `-- storage/     # Versioned UTF-8 session JSON and atomic checkpoints
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

## Persistent session backend (phase 1)

`SessionService` is the new UI-independent backend. It supports explicit workspace selection,
multiple isolated sessions, synchronous multi-turn execution, automatic checkpoints and restart
recovery. The existing `Main`/REPL remains on the legacy path in this phase: it does **not** yet
use this service. New terminal commands, asynchronous execution and cancellation are later phases.

Example Java integration (the selected workspace must already exist):

```java
Config config = Config.getInstance();
try (SessionService sessions = new DefaultSessionService(config)) {
    SessionSnapshot session = sessions.createSession(Path.of("G:/projects/example"));
    sessions.runTurn(session.id(), "Create a greeting in hello.txt");
    SessionSnapshot result = sessions.runTurn(session.id(), "Read it and change the greeting");
    System.out.println(result.lastRun().status());
    System.out.println(session.id()); // Keep this ID to resume after restarting the application.
}
```

Use imports from `com.demo.session`, `com.demo.config.Config` and `java.nio.file.Path`.
On a later startup, `getSession(id)` restores the history and `runTurn(id, message)` continues it;
`listSessions()` returns recent summaries plus individual unreadable-file issues.

- The default store is `${user.home}/.coding-agent/sessions/`; the constructor
  `DefaultSessionService(config, directory)` overrides it. CLI `--session-dir` is not implemented yet.
- Each UUID identifies a version-1 JSON file with title, real absolute workspace, UTC timestamps,
  complete messages and the latest run summary. No API key, system prompt or client is serialized.
  Titles use the first request's first nonblank line, up to 40 Unicode code points.
- Paths resolve inside the session workspace. Changing workspace means creating another session;
  restoring a missing or redirected directory still permits history viewing but blocks execution.
- User messages are saved before model requests, tool calls before execution, results after each
  tool, and final answers before the terminal run status. Files use UTF-8 and a temporary file with
  atomic replacement when supported. The repository assumes a single application process.
- A save failure stops further tools and blocks new turns in the service. The immutable snapshot
  retains unsaved state; repair storage and call `retrySave(id)`. It saves an interrupted turn with
  missing tool results marked unknown, without resuming or replaying it. Completed file changes
  are retained. A crash is handled the same way on restore, even if final text was already saved.
- One turn runs at a time; concurrent mutation requests receive `SessionException` with `BUSY`
  instead of being queued. Published snapshots remain readable. Model errors and iteration limits
  finish the turn, not the session. There is no cancellation or token streaming in this phase.
- Production constructors own and close the model client. The constructor accepting a client,
  repository and clock borrows the client by default; `ClientOwnership.OWNED` explicitly transfers
  ownership. A failed close checkpoint leaves the service open so saving/closing can be retried.
- The service never reads terminal input or prints model/tool results. Inject a scripted `LLMClient`,
  a `SessionRepository`, and a `Clock` for deterministic tests without paid API calls.

Pre-release development does not require backwards compatibility. Before removing or changing
existing functionality, describe the concrete change and obtain user confirmation. This applies
until the user declares the official release.

## Configuration

`config.yaml` contains non-secret defaults. Set `LLM_API_KEY` in the environment for real LLM access. The shell tool is disabled by default. If `run_shell` is enabled, it still applies only a narrow in-process policy: platform shell names are allowlisted, unknown shell names are rejected instead of falling back, command names must be on a small allowlist, and shell metacharacters, pipes, redirects, and background execution are blocked. This reduces accidental risk but is not an OS-level sandbox, permission boundary, or human approval flow.

## Model client configuration

Model communication uses the official OpenAI Java SDK 4.81.0 and the synchronous
Chat Completions API. The SDK handles HTTP transport and JSON serialization;
the application maps domain messages and tool schemas to SDK types and maps SDK
responses back to `LLMResponse`. `AgentLoop` continues to execute tools itself.

Existing `llm.api_url` / `LLM_API_URL` configuration remains supported. Both a
complete endpoint and a base URL are accepted, for example:

```text
https://api.openai.com/v1/chat/completions
https://api.openai.com/v1
https://example.com/custom/api/v3/chat/completions/
```

The client removes a final `/chat/completions` (and optional trailing slash)
before passing the base URL to the SDK, preserving custom path prefixes.
Only HTTP(S) URLs without embedded credentials, query parameters or fragments
are accepted. API keys and model settings still come from the project's YAML /
`LLM_*` environment configuration, not the SDK's `OPENAI_*` environment variables.

Each client reuses its SDK connection pool, has a 120-second request timeout,
and disables SDK retries. HTTP and transport/response decoding errors become
`LLMResponse.error()` values without logging raw responses. Compatibility with
third-party endpoints depends on their Chat Completions and function-calling
support; this migration does not add provider-specific features.

## How to Run Tests

```bash
./gradlew test
```

## Architecture

### Packages

- **agent**: Contains `Main`, `CodingAgent`, `AgentLoop`, `Context`, and `Memory`
- **config**: Contains YAML and environment configuration loading
- **llm**: Contains `LLMClient` and `LLMResponse`
- **llm.impl**: Contains `OpenAICompatibleLLMClient` and `DummyLLMClientImpl`
- **model**: Contains `Message`, `ToolCall`, and `ToolResult`
- **session**: Owns workspace-bound sessions, published snapshots, turn lifecycle and recovery
- **session.storage**: Stores and validates complete sessions, separately from legacy conversation files
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
- Session checkpoints preserve conversation and run state, but are not transactions with filesystem
  changes: an interrupted tool's actual outcome may be unknown. Tools are never replayed automatically.
- The legacy CLI has not yet been wired to the session backend. Cancellation and durable event replay
  are not implemented.
