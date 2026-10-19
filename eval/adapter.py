"""Harbor 0.24 installed-agent adapter; Java retains ownership of its agent loop."""
import json
import os
from pathlib import Path
import tempfile

from harbor.agents.installed.base import BaseInstalledAgent
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext


class CodingAgent(BaseInstalledAgent):
    @staticmethod
    def name() -> str:
        return "coding-agent-demo"

    def version(self) -> str:
        return os.environ.get("EVAL_SOURCE_SHA", "workspace")

    async def install(self, environment: BaseEnvironment) -> None:
        await self.exec_as_agent(environment, "test -f /opt/agent/lib/CodingAgentDemo.jar && command -v java")
        await environment.upload_file(Path(__file__).with_name("prepare_workspace.py"), "/tmp/prepare-workspace.py")
        await self.exec_as_agent(environment, "python3 /tmp/prepare-workspace.py /workspace")

    async def run(self, instruction: str, environment: BaseEnvironment, context: AgentContext) -> None:
        if os.environ.get("CODING_AGENT_EVAL_MODEL_CALLS") != "explicit":
            raise RuntimeError("Real-model evaluation requires explicit per-invocation consent")
        # Upload a file instead of interpolating an arbitrary task into a shell command.
        with tempfile.TemporaryDirectory() as directory:
            prompt = Path(directory) / "instruction.txt"
            prompt.write_text(self.render_instruction(instruction), encoding="utf-8")
            await environment.upload_file(prompt, "/tmp/eval-instruction.txt")
        env = {key: os.environ[key] for key in (
            "LLM_API_KEY", "LLM_API_URL", "LLM_MODEL", "LLM_PROVIDER", "LLM_MAX_TOKENS",
            "LLM_TEMPERATURE", "LLM_MAX_RETRIES", "AGENT_MAX_ITERATIONS", "CODING_AGENT_EVAL_MODEL_CALLS"
        ) if key in os.environ}
        command = (
            "java -Dfile.encoding=UTF-8 -cp '/opt/agent/lib/*' com.demo.eval.EvalMain "
            "--workspace /workspace --instruction-file /tmp/eval-instruction.txt "
            "--output-dir /logs/agent/eval --timeout-seconds 600 "
            "> /logs/agent/console.log 2>&1 < /dev/null"
        )
        # A Java failure is a recorded agent outcome. Let independent verification still run.
        result = await environment.exec(command, cwd="/opt/eval", env=env, timeout_sec=650)
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        (self.logs_dir / "process.json").write_text(json.dumps({"exit_code": result.return_code}), encoding="utf-8")

    def populate_context_post_run(self, context: AgentContext) -> None:
        path = self.logs_dir / "eval" / "metrics.json"
        if not path.is_file():
            return
        metrics = json.loads(path.read_text(encoding="utf-8"))
        context.n_input_tokens = metrics.get("input_tokens")
        context.n_output_tokens = metrics.get("output_tokens")
        context.n_cache_tokens = metrics.get("cached_tokens")
        context.metadata = {"usage_complete": metrics.get("usage_complete", False),
                            "agent_status": metrics.get("agent_status")}
