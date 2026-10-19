# CodingAgentDemo

一个基于 Java 17 的交互式编程 Agent。用户选定工作目录后，可以在同一会话（Session）中持续对话，让模型调用工具读取、创建和修改文件，并在退出后恢复历史、继续工作。

项目采用终端前端与会话后端分离的结构：终端通过 `SessionService` 提交任务、读取进度和请求取消，后端负责上下文、工具执行、自动保存和中断恢复。目前提供终端界面和 Java 服务接口，尚未提供 HTTP 或 Web 界面。

## 已实现的功能

- **持续多轮对话**：一轮任务结束后，会话继续存在，后续任务保留之前的消息与工具结果。
- **工作目录绑定**：每个会话绑定一个真实绝对目录，不同会话使用独立的历史与文件工具。
- **自动保存与恢复**：完整会话以 JSON 保存，重启后可按 ID 恢复；中断时不自动重放可能产生文件修改的操作。
- **异步执行与进度展示**：任务运行期间仍可输入命令，查看状态、历史或请求取消。
- **终端交互**：使用 JLine 编辑输入和展示后台进度；不支持完整终端能力时降级为普通行模式。
- **模型工具调用**：通过兼容 OpenAI Chat Completions 的服务请求模型，由 Agent 循环执行工具并反馈结果。
- **可测试的服务边界**：模型、工具、存储和终端均可替换，自动化测试不需要真实 API Key。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Java | JDK 17 或更高版本，设置正确的 `JAVA_HOME` |
| Gradle | 使用仓库内 Wrapper，版本为 8.10.2，无需单独安装 |
| 模型服务 | 支持 Chat Completions 和函数工具调用；未配置 API Key 时使用 Dummy 模式 |

首次构建需要访问依赖仓库。主要依赖包括 Log4j 2.26.1、OpenAI Java SDK 4.81.0、JLine 3.30.6、Gson 2.10.1、SnakeYAML 2.2 和 JUnit 5.10.0。

## 快速开始

以下命令均从项目根目录执行。

### 1. 配置模型

通过环境变量设置 API Key：

**Windows PowerShell：**

```powershell
$env:LLM_API_KEY = "你的 API Key"
```

**Linux / macOS：**

```bash
export LLM_API_KEY="你的 API Key"
```

仓库中的 `config.yaml` 当前配置为智谱兼容接口与 `glm-4.7-flash` 模型。API Key 必须与所选服务匹配；切换服务时，同时调整 `LLM_API_URL` 和 `LLM_MODEL`，或修改配置文件。

未配置 API Key 时可以直接启动程序体验会话与命令。此时默认 Dummy 客户端只返回模拟文本，不会真正分析代码或执行文件修改；其默认中文回复会明确说明没有实际执行任务。

### 2. 构建启动器

**Windows PowerShell：**

```powershell
.\gradlew.bat installDist
.\build\install\CodingAgentDemo\bin\CodingAgentDemo.bat
```

**Linux / macOS：**

```bash
./gradlew installDist
./build/install/CodingAgentDemo/bin/CodingAgentDemo
```

建议使用生成的启动器获得完整终端体验。启动器使用 UTF-8 编码；程序仍从启动时的当前目录查找配置，而不是从脚本所在目录查找。

也可以通过 Gradle 启动：

```powershell
.\gradlew.bat run
```

```bash
./gradlew run
```

Gradle 已转发标准输入，但通过管道或某些 IDE 启动时，终端能力可能降级。

### 3. 选择目录并开始对话

无参数启动后，程序提示选择工作目录：

```text
默认工作目录：G:\projects\example
输入目录或按回车使用默认值；/sessions 查看历史，/resume <序号> 恢复，/help 查看帮助。
目录 >
```

输入一个已存在且可读的目录，或回车采用配置默认目录。程序不会自动创建工作目录，也不会自动选择最近一次会话。

创建成功后即可连续提交任务：

```text
agent > 读取当前目录结构，说明这个项目的主要模块
……等待本轮完成……
agent > 在 README.md 中补充运行步骤
……等待本轮完成……
agent > /status
agent > /exit
```

每次 Enter 提交一行。任务文本中的引号无需闭合，不会触发多行续写。更换工作目录需要创建另一个会话。

