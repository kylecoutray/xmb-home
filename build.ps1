# Build XMB Home with JDK 11+ and Android SDK platform 34 / build-tools 36.1.0.
# Default signing uses this checkout's ignored development key. For release signing,
# set HOMESTEAD_KEYSTORE, HOMESTEAD_KEYPASS and optionally HOMESTEAD_KEYALIAS.
param([ValidateSet('', 'install')][string]$Action = '')

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
function Or($a, $b) { if ($a) { $a } else { $b } }
function Run([string]$exe, [string[]]$argv) {
    # Windows PowerShell 5.1 can treat native stderr as a PowerShell error.
    # Java tools also use stderr for successful diagnostics; trust their exit code.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $exe @argv
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($code -ne 0) { throw "$([IO.Path]::GetFileName($exe)) failed ($code)" }
}

if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
foreach ($command in 'java', 'javac', 'keytool') {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "$command not found. Install a JDK 11+ or set JAVA_HOME."
    }
}
$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaVersion = (& javac -version 2>&1 | ForEach-Object { $_.ToString() } | Out-String).Trim()
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($javaVersion -notmatch '^javac (\d+)' -or [int]$Matches[1] -lt 11) {
    throw 'A JDK 11 or later is required.'
}

$SDK = Or (Or $env:ANDROID_SDK_ROOT $env:ANDROID_HOME) "$env:LOCALAPPDATA\Android\Sdk"
$BT = Or $env:ANDROID_BUILD_TOOLS "$SDK\build-tools\36.1.0"
$ANDROID_JAR = Or $env:ANDROID_JAR "$SDK\platforms\android-34\android.jar"
foreach ($name in 'aapt.exe', 'aapt2.exe', 'd8.bat', 'zipalign.exe', 'apksigner.bat') {
    if (-not (Test-Path -LiteralPath "$BT\$name" -PathType Leaf)) {
        throw "Missing $name in $BT. Install Android build-tools 36.1.0."
    }
}
if (-not (Test-Path -LiteralPath $ANDROID_JAR -PathType Leaf)) {
    throw "Missing $ANDROID_JAR. Install Android SDK platform 34."
}
$ADB = Or $env:ADB "$SDK\platform-tools\adb.exe"
if ($Action -eq 'install') {
    if (-not $env:ADB_TARGET) { throw 'Set ADB_TARGET=<tv-ip>:5555 for install.' }
    if (-not (Test-Path -LiteralPath $ADB -PathType Leaf)) {
        throw "ADB not found at $ADB. Install Android platform-tools or set ADB."
    }
}

