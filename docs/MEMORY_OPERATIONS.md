# 分层记忆：启用、迁移、观测与恢复

源码按功能拆分为六个子包，独立单类保留在根包，逐文件职责和调用关系见 [Memory 架构说明](MEMORY_ARCHITECTURE.md)。

阶段 0～8 的实现已接入。默认 `memory.enabled=true`；初次启动会后台检查配置的 session-dir，符合资格的旧会话可能调用当前模型进行整理。前台接近预算时自动压缩，轮末达到阈值后提炼项目长期知识。全局知识必须明确批准。所有自动维护均不重放工具或命令。

## 文件和开关

| 位置 | 内容与共享方式 |
| --- | --- |
| `~/.coding-agent/global/MEMORY.md` | 用户跨项目稳定知识 |
| `<worktree>/.coding-agent/MEMORY.md` | 项目稳定条目，可由用户提交 Git |
| `<worktree>/.coding-agent/local/` | history、候选、来源、旧版本与事务；由上级 `.gitignore` 忽略 |
| 当前 `--session-dir` | 完整原始对话与会话状态，保持既有路径 |

Git 项目按 worktree 根隔离，子目录共享项目记忆；非 Git 项目按真实工作目录隔离。程序不自动提交记忆文件，也不清理原始会话、history 或修改前版本。全局目录可通过 Java `MemoryHarness` 构造器注入；测试和嵌入式调用应使用隔离目录。

```yaml
memory:
  enabled: true
  context_window_tokens: 32768
  safety_margin_tokens: 2048
  compact_trigger_ratio: 0.80
  compact_target_ratio: 0.60
  global_budget_tokens: 1024
  project_budget_tokens: 3072
  summary_budget_tokens: 1200
  retain_recent_blocks: 2
  consolidation:
    enabled: true
    new_history_threshold: 5
    batch_size: 10
  maintenance:
    enabled: true
    min_interval_hours: 24
    scan_interval_seconds: 60
```

配置优先级为环境变量 > 本地 YAML > 公共 YAML > 默认值。`MEMORY_ENABLED=false` 完整关闭记忆输入、记忆工具和维护执行器，保留旧上下文窗口。仅关闭 `MEMORY_MAINTENANCE_ENABLED` 会停止自动扫描，但保留前台压缩、轮末提炼和手动整理；`MEMORY_CONSOLIDATION_ENABLED=false` 停止自动长期提炼，手动 consolidate 仍可执行。

窗口是工程配置，不会自动识别模型上下文上限。若提高 `llm.max_tokens`，也要为系统提示、工具、长期记忆、摘要和近期消息保留足够输入预算；启动时发现预算不合理会拒绝配置。Token 估算仅用于输入决策，不作为真实账单。

## 常用操作

```text
/memory status
/memory show
/memory search 配置加载
/memory read 1
/memory pending
/memory approve project
/memory approve global
/memory resolve 保留旧的
/memory compact
/memory consolidate
/cancel
```

候选只在当前项目空闲时逐条展示。自然语言“保留旧的”“采用新的”只作用于该会话已经展示的候选；全局必须明确说“批准全局记忆”。模糊回复不批准。`compact`/`consolidate` 是异步请求，不阻塞终端输入；取消和关闭保留原始数据，退出不再新建整理任务。

只读查看不会追加对话消息。新会话加载精简全局/项目条目，其他历史通过检索；恢复同一会话加载已提交连续摘要与未覆盖消息。新克隆只有共享 `MEMORY.md` 也能读取，`show` 标记本地来源不可用；有本地审计不代表原始会话仍存在，`memory_read` 仍会独立核验来源。

## 用量和维护进度

`/status` 与 `/memory status` 显示当前开关、dirty 状态、最近项目维护及本服务进程用量。Java `SessionService.modelUsage()` 按 AGENT、COMPACTION、CONSOLIDATION 返回尝试次数、重试次数、响应/usage 覆盖、输入/输出/总 Token、缓存及耗时。SDK 每次实际 HTTP 尝试都计入，包括错误、重试和取消；格式修复属于新的模型请求。自定义 LLMClient 默认兼容入口将一次调用记作一次尝试；若自定义实现内部有重试，需实现带 attempt observer 的六参数入口逐次报告。

没有提供 usage 的请求保持未知；已知部分的 Token 只累加已知值，并明确标记不完整。没有请求时也不宣称用量完整。缓存是输入 Token 的子集，不能再次加进输入总数。并发请求通过请求局部 observer 统计，不共享可变请求用途。

前台压缩沿用会话事件；手动、后台及轮末提炼通过独立维护事件展示，不进入对话。Java `memoryEventsAfter(sessionId, cursor)` 只返回该项目的进度，提供全局游标和丢失区间标记；缓冲最多 256 条，完整过程写入普通文件日志。结构化日志使用 purpose/attempt 与 origin/operation/phase，不记录记忆正文、提示或原始来源内容。用量汇总不跨进程重启恢复；历史文件不是账单存储。

