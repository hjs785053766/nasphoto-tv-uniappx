<#
.SYNOPSIS
    uni-app x 本地打包环境一键安装（全部装在本目录内，不写系统目录、不用绝对路径）

.DESCRIPTION
    做四件事：
      1. 下载并解压 JDK 21（Corretto）到 tools\jdk-21
      2. 下载 Android 命令行工具并安装 platform-tools / platform-36 / build-tools-36 到 tools\android-sdk
      3. 下载 Gradle 9.2.1 与 8.14.3 到 tools\gradle
      4. 下载 uni-app x 原生 SDK（vapor + vdom），并根据 templates\ 生成 native\vapor 与 native\vdom 两个可编译工程

    全部离线可用（装完后不再需要联网，除非 HBuilderX 升级换了 SDK 版本）。

.PARAMETER HBuilderXVersion
    HBuilderX 版本号，必须与导出资源的编译器版本一致。默认 5.34。

.PARAMETER Force
    已存在的组件重新下载/重装。

.EXAMPLE
    .\setup.ps1
    .\setup.ps1 -Force
#>
[CmdletBinding()]
param(
    [string]$HBuilderXVersion = '5.34',
    [switch]$Force
)

$ErrorActionPreference = 'Continue'
$Root  = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$Tools = Join-Path $Root 'tools'
$Dl    = Join-Path $Tools '_downloads'
$Native= Join-Path $Root 'native'
$Tpl   = Join-Path $Root 'templates'

