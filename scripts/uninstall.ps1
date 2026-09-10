[CmdletBinding()]
param(
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'CGen')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedInstallDirectory = [System.IO.Path]::GetFullPath($InstallDirectory)
$pathRoot = [System.IO.Path]::GetPathRoot($resolvedInstallDirectory)
$markerPath = Join-Path $resolvedInstallDirectory '.cgen-install-marker'

if ($resolvedInstallDirectory -eq $pathRoot -or
    $resolvedInstallDirectory -eq [System.IO.Path]::GetFullPath($env:LOCALAPPDATA) -or
    $resolvedInstallDirectory -eq [System.IO.Path]::GetFullPath($env:USERPROFILE)) {
    throw "Refusing unsafe uninstall directory: $resolvedInstallDirectory"
}

if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
    throw "Refusing to remove an unrecognized directory: $resolvedInstallDirectory"
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$pathParts = @($userPath -split ';' | Where-Object {
    if ([string]::IsNullOrWhiteSpace($_)) {
        return $false
    }
    [System.IO.Path]::GetFullPath($_).TrimEnd('\') -ine $resolvedInstallDirectory.TrimEnd('\')
})
[Environment]::SetEnvironmentVariable('Path', ($pathParts -join ';'), 'User')

Remove-Item -Recurse -Force -LiteralPath $resolvedInstallDirectory
Write-Output 'CGen uninstalled. Open a new terminal to refresh PATH.'
