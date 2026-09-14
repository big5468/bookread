$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$lib = Join-Path $root '.tools/gradle-8.11.1/lib'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$out = Join-Path $root '.tools/domain-tests'
New-Item -ItemType Directory -Force $out | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'app/src/main/java/com/guanjian/app/domain') -Filter '*.kt' -Recurse | ForEach-Object FullName)
$sources += @(Get-ChildItem (Join-Path $root 'app/src/test/java/com/guanjian/app/domain') -Filter '*.kt' -Recurse | ForEach-Object FullName)
$cp = "$lib/kotlin-stdlib-2.0.20.jar;$lib/junit-4.13.2.jar;$lib/hamcrest-core-1.3.jar"
& $java -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $cp -d $out @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp "$out;$cp" org.junit.runner.JUnitCore com.guanjian.app.domain.ReadingRulesTest com.guanjian.app.domain.FocusClockTest com.guanjian.app.domain.WeeklyStatsTest
exit $LASTEXITCODE
