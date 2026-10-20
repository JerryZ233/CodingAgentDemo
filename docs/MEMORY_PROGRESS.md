# Memory Harness 实施进度

更新时间：2026-10-20。

设计合同见 [MEMORY_PLAN.md](MEMORY_PLAN.md)。

## 当前阶段

- 阶段 0：已完成配置与设计/交接文档，本地回归通过。
- 阶段 1：已完成稳定消息 ID、v2 会话、内容版本、整理标记和项目定位，本地回归通过。
- 阶段 2：已完成 Markdown 条目、增量历史、本地版本/事务、文件锁、会话条件检查点与中断恢复，本地回归通过。
- 阶段 3：已实现协议块、Token 估算、统一组装、请求前准备入口和上下文受阻结果；验证结果见下方阶段 3 交接。
- 阶段 4～8：尚未实现。
- Memory 改造尚未创建提交；以当前工作区差异为准。

## 当前接口与格式

- `Config.getMemory()` 返回不可变 `MemoryConfig`，旧构造入口仍可用；`withMemory` 方便测试注入。
- 默认 `memory.enabled=false`，保留旧窗口行为。开启后只读加载有效长期条目与已提交连续摘要，对全部模型输入统一估算并拒绝超限；自动压缩、提炼与后台整理仍未接入。
- `Message.getId()` 提供内部 UUID，`copyOf`/`withContent` 保留；`withId` 用于显式恢复。现有模型 mapper 不发送该字段。
- 会话写版本 2，读版本 1/2。版本 1 ID 使用 `coding-agent:v1:<sessionId>:<ordinal>` 的 UTF-8 字节生成 name UUID，后续必须保持此算法兼容。
- `SessionSnapshot` 新增 `contentRevision` 和 `memoryState`，旧构造入口以消息数初始化版本。
- `Context` 追踪内容版本；提示与读取不推进，追加消息/非空 clear 推进。
- 恢复保留已有消息 ID/记忆状态，只按补写的未知工具结果数量递增内容版本。
- `SessionMemoryState.eligible` 只判断首次或 dirty+间隔；调度器仍须检查有消息、活动写入和退避。
- `CompressionState` 验证覆盖边界存在且不拆工具块；归档有效性还需 HistoryRepository 的提交记录，不应只看摘要字段。
- `ProjectLocator` 只读定位，不创建项目记忆文件，不扩大文件工具范围。

## 基线与工作区

- 开始实施时，用户已有 `test/README.md`、`test/fibonacci.py`、`test/test_fibonacci.py` 三项删除，必须保留。
- 测试执行规则已按用户最新要求统一：普通测试（含 Docker/Harbor 离线验证）可正常运行；只有真实模型 API 测试需要显式指示，“跑一次harbor测试”默认授权一次真实模型评测。
- 原始 Gradle 启动没有输出，已终止。显式选择 JDK 17 后确认沙箱不能写用户 Gradle wrapper 缓存，改为授权访问缓存运行本地检查。
- 首次运行与测试文件更新重叠，旧编译快照报告两项断言失败：旧测试仍把版本 2 视为不支持、仍使用 Message.fromJson 构造可变 Gson 列表。已分别更新为未知版本 3 和直接 Gson 构造的防御性复制测试。
- 最终执行 `gradlew.bat --offline --no-daemon test`，使用已安装的 JDK 17；构建成功，317 项测试，312 通过、5 跳过、0 失败/错误。
- `git diff --check` 通过。Gradle 提示已有的废弃特性，模型 SDK 编译提示已有的 deprecated/unchecked 用法。
- 未运行 Docker/Harbor 或真实模型评测。

## 下一阶段

从阶段 4 接续：无工具结构化摘要、增量压缩、连续摘要事务与请求用途标记。沿用阶段 3 的 `ContextPreparation` 请求前入口；提交后必须协调更新运行中 Session/Context，不能仅写磁盘。保持默认关闭。

## 阶段 2 存储交接

