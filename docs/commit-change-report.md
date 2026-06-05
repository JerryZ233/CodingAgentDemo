# Coding Agent Refactor Commit Report

本文档解释本轮架构和代码质量修复产生的 15 个提交。每个提交都对应一次独立的修复、测试、评估和 commit 循环。

## 总览

这轮改动围绕一个简陋 coding agent 的核心风险展开，重点改善了以下方面：

- shell 工具安全边界
- agent loop 的错误恢复能力
- prompt、context window、conversation state 的模块边界
- 执行 trace 和结构化运行结果
- 工具参数校验
- 上下文裁剪
- 日志、存储、可变状态和安全策略的一致性

所有提交完成后，完整测试命令已通过：

```bat
G:\zyq20\Documents\CodingAssistDemo\gradlew.bat test
```

## Commit 1: `0b85d7f` Restrict path-capable shell commands

### 背景

审查发现 `run_shell` 即使默认禁用，一旦启用后也不能真正保证 workspace 边界。`ProcessBuilder.directory(workspaceRoot)` 只设置进程工作目录，不限制命令参数访问绝对路径。例如 `dir C:\...` 或 `ls /...` 仍可能读取 workspace 外的内容。

### 核心改动

- 在 `ShellPolicy` 中增加针对路径型命令的参数限制。
- `cd`、`pwd`、`dir`、`ls` 这类命令不再允许携带参数。
- 路径访问能力交给已有的 `read_file`、`list_files` 等 workspace-aware 工具。
- 添加测试覆盖 `dir C:\` 或 `ls /` 被拒绝的场景。

### 影响

这个提交没有让 shell 成为 OS 级 sandbox，但明显收窄了默认 allowlist 的泄露面。它把路径访问从通用 shell 里拿出来，逼迫 agent 使用更可控的文件工具。

### 测试

- 新增 `ShellRunToolTest.pathCapableCommandsRejectArguments`
- 完整 `gradlew.bat test` 通过

## Commit 2: `bddd352` Continue agent loop after tool failures

### 背景

原来的 `AgentLoop` 在任何工具失败后立刻返回 `TOOL_ERROR`。这和系统 prompt 中的 "If something goes wrong, explain the error and try to fix it" 冲突，也让模型无法看到失败 observation 并尝试修正。

### 核心改动

- 移除 `AgentLoop` 中工具失败后立即终止的逻辑。
- 不论工具成功或失败，都把 tool result 写入 conversation。
- 下一轮 LLM 调用可以看到失败结果并继续决策。
- 增加多工具调用测试，确认同一轮中即使一个工具失败，也会记录后续工具结果。

### 影响

agent loop 更接近实际工具调用协议：工具失败是 observation，不一定是 run failure。只有 LLM 错误或达到 max iterations 才终止。

### 测试

- 更新 unknown tool 和 failing tool 测试
- 新增 `recordsAllToolCallsWhenOneFails`
- 完整 `gradlew.bat test` 通过

## Commit 3: `afc77c5` Extract prompt and context window components

### 背景

`Context` 同时负责 message history、system prompt、tool rendering、context trimming 和 persistence facade。职责过多导致后续扩展 tokenizer、prompt version、summary memory 时会继续膨胀。

### 核心改动

- 新增 `PromptBuilder`
  - 负责构造 system prompt
  - 负责根据 tool specs 渲染 prompt text
  - 保留 OpenAI tools JSON 的来源一致性
- 新增 `ContextWindowStrategy`
  - 负责选择进入 LLM 的历史消息
  - 保留 tool call 和 tool result 的原子块
- `Context` 改为委托这两个组件。
- 保持 `Context` 现有 public API 不变，降低外部调用影响。

### 影响

模块边界更清楚。`Context` 从"什么都管"变成 conversation state 的协调者。prompt 和窗口策略可以独立测试、替换和演进。

### 测试

- 原有 `ContextTest` 全部通过
- 完整 `gradlew.bat test` 通过

## Commit 4: `7430001` Add agent execution event log

### 背景

原项目只能通过最终 conversation messages 推测发生过什么。缺少结构化 trace，无法很好支持 replay、debug、checkpoint 或 run diagnostics。

### 核心改动

- 新增 `AgentEvent`
  - 表示 run started、iteration started、LLM response、tool result、final response、max iterations、run completed 等事件。
- 新增 `AgentEventLog`
  - append-only 事件 sink 接口。
- 新增 `InMemoryAgentEventLog`
  - 用于测试和诊断。
- `AgentLoop` 增加可注入 event log。
- 在 loop 关键节点追加结构化事件。

### 影响

这不是完整 durable resume 引擎，但建立了可替换的事件边界。未来可以把 `AgentEventLog` 换成 JSONL、数据库或 durable checkpoint，而不用重写主循环。

### 测试

- 新增 `AgentLoopTest.appendsStructuredExecutionEvents`
- 完整 `gradlew.bat test` 通过

## Commit 5: `03cd7d5` Return run results from coding agent

### 背景

`AgentRunResult` 已存在，但 `CodingAgent.execute()` 和 `executeWithHistory()` 调用 loop 后直接丢弃结果。上层无法知道成功、失败原因和迭代次数。

### 核心改动

- `CodingAgent.execute(String)` 返回 `AgentRunResult`。
- `CodingAgent.executeWithHistory(String)` 返回 `AgentRunResult`。
- 保留原有 observer 输出行为。
- 增加测试确认两个入口都会返回结构化结果。

### 影响

CLI、API 或其他调用方可以基于 result status 做后续决策。旧调用方如果忽略返回值，仍可继续工作。

### 测试

- 新增 `executeReturnsRunResult`
- 新增 `executeWithHistoryReturnsRunResult`
- 完整 `gradlew.bat test` 通过

## Commit 6: `b8df064` Remove hidden config dependency from tool tests

### 背景

工具和安全测试曾通过反射修改 `Config` singleton 的 private field。这个做法暴露了隐式全局状态，也容易造成测试污染。

### 核心改动

- `SecurityUtil.getWorkspaceRoot()` 不再读取 `Config.getInstance()`。
- 无参 workspace root 只表示当前进程默认 workspace。
- 测试改为显式构造 `FileReadTool(workspace)`、`FileWriteTool(workspace)`、`FileListTool(workspace)`。
- 删除测试中的反射修改配置逻辑。

### 影响

工具测试不再依赖全局 singleton。生产组装路径仍通过 `ToolRegistry.fromConfig(config)` 显式传入 workspace。

### 测试

- 更新 `WorkspaceFileToolsSecurityTest`
- 完整 `gradlew.bat test` 通过

## Commit 7: `3fbd067` Validate tool arguments explicitly

### 背景

工具执行接口仍是 `execute(String args)`，但原来参数解析依赖 `JsonUtil.getString()`。非法 JSON、非对象、非字符串字段都会被吞成 `null`，错误信息不清楚。

### 核心改动

- 新增 `ToolArguments`
  - 显式解析 JSON object
  - 区分 invalid JSON、non-object JSON、missing required string、non-string field
- 文件工具和 shell 工具迁移到 `ToolArguments`。
- `JsonUtil.getString()` 不再被主工具代码使用。
- 新增 `ToolArgumentsTest`。

### 影响

工具参数错误变得可诊断。模型传错参数时，tool result 会明确说明问题，而不是笼统地报 missing field。

### 测试

- 新增 `ToolArgumentsTest`
- 完整 `gradlew.bat test` 通过

## Commit 8: `5d47bed` Trim oversized context messages

### 背景

上下文裁剪已经从 `Context` 抽到 `ContextWindowStrategy`，但仍存在单条超大 tool result 吃掉大部分上下文预算的问题。

### 核心改动

- `ContextWindowStrategy` 增加 `DEFAULT_MAX_MESSAGE_CHARS`。
- 对进入 LLM 的 message 副本做单条内容截断。
- 截断时保留 role、tool_call_id、toolName、tool success/error 等协议元数据。
- `Message` 增加 `withContent` 用于复制 message 并替换 content。
- 原始 conversation history 不被截断污染。

### 影响

上下文仍是字符近似，不是 tokenizer 级别，但更稳了。超大工具输出不会原样塞给模型，也不会破坏持久化历史。

### 测试

- 新增 oversized message 截断测试
- 完整 `gradlew.bat test` 通过

## Commit 9: `aee0203` Preserve interrupt state only for interruptions

### 背景

`ShellRunTool` 原来在 catch `Exception` 时无条件调用 `Thread.currentThread().interrupt()`。这会让普通 IOException、ExecutionException 也污染当前线程的 interrupt state。

### 核心改动

- 将 `InterruptedException` 单独 catch。
- 只有真正的中断异常才恢复 interrupt state。
- 普通执行失败返回 `Execution failed`，不设置 interrupt flag。
- 添加测试覆盖非中断启动失败后线程未被标记 interrupted。

### 影响

shell 工具的异常处理更符合 Java 线程中断语义，避免隐式影响后续测试或调用逻辑。

### 测试

- 新增 `nonInterruptExecutionFailureDoesNotInterruptThread`
- 完整 `gradlew.bat test` 通过

## Commit 10: `ea47db3` Refine sensitive path policy

### 背景

原 `SecurityUtil.isDangerousPath` 使用全路径 substring match，容易误杀。例如 `tokenizer`、`passwordless-login.md` 会因为包含敏感词而被拒。同时 `.env` 既在 blocked pattern 里，又在 allowed extension 里，策略冲突。

### 核心改动

- 将敏感词判断改为路径组件级别。
- 保留系统目录类 pattern 检查。
- 保留 `.git/config` 特殊拒绝。
- 从 allowed write extensions 中移除 `.env`。
- 增加 `SecurityUtilTest` 覆盖误杀和 `.env` 冲突。

### 影响

策略仍偏保守，但更可预测。合法路径名不会因为包含某个 substring 被误伤，敏感路径组件仍被拒绝。

### 测试

- 新增 `SecurityUtilTest`
- 完整 `gradlew.bat test` 通过

## Commit 11: `a36b93b` Stop LLM adapter stderr logging

### 背景

LLM adapter 和 response parser 在错误时直接写 `System.err`。这绕过了 observer/event log，也可能把原始 LLM response 泄漏到控制台。

### 核心改动

- 移除 `LLMClientImpl` 中 HTTP/IO 错误的 stderr 输出。
- 移除 `OpenAIResponseParser` 中 parse failure 的 stderr 输出。
- 错误仍通过 `LLMResponse.error(...)` 结构化返回。
- 新增测试确认 invalid JSON 不写 stderr。

### 影响

LLM 层不再直接绑定控制台。错误传播路径更统一，也减少原始 response 泄漏风险。

### 测试

- 新增 `invalidJsonDoesNotWriteToStderr`
- 完整 `gradlew.bat test` 通过

## Commit 12: `16d16f4` Version prompt templates

### 背景

虽然前面已经抽出了 `PromptBuilder`，但默认 prompt 文本仍在 builder 内部。prompt 是 agent 协议的一部分，应该有版本和独立测试。

### 核心改动

- 新增 `PromptTemplate`
  - 包含 template version
  - 包含默认模板文本
  - 负责渲染 `{TOOLS}` placeholder
- `PromptBuilder` 改为持有 `PromptTemplate`。
- 默认模板版本为 `coding-agent-default-v1`。
- 自定义 prompt 标记为 `custom`。
- 新增 `PromptBuilderTest`。

### 影响

prompt 内容和构造逻辑进一步分离。未来可以做 snapshot test、模板迁移或按模型选择 prompt version。

### 测试

- 新增 `PromptBuilderTest`
- 原 `ContextTest` prompt 测试继续通过
- 完整 `gradlew.bat test` 通过

## Commit 13: `d9ab572` Protect conversation state from external mutation

### 背景

`Context.getMessages()` 直接返回内部 mutable list。`Message` 也直接持有调用方传入的 `toolCalls` list。外部代码可以绕过 Context 的方法修改 conversation state。

### 核心改动

- `Context.getMessages()` 返回 `List.copyOf(messages)`。
- `Message` 构造时对 `toolCalls` 做 defensive copy。
- `Message.getToolCalls()` 返回的 list 因为 `List.copyOf` 变为不可变。
- 增加测试覆盖外部 mutation 被拒绝。

### 影响

conversation state 不再轻易被外部代码篡改。状态修改路径更加集中，便于之后加 event log、audit 或 invariants。

### 测试

- 更新 `ContextTest.testGetMessages`
- 新增 `MessageTest.assistantToolCallMessagesDefensivelyCopyToolCalls`
- 完整 `gradlew.bat test` 通过

## Commit 14: `38f08ca` Save memory files atomically

### 背景

`Memory.save` 原来直接用 `FileWriter(path)` 覆盖目标文件。如果写到一半失败，可能留下半截 JSON，损坏 transcript。

### 核心改动

- 保存时先写入同目录临时文件。
- 写入完成后使用 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` 替换目标。
- 如果平台不支持 atomic move，则 fallback 到普通 replace。
- 失败时 best-effort 清理临时文件。

