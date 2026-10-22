param([string]$SdkPath = '', [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
function Get-AsciiToolPath([string]$TargetPath, [string]$Role) {
    $resolved = (Resolve-Path -LiteralPath $TargetPath).Path
    if ($resolved -notmatch '[^\x00-\x7F]') { return $resolved }
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $tag = ([BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($resolved)))).Replace('-','').Substring(0,12) }
    finally { $hasher.Dispose() }
    $link = Join-Path ([IO.Path]::GetTempPath()) ("focuslock-$Role-$tag")
    if (-not (Test-Path -LiteralPath $link)) { New-Item -ItemType Junction -Path $link -Target $resolved | Out-Null }
    return $link
}
$projectPath = Get-AsciiToolPath $PSScriptRoot 'project'
Set-Location -LiteralPath $projectPath
if ($SdkPath) {
    if (-not (Test-Path -LiteralPath (Join-Path $SdkPath 'platforms/android-36/android.jar'))) { throw '需要 Android SDK platform 36' }
    $sdkToolPath = Get-AsciiToolPath $SdkPath 'sdk'
    ('sdk.dir=' + $sdkToolPath.Replace('\','/').Replace(':','\:')) | Set-Content -LiteralPath local.properties -Encoding ascii
}
$tasks = if ($SkipTests) { @(':app:assembleDebug') } else { @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug') }
& (Join-Path $projectPath 'gradlew.bat') -p $projectPath @tasks
if ($LASTEXITCODE -ne 0) { throw '构建失败，请检查 Gradle 输出。' }
Get-FileHash -LiteralPath 'app/build/outputs/apk/debug/app-debug.apk' -Algorithm SHA256
