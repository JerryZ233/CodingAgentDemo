# 本地端到端 Agent 评测

Harbor 0.24.0 调度本项目 Java Agent 修改样例工程，再启动独立容器，用可信的 JUnit 测试验收源码。支持三个任务：词频统计、CSV 解析修复、待办事项状态筛选。

**仅在用户明确要求时运行测试。真实模型评测默认禁止，必须对本次运行显式传入 `-AllowModelCalls`；每次重跑也需要用户明确指示。不要将其接入定时任务、自动 CI、Git hooks 或日常构建。** 启动脚本、Python 编排器、Harbor 适配器和 Java 入口均检查本次调用的授权；配置了 API Key 不代表允许运行评测。

## 运行

需要 Docker Desktop 的 **Linux 容器引擎**正常运行。Harbor、Python、JDK 和 JUnit 均在镜像内准备，宿主机无需另外安装 Harbor。首次构建需要联网下载 Gradle、Java 依赖及 Python 依赖。

在项目根目录运行：

```powershell
# 先验证任务与容器链路：应用参考解答，不调用模型，不需要 API Key
.\eval\run.ps1 -Mode oracle

# 验证初始代码确实不能过关，预期退出码 1
.\eval\run.ps1 -Mode initial -SkipBuild

# 正式评测：密钥仅从当前环境读取
$env:LLM_API_KEY = '你的密钥'
.\eval\run.ps1 -Mode agent -AllowModelCalls

# 或显式使用日常配置中的模型设置和密钥
.\eval\run.ps1 -Mode agent -AllowModelCalls -LocalConfig config.local.yaml

# 只运行一个任务；源码修改后应重新构建，省略 -SkipBuild
.\eval\run.ps1 -Mode agent -AllowModelCalls -Task csv-parser
```

默认使用与项目一致的 DeepSeek 接口和模型；独立配置在 `eval/config.yaml`。用 `-LocalConfig config.local.yaml` 可显式读取本地模型配置：文件只读挂载到编排容器，不进入镜像和结果目录，只提取 `llm` 中的模型字段，不带入宿主工作目录及工具路径。优先级为环境变量、本地配置、评测默认配置。环境变量可覆盖 `LLM_API_URL`、`LLM_MODEL`、`LLM_PROVIDER`、`LLM_MAX_TOKENS`、`LLM_TEMPERATURE`、`LLM_MAX_RETRIES`、`AGENT_MAX_ITERATIONS`。容器访问宿主模型服务时，URL 中使用 `host.docker.internal`，不能使用宿主机的 `localhost`。

每次运行创建独立结果目录。退出码：`0` 全部任务通过；`1` 存在未通过任务；`2` 启动或编排失败。`-SkipBuild` 复用镜像，必须已经构建过本次选中的所有任务。报告记录实际镜像 ID；工作区源码摘要不代表复用镜像里的源码版本。

## 运行过程

1. PowerShell 构建 Agent 基础镜像、任务镜像、验收镜像和 Harbor 编排镜像。
2. 编排容器通过 Docker socket 创建任务容器；启动探针确认 Harbor 的日志路径能被同一 Docker 引擎正确挂载。
3. `adapter.py` 上传任务文本，调用 `com.demo.eval.EvalMain`。每个任务新建会话，无终端输入。
4. 评测入口通过 `RunHandle` 自动批准已经通过现有 Shell 规划检查的每次执行请求，并记录审批事件。原有 CLI 的人工审批逻辑不变；命令自身的交互问题不代答，stdin 关闭。
5. Agent 结束后，Harbor 将 `/workspace/src` 复制到独立验收容器。验收器重新编译源码，分别执行功能与回归测试；两者均通过才得到 `reward=1`。
6. 汇总 JSON/CSV，保留日志、源码产物和 JUnit XML。

适配器会在 Java 绑定工作目录之前将任务目录实体化到容器可写层，避免 OverlayFS 首次写入时改变目录创建时间而触发 `WORKSPACE_CHANGED`。原有目录身份校验保持启用。镜像构建后可执行 `python eval/check_container.py` 做无网络、无密钥的文件写入检查。