## 启动参数

| 参数 | 说明 |
| --- | --- |
| `--workspace <目录>` | 在指定目录创建会话，跳过目录选择 |
| `--session <完整UUID>` | 恢复已有会话，跳过目录选择 |
| `--session-dir <目录>` | 指定会话存储目录 |
| `--help` | 显示启动帮助并退出，不初始化模型或会话存储 |
| `--` | 停止解析启动选项，后续参数全部作为任务文本 |
| 位置参数 | 按空格连接成一个任务，执行一轮、自动保存后退出 |

`--workspace` 与 `--session` 互斥。相对目录始终相对于进程启动目录解析，不相对于当前会话目录。

以下示例使用 Windows 启动器；Linux / macOS 将启动器路径替换为 `./build/install/CodingAgentDemo/bin/CodingAgentDemo`：

```powershell
# 指定工作目录，进入持续对话
.\build\install\CodingAgentDemo\bin\CodingAgentDemo.bat --workspace "G:/projects/my project"

# 兼容方式：使用已知的完整 UUID 恢复会话；交互界面推荐 /sessions 后 /resume <序号>
.\build\install\CodingAgentDemo\bin\CodingAgentDemo.bat --session "会话的完整UUID"

# 使用独立的会话存储目录
.\build\install\CodingAgentDemo\bin\CodingAgentDemo.bat --session-dir "G:/agent-history" --workspace "G:/projects/example"

# 单任务模式：执行后退出，会话仍会保存
.\build\install\CodingAgentDemo\bin\CodingAgentDemo.bat --workspace "G:/projects/example" -- "在 hello.txt 中写入问候语"

# 在已有会话中执行一轮后退出
.\build\install\CodingAgentDemo\bin\CodingAgentDemo.bat --session "会话的完整UUID" -- "读取 hello.txt 并修改问候语"
```

单任务模式未指定会话或工作目录时，会在配置默认目录创建新会话，不提示选择。

| 退出码 | 含义 |
| --- | --- |
| `0` | 正常交互退出，或单任务正常完成 |
| `1` | 启动、执行、保存或资源关闭失败 |
| `2` | 启动参数错误 |
| `130` | 单任务通过取消流程结束 |

交互模式中的单轮失败不会结束整个会话。普通行模式下的操作系统退出信号按所在平台处理。

## 交互命令

| 命令 | 功能 |
| --- | --- |
| `/help` | 显示帮助 |
| `/new [目录]` | 创建并切换会话；省略目录时采用配置默认目录 |
| `/sessions` | 列出会话序号、标题、目录、本地更新时间、中文状态和未保存标记 |
| `/resume <序号>` | 恢复并切换会话；先用 `/sessions` 列出，失败时保留当前选择 |
| `/history` | 以“用户”“助手”展示对话正文，隐藏工具原始数据及已知系统诊断 |
| `/status` | 展示会话标题、模型、工作目录、中文状态及保存情况 |
| `/cancel` | 请求取消当前任务 |
| `/retry-save [序号]` | 重试保存指定会话；省略序号保存当前会话，不继续原任务 |
| `/exit`、`/quit` | 取消活动任务并关闭服务，关闭成功后退出 |

会话序号从 1 开始，仅在再次执行 `/sessions` 时刷新；新建会话或任务更新不会改变上次列表的序号。序号仅在当前程序中有效，不写入会话文件。`/resume`、`/retry-save` 继续兼容完整 UUID 参数，终端不会自动显示 UUID。

命令名不区分大小写，参数保留大小写；含空格的目录可以整体输入，也可以加成对的外层引号。

普通文本会提交给模型。以 `/` 开头的任务需要使用 `//` 转义，例如 `//解释这段文本` 会提交 `/解释这段文本`。旧的裸命令 `help`、`exit`、`save`、`load`、`clear` 已不再作为命令识别。

### 执行期间的操作

一个服务同时只运行一轮任务，不排队。运行期间可以查看帮助、会话列表、历史和状态，也可以取消或退出；新任务、新建或切换会话、重试保存均会被拒绝。

