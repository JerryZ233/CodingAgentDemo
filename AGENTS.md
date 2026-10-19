# Test and evaluation execution policy

- Run tests and evaluations only when the user explicitly requests them in the current task. Do not run them automatically as part of editing, committing, pushing, scheduled jobs, or background automation.
- A general request to run tests authorizes offline tests only. Real-model/API evaluations require an explicit request for real-model evaluation, because they consume tokens and may incur charges.
- Do not repeat a real-model evaluation automatically after a failure or a code change. Obtain a new explicit request for each additional invocation.
- Never add real-model evaluations to Gradle `test`/`build`, Git hooks, automatic CI triggers, cron, or recurring automations.
- Real-model runs must explicitly pass `-Mode agent -AllowModelCalls` to `eval/run.ps1` (or `--mode agent --allow-model-calls` to `eval/run.py`). Never enable the consent marker in a persistent environment or image.
- Compilation and static inspection are allowed without running tests. When tests are not requested, report that they were not run.