任务容器不挂载宿主项目或 Docker socket。自动审批入口要求专用环境变量和 Docker 容器标记；它不能替代操作系统安全边界，请只在本地受控评测环境中使用。编排容器持有 Docker 控制权限。

隐藏测试和参考解答不进入 Agent 的任务镜像。验收器只执行可信测试与编译命令，不使用候选工程的构建脚本、测试文件或已编译产物。首版用于受控样例任务，不作为对抗恶意候选代码的强隔离判题系统。

## 结果与统计口径

```text
eval/results/<时间-随机标识>/
  provenance.json                 # 源码提交、工作区摘要、镜像 ID、镜像准备耗时
  summary.json / summary.csv      # 所有计划任务，包括失败及未启动任务
  jobs/evaluation/<trial>/
    result.json                   # Harbor 原始运行结果
    agent/eval/metrics.json        # Agent 状态、回答、迭代数、Token、耗时
    agent/eval/attempts.jsonl      # 每次实际模型请求，包括重试、取消
    agent/eval/events.jsonl        # 进度、工具结果、自动审批记录
    agent/eval/sessions/           # 独立会话检查点及命令输出
    verifier/verification.json    # 功能/回归分别判定
    verifier/functional/          # JUnit XML
    verifier/regression/           # JUnit XML
    artifacts/                    # 提交源码
```

- **完成状态与验收结果分别记录**：`COMPLETED` 只表示 Agent 正常结束，不代表通过测试。成功率以所有计划任务为分母。
- **Token 来自服务端 usage**，累计所有收到用量的实际请求。输入 Token 已含缓存命中量，缓存不重复相加。支持标准缓存字段和 DeepSeek 的 `prompt_cache_hit_tokens`。
- 未返回 usage 的请求记为未知；`usage_complete=false` 时，总量仅为已知部分，不能当作完整账单。被取消的请求可能仍被远端计费。
- 模型请求耗时不含重试等待；Agent 总耗时包含工具、审批和重试等待。另列环境准备、验收、任务端到端耗时；镜像准备耗时独立记录。
- 外层强制终止导致没有最终指标时，从请求日志恢复已知用量，并标记不完整。不要将缺失用量视为零。
- 默认 30 次迭代、Agent 600 秒超时；取消最多等待 15 秒，Java 会话关闭使用既有 5 秒预算；适配器执行兜底 650 秒，Harbor 外层 660 秒；验收最多 120 秒。
- `oracle` 和 `initial` 模式不使用模型，Token 为未知/不适用。

日志会脱敏已注册密钥；源码、任务文本和工具输出仍可能包含其他业务信息。结果目录已加入 Git 忽略列表。

## 无 Docker 的确定性验证

需要本机 JDK 17、Python 3.9+ 和 JUnit Platform Console Standalone **1.10.0**：

```powershell
.\gradlew.bat test
python -m unittest discover -s eval -p 'test_*.py' -v
python eval/check_tasks.py --junit <junit-platform-console-standalone-1.10.0.jar的路径>
```

任务检查逐个验证初始版本、参考解答及破坏旧功能的版本，共九组。输出保留在 `build/eval-task-check/`。Java 测试使用脚本化模型和本地 HTTP 服务，验证自动审批、取消、重试、usage 缺失与缓存统计，不调用付费接口。

Docker 引擎未启动或镜像构建失败时，启动脚本也会输出失败汇总。Docker 自身不可用时无法执行容器联调；可先完成上述本地检查，再运行 `-Mode oracle` 验证完整链路。

若真实模型请求报告 `SSLHandshakeException` / `PKIX path building failed`，先检查网络路径、HTTPS 代理和服务端证书链。实际联调曾观察到同一域名间歇返回不受信任的自签名证书链；此时保留失败结果和未知用量，不关闭 TLS 验证、不自动信任接口返回的证书。
