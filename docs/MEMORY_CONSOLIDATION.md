# 长期知识提炼与候选裁决（阶段 6）

记忆默认开启，可通过 `MEMORY_ENABLED=false` 完整关闭。`ConsolidationService` 绑定当前项目和宿主配置的全局目录；模型不能指定存储路径。提炼通过当前客户端发起 `CONSOLIDATION` 用途的无工具请求，使用 `consolidation-v1` 提示。输入只有新增已提交 history 的独立归档摘要，以及现有全局/项目条目；不会重新总结整个 history 或累计连续摘要。

## 增量操作

每批最多 `memory.consolidation.batch_size` 条，默认 10；输入估算含系统提示、JSON、修复提示预留和输出/安全预留。超预算时缩小批次；单条仍超限则停止且不推进游标。严格 JSON 拒绝重复字段、多余字段、未知来源、未知条目和工具调用；格式不合规最多修复一次，API 失败不做格式重试。

操作只有 ADD、UPDATE、INVALIDATE、IGNORE。条目 ID 由宿主生成，更新沿用已有 ID，来源必须属于本批 history。项目 ADD 在模型判断无冲突且预算允许时自动落盘。项目 UPDATE/INVALIDATE 还需要引用摘要中确切的迁移或纠正语句；其他更新保留旧值并持久化为候选。全局操作始终需要明确批准。精确重复条目去重，语义冲突的识别仍依赖提炼模型；使用记忆时实际代码和当前要求优先。

预算按有效条目及输入封装计算，不把全部细节升入长期文件。超预算操作保留为候选，审批也不能绕过预算。即时 `memory_propose` 提案始终进入候选；来源由宿主绑定当前会话真实的最新用户消息，工具不接受来源 ID 或路径。

## 可恢复状态

项目 `.coding-agent/local/consolidation.json` 保存 schema/revision、已处理 history ID、候选、准备操作、当前批次和裁决记录。使用独立短锁、完整状态 CAS 与原子替换；模型请求不持文件锁。

提交顺序是：持久化准备计划和候选 → 使用稳定 operation ID 执行局部修改 → 持久化已处理 ID 并清空准备计划。每个 history 有落盘操作、IGNORE 审计或持久候选后才算处理完成。原始会话和 history 均保留。中断后重放相同操作 ID 不重复写入；每次长期修改沿用存储层旧版本备份与事务日志。

文档版本已改变但尚未执行的准备操作转为候选，不自动合并或覆盖新文件；显示后可重新确认。若底层长期文件事务本身与手动编辑发生冲突，停止修改并保留事务与备份供诊断，不能靠重新提炼覆盖它。

全局批准不从项目准备计划重放。只有用户明确批准的入口才能调用全局 apply/recover；已经提交的全局 changes 作为完成凭据，可收尾中断的候选移除。全局写入中断时重新显示仍固定原操作，重新明确批准后恢复事务。项目状态中的 GLOBAL prepared 会被拒绝。

## 展示与裁决

交互 CLI 在当前会话空闲、支持交互输入时一次展示一条当前项目候选。非交互入口只持久保存候选。展示包含作用域、当前值、提案与理由；宿主按会话记住展示对象，后台新候选不能替换其目标。进程重启后必须重新显示，不能批准一个未展示的对象。

确定性识别的回复：

| 回复 | 作用 |
| --- | --- |
| 保留旧的 / 拒绝 / keep old / reject | 拒绝已展示候选 |
| 采用新的 / 批准项目记忆 / use new / approve project memory | 仅批准项目候选 |
| 批准全局记忆 / approve global memory | 批准全局候选 |

其他回复不写记忆，可以继续作为编码消息；不是让模型猜测批准意图。`memory_resolve_latest` 没有参数，宿主取真实最新用户消息和已展示对象，模型不能指定候选 ID 或伪造批准文本。批准前再次检查状态、文件版本、条目和预算；文件已变化时先重新显示。裁决保存结果、时间、真实回复和宿主会话 ID。

```text
/memory show
/memory pending
/memory resolve 保留旧的
/memory approve project
/memory approve global
/memory consolidate
```

`SessionService.memoryCommand` 提供 show/pending；`resolveMemoryReply` 处理宿主用户回复；`requestMemoryMaintenance` 异步执行需要模型的操作。查看与裁决不添加原始对话消息，不制造 contentRevision/dirty。`MEMORY_WRITE` 工具能力与普通工作区文件写权限分开。
