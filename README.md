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

首次构建需要访问依赖仓库。主要依赖包括 OpenAI Java SDK 4.81.0、JLine 3.30.6、Gson 2.10.1、SnakeYAML 2.2 和 JUnit 5.10.0。

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

未配置 API Key 时可以直接启动程序体验会话与命令。此时默认 Dummy 客户端只返回模拟文本，不会真正分析代码或执行文件修改；其“任务已完成”回复不代表实际完成了任务。

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
输入目录或按 Enter 使用默认值；/sessions 查看历史，/resume <id> 恢复，/help 查看帮助。
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

# 恢复已有会话：将占位内容替换为 /sessions 列出的完整 UUID
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
| `/sessions` | 列出会话 ID、标题、目录、更新时间、最近状态和未保存标记，同时报告无法读取的文件 |
| `/resume <完整UUID>` | 恢复并切换会话；失败时保留当前选择 |
| `/history` | 展示当前会话的完整消息、工具调用 ID、参数及结果 |
| `/status` | 展示当前会话、工作目录、运行 ID、实时状态及未保存标记 |
| `/cancel` | 请求取消当前任务 |
| `/retry-save [完整UUID]` | 重试保存指定会话；省略 ID 时保存当前会话，不继续原任务 |
| `/exit`、`/quit` | 取消活动任务并关闭服务，关闭成功后退出 |

命令名不区分大小写，参数保留大小写；含空格的目录可以整体输入，也可以加成对的外层引号。

普通文本会提交给模型。以 `/` 开头的任务需要使用 `//` 转义，例如 `//解释这段文本` 会提交 `/解释这段文本`。旧的裸命令 `help`、`exit`、`save`、`load`、`clear` 已不再作为命令识别。

### 执行期间的操作

一个服务同时只运行一轮任务，不排队。运行期间可以查看帮助、会话列表、历史和状态，也可以取消或退出；新任务、新建或切换会话、重试保存均会被拒绝。

进度每 100ms 增量读取一次。模型回答只展示一次；工具结果默认最多展示 2,000 个 Unicode 码点，完整内容可通过 `/history` 查看。达到迭代上限或模型返回错误时，本轮结束，会话继续保留。

完整 JLine 模式下：

- Ctrl+C：运行中请求取消，空闲时清空当前输入。
- Ctrl+D：进入退出流程。
- 后台进度显示在输入行上方，保留正在编辑的内容；输入历史仅保存在内存。

无完整终端能力或环境声明 `TERM=dumb` 时，程序显示“普通行模式”。此时使用 `/cancel` 取消任务，Ctrl+C 按操作系统退出信号处理。连续管道输入不会被当作任务队列。

## 配置说明

配置优先级为：**非空环境变量 → YAML 配置 → 内置默认值**。

程序先读取进程启动目录下的 [config.yaml](config.yaml)，找不到时尝试类路径资源，最后使用内置默认值。终端暂不支持指定配置文件路径，也不会在切换会话时重新加载配置。

下表中的值是仓库当前 `config.yaml` 的设置：

| YAML 配置项 | 环境变量 | 当前值 |
| --- | --- | --- |
| `llm.api_url` | `LLM_API_URL` | `https://open.bigmodel.cn/api/paas/v4/chat/completions` |
| `llm.model` | `LLM_MODEL` | `glm-4.7-flash` |
| `llm.api_key` | `LLM_API_KEY` | 空，使用 Dummy 模式 |
| `llm.max_tokens` | `LLM_MAX_TOKENS` | `2048` |
| `llm.temperature` | `LLM_TEMPERATURE` | `0.7` |
| `agent.max_iterations` | `AGENT_MAX_ITERATIONS` | `10` |
| `agent.workspace_dir` | `WORKSPACE_DIR` | `.` |
| `tools.enabled` | 无 | `read_file`、`write_file`、`list_files` |

如果未加载任何配置文件，内置接口地址为 `https://api.openai.com/v1/chat/completions`，模型为 `gpt-4`；其余上述参数采用相同默认值。使用发行启动器在其他目录运行时，应通过环境变量或该目录的配置文件明确指定模型服务。

模型配置使用本项目的 `LLM_*` 环境变量，不使用 SDK 的 `OPENAI_*` 变量。接口地址接受完整的 `/chat/completions` 地址或服务基础地址，仅允许 HTTP(S)，不接受内嵌凭据、查询参数或片段。

请求通过 SDK 异步发送，使用非流式 Chat Completions；请求超时为 120 秒，SDK 自动重试已关闭。第三方服务是否可用取决于其模型参数及工具调用兼容性。

## 工具与执行边界

| 工具 | 默认启用 | 用途 |
| --- | --- | --- |
| `read_file` | 是 | 读取工作目录内的文件 |
| `write_file` | 是 | 创建或修改工作目录内的文件 |
| `list_files` | 是 | 列出工作目录内的目录内容 |
| `run_shell` | 否 | 在会话工作目录执行受限命令 |

文件工具会检查路径、符号链接和敏感路径等规则，部分读取或写入可能被拒绝。它们不提供任意文件访问能力。

需要启用命令工具时，在 `config.yaml` 的 `tools.enabled` 中加入 `run_shell`。目前仅允许少量命令，如 `echo`、`dir`、`ls`、`pwd`、`cd`、`ping`、`sleep`；其中目录相关命令不接受参数。管道、重定向、命令串联和后台执行等语法会被拒绝，也不支持通过该工具自由运行构建或测试命令。

命令限制是进程内策略，不是操作系统级沙箱或人工审批机制。启用 `run_shell` 不代表具备生产环境的隔离保障。

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
