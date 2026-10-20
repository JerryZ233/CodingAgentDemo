# Memory 存储接口（阶段 2）

存储可独立测试，构造入口使用显式目录，不会自行访问用户全局记忆。阶段 4 已通过 `SessionArchiveCoordinator` 接入前台自动压缩；阶段 5 已接入只读历史搜索/读取与 CLI 查看。长期提炼和后台任务尚未接入。

## 长期记忆

`LongTermMemoryRepository(directory)` 可用于全局或项目的记忆目录。`read()` 不创建目录、锁文件或本地索引，因此新克隆只有 `MEMORY.md` 时也可读取。

管理条目格式：

```markdown
<!-- memory:item id="00000000-0000-0000-0000-000000000001" kind="rule" status="active" -->
- 修改配置时保持环境变量优先于 YAML。
<!-- /memory:item -->
```

- `id` 是稳定 UUID；`kind` 为 `fact`、`rule`、`decision` 或 `preference`。
- `status` 为 `active` 或 `inactive`；未写 status 的旧条目按 active 读取。
- 用户可编辑条目正文。管理块以外的 Markdown 原样保留；未知/不完整管理标记、重复 ID、非法 UTF-8 会拒绝读取或更新。
- `MemoryDocument` 返回原文、内容 SHA-256 版本和条目；文件不存在的版本为 `missing`，区别于空文件的 SHA-256。
- 后续模型输入使用有效条目，不应把 inactive 条目作为当前规则重新注入。

调用方提交 `MemoryUpdate(operationId, expectedVersion, edits, sources, reason, createdAt)`：

1. 操作 ID 使用 UUID，同一请求重试必须保留完整请求和操作 ID。
2. `MemoryEdit` 只允许 ADD、UPDATE、INVALIDATE、IGNORE；没有任意路径或任意文本补丁入口。
3. 原始消息来源需要会话 ID 和消息 ID；归档来源可使用 history ID。
4. `apply()` 检查文件内容版本和条目目标，修改前保存旧内容，保存恢复事务，再原子替换 Markdown、追加本地变更记录。
5. 同一操作成功后重复调用不重复修改或追加记录；操作 ID 被不同请求复用时拒绝。

存储层检查来源的标识格式。来源是否真实存在、是否足以支持知识、全局批准、冲突候选和 Token 预算，由阶段 6 的 Harness 校验，不由磁盘仓库推断。

`recover()` 是显式写操作：

- 存在未完成事务，且正文仍是预期旧版时，继续应用已保存的请求。
- 正文已经是目标版时，补写变更记录。
- 变更记录已提交时，仅清理事务；保留提交之后的手动修改。
- 正文既非旧版也非目标版时，返回冲突并保留事务，不能覆盖用户新内容。

`changes()` 返回本地提交记录及来源。来源、原因、操作 ID 不写入共享 Markdown。

## 项目历史与会话检查点

`HistoryRepository(directory).read()` 只返回已提交归档，按提交顺序排列，并报告是否存在未完成日志尾行。读取不创建目录。

`HistoryEntry` 保存稳定 ID、操作 ID、会话和内容版本、此次覆盖的消息 ID、前一摘要 ID、归档摘要、连续摘要、关键词、来源、生成时间和提示版本。消息和来源列表不可变，来源必须属于本次覆盖区间。

`ArchiveCommitter(sessionRepository, historyRepository).commit(expectedSnapshot, entry, organizedAt)` 执行：

1. 校验磁盘状态与预期快照一致，原始消息已保存；归档必须覆盖当前未压缩部分的连续前缀，且不能拆开工具调用/结果块。
2. 保存 `local/transactions/archive-<operationId>.json`。
3. 向 history 追加 `ARCHIVE_PREPARED`。
4. 通过 `SessionRepository.compareAndSave` 保存摘要、覆盖边界和成功整理标记，原始消息和内容版本不变。
5. 追加 `ARCHIVE_COMMITTED`，然后清理事务文件。

只有第 5 步完成的归档进入正常历史读取。原始对话不被删除或截断。

`recover()` 不调用模型、不重放工具，也不擅自推进未保存的摘要：

- 会话已引用某个未提交归档（或通过后续摘要链引用它）时，补写提交标记。
- 会话未引用它时，保持 pending，不进入检索。
- 原始会话文件缺失时保持 pending；损坏则明确报告错误。
- 返回本次补交数量与仍待处理数量。

`pending()` 只供恢复协调器获取已保存的请求，包括尚未追加到 history 的事务；不能直接提供给普通检索工具。后续协调器确认会话版本仍匹配后，可复用 entry 的操作 ID 调用 commit；过期请求留作诊断，重新计算当前快照。

## 文件与并发约束

```text
<memoryDirectory>/
  MEMORY.md
  .gitignore                      # 只添加 /local/，保留原规则
  local/
    history.jsonl
    changes.jsonl
    transactions/
    versions/
    locks/memory.lck

<sessionDirectory>/
  <sessionId>.json
  .locks/<sessionId>.lck
```

- `StorageFiles` 把访问限制在指定根目录下，并拒绝该根及其内部的符号链接。创建/修改只在显式写操作中发生。
- 内容先写同目录临时文件并 force，再原子替换；不支持原子替换时报告失败，不降级为截断原文件。会话保存也采用这一方式。
- 日志每条记录以换行结束，追加后 force。只有未结束的最终尾行可以被忽略；完整但非法的行必须报错。
- 修复尾行时先保存原尾字节，再通过原子替换发布完整前缀，随后追加新记录，不在并发读者正在读取的文件上原地截断。
- 记忆目录使用短时独占文件锁。拿不到锁立即返回 LOCKED；会话普通保存和条件保存都遵守会话锁。锁文件保留，锁本身随句柄关闭/进程退出释放。
- 固定锁顺序为记忆目录 → 会话；不要反向持锁。锁内不允许模型请求。
- `compareAndSave` 比较完整持久化状态，包括内容、消息 ID、运行状态和摘要状态，忽略运行时 unsaved 标记。冲突返回 `SessionStorageException.CONFLICT`。
- 现有 `save` 仍是单写入者的无条件保存入口。磁盘 CAS 不能代替运行时准入和内存状态协调。

阶段 4/7 接入时必须通过 `SessionCommitCoordinator`：协调前台/后台写入，提交后同步现有 Session 的记忆元数据，再发布快照。活动 AgentLoop 仍持有 Context 引用，不应在循环中随意替换它。若磁盘提交部分成功后抛错，先恢复并读取真实检查点，再允许后续保存；不要用旧内存状态覆盖新摘要。

## 本地验证

测试使用显式临时目录、脚本化会话及故障注入，不访问真实记忆，不调用模型：

- 长期局部修改、手动编辑、内容版本冲突、来源隔离、失效及忽略。
- 长期事务 PREPARED/REPLACED/AUDITED 三个中断点恢复和重复请求去重。
- 归档 PREPARED/ARCHIVED/CHECKPOINTED/COMMITTED 四个中断点恢复和重试。
- 日志尾行损坏、完整坏记录、未知版本、半条提交标记。
- 只读存储、原子替换失败、会话条件保存竞争、另一 JVM 的文件锁。

完整本地回归使用 JDK 17 执行 `gradlew.bat --offline --no-daemon test`。普通测试及不调用真实模型的 Docker/Harbor 离线验证可正常运行；只有调用真实模型 API 的测试需要用户显式指示，“跑一次harbor测试”默认使用真实模型。以根目录 AGENTS.md 为准。
