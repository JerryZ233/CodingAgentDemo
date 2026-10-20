# Test and evaluation execution policy

- Ordinary tests that do not call real model APIs may run normally without an explicit user request. This includes `src/test`, Gradle unit/integration tests, local Python tests, Docker tests, and offline Harbor verification (`oracle` and `initial`). Permission depends on real model usage, not on Docker or Harbor usage.
- Only tests and evaluations that call real model APIs require an explicit user request, because they consume tokens and may incur charges. A general request to implement code, run ordinary tests, or perform offline verification does not authorize real-model calls.
- In this project, the user's phrase “跑一次harbor测试” explicitly authorizes one real-model Harbor evaluation. Use `agent` mode unless the user specifically requests `oracle`, `initial`, or offline verification. This phrase needs no additional confirmation; do not automatically repeat the run.
- Do not repeat a real-model evaluation automatically after a failure or a code change. Obtain a new explicit request for each additional invocation.
- Do not start real-model evaluations automatically as part of editing, committing, pushing, scheduled jobs, or background automation. Never add real-model evaluations to Gradle `test`/`build`, Git hooks, automatic CI triggers, cron, or recurring automations. Ordinary tests and offline verification are not subject to this restriction.
- Real-model Harbor runs must explicitly pass `-Mode agent -AllowModelCalls` to `eval/run.ps1` (or `--mode agent --allow-model-calls` to `eval/run.py`). Never enable the consent marker in a persistent environment or image.
- For an explicit Harbor test request, select real-model `agent` mode by default and supply the per-invocation consent flags above without asking for additional confirmation. Use offline modes only when requested or when independently performing ordinary offline verification.
- Report which checks ran and whether real model APIs were called.
