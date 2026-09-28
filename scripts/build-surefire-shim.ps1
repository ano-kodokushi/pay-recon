# 重建并安装离线 surefire provider（surefire-junit-platform shim）
#
# 什么时候需要跑这个脚本：
#   本机离线，官方 org.apache.maven.surefire:surefire-junit-platform 不在本地 Maven 仓，
#   `mvn test` 会报 "The following artifacts could not be resolved"。
#   本脚本把自建的等价 provider 编译并安装到本地仓（坐标与官方一致），
#   之后 `mvn -o test` 即可正常工作。
#
# 用法：
#   pwsh -File scripts\build-surefire-shim.ps1
#
# 注意：脚本内不硬编码工作区路径 —— PowerShell 在本机以 ANSI 读取 .ps1 文件，
#       硬编码的中文路径（作业）会被破坏成乱码。一律从 $PSScriptRoot 推导。

param(
    [string]$SurefireVersion = '3.2.5',
    [string]$JunitPlatformVersion = '1.10.2'
)

$ErrorActionPreference = 'Stop'

$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Microsoft\jdk-17.0.8.101-hotspot' }
$javac  = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jarExe = Join-Path $env:JAVA_HOME 'bin\jar.exe'

$projectRoot = Split-Path -Parent $PSScriptRoot
$shim        = Join-Path $projectRoot 'surefire-provider-shim'
$repo        = Join-Path $env:USERPROFILE '.m2\repository'

if (-not (Test-Path $javac)) { throw "javac not found at $javac" }

# ---------------------------------------------------------------- 依赖 classpath
$cpParts = @(
    (Join-Path $repo "org\apache\maven\surefire\surefire-api\$SurefireVersion\surefire-api-$SurefireVersion.jar"),
    (Join-Path $repo "org\apache\maven\surefire\maven-surefire-common\$SurefireVersion\maven-surefire-common-$SurefireVersion.jar"),
    (Join-Path $repo "org\junit\platform\junit-platform-launcher\$JunitPlatformVersion\junit-platform-launcher-$JunitPlatformVersion.jar"),
    (Join-Path $repo "org\junit\platform\junit-platform-engine\$JunitPlatformVersion\junit-platform-engine-$JunitPlatformVersion.jar"),
    (Join-Path $repo "org\junit\platform\junit-platform-commons\$JunitPlatformVersion\junit-platform-commons-$JunitPlatformVersion.jar")
)

# apiguardian / opentest4j 版本不固定，按前缀查找
foreach ($prefix in @('apiguardian-api-', 'opentest4j-')) {
    $found = Get-ChildItem $repo -Recurse -File -Filter "$prefix*.jar" -ErrorAction SilentlyContinue |
             Select-Object -First 1
    if ($found) { $cpParts += $found.FullName }
}

$missing = $cpParts | Where-Object { -not (Test-Path $_) }
if ($missing) { throw ("缺少依赖 jar:`n" + ($missing -join "`n")) }
$env:CLASSPATH = ($cpParts -join ';')

# ---------------------------------------------------------------- 编译
$classes = Join-Path $shim 'target\classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$source = Join-Path $shim 'src\main\java\org\apache\maven\surefire\junitplatform\JUnitPlatformProvider.java'
& $javac -encoding UTF-8 -d $classes $source
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

# ---------------------------------------------------------------- 打包
$outJar = Join-Path $shim "target\surefire-junit-platform-$SurefireVersion.jar"
if (Test-Path $outJar) { Remove-Item $outJar -Force }
Push-Location $classes
try { & $jarExe cf $outJar 'org' } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }

# ---------------------------------------------------------------- 安装到本地仓
$dest = Join-Path $repo "org\apache\maven\surefire\surefire-junit-platform\$SurefireVersion"
New-Item -ItemType Directory -Path $dest -Force | Out-Null
Copy-Item $outJar (Join-Path $dest "surefire-junit-platform-$SurefireVersion.jar") -Force
Copy-Item (Join-Path $shim 'install-pom.xml') (Join-Path $dest "surefire-junit-platform-$SurefireVersion.pom") -Force

Write-Host "==============================================="
Write-Host "surefire provider shim installed:"
Write-Host "  $dest"
Get-ChildItem $dest | ForEach-Object { Write-Host ("  " + $_.Name + "  " + $_.Length + "B") }
Write-Host "Now run:  mvn -o test"
Write-Host "==============================================="
