param(
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent),
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest')
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
if ($ProjectRoot -match '[^\x00-\x7F]') {
    throw 'Use -ProjectRoot with an ASCII directory or junction: Windows Java argument files cannot reliably load Gradle test classes from non-ASCII paths.'
}
if (-not $JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17 installation.' }
$previousEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'GRADLE_USER_HOME', 'ANDROID_HOME', 'ANDROID_USER_HOME')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location $ProjectRoot
try {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = Join-Path $ProjectRoot '.tools/gradle-home'
    $env:ANDROID_HOME = Join-Path $ProjectRoot '.tools/sdk'
    $env:ANDROID_USER_HOME = Join-Path $ProjectRoot '.tools/android-user'
    $gradle = Join-Path $ProjectRoot '.tools/gradle-8.11.1/bin/gradle.bat'
    if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $ProjectRoot 'gradlew.bat' }
    & $gradle @Tasks '-Pkotlin.compiler.execution.strategy=in-process' '--no-daemon' '--console=plain'
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
exit $buildExitCode
