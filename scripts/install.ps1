[CmdletBinding()]
param(
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'CGen'),
    [switch]$SkipBuild,
    [switch]$SkipPathUpdate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$resolvedInstallDirectory = [System.IO.Path]::GetFullPath($InstallDirectory)
$pathRoot = [System.IO.Path]::GetPathRoot($resolvedInstallDirectory)
$markerName = '.cgen-install-marker'
$markerPath = Join-Path $resolvedInstallDirectory $markerName

if ($resolvedInstallDirectory -eq $pathRoot -or
    $resolvedInstallDirectory -eq [System.IO.Path]::GetFullPath($env:LOCALAPPDATA) -or
    $resolvedInstallDirectory -eq [System.IO.Path]::GetFullPath($env:USERPROFILE)) {
    throw "Refusing unsafe installation directory: $resolvedInstallDirectory"
}

if (Test-Path -LiteralPath $resolvedInstallDirectory) {
    $hasContent = (Get-ChildItem -Force -LiteralPath $resolvedInstallDirectory | Measure-Object).Count -gt 0
    if ($hasContent -and -not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
        throw "Refusing to overwrite non-CGen directory: $resolvedInstallDirectory"
    }
}

if (-not $SkipBuild) {
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($null -eq $maven) {
        throw 'Maven (mvn.cmd) was not found on PATH.'
    }
    # Clean first so Shade never consumes a JAR that was already shaded by a prior build.
    & $maven.Source -f (Join-Path $repositoryRoot 'pom.xml') clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE"
    }
}

$jarCandidates = @(Get-ChildItem -File -LiteralPath (Join-Path $repositoryRoot 'target') -Filter 'cgen-*.jar' |
    Where-Object { $_.Name -notlike 'original-*' -and $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
    Sort-Object LastWriteTimeUtc -Descending)
if ($jarCandidates.Count -eq 0) {
    throw "No packaged CGen JAR found. Run without -SkipBuild first."
}

New-Item -ItemType Directory -Force -Path $resolvedInstallDirectory | Out-Null
$temporaryJar = Join-Path $resolvedInstallDirectory 'cgen.jar.new'
Copy-Item -Force -LiteralPath $jarCandidates[0].FullName -Destination $temporaryJar
Move-Item -Force -LiteralPath $temporaryJar -Destination (Join-Path $resolvedInstallDirectory 'cgen.jar')
Copy-Item -Force -LiteralPath (Join-Path $PSScriptRoot 'CGen.cmd') -Destination (Join-Path $resolvedInstallDirectory 'CGen.cmd')
Copy-Item -Force -LiteralPath (Join-Path $PSScriptRoot $markerName) -Destination $markerPath
Copy-Item -Force -LiteralPath (Join-Path $PSScriptRoot 'uninstall.ps1') -Destination (Join-Path $resolvedInstallDirectory 'Uninstall-CGen.ps1')

if (-not $SkipPathUpdate) {
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $pathParts = @($userPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $alreadyPresent = $pathParts | Where-Object {
        [System.IO.Path]::GetFullPath($_).TrimEnd('\') -ieq $resolvedInstallDirectory.TrimEnd('\')
    }
    if (-not $alreadyPresent) {
        $newUserPath = (@($pathParts) + $resolvedInstallDirectory) -join ';'
        [Environment]::SetEnvironmentVariable('Path', $newUserPath, 'User')
    }
    if (-not (($env:Path -split ';') -contains $resolvedInstallDirectory)) {
        $env:Path = $resolvedInstallDirectory + ';' + $env:Path
    }
}

Write-Output "CGen installed in $resolvedInstallDirectory"
if (-not $SkipPathUpdate) {
    Write-Output 'Open a new terminal, then run: CGen --help'
}
