param(
    [string]$SigningProperties = $env:FORGE_RELEASE_SIGNING_PROPERTIES
)

$ErrorActionPreference = 'Stop'
if ($args.Count -ne 0) { throw 'Only the SigningProperties parameter is supported; additional Gradle/logging flags are rejected.' }
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($SigningProperties)) {
    throw 'Supply the private signing properties path, or set FORGE_RELEASE_SIGNING_PROPERTIES.'
}
$propertiesPath = [System.IO.Path]::GetFullPath($SigningProperties)
$repositoryPrefix = $repositoryRoot + [System.IO.Path]::DirectorySeparatorChar
if ($propertiesPath.Equals($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    $propertiesPath.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Signing properties must be outside the repository.'
}
if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) {
    throw 'The private signing properties file is unavailable.'
}
if (((Get-Item -LiteralPath $propertiesPath).Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Use the private signing properties file directly rather than a link.'
}

$previousPropertiesPath = $env:FORGE_RELEASE_SIGNING_PROPERTIES
try {
    $env:FORGE_RELEASE_SIGNING_PROPERTIES = $propertiesPath
    Push-Location $repositoryRoot
    try {
        & (Join-Path $repositoryRoot 'gradlew.bat') --no-configuration-cache --console=plain --warn :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Production release build failed. Review the sanitized Gradle failure.' }
    } finally { Pop-Location }
} finally {
    $env:FORGE_RELEASE_SIGNING_PROPERTIES = $previousPropertiesPath
}
