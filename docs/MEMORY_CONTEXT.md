# 上下文组装与预算（阶段 3）

默认 `memory.enabled=true`，`DefaultSessionService` 为每个前台会话注入 `MemoryHarness`；显式关闭时使用旧的字符数/消息数窗口。本文描述阶段 3 的组装接口；阶段 4 已加入自动压缩，详见 [压缩说明](MEMORY_COMPACTION.md)。直接使用只读 preparation 重载时仍不会生成摘要或写记忆。配置、升级和验收见 [运行说明](MEMORY_OPERATIONS.md)。

## 请求链路

```text
CodingAgent 设置工具规格
→ AgentLoop 每次迭代调用 Context.prepareForLLM(token)
→ ContextPreparation
→ MemoryHarness 读取与验证
→ ContextAssembler + ContextBudget
→ 同一个 Input 中的 messages / toolsDescription 发送给 LLMClient
```

`Context` 只保存消息、内容版本和宿主准备入口，不自己操作记忆文件。`ContextPreparation.Input` 保存不可变消息快照、对应工具 schema 和可选预算报告；兼容路径没有报告。启用记忆后，输入顺序为基础系统提示、全局有效条目、项目有效条目、会话连续摘要、全部未覆盖消息。

全局默认目录为 `~/.coding-agent/global`。项目目录由 `ProjectLocator` 定位到当前 worktree 根的 `.coding-agent`；文件工具的工作范围不改变。每次请求重新读取长期文件，只渲染 active 管理条目；管理块之外的文本仍由存储保留，但不作为长期规则自动注入。

长期记忆与摘要使用有标签的背景数据消息，不作为新的系统提示；基础提示追加其历史证据属性和优先级说明。内部消息 ID、工具诊断字段不发送到模型，也不计入估算。

## 估算与拒绝行为

`TokenEstimator` 使用 ASCII 字符总量向上取三分之一，加非 ASCII Unicode 码点数；另计请求、消息、工具调用与 schema 的结构开销。这不是精确 tokenizer，实际服务商 usage 不会自动改变预算。

`ContextBudget.Report` 含：

- 系统提示、工具 schema、全局记忆、项目记忆、连续摘要、近期消息的分项估算。
- 最大协议块估算与近期块数。
- 输入限额、输出预留、安全余量、总窗口、压缩触发与目标阈值。
- 分层超限、总输入超限及单块无法容纳的检查结果。

每个分项计入其实际消息标签与封装开销；输入总量另外加请求开销。输入硬限额为 `context_window_tokens - safety_margin_tokens - llm.max_tokens`。组装器只标记 `compactionRecommended`；运行时 Harness 在触发后执行压缩，再由同一组装器重新验证。硬限额或分层限额不因压缩失败而放宽。

启用后不再经过旧的 100 条/64,000 字符窗口和单消息 8,000 字符裁剪；保留工具自身的输出限制。不能容纳时返回 `CONTEXT_BLOCKED`，保存用户消息和运行结果，不把诊断追加成助手消息，也不发送模型请求。

拒绝原因：

| 原因 | 含义 |
| --- | --- |
| `BUDGET_EXCEEDED` | 总输入、分层或协议块超限，报告保留具体分项 |
| `INVALID_HISTORY` | 结构不完整、孤立工具结果或非法覆盖边界 |
| `UNCOMMITTED_SUMMARY` | 归档未提交，或摘要与完整覆盖链不一致 |
| `MEMORY_UNAVAILABLE` | 项目定位、长期文件或历史读取失败 |

错误不会清除既有摘要和原始消息。修复预算或存储问题后可以继续提交新一轮。当前尚无 `/memory compact` 等命令，不应提示使用未实现的命令。

## 协议块与摘要可信性

`ConversationBlockBuilder` 被旧窗口、新组装器和 `CompressionState` 共用。一个 assistant 工具调用消息及其全部结果是一个块；结果可以乱序，重复/不匹配结果被拒绝。未完成的最终工具块可以存在于检查点，但不能被压缩或作为新的模型请求发出。

Harness 使用摘要前检查 `HistoryRepository.read()` 返回的已提交历史：

- 当前归档及所有前驱都已提交，属于同一会话，链无环。
- 当前摘要正文、覆盖边界、内容版本和压缩版本与链一致。
- 从最早归档到当前归档的消息 ID 串联后，正好等于原始消息的已覆盖前缀。

该校验只读。出现“会话检查点已保存但提交标记未写”的中断状态时拒绝直接使用摘要；阶段 4 通过独立运行协调入口恢复本会话提交标记，再重新校验，不在纯组装器中写磁盘或替换内存 Context。

## 可观察性与后续接入

- 成功准备发出 `AgentEvent.CONTEXT_PREPARED`，转为 `SessionEvent.ContextPrepared`，包含无正文的预算报告；CLI 不重复打印分项，日志记录报告。
- `Context.getLastBudgetReport()` 返回最近一次成功或预算拒绝的报告；无报告的失败会清除旧报告，避免显示过期数据。
- `CONTEXT_BLOCKED` 是独立的 Agent/Session 结果，CLI 有中文状态和保留消息提示；单任务模式返回失败退出码。
- 测试/嵌入应用通过 `DefaultSessionService` 的显式 Harness 注入入口指定隔离全局目录，不应读写真实用户记忆。
- 阶段 4 的运行 preparation 重载接收 `MemoryModel` 与 `SessionArchiveAccess`：先估算、归档并协调更新内存状态，再重新组装；准备与模型请求使用同一取消信号。纯组装和只读重载不发起模型请求。

普通离线回归使用 `gradlew.bat --offline --no-daemon test`。真实模型测试须遵循根目录 `AGENTS.md` 的显式授权规则。