进度每 100ms 增量读取一次。终端显示请求模型、重试、文件操作等中文提示，保留模型的中间自然语言说明和最终回答，每条仅展示一次。原始工具参数、结果、迭代次数和模型错误字段写入日志，终端不展示。达到处理上限或模型返回错误时，本轮结束，会话继续保留。

完整 JLine 模式下：

- Ctrl+C：运行中请求取消，空闲时清空当前输入。
- Ctrl+D：进入退出流程。
- 后台进度显示在输入行上方，保留正在编辑的内容；输入历史仅保存在内存。

无完整终端能力或环境声明 `TERM=dumb` 时，程序显示“普通行模式”。此时使用 `/cancel` 取消任务，Ctrl+C 按操作系统退出信号处理。连续管道输入不会被当作任务队列。

## 配置说明

配置优先级为：**非空环境变量 → config.local.yaml 本地配置 → YAML 配置 → 内置默认值**。

程序先读取进程启动目录下的 [config.yaml](config.yaml)，找不到时尝试类路径资源，最后使用内置默认值。随后读取同目录的 `config.local.yaml`，按字段覆盖公共配置；此文件已被 Git 忽略，可用于保存 `llm.api_key`，不会打包到发行文件中。终端暂不支持指定配置文件路径，也不会在切换会话时重新加载配置。

下表中的值是仓库当前 `config.yaml` 的设置：

| YAML 配置项 | 环境变量 | 当前值 |
| --- | --- | --- |
| `llm.api_url` | `LLM_API_URL` | `https://open.bigmodel.cn/api/paas/v4/chat/completions` |
| `llm.model` | `LLM_MODEL` | `glm-4.7-flash` |
| `llm.provider` | `LLM_PROVIDER` | `auto` |
| `llm.max_retries` | `LLM_MAX_RETRIES` | `10`（不含首次请求） |
| `llm.retry_initial_delay_ms` | `LLM_RETRY_INITIAL_DELAY_MS` | `2000` |
| `llm.retry_max_delay_ms` | `LLM_RETRY_MAX_DELAY_MS` | `30000` |
| `llm.error_details` | `LLM_ERROR_DETAILS` | `false` |
| `llm.api_key` | `LLM_API_KEY` | 空，使用 Dummy 模式 |
| `llm.max_tokens` | `LLM_MAX_TOKENS` | `2048` |
| `llm.temperature` | `LLM_TEMPERATURE` | `0.7` |
| `agent.max_iterations` | `AGENT_MAX_ITERATIONS` | `10` |
| `agent.workspace_dir` | `WORKSPACE_DIR` | `.` |
| `tools.enabled` | 无 | `read_file`、`write_file`、`list_files` |

如果未加载任何配置文件，内置接口地址为 `https://api.openai.com/v1/chat/completions`，模型为 `gpt-4`；其余上述参数采用相同默认值。使用发行启动器在其他目录运行时，应通过环境变量或该目录的配置文件明确指定模型服务。

模型配置使用本项目的 `LLM_*` 环境变量，不使用 SDK 的 `OPENAI_*` 变量。接口地址接受完整的 `/chat/completions` 地址或服务基础地址，仅允许 HTTP(S)，不接受内嵌凭据、查询参数或片段。

请求通过 SDK 异步发送，使用非流式 Chat Completions；每次尝试超时为 120 秒。SDK 自动重试关闭，由项目按以下规则执行有界重试。第三方服务是否可用取决于其模型参数及工具调用兼容性。

### 模型请求的错误与重试

配置示例（也可写入 `config.local.yaml`，环境变量优先）：

```yaml
llm:
  provider: auto
  max_retries: 10
  retry_initial_delay_ms: 2000
  retry_max_delay_ms: 30000
  error_details: false
```

`provider` 可为 `auto`、`openai`、`zhipu`、`deepseek`、`dashscope`、`gemini`、`generic`。`auto` 仅通过官方端点的确切主机名识别：`api.openai.com`、`open.bigmodel.cn`、`api.deepseek.com`、`dashscope.aliyuncs.com`、`dashscope-intl.aliyuncs.com`、`dashscope-us.aliyuncs.com`、`generativelanguage.googleapis.com`；未知主机使用 `generic`，不根据模型名推断。第三方网关默认按自身兼容接口语义处理，只有明确保留上游错误格式时才显式配置对应 provider。Gemini 仅使用其 OpenAI 兼容端点，不接入原生接口。