## 升级与回退

升级前可备份当前 session-dir 和项目/全局 `.coding-agent` 目录。版本 1 会话可只读加载，消息 ID 由会话 ID 与序号确定性生成；只有保存或成功归档才写成版本 2。不要为了升级手动清空历史。

共享 Markdown 保留稳定条目 ID，人工只改正文；不要复制重复 ID 或破坏管理标记。跨机器克隆不包含 local 的来源、备份与候选，缺少这些本地信息不会把共享正文当成全局指令。实际代码和当前用户要求始终优先。

要停止维护或排查问题，先设置 `MEMORY_MAINTENANCE_ENABLED=false`，需要完全恢复旧上下文策略时设置 `MEMORY_ENABLED=false`，重启服务。关闭功能不会删除 v2 会话和已归档数据；旧版程序未必能读取 v2，因此回退旧二进制时使用升级前备份，保留新文件另行备查。

## 中断与异常恢复

| 情况 | 行为与处理 |
| --- | --- |
| 进程异常退出 | 原始消息按检查点恢复，不依赖退出整理；恢复会话不重放工具 |
| 已写归档、未写提交标记 | 请求准备和归档协调器按该会话引用恢复；未提交归档不会被检索 |
| 长期操作写了一部分 | 稳定 operation ID、准备计划和长期事务支持重试；成功后才推进 history 游标 |
| 其他进程或人工改动 | CAS 拒绝过期写入；未执行的长期准备操作转为候选，重新展示后确认 |
| 损坏的完整日志行或 Markdown 标记 | 停止自动修改，不覆盖或从摘要重建原始来源；先备份现状，再核对版本与日志 |
| 写入时截断的 journal 尾行 | 读取保留完整前缀并标记不完整；下一次合法写入备份尾部并恢复完整边界 |
| 长期事务与人工改动冲突 | 保留 pending、版本备份和现状，停止自动覆盖；核对三者后选择一致版本再重试，不能只删除游标强行跳过 |
| 项目只读或磁盘空间不足 | 允许只读加载，不转存其他目录；压缩无法持久化且完整输入超限时暂停，修复权限/空间后重试 |
| 关闭超过五秒 | 保持可重试关闭，不宣称模型/工具已释放；稍后重试 `/exit` |

原始会话、归档链与连续摘要须匹配，不能只把 session 的覆盖 ID 清零或删除 history 以绕过校验。全局恢复不从项目 prepared 自动重放；未完成全局写入需要重新展示并明确批准，已经提交的全局审计凭据可完成候选移除。详细存储合同见 [MEMORY_STORAGE.md](MEMORY_STORAGE.md)。

## 离线验收与编码评测隔离

```powershell
.\gradlew.bat --offline --no-daemon test
.\gradlew.bat --offline --no-daemon memoryTest
python -m unittest discover -s eval -p 'test_*.py' -v
```

`memoryTest` 只用脚本模型、本地 HTTP、临时全局目录与独立项目标记，不调用真实模型 API。HTML 报告在 `build/reports/tests/memoryTest/index.html`，JUnit XML 在 `build/test-results/memoryTest/`。它涵盖以下验收项：

| 场景 | 主要测试 |
| --- | --- |
| 早期约束、分批压缩与连续摘要 | AutomaticCompactionIntegrationTest、MemoryModelTest |
| 默认启用、多会话继承、重启与新克隆 | LayeredMemoryAcceptanceTest |
| 检索、有界来源与项目隔离 | HistorySearchServiceTest、HistoryRetrievalIntegrationTest |
| 提炼游标、候选裁决、全局批准 | ConsolidationServiceTest、CliMemoryProposalIntegrationTest |
| 首次回填、24h、退避、并发提交和取消 | MemoryMaintenanceTest、MemoryMaintenanceIntegrationTest |
| 崩溃恢复、短锁、冲突和只读存储 | memory.storage 下的测试 |
| usage、缓存、重试和缺失字段 | MemoryTelemetryTest、ObservedLLMClientTest、本地 HTTP 客户端测试 |
| CLI 独立进度、隐私与去重 | CliMemoryProgressIntegrationTest |

普通编码评测的两个 YAML、Harbor adapter 以及 EvalMain 均显式关闭记忆；EvalMain 即使收到启用配置也强制关闭并记录 `memory_enabled=false`。每次评测原始会话仍写到独立 output/sessions，避免历史或后台维护影响分数和成本。真实模型 Harbor 评测须遵守根 AGENTS.md 的逐次显式授权，本阶段不自动运行。

离线场景验证存储和调度合同，不代表已经评估某个真实模型的摘要保真度或知识提炼质量；这部分需要单独明确授权的真实模型评测。