function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    [OK] $m" -ForegroundColor Green }
function Info($m) { Write-Host "    $m" -ForegroundColor Gray }
function Warn2($m){ Write-Host "    [!] $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host "`n[X] $m" -ForegroundColor Red; exit 1 }

function Get-File($Url, $OutFile) {
    if ((Test-Path $OutFile) -and -not $Force -and (Get-Item $OutFile).Length -gt 1MB) {
        Info "已存在，跳过下载：$(Split-Path $OutFile -Leaf)"
        return
    }
    Info "下载 $(Split-Path $OutFile -Leaf) ..."
    New-Item -ItemType Directory -Force (Split-Path $OutFile -Parent) | Out-Null
    $tmp = "$OutFile.part"
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    & curl.exe -L --fail --retry 3 --retry-delay 3 --silent --show-error -o $tmp $Url
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tmp)) { Die "下载失败：$Url" }
    Move-Item $tmp $OutFile -Force
    Ok ("{0} ({1:N0} MB)" -f (Split-Path $OutFile -Leaf), ((Get-Item $OutFile).Length / 1MB))
}

function Expand-Zip($Zip, $Dest) {
    New-Item -ItemType Directory -Force $Dest | Out-Null
    & tar.exe -xf $Zip -C $Dest
    if ($LASTEXITCODE -ne 0) { Die "解压失败：$Zip" }
}

New-Item -ItemType Directory -Force $Tools, $Dl | Out-Null

Write-Host ''
Write-Host '============================================' -ForegroundColor Cyan
Write-Host ' uni-app x 本地打包环境安装' -ForegroundColor Cyan
Write-Host " 安装目录：$Root" -ForegroundColor Cyan
Write-Host '============================================' -ForegroundColor Cyan

# ---------- 1. JDK 21 ----------
Step 'JDK 21（Gradle 编译必需，SDK 插件是 Java 21 编译的，JDK 17 会报 UnsupportedClassVersionError）'
$jdkDir = Join-Path $Tools 'jdk-21'
if ((Test-Path (Join-Path $jdkDir 'bin\java.exe')) -and -not $Force) {
    Ok '已安装，跳过'
} else {
    $zip = Join-Path $Dl 'corretto-21-jdk.zip'
    Get-File 'https://corretto.aws/downloads/latest/amazon-corretto-21-x64-windows-jdk.zip' $zip
    if (Test-Path $jdkDir) { Remove-Item $jdkDir -Recurse -Force }
    Expand-Zip $zip $jdkDir
    # 压缩包里通常还有一层 jdk21.x 目录，拍平到 jdk-21\ 下
    if (-not (Test-Path (Join-Path $jdkDir 'bin\java.exe'))) {
        $inner = Get-ChildItem $jdkDir -Directory | Select-Object -First 1
        if ($inner) {
            Get-ChildItem $inner.FullName -Force | Move-Item -Destination $jdkDir -Force
            Remove-Item $inner.FullName -Recurse -Force
        }
    }
    if (-not (Test-Path (Join-Path $jdkDir 'bin\java.exe'))) { Die 'JDK 解压结构异常' }
    Ok 'JDK 21 就绪'
}

# ---------- 2. Android SDK ----------
Step 'Android SDK（platform-tools + platform-36 + build-tools-36）'
$sdkDir = Join-Path $Tools 'android-sdk'
$ctDir  = Join-Path $sdkDir 'cmdline-tools\latest'
if ((Test-Path (Join-Path $sdkDir 'platforms\android-36')) -and -not $Force) {
    Ok '已安装，跳过'
} else {
    $zip = Join-Path $Dl 'commandlinetools-win.zip'
    Get-File 'https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip' $zip
    if (Test-Path $ctDir) { Remove-Item $ctDir -Recurse -Force }
    Expand-Zip $zip $ctDir
    # 压缩包内层还有一层 cmdline-tools
    if (Test-Path (Join-Path $ctDir 'cmdline-tools\bin')) {
        Get-ChildItem (Join-Path $ctDir 'cmdline-tools') -Force | Move-Item -Destination $ctDir -Force
        Remove-Item (Join-Path $ctDir 'cmdline-tools') -Recurse -Force
    }
    $env:JAVA_HOME = $jdkDir
    $ai = Join-Path $ctDir 'bin\android.exe'
    if (-not (Test-Path $ai)) { Die "命令行工具解压异常：$ctDir" }
    foreach ($pkg in @('platform-tools', 'platforms;android-36', 'build-tools;36.0.0', 'platforms;android-35', 'build-tools;35.0.0')) {
        Info "安装 $pkg ..."
        & $ai --sdk=$sdkDir sdk install $pkg 2>&1 | Out-Null
    }
    if (-not (Test-Path (Join-Path $sdkDir 'platforms\android-36'))) { Die 'Android SDK 组件安装失败' }
    Ok 'Android SDK 就绪'
}

# ---------- 3. Gradle ----------
Step 'Gradle（vapor 用 9.2.1，vdom 用 8.14.3）'
foreach ($v in @('9.2.1', '8.14.3')) {
    $gDir = Join-Path $Tools "gradle\gradle-$v"
    if ((Test-Path (Join-Path $gDir 'bin\gradle.bat')) -and -not $Force) { Ok "gradle-$v 已存在"; continue }
    $zip = Join-Path $Dl "gradle-$v-bin.zip"
    Get-File "https://mirrors.cloud.tencent.com/gradle/gradle-$v-bin.zip" $zip
    New-Item -ItemType Directory -Force (Join-Path $Tools 'gradle') | Out-Null
    Expand-Zip $zip (Join-Path $Tools 'gradle')
    Ok "gradle-$v 就绪"
}

# ---------- 4. uni-app x 原生 SDK ----------
Step "uni-app x 原生 SDK $HBuilderXVersion"
# 版本不同 URL 会变，以官方下载页为准：https://doc.dcloud.net.cn/uni-app-x/native/download/android.html
$sdkUrls = @{
    vapor = "https://web-ext-storage.dcloud.net.cn/uni-app-x/sdk/Android-Vapor/Android-uni-app-x-vapor-SDK@101387-5.34.zip"
    vdom  = "https://web-ext-storage.dcloud.net.cn/uni-app-x/sdk/Android/Android-uni-app-x-SDK@15179-5.34.zip"
}
$sdkRoot = Join-Path $Tools "uniappx-sdk\$HBuilderXVersion"
foreach ($mode in @('vapor', 'vdom')) {
    $target = Join-Path $sdkRoot $mode
    if ((Test-Path $target) -and -not $Force) { Ok "$mode SDK 已存在"; continue }
    $zip = Join-Path $Dl "uniappx-$mode-sdk.zip"
    Get-File $sdkUrls[$mode] $zip
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    Expand-Zip $zip $target
    Ok "$mode SDK 就绪"
}

# ---------- 5. 生成 native 工程 ----------
Step '根据 templates 生成 native\vapor 与 native\vdom'

# 各模式需要的 aar（与官方文档的最小集一致；vdom 额外含 uni-video）
$aarList = @{
    vapor = @(
        'app-common-release.aar','app-runtime-release.aar','breakpad-build-release.aar','uts-runtime-release.aar',
        'ext-component-release.aar','uni-dialogPage-release.aar','uni-event-release.aar','uni-crash-release.aar',
        'uni-getElementById-release.aar','uni-pullDownRefresh-release.aar','uni-storage-release.aar','uni-exit-release.aar',
        'uni-theme-release.aar','uni-getAppBaseInfo-release.aar','uni-getDeviceInfo-release.aar','uni-getSystemInfo-release.aar',
        'uni-getAccessibilityInfo-release.aar','uni-getAppAuthorizeSetting-release.aar','uni-getSystemSetting-release.aar',
        'uni-openAppAuthorizeSetting-release.aar','uni-prompt-release.aar','uni-rpx2px-release.aar',
        'uni-secure-network-release.aar','uni-uasm-release.aar','uni-convert2AbsFullPath-release.aar'
    )
    vdom = @(
        'app-common-release.aar','app-runtime-release.aar','breakpad-build-release.aar','uts-runtime-release.aar',
        'dcloud-layout-release.aar','framework-release.aar','android-gif-drawable-1.2.30.aar',
        'uni-exit-release.aar','uni-getAccessibilityInfo-release.aar','uni-getAppAuthorizeSetting-release.aar',
        'uni-getAppBaseInfo-release.aar','uni-getSystemSetting-release.aar','uni-openAppAuthorizeSetting-release.aar',
        'uni-prompt-release.aar','uni-storage-release.aar','uni-getDeviceInfo-release.aar','uni-getSystemInfo-release.aar',
        'uni-rpx2px-release.aar','uni-theme-release.aar','uni-convert2AbsFullPath-release.aar','uni-video-release.aar'
    )
}
$pluginJars = @{
    vapor = @('uts-kotlin-compiler-plugin-0.0.1.jar','uts-kotlin-gradle-plugin-0.0.1.jar','auto-register-gradle-plugin-1.0.0.jar','auto-register-processor-1.0.0.jar')
    vdom  = @('uts-kotlin-compiler-plugin-0.0.1.jar','uts-kotlin-gradle-plugin-0.0.1.jar')
}

foreach ($mode in @('vapor', 'vdom')) {
    $projDir = Join-Path $Native $mode
    if ((Test-Path (Join-Path $projDir 'app\build.gradle')) -and -not $Force) { Ok "native\$mode 已存在"; continue }

    # 找到 SDK 解压出来的真实目录（zip 里有一层带 @build 号的外壳）
    $modeRoot = Get-ChildItem (Join-Path $sdkRoot $mode) -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notlike '__MACOSX' } | Select-Object -First 1
    if (-not $modeRoot) { Die "SDK 目录异常：$sdkRoot\$mode" }
    $sdkLibs = Join-Path $modeRoot.FullName 'SDK\libs'
    $sdkPlug = Join-Path $modeRoot.FullName 'plugins'
    if (-not (Test-Path $sdkLibs)) { Die "SDK 缺 libs：$sdkLibs" }

    if (Test-Path $projDir) { Remove-Item $projDir -Recurse -Force }
    Copy-Item (Join-Path $Tpl $mode) $projDir -Recurse -Force

    $libsDir = Join-Path $projDir 'uniappx\libs'
    $plugDir = Join-Path $projDir 'plugins'
    New-Item -ItemType Directory -Force $libsDir, $plugDir | Out-Null

    $missing = @()
    foreach ($a in $aarList[$mode]) {
        $src = Join-Path $sdkLibs $a
        if (Test-Path $src) { Copy-Item $src $libsDir -Force } else { $missing += $a }
    }
    foreach ($j in $pluginJars[$mode]) {
        Copy-Item (Join-Path $sdkPlug $j) $plugDir -Force
    }
    if ($missing.Count) { Warn2 ("SDK 里缺这些 aar（可能版本差异）：" + ($missing -join ', ')) }

    # local.properties 指向本目录内的 SDK（路径由安装时生成，不属于硬编码）
    $sdkForGradle = ($sdkDir -replace '\\', '/')
    Set-Content -Path (Join-Path $projDir 'local.properties') -Value "sdk.dir=$sdkForGradle" -Encoding ASCII

    Ok ("native\{0} 已生成（aar {1} 个，插件 {2} 个）" -f $mode, (Get-ChildItem $libsDir -Filter *.aar).Count, (Get-ChildItem $plugDir -Filter *.jar).Count)
}

# ---------- 完成 ----------
Write-Host ''
Write-Host '============================================' -ForegroundColor Green
Write-Host ' 安装完成' -ForegroundColor Green
Write-Host ' 下一步：' -ForegroundColor Green
Write-Host '   1) HBuilderX 打开项目 → 发行 → 原生App-本地打包 → 生成本地打包App资源' -ForegroundColor Green
Write-Host '   2) 双击 打包.bat（或命令行 .\打包.bat "项目路径"）' -ForegroundColor Green
Write-Host '============================================' -ForegroundColor Green