默认每次模型调用最多重试 10 次（含首次请求共 11 次），终端提示为“重新连接...（x/10）”。退避基数为 2、4、8、16 秒，之后为 30 秒，增加 0–20% 随机延迟，普通退避不超过配置的最大值。`max_retries: 0` 禁用重试。次数必须非负且小于 2147483647，延迟必须为正且最大值不小于初始值。

服务商业务码优先于 HTTP 回退规则；明确不可重试的业务错误不会因 HTTP 429/500 被重试。通用规则为：408、429、500、502、503、504 可重试，其余未识别状态（包括 409、501、505、529）直接返回。连接/读取超时、连接重置、临时连接中断可重试；DNS 名称无法解析、TLS 证书错误、请求构造和响应解析失败不重试。不自动改变模型、参数或上下文。

| 服务商 | 主要规则与官方来源 |
| --- | --- |
| 智谱 | `1302`、`1305` 可重试；`1113`、`1308`–`1311`、`1313`–`1321` 以及明确鉴权、参数、权限、内容与上下文错误不重试。[错误码](https://docs.bigmodel.cn/cn/api/api-code) |
| OpenAI | 请求限流、`slow_down`、`server_is_overloaded` 可重试；`insufficient_quota`、`credit_balance_exhausted`、`organization_spend_limit_exceeded`、`project_spend_limit_exceeded`、`organization_usage_limit_exceeded` 不重试。[错误码](https://developers.openai.com/api/docs/guides/error-codes) |
| DeepSeek | 429/500/503 可重试，400/401/402/422 直接返回。[错误码](https://api-docs.deepseek.com/quick_start/error_codes/) |
| 百炼 | 请求频率、突发、并发、TPM/TPS 和服务过载限流可重试，包括相应的 `Throttling.AllocationQuota` / `insufficient_quota`；`AllocationQuota.FreeTierOnly`、`Arrearage`、`CommodityNotPurchased`、`PrepaidBillOverdue` 不重试。官方固定消息 `Free allocated quota exceeded.` 与 `Maximum voice-clone voice limit exceeded.` 优先判为不重试，同名 `insufficient_quota` 不套用 OpenAI 的规则。[错误码](https://help.aliyun.com/zh/model-studio/error-code/) |
| Gemini | 短期限流和临时服务错误可重试，明确 `quota_exceeded`、每日额度、计费、权限和参数错误不重试。`RESOURCE_EXHAUSTED` 本身不能区分短期限流与日额度耗尽；兼容响应未保留结构化细节时使用通用规则。[重试](https://ai.google.dev/gemini-api/docs/troubleshooting)、[额度](https://ai.google.dev/gemini-api/docs/rate-limits) |

`Retry-After` 支持秒数和 HTTP 日期，取服务端等待要求与本地退避的较大值。OpenAI 的 `retry-after-ms`、响应中数值型 `retry_after`、Gemini 响应实际保留的 `google.rpc.RetryInfo.retryDelay` 也可提供等待时间。服务端要求单次等待超过 120 秒时直接结束，状态为 `SERVER_WAIT_LIMIT`，不会提前重试。未知业务码执行通用回退，不保证能够识别所有服务或网关的语义。

重试仅作用于当前模型请求，不推进 Agent 迭代，也不会重新执行已完成工具。工具写文件后，后续模型请求重试耗尽，已完成的文件和工具记录仍保留。网络超时后重试不保证远端只生成或计费一次；本地只接纳一次成功响应。等待和网络请求均可取消。

错误字段分为 `[server]` 与 `[client]`：前者仅包含实际收到的 `http_status`、原始 `code/type/status/param`、请求 ID、等待字段；后者包含来源、尝试次数、状态和等待时长。不生成原因解读、翻译或处理建议。字段值采用 JSON 字面量，区分数字码与字符串码，缺失字段省略。SDK 读取错误响应时最多保留 64 KiB 的原始 JSON 副本，用于保留异常对象会丢失的外层字段；超过上限或非 JSON 正文时回退到 SDK 异常提供的字段。该副本不进入进度事件或会话文件。

`error_details: true` 时在日志诊断及原有持久化诊断中额外保留原始 `message` 和允许的嵌套细节（如 `error_code/reason/domain/@type/retryDelay/quota_metric/quota_id/limit`）；默认关闭。展示值会替换已配置的 API Key、Bearer 凭据及常见敏感键值为 `[redacted]`，转义控制字符，每个字符串最多 1000 个 Unicode 码点后附 `[truncated]`；省略字段标记为 `fields_omitted: true`。模型错误诊断不包含完整响应头、Cookie、请求正文、堆栈或 HTML/非 JSON 正文；未预期异常的脱敏堆栈仅写入文件日志。脱敏不能保证消除 message 中所有业务敏感信息。中间错误进入进度事件和文件日志，最终诊断按原有策略保存，历史会话文件格式不变。终端仅展示中文重试和失败提示；`/history` 不展示这些系统诊断。

Claude 仅作为后续适配参考，当前没有 `anthropic` 配置值或原生 Messages 客户端：临时 429、500、529 可以重试；`error.details.error_code=enforced_spend_limit_reached` 不应重试，不能仅凭缺少 `Retry-After` 判定。[错误说明](https://platform.claude.com/docs/en/api/errors)、[额度字段](https://platform.claude.com/docs/en/api/rate-limits)

## 日志与终端输出

日志使用 Log4j 2，默认写入用户主目录下的 `.coding-agent/logs/agent-<启动时间>-<进程ID>.log`，不随会话工作目录切换。终端不接收日志框架输出；`--help` 不初始化日志、模型或会话存储。

| 设置 | 默认值 | 环境变量 | JVM 属性 |
| --- | --- | --- | --- |
| 日志目录 | `${user.home}/.coding-agent/logs` | `CODING_AGENT_LOG_DIR` | `coding.agent.log.dir` |
| 应用等级 | `DEBUG` | `CODING_AGENT_LOG_LEVEL` | `coding.agent.log.level` |

优先级为非空 JVM 属性 → 非空环境变量 → 默认值。日志配置独立于 YAML，在读取模型配置前生效。等级可设为 `DEBUG`、`INFO`、`WARN`、`ERROR` 或 `OFF`；未识别值采用 `DEBUG`。第三方依赖默认 `WARN`，不启用 HTTP 正文调试。

```powershell
$env:CODING_AGENT_LOG_DIR = "G:/agent-logs"
$env:CODING_AGENT_LOG_LEVEL = "INFO"
.\build\install\CodingAgentDemo\bin\CodingAgentDemo.bat
```

每条日志包含带时区时间、等级、线程、记录来源，以及适用的会话 ID、运行 ID、迭代和工具调用 ID。程序描述及字段名为英文；用户输入、模型回复、工具结果和路径保留 Unicode 原文，不进行翻译。原始正文经过敏感信息脱敏和控制字符转义，使用带引号的数据字段保存，不主动截断。

| 等级 | 主要内容 |
| --- | --- |
| `DEBUG` | 迭代、请求摘要、模型回复、工具参数和结果、检查点 |
| `INFO` | 启动、模型选择、会话创建与恢复、任务生命周期、取消与关闭 |
| `WARN` | 请求重试、工具失败、处理上限、中断恢复、终端降级、部分历史加载失败 |
| `ERROR` | 请求最终失败、保存与资源清理失败、启动和未预期异常 |

单文件达到 10 MiB 后压缩轮转，每次启动最多保留 10 份归档；轮转时清理本应用超过 14 天的归档。不同进程使用独立文件。默认 DEBUG 日志可能包含用户内容和代码；脱敏覆盖配置密钥、Bearer 凭据和常见敏感键，不能识别所有业务敏感信息。

日志无法初始化时以中文提示并返回退出码 1。运行中写入失败时只提示一次，任务及会话保存继续。改变日志等级不会改变终端展示、重试行为或会话 JSON 格式。

## 工具与执行边界

| 工具 | 默认启用 | 用途 |
| --- | --- | --- |
| `read_file` | 是 | 读取工作目录内的文件 |
| `write_file` | 是 | 创建或修改工作目录内的文件 |
| `list_files` | 是 | 列出工作目录内的目录内容 |
| `run_shell` | 否 | 在会话工作目录执行受限命令 |

文件工具绑定会话的真实工作目录，检查输入、规范化路径和最终目标；工作区被替换后拒绝继续操作。`.env`、`.env.*`、`*.env`、`config.local.yaml`、私钥、证书容器及敏感目录受到保护，`.git` 禁止写入，同时保护原有系统关键目录。读取链接需验证最终目标；写入拒绝符号链接、目录联接等重解析路径。文件权限检查代替扩展名白名单，支持 `Dockerfile`、`.gitignore`、`.toml` 等正常 UTF-8 文本文件。

`tools.enabled` 缺省时启用三个文件工具，显式配置 `enabled: []` 则全部禁用。列表类型、非字符串、空名称及未知工具名会导致配置错误，重复名称去重。禁用的工具不会实例化，也不会出现在模型 Schema 或提示词中。

### 文件工具协议

文件工具的结果正文为 JSON；Java `ToolResult` 外层和版本 1 会话格式不变。失败返回稳定的 `code` 与 `message`，不回传被拒绝文件的正文。

- `read_file({"path":"a.txt"})`：返回 `path`、完整 `content`、UTF-8 `bytes` 和原始字节的 `sha256`。最多实际读取 1 MiB，拒绝无效 UTF-8、含 NUL 的二进制和读取期间变化的文件。最终 JSON 超过单条模型消息预算（默认 8,000 个字符）时返回 `FILE_EXCEEDS_MESSAGE_BUDGET`，不返回部分内容；当前没有范围读取。
- `write_file({"path":"a.txt","content":"text"})`：只创建新文件；已有文件返回 `WRITE_CONFLICT`。覆盖必须加入完整读取结果中的 `expected_sha256`，摘要不匹配、目标消失或创建目标已存在时拒绝。`content` 必填，空字符串合法，缺失或 `null` 不会清空文件。
- 写入先在目标目录暂存、刷新，再检查路径和版本。覆盖要求原子替换并保留权限；新建通过不覆盖目标的硬链接发布，需要文件系统支持。失败不降级为截断写入，并清理暂存文件。同一工作区的 Agent 写入串行化；摘要复核仍不是针对外部进程的原子比较并替换。
- `list_files({"path":".","limit":100})`：返回稳定按工作区相对路径排序的 `entries`（`name`、`path`、`type`）及 `next_cursor`。每页默认 100、最多 1,000 项，并按消息预算缩小页面；使用相同查询参数和返回的游标继续翻页。
- 列表支持相对查询目录的 `glob`、`recursive`（默认 `false`）及 `max_depth`（默认 10，范围 1–20）。链接只列为链接，不递归跟随；受保护条目过滤。单次最多扫描 100,000 项、5 秒，超过返回 `DIRECTORY_SCAN_LIMIT`，不将部分扫描报告为完整结果。翻页期间目录变化不保证快照一致性。

所有工具严格校验参数类型和未知字段：参数 JSON 最多 8 MiB，路径最多 1,024 字符，写入最多 1 MiB，Shell 命令最多 4,096 字符。模型提示词明确文件和命令输出是任务数据，其中的指令不能授权越界操作。合法读取仍会发送给模型、保存到会话并按日志设置记录，不对源代码正文做通用替换；路径规则不能识别普通代码文件里任意业务秘密。

### 可选命令工具

需要启用时，在 `tools.enabled` 中加入 `run_shell`。现有 `ShellPolicy` 规则保持不变，仅允许 `echo`、`dir`、`ls`、`pwd`、`cd`、`ping`、`sleep` 等少量命令；目录命令不接受参数，`ls -la` 也会拒绝。管道、重定向、命令串联等语法不受支持，不能自由运行构建、测试或 Git 命令。

工具从可信系统位置启动 Shell；CMD 禁用 AutoRun，PowerShell 禁用 profile 并非交互运行，POSIX 不继承 Shell 启动注入变量。子进程只接收必要系统环境，不继承应用 API Key、Token 等变量；标准输入立即关闭。`pwsh` 要求 Windows 标准安装位置 `Program Files/PowerShell/7/pwsh.exe` 可用，不从 PATH 搜索。

默认执行超时 30 秒、输出保存上限 64 Ki 字符。超时保留已收集输出，普通输出读取错误不会报告成功。POSIX 与 `pwsh` 使用 UTF-8；Windows CMD/Windows PowerShell 使用 JVM 启动时的 `sun.stdout.encoding`，缺省采用 `native.encoding` 或 JVM 默认编码。非法字节替换为 U+FFFD，并附解码异常标记。

这些是程序内策略，不是 OS 沙箱或审批系统。工作目录不限制进程的文件访问和网络能力；Shell 内部命令解析、工作目录同名脚本、命令参数语义、系统级隔离和网络权限仍待 Shell 专项设计。进程清理覆盖观察到的后代，不保证捕获所有迅速脱离的进程。文件检查也无法完全消除恶意外部进程造成的路径竞态。

### 工具代码组织

`com.demo.tools` 保留接口、规格、注册器和描述渲染；四个 `Tool` 实现放在 `tools.builtin`，通用参数和预算放在 `tools.support`，文件访问与提交放在 `tools.fs`，Shell 策略与进程生命周期放在 `tools.process`。构造工具必须显式传入工作目录，不保留旧包包装类。`ToolSpec` 的能力元数据供描述和审计使用，不授予权限。

## 自动保存、中断恢复与取消

### 会话存储

默认存储位置为：

```text
用户主目录/
└── .coding-agent/
    └── sessions/
        └── <会话UUID>.json
```

可用 `--session-dir` 覆盖存储目录；再次恢复时需要使用同一目录。

每份版本 1 的 JSON 保存会话 ID、标题、固定工作目录、时间、完整消息及最近一轮运行摘要。新会话标题为“新会话”，首次提交后取首条用户任务首行，最多 40 个 Unicode 码点。列表按更新时间倒序排列。

程序不序列化配置中的 API Key、客户端、工具实例或动态系统提示词；但用户消息、代码和工具输出会完整保存，因此其中若包含敏感内容，也会进入会话文件。旧版裸消息 JSON 不会自动导入。

### 检查点与恢复

自动保存发生在用户消息加入后、工具调用执行前、每个工具结果记录后，以及最终回答和运行状态更新时。文件以 UTF-8 写入同目录临时文件，再优先原子替换正式文件；保存失败时尽量保留上一份有效记录。

保存失败会停止后续操作、保留内存状态并阻止新任务。修复存储问题后，使用 `/retry-save` 将已有状态落盘；该命令不会继续或重放原任务。

重启恢复未结束的运行时：

- 标记为 `INTERRUPTED`，保留已有消息和回答。
- 为缺少结果的工具调用补充“执行结果未知”的记录。
- 不自动重试工具，不推断之前的文件修改是否成功。

会话工作目录固定。目录被删除或解析到了不同的真实位置时，仍可读取历史，但新任务会被阻止执行。

### 取消与退出

`/cancel` 表示请求取消，不表示已立即停止。需等待任务结束和资源清理完成，才能提交下一轮任务。

取消会中止本地模型请求处理并关闭响应流；不保证远端服务同步停止生成。已经开始的短文件操作会完成并保留真实结果，已完成的文件修改不会回滚。命令工具会尝试终止观察到的进程树，并清理输出读取线程和流。

正常退出沿用服务的五秒关闭等待预算。关闭失败时，交互式 `/exit` 返回提示符，允许查看状态或重试；关闭超时后，服务处于 `CLOSING`，拒绝新任务。资源清理失败也会阻止后续执行，不会仅因重试保存成功就解除阻塞。

EOF 和 JVM 关闭钩子只尝试一次关闭并报告失败。强制终止进程无法保证未保存的内存状态落盘。

## 项目结构与架构

```text
src/
├── main/java/com/demo/
│   ├── cli/                 # 入口、参数、命令、终端输入输出、事件展示与退出协调
│   ├── session/             # 会话生命周期、快照、异步句柄、恢复与目录校验
│   │   └── storage/         # 完整会话 JSON 编解码及文件存储
│   ├── agent/               # 单轮执行循环、上下文、提示词和执行检查点
│   ├── execution/           # 取消令牌、取消通知及资源清理异常
│   ├── llm/                 # 模型客户端接口与选择工厂
│   │   └── impl/            # SDK 适配、HTTP 取消与 Dummy 客户端
│   ├── model/               # 消息、工具调用和工具结果
│   ├── tools/               # 文件工具、受限命令工具与工作目录策略
│   └── config/              # YAML、环境变量与配置对象
└── test/java/com/demo/      # 对应模块的单元与集成测试
```

调用关系：

```text
终端 Main → CliApplication → CliController
                                │
                                ▼
                         SessionService
                                │
                     DefaultSessionService
                       ├── SessionRepository → JSON 检查点
                       └── CodingAgent → AgentLoop
                                           ├── Context → 提示词与历史裁剪
                                           ├── LLMClient → 模型服务
                                           └── 工作目录绑定的 Tools

终端 RunEventPump ← RunHandle / SessionEvent
```

`SessionService` 拥有历史与工作目录；`CodingAgent` 接收外部上下文，只执行一轮；终端仅展示快照和事件，不直接修改后端上下文。

## Java 服务接口

其他前端可以直接使用会话服务，无需终端输入输出。工作目录必须已存在：

```java
import com.demo.config.Config;
import com.demo.session.DefaultSessionService;
import com.demo.session.SessionService;
import com.demo.session.SessionSnapshot;
import java.nio.file.Path;

try (SessionService sessions = new DefaultSessionService(Config.getInstance())) {
    SessionSnapshot session = sessions.createSession(Path.of("G:/projects/example"));
    sessions.runTurn(session.id(), "创建 hello.txt，写入问候语");
    SessionSnapshot result = sessions.runTurn(session.id(), "读取 hello.txt，修改问候语");
    System.out.println(session.id()); // 保存 ID，用于之后恢复
    System.out.println(result.lastRun().status());
}
```

在新服务实例中调用 `getSession(id)` 恢复，再调用 `runTurn(id, text)` 继续。常用接口如下：

| 接口 | 用途 |
| --- | --- |
| `createSession(Path)` | 创建并保存空会话 |
| `listSessions()` | 获取摘要和各文件的加载问题 |
| `getSession(id)` | 读取不可变快照，必要时加载并执行中断恢复 |
| `runTurn(id, text)` | 同步执行一轮，返回结束快照 |
| `submitTurn(id, text)` | 异步提交，返回 `RunHandle` |
| `getActiveRun()` | 查询当前活动运行 |
| `retrySave(id)` | 修复并重试保存，不重放工具 |
| `close()` | 请求取消、保存并释放服务拥有的资源 |

`RunHandle` 提供 `state()`、`eventsAfter(sequence)`、`completion()` 和 `cancel()`。前端从 `eventsAfter(0)` 开始，持续用最后的事件序号增量读取，完成时再排空剩余事件。事件仅保留在句柄内存中，不会跨重启恢复。

取消必须调用 `cancel()`，取消 `completion()` 转换得到的 Future 副本不会取消任务。普通取消返回状态为 `CANCELLED` 的快照；保存或资源清理失败会异常完成。模型错误和迭代上限则保存在运行结果中。

默认构造的服务拥有模型客户端；注入客户端的构造入口默认借用客户端，也可显式指定 `ClientOwnership.OWNED`。测试可注入脚本化模型、仓库和时钟。自定义长耗时客户端应实现支持取消令牌的接口。

## 构建与测试

**Windows PowerShell：**

```powershell
.\gradlew.bat test
.\gradlew.bat build installDist
```

**Linux / macOS：**

```bash
./gradlew test
./gradlew build installDist
```

`build` 包含测试与发行归档，`installDist` 生成可运行目录。测试报告位于 `build/reports/tests/test/index.html`。

测试覆盖多轮文件修改、目录隔离、重启恢复、不确定操作不重放、保存失败、取消竞争、模型传输中止、进程清理、CLI 命令及终端输入重绘等场景。使用模拟模型和本地服务，不调用付费模型接口；符号链接相关测试可能因系统权限条件跳过。

### 本机双击启动

在项目根目录双击 start-agent.bat，启动器会先切换到项目目录，再调用 build/install 下的程序，确保读取根目录的 config.yaml 和 config.local.yaml。修改源码后需重新运行 .\gradlew.bat installDist。直接双击安装目录 bin 下的 BAT 时，需要在 bin 中自行放置配置文件；本地密钥配置不会自动打包进发行包。
