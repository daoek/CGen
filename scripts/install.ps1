<#
    Installs CGen into a per-user directory (default %LOCALAPPDATA%\CGen). No admin rights
    are required and nothing outside the install directory is touched, other than adding that
    directory to the current user's PATH.

    Two modes:
      - Pass -Version <tag> (e.g. "0.1.0-beta.3" - see the Releases page for what's published)
        to download that published release's jar and
        SHA256SUMS from GitHub over HTTPS, verify the checksum, and install only if it matches.
        Nothing is written to the install directory if verification fails.
      - Omit -Version to build from the local checkout instead (`mvn clean package`), which is
        what the "CGen: Package + Install" VS Code task and contributors use.

    A note on -ExecutionPolicy Bypass, since it shows up in the recommended invocation: that
    flag scopes to the single powershell.exe process it's passed to. It does not change the
    machine's or the current user's execution policy - running this script that way leaves
    every other script on the machine subject to whatever policy was already in effect.
#>
[CmdletBinding()]
param(
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'CGen'),
    [string]$Version,
    [switch]$SkipBuild,
    [switch]$SkipPathUpdate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Version -and $SkipBuild) {
    throw '-Version and -SkipBuild are mutually exclusive: -Version installs a downloaded release and never builds locally.'
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$repositorySlug = 'daoek/CGen'
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

function Assert-Sha256Match {
    # Verifies $FilePath's SHA-256 against the entry for $FileName in a SHA256SUMS file
    # (the standard "<hash>  <filename>" format). Throws - and installs nothing - on any
    # mismatch or missing entry, so a corrupted or tampered download never gets installed.
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [Parameter(Mandatory)] [string]$FileName,
        [Parameter(Mandatory)] [string]$Sha256SumsPath
    )
    $expectedLine = Get-Content -LiteralPath $Sha256SumsPath |
        Where-Object { $_ -match ('^\s*[0-9a-fA-F]{64}\s+\*?' + [regex]::Escape($FileName) + '\s*$') } |
        Select-Object -First 1
    if (-not $expectedLine) {
        throw "No checksum entry for '$FileName' found in $Sha256SumsPath"
    }
    $expectedHash = ($expectedLine.Trim() -split '\s+')[0].ToLowerInvariant()
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $FilePath).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "Checksum mismatch for '$FileName': expected $expectedHash, got $actualHash. Refusing to install."
    }
    Write-Output "Checksum verified for $FileName ($actualHash)"
}

if ($Version) {
    $tag = if ($Version.StartsWith('v')) { $Version } else { "v$Version" }
    $bareVersion = $tag.TrimStart('v')
    $jarName = "cgen-$bareVersion.jar"
    $releaseBaseUrl = "https://github.com/$repositorySlug/releases/download/$tag"

    $downloadDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "cgen-install-$tag"
    New-Item -ItemType Directory -Force -Path $downloadDirectory | Out-Null
    $downloadedJar = Join-Path $downloadDirectory $jarName
    $downloadedSums = Join-Path $downloadDirectory 'SHA256SUMS'

    Write-Output "Downloading $jarName from release $tag..."
    try {
        Invoke-WebRequest -Uri "$releaseBaseUrl/$jarName" -OutFile $downloadedJar -UseBasicParsing
        Invoke-WebRequest -Uri "$releaseBaseUrl/SHA256SUMS" -OutFile $downloadedSums -UseBasicParsing
    } catch {
        throw "Could not download release '$tag' from https://github.com/$repositorySlug/releases: $($_.Exception.Message)"
    }
    Assert-Sha256Match -FilePath $downloadedJar -FileName $jarName -Sha256SumsPath $downloadedSums
    $sourceJarPath = $downloadedJar

    # -Version mode is meant to run from a single downloaded install.ps1, with no repo
    # checkout alongside it - so CGen.cmd/uninstall.ps1 can't be assumed to sit next to this
    # script (via $PSScriptRoot) the way they do in the local-build path below. Fetch them
    # from the same tagged ref instead.
    $cgenCmdSource = Join-Path $downloadDirectory 'CGen.cmd'
    $uninstallSource = Join-Path $downloadDirectory 'uninstall.ps1'
    $rawBaseUrl = "https://raw.githubusercontent.com/$repositorySlug/$tag/scripts"
    try {
        Invoke-WebRequest -Uri "$rawBaseUrl/CGen.cmd" -OutFile $cgenCmdSource -UseBasicParsing
        Invoke-WebRequest -Uri "$rawBaseUrl/uninstall.ps1" -OutFile $uninstallSource -UseBasicParsing
    } catch {
        throw "Could not download install scripts for release '$tag': $($_.Exception.Message)"
    }
} else {
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
    $sourceJarPath = $jarCandidates[0].FullName
    $cgenCmdSource = Join-Path $PSScriptRoot 'CGen.cmd'
    $uninstallSource = Join-Path $PSScriptRoot 'uninstall.ps1'
}

New-Item -ItemType Directory -Force -Path $resolvedInstallDirectory | Out-Null
$temporaryJar = Join-Path $resolvedInstallDirectory 'cgen.jar.new'
Copy-Item -Force -LiteralPath $sourceJarPath -Destination $temporaryJar
Move-Item -Force -LiteralPath $temporaryJar -Destination (Join-Path $resolvedInstallDirectory 'cgen.jar')
Copy-Item -Force -LiteralPath $cgenCmdSource -Destination (Join-Path $resolvedInstallDirectory 'CGen.cmd')
Copy-Item -Force -LiteralPath $uninstallSource -Destination (Join-Path $resolvedInstallDirectory 'Uninstall-CGen.ps1')
Set-Content -LiteralPath $markerPath -Value 'CGen managed installation. Safe removal requires this marker.' -Encoding utf8

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

$installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $resolvedInstallDirectory 'cgen.jar')).Hash.ToLowerInvariant()
Write-Output "CGen installed in $resolvedInstallDirectory"
Write-Output "Installed jar SHA256: $installedHash"
if (-not $SkipPathUpdate) {
    Write-Output 'Open a new terminal, then run: CGen --help'
}
