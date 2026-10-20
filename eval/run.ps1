param(
    [ValidateSet('agent', 'oracle', 'initial')][string]$Mode = 'agent',
    [ValidateSet('csv-parser', 'todo-workflow', 'repo-search-fix', 'large-file-edit')][string[]]$Task,
    [string]$LocalConfig,
    [switch]$AllowModelCalls,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'agent' -and !$AllowModelCalls) {
    Write-Error 'Real-model evaluation is disabled by default. Only when explicitly requested, pass -Mode agent -AllowModelCalls.' -ErrorAction Continue
    exit 2
}
$repo = Split-Path $PSScriptRoot -Parent
$taskNames = if ($Task) { @($Task | Select-Object -Unique) } else { @('csv-parser', 'todo-workflow', 'repo-search-fix', 'large-file-edit') }
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$results = Join-Path $PSScriptRoot "results/$runId"
$containerName = "coding-agent-eval-$runId"
$exitCode = 2

function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed with exit code $LASTEXITCODE" }
}

Push-Location $repo
try {
    New-Item -ItemType Directory -Path $results | Out-Null
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $engine = & docker info --format '{{.OSType}}' 2>$null; $engineExit = $LASTEXITCODE }
    finally { $ErrorActionPreference = $savedPreference }
    if ($engineExit -ne 0 -or $engine -ne 'linux') { throw 'Start Docker Desktop with the Linux container engine before running evaluations.' }
    $localConfigPath = $null
    if ($LocalConfig) {
        $localConfigPath = (Resolve-Path -LiteralPath $LocalConfig).Path
        if (!(Test-Path -LiteralPath $localConfigPath -PathType Leaf)) { throw 'LocalConfig must be a file.' }
    }
    if ($Mode -eq 'agent' -and !$localConfigPath -and [string]::IsNullOrWhiteSpace($env:LLM_API_KEY)) {
        throw 'Set LLM_API_KEY or specify -LocalConfig config.local.yaml.'
    }
    $buildTimer = [Diagnostics.Stopwatch]::StartNew()
    if (!$SkipBuild) {
        Invoke-Docker -Arguments @('build', '-f', 'eval/docker/agent.Dockerfile', '-t', 'coding-agent-eval-base:local', '.')
        foreach ($name in $taskNames) {
            Invoke-Docker -Arguments @('build', '-f', "eval/tasks/$name/environment/Dockerfile", '-t', "coding-agent-eval-$name`:local", "eval/tasks/$name/environment")
            Invoke-Docker -Arguments @('build', '-f', "eval/tasks/$name/tests/Dockerfile", '-t', "coding-agent-verifier-$name`:local", 'eval')
        }
        Invoke-Docker -Arguments @('build', '-f', 'eval/docker/orchestrator.Dockerfile', '-t', 'coding-agent-eval-orchestrator:local', '.')
    }
    $buildTimer.Stop()
    $imageIds = @{}
    foreach ($image in (@('coding-agent-eval-base:local', 'coding-agent-eval-orchestrator:local') +
            @($taskNames | ForEach-Object { "coding-agent-eval-$_`:local"; "coding-agent-verifier-$_`:local" }))) {
        $imageId = & docker image inspect --format '{{.Id}}' $image
        if ($LASTEXITCODE -ne 0) { throw "Missing image $image. Run again without -SkipBuild." }
        $imageIds[$image] = $imageId
    }
    $sourceSha = (& git rev-parse HEAD).Trim()
    $sourceHasher = [Security.Cryptography.SHA256]::Create()
    try {
        # Hash the exact implementation inputs, including untracked evaluation sources, excluding secrets/results.
        $manifest = foreach ($inputPath in @('src/main', 'eval', 'gradle', 'build.gradle.kts', 'settings.gradle.kts', 'gradlew', '.dockerignore')) {
            Get-ChildItem -LiteralPath (Join-Path $repo $inputPath) -File -Recurse | Where-Object {
                $_.FullName -notmatch '[\\/]eval[\\/]results[\\/]|[\\/]__pycache__[\\/]|config\.local\.yaml$'
            } | Sort-Object FullName | ForEach-Object {
                $_.FullName.Substring($repo.Length).Replace('\', '/') + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        }
        $digest = [BitConverter]::ToString($sourceHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(($manifest -join "`n")))).Replace('-', '').ToLowerInvariant()
    } finally { $sourceHasher.Dispose() }
    @{ source_sha = $sourceSha; source_digest = $digest; image_ids = $imageIds; image_preparation_ms = $buildTimer.ElapsedMilliseconds;
       source_dirty = [bool](& git status --porcelain -- src/main eval build.gradle.kts settings.gradle.kts .dockerignore) } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $results 'provenance.json') -Encoding UTF8

    # Docker Desktop exposes Windows drives under this daemon-visible path. Preserve that exact path
    # inside the orchestrator so Harbor's sibling-container log bind mounts resolve correctly.
    $absolute = [IO.Path]::GetFullPath($results)
    if ($absolute -notmatch '^([A-Za-z]):[\\/](.*)$') { throw 'Expected a local Windows drive path for the output directory.' }
    $daemonPath = '/run/desktop/mnt/host/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2].Replace('\', '/')
    $arguments = @('run', '--name', $containerName, '--init', '--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
        '--mount', "type=bind,source=$absolute,target=$daemonPath", '--env', "EVAL_SOURCE_SHA=$sourceSha")
    if ($localConfigPath) {
        $arguments += @('--mount', "type=bind,source=$localConfigPath,target=/run/secrets/eval-model.yaml,readonly",
            '--env', 'EVAL_MODEL_CONFIG=/run/secrets/eval-model.yaml')
    }
    foreach ($key in @('LLM_API_KEY', 'LLM_API_URL', 'LLM_MODEL', 'LLM_PROVIDER', 'LLM_MAX_TOKENS', 'LLM_TEMPERATURE', 'LLM_MAX_RETRIES', 'AGENT_MAX_ITERATIONS')) {
        if ([Environment]::GetEnvironmentVariable($key)) { $arguments += @('--env', $key) }
    }
    $arguments += @('coding-agent-eval-orchestrator:local', '--output-dir', $daemonPath, '--mode', $Mode)
    if ($Mode -eq 'agent' -and $AllowModelCalls) {
        # Existing release images may predate the Python consent flag. Keep those images unchanged;
        # the PowerShell entry point has already required explicit consent for this invocation.
        $runnerHelp = & docker run --rm --network none coding-agent-eval-orchestrator:local --help 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the existing orchestrator release options.' }
        if (($runnerHelp | Out-String).Contains('--allow-model-calls')) { $arguments += '--allow-model-calls' }
    }
    foreach ($name in $taskNames) { $arguments += @('--task', $name) }
    & docker @arguments
    $exitCode = $LASTEXITCODE
    if (!(Test-Path -LiteralPath (Join-Path $results 'summary.json'))) {
        throw "Orchestrator exited before writing a report (exit code $exitCode)."
    }
} catch {
    $exitCode = 2
    $diagnostic = $_.Exception.Message
    if ($env:LLM_API_KEY) { $diagnostic = $diagnostic.Replace($env:LLM_API_KEY, '[redacted]') }
    if (!(Test-Path -LiteralPath (Join-Path $results 'summary.json'))) {
        $rows = @($taskNames | ForEach-Object {
            [pscustomobject]@{ task = $_; agent_status = 'NOT_RUN'; functional_passed = $false;
                regression_passed = $false; reward = 0; passed = $false; total_tokens = $null;
                usage_complete = $false; error = $diagnostic }
        })
        @{ schema_version = 1; planned_tasks = $taskNames.Count; passed_tasks = 0; success_rate = 0;
            infrastructure_error = $diagnostic; tasks = $rows } | ConvertTo-Json -Depth 8 |
            Set-Content -LiteralPath (Join-Path $results 'summary.json') -Encoding UTF8
        $rows | Export-Csv -LiteralPath (Join-Path $results 'summary.csv') -NoTypeInformation -Encoding UTF8
    }
    Write-Error $diagnostic -ErrorAction Continue
} finally {
    # Harbor normally removes its environments. Recover only this run's projects after interruption.
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker rm -f $containerName 2>$null | Out-Null
        $manifestPath = Join-Path $results 'trial-manifest.jsonl'
        if (Test-Path -LiteralPath $manifestPath) {
            $trialPrefixes = @(Get-Content -LiteralPath $manifestPath | ForEach-Object {
                try {
                    $trial = ($_ | ConvertFrom-Json).trial_name.ToLowerInvariant()
                    if ($trial -notmatch '^[a-z0-9]') { $trial = '0' + $trial }
                    $trial -replace '[^a-z0-9_-]', '-'
                } catch { } # Ignore an interrupted final journal write.
            })
            foreach ($kind in @('container', 'network')) {
                $format = '{{.ID}}|{{.Label "com.docker.compose.project"}}'
                $items = if ($kind -eq 'container') {
                    & docker ps -a --filter 'label=com.docker.compose.project' --format $format 2>$null
                } else {
                    & docker network ls --filter 'label=com.docker.compose.project' --format $format 2>$null
                }
                foreach ($item in $items) {
                    $parts = $item -split '\|', 2
                    if ($parts.Count -ne 2 -or $parts[0] -notmatch '^[a-f0-9]{12,64}$') { continue }
                    $owned = @($trialPrefixes | Where-Object {
                        $parts[1] -eq ($_ + '__env') -or $parts[1].StartsWith($_ + '__verifier__')
                    }).Count -gt 0
                    if ($owned) {
                        if ($kind -eq 'container') { & docker rm -f $parts[0] 2>$null | Out-Null }
                        else { & docker network rm $parts[0] 2>$null | Out-Null }
                    }
                }
            }
        }
    }
    finally { $ErrorActionPreference = $savedPreference }
    Pop-Location
    Write-Host "Evaluation output: $results"
}
exit $exitCode
