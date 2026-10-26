param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pluginJar = Join-Path $projectRoot 'target/BFBackpack-2.0.0-SNAPSHOT.jar'
$apiJar = Join-Path $projectRoot 'target/BFBackpack-2.0.0-SNAPSHOT-api.jar'
$output = Join-Path $projectRoot 'target/artifact-check'
New-Item -ItemType Directory -Path $output -Force | Out-Null
if (-not $JavaHome) { throw 'Set JAVA_HOME to a Java 17 JDK first.' }
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -proc:none -cp $apiJar -d $output (Join-Path $projectRoot 'docs/examples/BattlefieldApiExample.java')
if ($LASTEXITCODE -ne 0) { throw 'Public API example compilation failed' }
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -proc:none -cp $pluginJar -d $output (Join-Path $PSScriptRoot 'ArtifactSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Shaded artifact smoke compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -cp "$output;$pluginJar" ArtifactSmoke $apiJar $output
if ($LASTEXITCODE -ne 0) { throw 'Shaded artifact smoke failed' }
& (Join-Path $JavaHome 'bin/jdeps.exe') --multi-release 17 --print-module-deps $apiJar
if ($LASTEXITCODE -ne 0) { throw 'API dependency isolation failed' }
