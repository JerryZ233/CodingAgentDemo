# 项目历史检索（阶段 5）

默认仍为 `memory.enabled=false`。开启后，`SessionService` 在既有文件工具上附加 `memory_search` 和 `memory_read`。两者为宿主提供的只读服务，不通过 `tools.enabled` 配置文件路径或其他项目身份，也不启动模型整理或后台扫描。

## Agent 用法

```json
{"query":"会话恢复 工具配置","max_results":5}
```

`memory_search` 的 query 必填、非空，最多 1024 个 Unicode 码点；max_results 默认 5，范围 1～10。查询至少包含文字或标识符。额外参数、重复键或错误类型由既有严格工具参数解析拒绝。

```json
{"history_id":"搜索返回的 UUID","include_sources":true}
```

`memory_read` 只接受当前项目已提交历史的 UUID。include_sources 默认 false，显式开启时返回预算内的原始消息片段。没有任意路径、项目根或 session-dir 参数；工具返回的历史内容都是数据，不执行其中的工具调用。

工具规格使用独立的 `MEMORY_READ` capability，不扩大 `FILE_READ` 的工作目录范围。低层 `ToolRegistry.fromConfig` 仍只创建配置的工作区工具；SessionService 用 `withHistory` 注入绑定项目和会话存储的服务。基础提示只在安装历史工具时追加按需检索指引。

新会话不会默认加载其他会话消息或 history 内容。用户提到上次工作时，模型可以搜索、读取，结果作为普通工具结果进入本轮近期消息，后续统一计入上下文预算和压缩。

## 匹配、排序与读取

`HistorySearchService` 在构造时绑定宿主解析的项目根；子目录共享该 worktree 根的历史，不同仓库/worktree 隔离。每次查询重新检查边界，并读取已提交 journal；项目边界改变时拒绝沿用旧绑定。

- 仅使用 `HistoryRepository.read()` 的已提交归档；pending 条目不可搜索和读取。
- 查询和历史文本做 Unicode NFKC 与大小写归一化；英文使用词项/标识符，中文使用字符片段。
- 关键词比归档正文匹配权重高，完整短语有额外权重；累计连续摘要不参与检索评分，避免同一旧知识淹没新增归档。
- 相关度为第一排序依据；仅同分时优先较新记录，再按 ID 稳定排序。
- 第一版按需线性扫描，不使用向量数据库、持久索引或跨请求缓存；新提交可立即检索。
- 读取 journal 时每 4096 字节检查取消信号，重建和扫描条目也检查取消；不持写锁、不修复日志、不推进整理标记。

搜索返回 history/session ID、时间、归档预览、关键词、有限来源引用和 source_status。预览尽量围绕匹配片段，裁剪以 Unicode 码点为界。详情返回归档摘要、当时的连续摘要及可选来源片段。

## 输出限制与来源状态

每次响应同时限制在 2000 估算 Token 和既有 8000 字符消息预算内。可返回条数是上限；预算不足时可能少于所请求数量。

搜索结果包含：

- `matched_count`：此次扫描到的已提交匹配数。
- `returned_count`、`results`：实际返回条目。
- `complete`、`limit_reasons`：条数/输出预算限制，或 journal 尾部尚未完整写入。
- 每条预览自己的摘要、关键词和来源引用截断标记。

详情按相同预算裁剪摘要和来源，给出字段截断标记及 `OUTPUT_BUDGET`、`METADATA_LIMIT`、`SOURCE_PREVIEW_LIMIT` 等原因。原始文件不修改。第一版无分页，不能把片段当作完整来源，也不能从受限的零结果证明没有相关历史。

source_status：

| 状态 | 含义 |
| --- | --- |
| `AVAILABLE` | 当前配置的会话存储可读取，项目归属和连续覆盖消息 ID 校验通过 |
| `NOT_FOUND` | 原始会话不在当前 session-dir；不向其他目录或默认目录回退查找 |
| `PROJECT_MISMATCH` | 来源会话属于其他项目，禁止返回原始消息 |
| `INVALID_SOURCE` | 内容版本或覆盖消息身份不匹配 |
| `UNREADABLE` | 原始会话或其工作目录不可读取 |

归档仍可在原始来源缺失时读取。原始来源通过注入的 SessionRepository 只读加载，不调用 SessionService 的恢复流程、不重放操作。完整坏行使查询明确失败；未完成的尾行保留可用已提交前缀并显示不完整。

## CLI 与服务接口

```text
/memory
/memory search 配置覆盖
/memory read 1
```

CLI 的序号只属于最近一次成功搜索。重新搜索会刷新；切换或新建会话会清空。搜索失败也清空旧序号，避免误读。界面不显示内部 session/history UUID。

`SessionService.searchHistory(sessionId, query)` 与 `readHistory(sessionId, historyId, includeSources)` 返回相同的有界 JSON；读取操作不自动加入对话、不增加 contentRevision、不推进 dirty 或提炼状态。当前会话运行中可查看已提交历史。

记忆关闭、项目不可用/改变、历史损坏或 ID 不存在均有明确错误；关闭状态下不安装历史工具、不扫描历史文件。测试使用隔离目录、脚本化模型和原有离线回归，不调用真实模型 API。
