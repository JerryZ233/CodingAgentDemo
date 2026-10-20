param(
    [string]$GradleUserHome,
    [switch]$NoHostCache,
    [switch]$Offline,
    [string]$ImageTag = 'coding-agent-eval-base:local'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (!$GradleUserHome) {
    $GradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
}
# Only distribution binaries and dependency artifacts/metadata enter this context.
# Never copy gradle.properties, init scripts, credentials, daemon state or lock files.
$seed = Join-Path $repo ('build/eval-gradle-seed/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $seed -Force | Out-Null

try {
    function Copy-CacheTree([string]$Source, [string]$Destination) {
        if (!(Test-Path -LiteralPath $Source -PathType Container)) { return }
        Get-ChildItem -LiteralPath $Source -File -Recurse | Where-Object {
            $_.Name -notmatch '\.(lock|lck|part)$' -and $_.Name -ne 'gc.properties'
        } | ForEach-Object {
            $relative = $_.FullName.Substring($Source.Length).TrimStart([char[]]'\/')
            $target = Join-Path $Destination $relative
            New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $_.FullName -Destination $target
        }
    }

    if (!$NoHostCache -and (Test-Path -LiteralPath $GradleUserHome -PathType Container)) {
        $GradleUserHome = (Resolve-Path -LiteralPath $GradleUserHome).Path
        Copy-CacheTree (Join-Path $GradleUserHome 'caches/modules-2/files-2.1') (Join-Path $seed 'caches/modules-2/files-2.1')
        # Gradle's portable metadata caches use relative paths; omit the live lock/gc files.
        Get-ChildItem -LiteralPath (Join-Path $GradleUserHome 'caches/modules-2') -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^metadata-2\.\d+$' } | ForEach-Object {
                Copy-CacheTree $_.FullName (Join-Path $seed ('caches/modules-2/' + $_.Name))
            }
        $properties = Get-Content -LiteralPath (Join-Path $repo 'gradle/wrapper/gradle-wrapper.properties') -Raw
        if ($properties -notmatch '/(gradle-[0-9.]+-bin)\.zip') { throw 'Cannot identify the Gradle wrapper distribution.' }
        $distribution = $Matches[1]
        $distributions = Join-Path $GradleUserHome ('wrapper/dists/' + $distribution)
        if (Test-Path -LiteralPath $distributions -PathType Container) {
            Get-ChildItem -LiteralPath $distributions -Directory | ForEach-Object {
                # The wrapper marks a fully extracted distribution with .zip.ok.
                if (Test-Path -LiteralPath (Join-Path $_.FullName ($distribution + '.zip.ok'))) {
                    Copy-CacheTree $_.FullName (Join-Path $seed ('wrapper/dists/' + $distribution + '/' + $_.Name))
                }
            }
        }
    }

    $arguments = @('build', '--progress=plain', '--build-context', "gradle-seed=$seed",
        '--build-arg', ('GRADLE_BUILD_OFFLINE=' + $Offline.IsPresent.ToString().ToLowerInvariant()),
        '-f', 'eval/docker/agent.Dockerfile', '-t', $ImageTag, '.')
    Push-Location $repo
    try {
        & docker @arguments
        if ($LASTEXITCODE -ne 0) { throw "Agent image build failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    # Delete only this invocation's generated staging directory inside the workspace.
    $resolvedSeed = (Resolve-Path -LiteralPath $seed).Path
    $seedRoot = [IO.Path]::GetFullPath((Join-Path $repo 'build/eval-gradle-seed'))
    if ((Split-Path $resolvedSeed -Parent) -ne $seedRoot) { throw 'Unexpected Gradle seed directory; refusing cleanup.' }
    Remove-Item -LiteralPath $resolvedSeed -Recurse -Force
}