### 影响

保存 transcript 更可靠。正常情况下不会边写边破坏原文件。CLI 显式 save/load 的路径自由度保持不变。

### 测试

- 原有 `MemoryTest` 覆盖 round-trip、overwrite、save to directory failure 等场景
- 完整 `gradlew.bat test` 通过

## Commit 15: `2261ea9` Remove duplicate shell blocked commands

### 背景

`SecurityUtil` 和 `ShellRunTool` 里各自有一份 blocked command 常量，其中 `ShellRunTool` 的那份已经不再使用。重复常量会造成后续维护时改一处漏一处。

### 核心改动

- 删除 `ShellRunTool.BLOCKED_COMMANDS` 死代码。
- 删除对应 unused import。
- 确认 blocked command 列表只剩 `SecurityUtil` 一处定义。

### 影响

清理重复逻辑，降低维护成本。行为不变。

### 测试

- 完整 `gradlew.bat test` 通过
- 代码搜索确认 `BLOCKED_COMMANDS` 只在 `SecurityUtil` 定义和使用

## 总体结果

本轮提交后，项目的整体结构有以下实质改善：

- `AgentLoop` 更接近真实 agent 的 observe and continue 流程。
- `Context` 的职责明显减轻，prompt 和 window strategy 可独立演进。
- 工具层参数校验更明确。
- shell 工具风险被进一步收窄。
- LLM 错误、agent run result 和 event log 的结构化程度提高。
- conversation state 和 memory persistence 更稳。
- 测试覆盖从单纯 happy path 扩展到了错误路径、安全策略、trace、不可变状态和 prompt 模板。

仍然保留的边界：

- `run_shell` 仍不是 OS 级 sandbox。
- context window 仍是字符近似，不是 tokenizer 精确预算。
- event log 目前是可注入边界和 in-memory 实现，不是完整 durable resume 系统。
- CLI `save/load` 仍允许用户显式指定任意进程可访问路径。