try {
    if ($env:HOMESTEAD_KEYSTORE) {
        $KEYSTORE = $env:HOMESTEAD_KEYSTORE
        $KEYALIAS = Or $env:HOMESTEAD_KEYALIAS 'homestead'
        if (-not (Test-Path -LiteralPath $KEYSTORE -PathType Leaf)) { throw "Signing keystore not found: $KEYSTORE" }
        if (-not $env:HOMESTEAD_KEYPASS) { throw 'Set HOMESTEAD_KEYPASS for your external signing key.' }
        $env:XMB_SIGNING_PASSWORD = $env:HOMESTEAD_KEYPASS
        Write-Host '==> using external signing key'
    } else {
        $KEYSTORE = Join-Path $PSScriptRoot '.local\signing\development.jks'
        $KEYALIAS = 'xmbhome'
        $env:XMB_SIGNING_PASSWORD = 'android'
        if (-not (Test-Path -LiteralPath $KEYSTORE)) {
            Write-Host '==> creating local development signing key'
            New-Item -ItemType Directory -Force '.local\signing' | Out-Null
            Run 'keytool' @('-genkeypair', '-noprompt', '-storetype', 'PKCS12', '-keystore', $KEYSTORE,
                '-storepass:env', 'XMB_SIGNING_PASSWORD', '-keypass:env', 'XMB_SIGNING_PASSWORD',
                '-alias', $KEYALIAS, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
                '-dname', 'CN=XMB Home Development') | Out-Null
        }
        Write-Host '==> development signing (not the release certificate)'
    }

    $MIN_SDK = 22
    $TARGET_SDK = 28
    Write-Host '==> clean'
    foreach ($path in 'classes', 'dex', 'gen', 'compiled.flata', 'app-unsigned.apk', 'app-with-dex.apk', 'app-aligned.apk') {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    }
    New-Item -ItemType Directory -Force classes, dex, 'res\drawable-nodpi' | Out-Null

    Write-Host '==> artwork'
    Run 'java' @('tools\MakeArt.java', 'res\drawable-nodpi') | Out-Null
    Run 'java' @('tools\MakeLandscapes.java', 'assets') | Out-Null

    Write-Host '==> resources'
    Run "$BT\aapt2.exe" @('compile', '--dir', 'res', '-o', 'compiled.flata')
    Run "$BT\aapt2.exe" @('link', '-o', 'app-unsigned.apk', '-I', $ANDROID_JAR,
        '--manifest', 'AndroidManifest.xml', '-R', 'compiled.flata',
        '--min-sdk-version', "$MIN_SDK", '--target-sdk-version', "$TARGET_SDK",
        '-A', 'assets', '-0', 'mp4', '-0', 'wav', '--java', 'gen', '--auto-add-overlay')

    # Relative argument-file paths work even when the checkout's path has spaces.
    $rootPrefix = $PSScriptRoot.TrimEnd('\') + '\'
    Write-Host '==> Java'
    Get-ChildItem -Recurse src, gen -Filter *.java | ForEach-Object {
        '"' + ($_.FullName.Substring($rootPrefix.Length) -replace '\\', '/') + '"'
    } | Set-Content -Encoding ascii sources.txt
    Run 'javac' @('--release', '11', '-nowarn', '-encoding', 'UTF-8',
        '-classpath', $ANDROID_JAR, '-d', 'classes', '@sources.txt')
    Remove-Item sources.txt
    # D8 argument files treat quotes literally; relative class paths contain no spaces.
    Get-ChildItem -Recurse classes -Filter *.class | ForEach-Object {
        $_.FullName.Substring($rootPrefix.Length) -replace '\\', '/'
    } | Set-Content -Encoding ascii classes.txt
    Run "$BT\d8.bat" @('--lib', $ANDROID_JAR, '--min-api', "$MIN_SDK", '--release',
        '--output', "$PWD\dex", '@classes.txt')
    Remove-Item classes.txt

    Write-Host '==> package and sign'
    Copy-Item app-unsigned.apk app-with-dex.apk
    Push-Location dex
    try { Run "$BT\aapt.exe" @('add', '..\app-with-dex.apk', 'classes.dex') | Out-Null } finally { Pop-Location }
    Run "$BT\zipalign.exe" @('-f', '-p', '4', 'app-with-dex.apk', 'app-aligned.apk')
    Run "$BT\apksigner.bat" @('sign', '--ks', $KEYSTORE, '--ks-key-alias', $KEYALIAS,
        '--ks-pass', 'env:XMB_SIGNING_PASSWORD', '--key-pass', 'env:XMB_SIGNING_PASSWORD',
        '--min-sdk-version', "$MIN_SDK", '--out', 'xmb-home.apk', 'app-aligned.apk')
    Run "$BT\apksigner.bat" @('verify', '--min-sdk-version', "$MIN_SDK", 'xmb-home.apk')
    Write-Host "==> xmb-home.apk ($((Get-Item xmb-home.apk).Length) bytes)"
    if ($Action -eq 'install') { Run $ADB @('-s', $env:ADB_TARGET, 'install', '-r', 'xmb-home.apk') }
} finally {
    Remove-Item Env:\XMB_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
