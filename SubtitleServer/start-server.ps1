param(
    [Parameter(Mandatory)]
    [string]$KeyFile
)

$ErrorActionPreference = 'Stop'
$serverJar = Join-Path $PSScriptRoot 'target/SubtitleServer-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) {
    throw 'Build the SubtitleServer JAR before launching it.'
}

try {
    $subtitleApiKey = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $KeyFile).ProviderPath).Trim()
} catch {
    throw 'Could not read the API key file.'
}
if ([string]::IsNullOrWhiteSpace($subtitleApiKey) -or $subtitleApiKey -match '\s') {
    throw 'The API key file must contain one key.'
}

$previousSubtitleKey = $env:OPENAI_API_KEY
try {
    # Java inherits the key through its environment, keeping it off the command line.
    $env:OPENAI_API_KEY = $subtitleApiKey
    & java -jar $serverJar
    $serverExitCode = $LASTEXITCODE
} finally {
    $env:OPENAI_API_KEY = $previousSubtitleKey
}
exit $serverExitCode