- 具体格式、恢复方式及接入限制见 [MEMORY_STORAGE.md](MEMORY_STORAGE.md)。
- `LongTermMemoryRepository` 支持显式目录，read 不初始化目录；apply/recover 是写操作。四类局部操作通过稳定条目 ID 定位，正文以 SHA-256 版本检查。
- `HistoryRepository.read` 只返回已提交历史。`ArchiveCommitter.pending` 只用于恢复，不可作为普通检索结果。
- `ArchiveCommitter` 实现事务 → 归档 → 会话 CAS → 提交记录；任一步失败均保留原始消息，重试复用操作 ID。
- `SessionRepository.compareAndSave` 默认不支持，避免自定义仓库错误宣称有原子 CAS；Json 实现已支持。普通/条件保存均有会话锁。
- 会话目录新增 `.locks/`；记忆目录 local/ 由自身 `.gitignore` 忽略，不改仓库根忽略规则。
- 文件替换不再降级为非原子覆盖；写入/日志追加执行 force。损坏尾行先备份，原子发布有效前缀，完整坏行拒绝修改。
- 存储层没有接入正在运行的 Session。阶段 4/7 必须协调内存元数据与磁盘提交，不能直接将 ArchiveCommitter 接到 AgentLoop 后继续用旧 Context/Session 保存。
- 全局授权、来源真实性、冲突候选及预算校验留在 Harness；存储只验证结构、来源标识、版本和覆盖关系。
- 本阶段没有模型请求、后台扫描或用户目录记忆写入。
- 验证：先执行 memory/storage/session-storage 专项测试，再对最终代码执行 JDK 17 的 `gradlew.bat --offline --no-daemon test`；348 项，343 通过、5 跳过、0 失败/错误。包含另一 JVM 的文件锁验证。
- `git diff --check` 通过。保留用户原有的 test/ 三项删除；未执行 Docker/Harbor、真实模型评测或 Git 提交。

## 阶段 3 上下文交接

- 具体 API、预算口径与后续接入限制见 [MEMORY_CONTEXT.md](MEMORY_CONTEXT.md)。
- `ConversationBlockBuilder` 共用于旧窗口、覆盖边界校验与新输入组装；保留完整工具块，拒绝孤立、重复、不匹配结果。未完成尾块只允许作为检查点存在。
- `TokenEstimator` 估算 Unicode 码点、工具参数、实际协议字段及结构开销；`ContextBudget.Report` 提供全部分项、输出/安全预留、压缩阈值和超限项。
- `ContextAssembler` 是纯函数，不截断、不改原始消息。`ContextPreparation.Input` 将消息和工具 schema 固定在同一个请求快照中。
- `MemoryHarness` 只读加载全局/项目有效条目，验证已提交摘要链的会话、版本、正文和完整前缀覆盖。缺失/未提交/不一致摘要明确阻止请求，不悄悄使用不可信边界。
- `DefaultSessionService` 在开启记忆时安装准备入口，`AgentLoop` 每次模型请求前执行，传递相同取消信号；禁用时不读取记忆文件，保持兼容窗口。
- 新增 `CONTEXT_BLOCKED` Agent/Session 状态，保存已有消息、不伪造助手诊断、不请求模型；CLI 显示中文暂停提示。准备成功通过 `CONTEXT_PREPARED` 事件发布无正文预算报告。
- 构造 Service 时可显式注入 Harness/global 目录，测试全部隔离。测试临时目录位于仓库的 build/ 内，因此集成测试需建立独立 `.git` 标记，防止项目定位继承真实仓库根。
- 默认仍关闭；当前只提供请求准备和只读加载，不执行压缩、长期提炼、回填或额外模型调用。达到压缩触发阈值但未超硬限额时可继续，第四阶段再执行自动压缩。
- 首次专项运行有两项测试因上述项目隔离夹具失败，补齐标记后专项 116 项全部通过。
- 最终使用 JDK 17 执行 `gradlew.bat --offline --no-daemon test`：371 项，366 通过、5 跳过、0 失败/错误。`git diff --check` 通过。
- 普通沙箱无法写用户 Gradle 缓存锁，使用授权的现有缓存运行离线检查。未运行真实模型 API、Docker/Harbor 或创建 Git 提交。
- 工作期间评测策略/指标及临时 test/ 删除出现独立提交，未回退这些改动；当前 Memory 源码与文档仍留在工作区。
