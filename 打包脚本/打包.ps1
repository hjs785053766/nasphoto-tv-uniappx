<#
.SYNOPSIS
    uni-app x 本地打包（离线打包）一键脚本 —— 把 HBuilderX 导出的项目资源打成 APK

.DESCRIPTION
    流程：
      1. 读取你的 uni-app x 项目（manifest.json），拿到 appid / 应用名
      2. 找到 HBuilderX 导出的资源目录（unpackage\resource\app-android\...）
      3. 自动判断是「蒸汽模式(Vapor)」还是「VDOM 模式」，选对应原生工程
      4. 把资源、appid、包名、应用名、版本号、签名全部配好
      5. 调 Gradle 编译出 APK，并复制到 output 目录

.PARAMETER ProjectPath
    HBuilderX 里那个 uni-app x 项目目录（里面要有 manifest.json）

.PARAMETER Resources
    资源目录。不填则自动在 <项目>\unpackage\resource 下找

.PARAMETER Mode
    auto / vapor / vdom，默认 auto（按导出内容自动判断）

.PARAMETER Variant
    release（正式签名，默认）或 debug（调试用，安装快）

.PARAMETER PackageName
    安卓包名，不填则用 manifest.json 里的配置（或工程的 gradle.properties）

.PARAMETER AppName
    桌面显示的应用名，不填则用 manifest.json 的 name

.PARAMETER Abi
    CPU 架构，默认 arm64-v8a；需要兼容老机器写 "arm64-v8a,armeabi-v7a"

.PARAMETER Install
    打包成功后自动用 adb 安装到已连接的手机

.PARAMETER Clean
    打包前先 clean（改了原生配置/图标/签名时建议加）

.EXAMPLE
    .\打包.ps1 -ProjectPath ..\my-uni-app-x

.EXAMPLE
    .\打包.ps1 -ProjectPath ..\my-uni-app-x -Variant debug -Install
#>
[CmdletBinding()]
param(
    # 不填也可以：脚本会提示输入（最近一次的项目会作为默认值）
    [Parameter(Position = 0)]
    [string]$ProjectPath,

    [string]$Resources,
    [ValidateSet('auto', 'vapor', 'vdom')]
    [string]$Mode = 'auto',
    [ValidateSet('release', 'debug')]
    [string]$Variant = 'release',
    [string]$PackageName,
    [string]$AppName,
    [string]$Abi,
    [int]$VersionCode = 0,
    [string]$VersionName,
    # 应用图标源文件（默认自动找 <项目>\static\logo.png）
    [string]$Icon,
    [switch]$Install,
    [switch]$Clean,
    # 导出资源比源码旧时不再询问，直接继续
    [switch]$Force
)

# 不用 Stop：PowerShell 5.1 会把 native 程序写到 stderr 的正常输出当成终止错误
$ErrorActionPreference = 'Continue'


$Root      = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
$OutputDir = Join-Path $Root 'output'
$LogDir    = Join-Path $Root 'logs'
# 原生工程必须放在纯英文路径：Android Gradle Plugin 不接受含中文的工程路径
$NativeRoot = Join-Path $Root 'native'

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Info($msg) { Write-Host "    $msg" -ForegroundColor Gray }
function Write-Warn2($msg){ Write-Host "    [!] $msg" -ForegroundColor Yellow }
function Fail($msg)       { Write-Host "`n[X] $msg" -ForegroundColor Red; exit 1 }
function Write-Utf8NoBom($Path, $Text) {
    # 必须写「无 BOM」的 UTF-8：Gradle 的 Groovy 解析器遇到 BOM 会报 Unexpected character
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false)))
}

function Set-AppIcon {
    param([string]$ProjDir, [string]$IconPath)
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile($IconPath)
    $sizes = [ordered]@{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
    foreach ($k in $sizes.Keys) {
        $px  = $sizes[$k]
        $dir = Join-Path $ProjDir "app\src\main\res\mipmap-$k"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null

        $scale = [Math]::Min($px / $img.Width, $px / $img.Height)
        $w = [int]($img.Width * $scale); $h = [int]($img.Height * $scale)
        $x = [int](($px - $w) / 2); $y = [int](($px - $h) / 2)

        # 方形图标
        $bmp = New-Object System.Drawing.Bitmap $px, $px
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.Clear([System.Drawing.Color]::Transparent)
        $g.DrawImage($img, $x, $y, $w, $h)
        $g.Dispose()
        $bmp.Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()

        # 圆形图标（部分桌面用 roundIcon）
        $bmp2 = New-Object System.Drawing.Bitmap $px, $px
        $g2 = [System.Drawing.Graphics]::FromImage($bmp2)
        $g2.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g2.Clear([System.Drawing.Color]::Transparent)
        $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
        $clip.AddEllipse(0, 0, $px - 1, $px - 1)
        $g2.SetClip($clip)
        $g2.DrawImage($img, $x, $y, $w, $h)
        $g2.ResetClip(); $g2.Dispose()
        $bmp2.Save((Join-Path $dir 'ic_launcher_round.png'), [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp2.Dispose(); $clip.Dispose()
    }

    # 电视（Leanback）桌面用的大 banner：320x180 比例，深色底 + logo + 应用名（字号自适应，避免被裁）
    $name = if ($AppName) { $AppName } else { 'uni-app x' }
    $banners = [ordered]@{ 'mdpi' = @(320,180); 'hdpi' = @(480,270); 'xhdpi' = @(640,360); 'xxhdpi' = @(960,540) }
    foreach ($bk in $banners.Keys) {
        $W = $banners[$bk][0]; $H = $banners[$bk][1]
        $bdir = Join-Path $ProjDir "app\src\main\res\drawable-$bk"
        New-Item -ItemType Directory -Force -Path $bdir | Out-Null
        $bb = New-Object System.Drawing.Bitmap $W, $H
        $bg = [System.Drawing.Graphics]::FromImage($bb)
        $bg.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $bg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $bg.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $bg.Clear([System.Drawing.Color]::FromArgb(255, 14, 17, 22))

        # logo：高度占 56%，靠左
        $lh = [int]($H * 0.56)
        $lw = [int]($lh * $img.Width / $img.Height)
        $lx = [int]($W * 0.05)
        $ly = [int](($H - $lh) / 2)
        $bg.DrawImage($img, $lx, $ly, $lw, $lh)

        # 应用名：从 logo 右边开始，自动缩小字号直到放得下
        $tx    = $lx + $lw + [int]($W * 0.05)
        $avail = $W - $tx - [int]($W * 0.04)
        $fs    = [single]($H * 0.24)
        $font  = New-Object System.Drawing.Font('Microsoft YaHei UI, Arial', $fs, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
        $brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 240, 244, 250))
        while ($fs -gt 10 -and $bg.MeasureString($name, $font).Width -gt $avail) {
            $font.Dispose()
            $fs -= [single]1
            $font = New-Object System.Drawing.Font('Microsoft YaHei UI, Arial', $fs, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
        }
        $sz = $bg.MeasureString($name, $font)
        $ty = [int](($H - $sz.Height) / 2)
        $bg.DrawString($name, $font, $brush, [single]$tx, [single]$ty)
        $brush.Dispose(); $font.Dispose(); $bg.Dispose()
        $bb.Save((Join-Path $bdir 'app_banner.png'), [System.Drawing.Imaging.ImageFormat]::Png)
        $bb.Dispose()
    }
    $img.Dispose()
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# ---------- 0.0 选项目：双击时用上次的项目，没有就弹文件夹选择框 ----------
$StateFile = Join-Path $Root '.lastproject'
if (-not $ProjectPath) {
    $lastProj = ''
    if (Test-Path $StateFile) { $lastProj = (Get-Content $StateFile -Raw -Encoding UTF8).Trim() }

    if ($lastProj -and (Test-Path $lastProj)) {
        $ProjectPath = $lastProj
        Write-Host ''
        Write-Info "沿用上次的项目：$ProjectPath"
        Write-Info '（要换项目：把新的项目文件夹拖到桌面快捷方式 / 打包.bat 上）'
    } else {
        try {
            Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
            $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
            $dlg.Description = '选择 HBuilderX 的 uni-app x 项目文件夹（里面要有 manifest.json）'
            $dlg.ShowNewFolderButton = $false
            if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { $ProjectPath = $dlg.SelectedPath }
        } catch {
            Write-Warn2 "弹不出选择框：$($_.Exception.Message)"
        }
    }
}

if (-not $ProjectPath) { Fail '没有项目路径。用法：把项目文件夹拖到 打包.bat 上，或再次双击后选择文件夹。' }
Write-Utf8NoBom $StateFile $ProjectPath   # 记住这次的项目（UTF-8，中文路径不会变问号）
# ---------- 0. 环境检查 ----------
Write-Step '检查编译环境'

$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
$jdkCandidates += @((Join-Path $Root 'tools\jdk-21'))
$jdk = $null
foreach ($c in $jdkCandidates) {
    # 直接读 JDK 自带的 release 文件判断版本，避免调用 java.exe（它往 stderr 写版本号，会干扰脚本）
    $rel = Join-Path $c 'release'
    if ((Test-Path (Join-Path $c 'bin\javac.exe')) -and (Test-Path $rel)) {
        $relTxt = Get-Content $rel -Raw
        if ($relTxt -match 'JAVA_VERSION="(\d+)') {
            if ([int]$Matches[1] -ge 21) { $jdk = $c; break }
        }
    }
}
if (-not $jdk) { Fail '没找到 JDK 21+。uni-app x 离线 SDK 的 Gradle 插件需要 JDK 21（JDK 17 会报 UnsupportedClassVersionError）。请先安装 JDK 21，或设置 JAVA_HOME 指向 JDK 21。' }
$env:JAVA_HOME = $jdk
Write-Info "JDK: $jdk"

$sdkDir = Join-Path $Root 'tools\android-sdk'
if (-not (Test-Path $sdkDir) -and $env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { $sdkDir = $env:ANDROID_HOME }
if (Test-Path $sdkDir) {
    $env:ANDROID_HOME = $sdkDir
    $env:ANDROID_SDK_ROOT = $sdkDir
    Write-Info "Android SDK: $sdkDir"
} else {
    Fail '没找到 Android SDK。请先运行本目录下的 setup.ps1 安装环境（或设置 ANDROID_HOME）。'
}

# ---------- 1. 定位项目 ----------
Write-Step '读取 HBuilderX 项目'

$ProjectPath = $ProjectPath.Trim('"').TrimEnd('\')
if (-not (Test-Path $ProjectPath)) { Fail "项目目录不存在：$ProjectPath" }
$ProjectPath = (Resolve-Path $ProjectPath).Path

$projManifestFile = Join-Path $ProjectPath 'manifest.json'
if (-not (Test-Path $projManifestFile)) { Fail "这不是一个 uni-app/uni-app x 项目（找不到 manifest.json）：$ProjectPath" }
$projManifest = Get-Content $projManifestFile -Raw -Encoding UTF8 | ConvertFrom-Json

$projAppId = $projManifest.appid          # uni-app x 项目的真实 appid 在 appid 字段
if (-not $projAppId) { $projAppId = $projManifest.id }
Write-Info "项目：$ProjectPath"
if ($projAppId) { Write-Info "appid（项目 manifest）：$projAppId" }

# ---------- 2. 找导出资源 ----------
Write-Step '查找本地打包资源'

function Test-AppContent($dir) {
    if (-not (Test-Path $dir)) { return $false }
    return (Test-Path (Join-Path $dir 'www'))
}

$contentRoot = $null
$resourceRoot = $null

if ($Resources) {
    $cand = $Resources.Trim('"')
    if (Test-AppContent $cand) { $contentRoot = $cand; $resourceRoot = Split-Path $cand -Parent }
    elseif ((Test-Path $cand) -and (Get-ChildItem $cand -Directory | Where-Object { Test-AppContent $_.FullName })) {
        $contentRoot = (Get-ChildItem $cand -Directory | Where-Object { Test-AppContent $_.FullName } | Select-Object -First 1).FullName
        $resourceRoot = $cand
    }
}

if (-not $contentRoot) {
    $searchDirs = @(
        (Join-Path $ProjectPath 'unpackage\resource\app-android'),
        (Join-Path $ProjectPath 'unpackage\resources\app-android'),
        (Join-Path $ProjectPath 'unpackage\resource'),
        (Join-Path $ProjectPath 'unpackage\resources')
    ) | Where-Object { Test-Path $_ }

    foreach ($sd in $searchDirs) {
        if (Test-AppContent $sd) { $contentRoot = $sd; $resourceRoot = $sd; break }
        $hit = Get-ChildItem $sd -Directory -ErrorAction SilentlyContinue | Where-Object { Test-AppContent $_.FullName } | Select-Object -First 1
        if ($hit) { $contentRoot = $hit.FullName; $resourceRoot = $sd; break }
    }
}

if (-not $contentRoot) {
    Write-Host ''
    Fail @"
没找到打包资源（目录里要有 www）。

请先在 HBuilderX 里操作：
    菜单：发行 → 原生App-本地打包 → 生成本地打包App资源
然后确认 <项目>\unpackage\resource\app-android\ 下出现了 www 目录，再运行本脚本。

如果你已经把资源导出到别的地方，用 -Resources <目录> 指定。
"@
}
Write-Info "资源目录：$contentRoot"

# 导出的 appid 目录名最可信（__UNI__xxxx）；如果目录名就是 appid，直接采用
$contentLeaf = Split-Path $contentRoot -Leaf
if ($contentLeaf -like '__UNI__*') { $projAppId = $contentLeaf }

# 导出结构有两种：
#   A) app-android\{uniappx, uni_modules, __UNI__xxx{www}}   ← 5.34 实际结构
#   B) app-android\__UNI__xxx\{www, uniappx, ...}
# 所以资源根（放 uniappx / uni_modules 的地方）可能是 contentRoot，也可能是它的上一层
$resourceRoot = $contentRoot
$parentDir = Split-Path $contentRoot -Parent
if ($parentDir -and ((Test-Path (Join-Path $parentDir 'uniappx')) -or (Test-Path (Join-Path $parentDir 'uni_modules')))) {
    $resourceRoot = $parentDir
}

# ---------- 2.1 检查导出资源是否比源码旧 ----------
# 脚本不编译 uni-app x（uvue/uts → kt/www 只能由 HBuilderX 做），
# 源码改过却忘了重新导出时，打出来的会是旧版本 —— 这里主动拦住。
$skipDirs = '\\(unpackage|node_modules|\.git|\.hbuilderx|\.idea|\.codebuddy|dist)\\|\\logs\\'
try {
    $srcFiles = Get-ChildItem $ProjectPath -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch $skipDirs -and @('.uvue', '.uts', '.ts', '.js', '.vue', '.json') -contains $_.Extension }
    $newestSrc = $srcFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 1

    $exportFiles = Get-ChildItem $resourceRoot -Recurse -File -ErrorAction SilentlyContinue
    $newestExport = $exportFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if ($newestSrc -and $newestExport -and ($newestSrc.LastWriteTime -gt $newestExport.LastWriteTime.AddSeconds(2))) {
        Write-Host ''
        Write-Warn2 '导出资源比源码旧！这样打出来的是改动之前的版本。'
        Write-Warn2 ("  最新源码：{0}  ({1:yyyy-MM-dd HH:mm})" -f ($newestSrc.FullName -replace [regex]::Escape($ProjectPath), ''), $newestSrc.LastWriteTime)
        Write-Warn2 ("  导出资源：{0:yyyy-MM-dd HH:mm}" -f $newestExport.LastWriteTime)
        Write-Warn2 '  请回 HBuilderX：发行 → 原生App-本地打包 → 生成本地打包App资源'
        Write-Host ''
        if (-not $Force) {
            $ans = ''
            try { $ans = Read-Host '  仍要继续打包吗？(y = 继续，其它 = 退出)' } catch { }
            # 注意：非交互环境下 Read-Host 返回 $null，而 $null -notmatch 会返回空数组（if 判假），必须转成字符串
            $ans = [string]$ans
            if ($ans -notmatch '^[yY]') { Fail '已取消。请先在 HBuilderX 里重新导出资源，或用 -Force 强制继续。' }
        }
    } else {
        Write-Info '资源不旧于源码'
    }
} catch {
    Write-Info "跳过资源新旧检查：$($_.Exception.Message)"
}

# 资源里的 manifest.json：可能在 contentRoot 下，也可能在 www 下
$resManifest = $null
foreach ($cand in @((Join-Path $contentRoot 'manifest.json'), (Join-Path $contentRoot 'www\manifest.json'))) {
    if (Test-Path $cand) {
        try { $resManifest = Get-Content $cand -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
        if ($resManifest) { break }
    }
}

if (-not $projAppId) { Fail '拿不到 appid：请确认项目 manifest.json 里有 appid 字段。' }
Write-Ok "appid：$projAppId"

# ---------- 3. 判断渲染模式 ----------
Write-Step '判断渲染模式'

$detected = $null
$vaporMark = Get-ChildItem $resourceRoot -Filter 'uni-app-x-framework-vapor*' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if ($vaporMark) { $detected = 'vapor' }
elseif (Test-Path (Join-Path $resourceRoot 'uniappx\app-android\src')) { $detected = 'vdom' }
elseif (Test-Path (Join-Path $resourceRoot 'uniappx')) { $detected = 'vdom' }

if ($Mode -eq 'auto') {
    if (-not $detected) {
        # 退一步看项目 manifest.json
        $raw = Get-Content $projManifestFile -Raw -Encoding UTF8
        if ($raw -match '"vapor"\s*:\s*true') { $detected = 'vapor' } else { $detected = 'vdom' }
        Write-Warn2 '资源特征不明显，按 manifest.json 推断'
    }
    $Mode = $detected
}
Write-Ok "渲染模式：$Mode"

$projDir = Join-Path $NativeRoot $Mode
if (-not (Test-Path $projDir)) { Fail "缺少原生工程目录：$projDir" }
$nativeManifest = Join-Path $projDir 'uniappx\src\main\AndroidManifest.xml'
$gradleProps    = Join-Path $projDir 'gradle.properties'
if (-not (Test-Path $nativeManifest)) { Fail "原生工程不完整（缺 uniappx\src\main\AndroidManifest.xml）：$projDir" }

# ---------- 4. 包名 / 应用名 / 版本 ----------
Write-Step '写入包名、应用名、版本号'

if (-not $PackageName) {
    # uni-app x 正确字段是 app-android.distribute.packagename；
    # 这里把常见写法都兼容一下（有人写成 package / app.distribute.android.packagename）
    $candidates = @(
        @{ v = $projManifest.'app-android'.distribute.packagename },
        @{ v = $projManifest.'app-android'.distribute.package },
        @{ v = $projManifest.app.distribute.android.packagename },
        @{ v = $projManifest.app.distribute.android.package },
        @{ v = $resManifest.'app-android'.distribute.packagename },
        @{ v = $resManifest.'app-android'.distribute.package }
    )
    foreach ($c in $candidates) {
        if (-not $PackageName -and $c.v -and "$($c.v)".Trim()) { $PackageName = "$($c.v)".Trim() }
    }
    if ($PackageName) { Write-Info "包名来自 manifest.json：$PackageName" }
}
# 包名记忆文件：记住上次用过的包名，下次不带 -PackageName 也不会退回 com.example.xxx
$PkgStateFile = Join-Path $Root '.lastpkg'
if (-not $PackageName) {
    if (Test-Path $PkgStateFile) {
        $prevPkg = (Get-Content $PkgStateFile -Raw -Encoding UTF8).Trim()
        if ($prevPkg) {
            $PackageName = $prevPkg
            Write-Info "沿用上次的包名：$PackageName（要改：-PackageName com.xxx.yyy，或写进 manifest.json）"
        }
    }
}
if (-not $PackageName) {
    $PackageName = 'com.example.' + ($projAppId -replace '[^A-Za-z0-9]', '' ).ToLower()
    Write-Warn2 "manifest.json 里没配安卓包名，临时用：$PackageName"
    Write-Warn2 '建议立刻用 -PackageName 指定正式包名（发布后不能改），指定一次之后就会被记住'
}

if (-not $AppName) { $AppName = $projManifest.name }
if (-not $AppName) { $AppName = 'uni-app x' }

if (-not $VersionName) {
    if ($resManifest -and $resManifest.version -and $resManifest.version.name) { $VersionName = $resManifest.version.name }
    elseif ($resManifest -and $resManifest.versionName) { $VersionName = $resManifest.versionName }
    elseif ($projManifest.versionName) { $VersionName = $projManifest.versionName }
    else { $VersionName = '1.0.0' }
}
if ($VersionCode -le 0) {
    if ($resManifest -and $resManifest.version -and $resManifest.version.code) { $VersionCode = [int]$resManifest.version.code }
    elseif ($resManifest -and $resManifest.versionCode) { $VersionCode = [int]$resManifest.versionCode }
    elseif ($projManifest.versionCode) { $VersionCode = [int]$projManifest.versionCode }
    else { $VersionCode = 1 }
}

Write-Utf8NoBom $PkgStateFile $PackageName
Write-Info "包名：$PackageName"
Write-Info "应用名：$AppName"
Write-Info "版本：$VersionName ($VersionCode)"

# ---------- 5. 拷贝资源 ----------
Write-Step '拷贝打包资源到原生工程'

# 每次都清空 apps 目录：否则上次打包的旧 appid 会残留在 APK 里
$appsDir = Join-Path $projDir 'uniappx\src\main\assets\apps'
if (Test-Path $appsDir) { Remove-Item $appsDir -Recurse -Force }
$assetsAppDir = Join-Path $appsDir $projAppId
New-Item -ItemType Directory -Force -Path $assetsAppDir | Out-Null
Copy-Item (Join-Path $contentRoot '*') $assetsAppDir -Recurse -Force
if (-not (Test-Path (Join-Path $assetsAppDir 'www'))) { Fail "资源拷贝失败：$assetsAppDir 下没有 www" }
Write-Ok "www 等资源 → uniappx\src\main\assets\apps\$projAppId"

# VDOM 模式：导出的 kotlin 源码必须拷进 src/main/java
if ($Mode -eq 'vdom') {
    $ktSrc = Join-Path $resourceRoot 'uniappx\app-android\src'
    $dstJava = Join-Path $projDir 'uniappx\src\main\java'
    if (Test-Path $dstJava) { Remove-Item $dstJava -Recurse -Force }   # 清掉上次项目的旧源码
    if (Test-Path $ktSrc) {
        New-Item -ItemType Directory -Force -Path $dstJava | Out-Null
        Copy-Item (Join-Path $ktSrc '*') $dstJava -Recurse -Force
        Write-Ok "kotlin 源码 → uniappx\src\main\java（$((Get-ChildItem $dstJava -Recurse -Filter *.kt).Count) 个文件）"
    } else {
        Write-Warn2 'VDOM 模式下没找到 uniappx\app-android\src（导出版本较新可能不需要），跳过'
    }
}

# ---------- 5.5 处理 UTS 插件 ----------
Write-Step '处理 UTS 插件'

# 从导出资源的 manifest.json 读安卓侧配置（minSdk / targetSdk / ABI / 权限）
$dist = $null
if ($resManifest -and $resManifest.'app-android' -and $resManifest.'app-android'.distribute) {
    $dist = $resManifest.'app-android'.distribute
}
$minSdk    = 24
$targetSdk = 36
if ($dist) {
    if ($dist.minSdkVersion)    { $minSdk = [int]$dist.minSdkVersion }
    if ($dist.targetSdkVersion) { $targetSdk = [int]$dist.targetSdkVersion }
}
if (-not $Abi -and $dist -and $dist.abiFilters) {
    $Abi = (@($dist.abiFilters) -join ',').ToLower()
    Write-Info "ABI（来自 manifest.json）：$Abi"
}

function Add-GradleDependency {
    param([string]$File, [string]$DepLine)
    $t = Get-Content $File -Raw -Encoding UTF8
    if ($t -match [regex]::Escape($DepLine)) { return }
    $rx = [regex]'(?m)^dependencies\s*\{'
    $t = $rx.Replace($t, ("dependencies {`r`n    " + $DepLine), 1)
    Write-Utf8NoBom $File $t
}

function Add-SettingsInclude {
    param([string]$File, [string]$ModuleName)
    $t = Get-Content $File -Raw -Encoding UTF8
    if ($t -match [regex]::Escape("':$ModuleName'")) { return }
    $t = $t.TrimEnd() + "`r`ninclude ':$ModuleName'`r`n"
    Write-Utf8NoBom $File $t
}

$utsRoot = Join-Path $resourceRoot 'uni_modules'
$utsPlugins = @()
if (Test-Path $utsRoot) {
    $utsPlugins = @(Get-ChildItem $utsRoot -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'utssdk\app-android') })
}

$settingsFile = Join-Path $projDir 'settings.gradle'

# 清掉上次打包残留、这次不再需要的 uts-* 模块，以及它们在 settings / build.gradle 里的引用
$wanted = @($utsPlugins | ForEach-Object { 'uts-' + ($_.Name -Replace '[^A-Za-z0-9]', '-').ToLower() })
$stale = @(Get-ChildItem $projDir -Directory -Filter 'uts-*' | Where-Object { $wanted -notcontains $_.Name })
foreach ($s in $stale) {
    Remove-Item $s.FullName -Recurse -Force
    foreach ($gf in @($settingsFile, (Join-Path $projDir 'app\build.gradle'), (Join-Path $projDir 'uniappx\build.gradle'))) {
        $gt = Get-Content $gf -Raw -Encoding UTF8
        $gt = [regex]::Replace($gt, "(?m)^\s*include ':$($s.Name)'\s*`r?`n", '')
        $gt = [regex]::Replace($gt, "(?m)^\s*implementation project\(':$($s.Name)'\)\s*`r?`n", '')
        Write-Utf8NoBom $gf $gt
    }
    Write-Warn2 "清掉过期 UTS 模块：$($s.Name)"
}

if ($utsPlugins.Count -eq 0) {
    Write-Info '本次没有 UTS 插件需要处理'
} else {
    foreach ($p in $utsPlugins) {
        $pluginDir = Join-Path $p.FullName 'utssdk\app-android'
        $modName   = 'uts-' + ($p.Name -Replace '[^A-Za-z0-9]', '-').ToLower()
        $ns        = 'io.dcloud.uts.' + ($p.Name -Replace '[^A-Za-z0-9]', '').ToLower()
        $modDir    = Join-Path $projDir $modName

        # config.json：只处理 minSdkVersion，其它（components/hooks/dependencies）需要手工确认
        $cfg = $null
        $cfgFile = Join-Path $pluginDir 'config.json'
        if (Test-Path $cfgFile) { try { $cfg = Get-Content $cfgFile -Raw -Encoding UTF8 | ConvertFrom-Json } catch { } }
        $modMinSdk = if ($cfg -and $cfg.minSdkVersion) { [int]$cfg.minSdkVersion } else { 21 }
        if ($cfg) {
            if ($cfg.components)  { Write-Warn2 "$($p.Name) 声明了 components，需要在 app\build.gradle 手工加 UTSRegisterComponents" }
            if ($cfg.hooksClass)  { Write-Warn2 "$($cfg.hooksClass) 声明了 hooksClass，需要手工加 UTSHooksClassArray" }
            if ($cfg.dependencies){ Write-Warn2 "$($p.Name) 声明了额外依赖，需要在 $modName\build.gradle 手工补 " }
        }

        if (Test-Path $modDir) { Remove-Item $modDir -Recurse -Force }
        New-Item -ItemType Directory -Force (Join-Path $modDir 'src\main\java'), (Join-Path $modDir 'libs') | Out-Null

        # kotlin 源码
        $srcDir = Join-Path $pluginDir 'src'
        if (Test-Path $srcDir) {
            Copy-Item (Join-Path $srcDir '*') (Join-Path $modDir 'src\main\java') -Recurse -Force
        }
        # 插件的 AndroidManifest（去掉 package 属性，交给 namespace）
        $pmf = Join-Path $pluginDir 'AndroidManifest.xml'
        if (Test-Path $pmf) {
            $pt = Get-Content $pmf -Raw -Encoding UTF8
            $pt = [regex]::Replace($pt, '\spackage\s*=\s*"[^"]*"', '')
            Write-Utf8NoBom (Join-Path $modDir 'src\main\AndroidManifest.xml') $pt
        }
        # 插件本地 libs
        $plibs = Join-Path $pluginDir 'libs'
        if (Test-Path $plibs) { Copy-Item (Join-Path $plibs '*') (Join-Path $modDir 'libs') -Recurse -Force }
        # assets / res
        foreach ($sub in @('assets', 'res')) {
            $s = Join-Path $pluginDir $sub
            if (Test-Path $s) {
                New-Item -ItemType Directory -Force (Join-Path $modDir "src\main\$sub") | Out-Null
                Copy-Item (Join-Path $s '*') (Join-Path $modDir "src\main\$sub") -Recurse -Force
            }
        }

        if ($Mode -eq 'vapor') {
            $kotlinAliasLine = '    alias(libs.plugins.kotlin.android)'
            $autoRegister = "`r`n    id 'io.dcloud.uts.auto-register'"
            $javaVer = '17'
            $jvmTarget = "'17'"
        } else {
            $kotlinAliasLine = '    alias(libs.plugins.jetbrains.kotlin.android)'
            $autoRegister = ''
            $javaVer = '1_8'
            $jvmTarget = "'1.8'"
        }

        $modGradle = @"
plugins {
    alias(libs.plugins.android.library)
$kotlinAliasLine
    id 'io.dcloud.uts.kotlin'$autoRegister
}

android {
    namespace '$ns'
    compileSdk 36

    defaultConfig {
        minSdk $modMinSdk
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_$javaVer
        targetCompatibility JavaVersion.VERSION_$javaVer
    }
    kotlinOptions {
        jvmTarget = $jvmTarget
    }
}

dependencies {
    compileOnly fileTree(include: ['*.aar'], dir: '../uniappx/libs')
    compileOnly fileTree(include: ['*.aar', '*.jar'], dir: 'libs')
    compileOnly "com.alibaba:fastjson:1.2.83"
    compileOnly "androidx.core:core-ktx:1.10.1"
    compileOnly "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3"
    compileOnly "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
}
"@
        Write-Utf8NoBom (Join-Path $modDir 'build.gradle') $modGradle

        Add-SettingsInclude -File $settingsFile -ModuleName $modName
        Add-GradleDependency -File (Join-Path $projDir 'app\build.gradle')     -DepLine "implementation project(':$modName')"
        Add-GradleDependency -File (Join-Path $projDir 'uniappx\build.gradle') -DepLine "implementation project(':$modName')"
        Write-Ok "UTS 插件 $($p.Name) → 模块 :$modName"
    }
}

# ---------- 5.6 生成 app 的 AndroidManifest（权限 + 电视 Leanback 入口） ----------
Write-Step '生成 app 的 AndroidManifest'

$permXml = ''
if ($dist -and $dist.permissions) {
    foreach ($p in @($dist.permissions)) { $permXml += "    $p`r`n" }
}

$leanback = $false
$nrManifest = Join-Path $ProjectPath 'nativeResources\android\AndroidManifest.xml'
if ((Test-Path $nrManifest) -and ((Get-Content $nrManifest -Raw -Encoding UTF8) -match 'LEANBACK_LAUNCHER')) { $leanback = $true }

$activityClass = if ($Mode -eq 'vapor') { 'io.dcloud.uniappxv.UniAppActivity' } else { 'io.dcloud.uniapp.UniAppActivity' }
$leanbackXml = ''
if ($leanback) {
    $leanbackXml = @"

    <!-- 电视（Leanback）入口：项目的 nativeResources\android\AndroidManifest.xml 里声明了 LEANBACK_LAUNCHER，这里合并进来 -->
    <uses-feature android:name="android.software.leanback" android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
"@
}
$leanbackActivity = ''
if ($leanback) {
    $leanbackActivity = @"

        <activity
            android:name="$activityClass"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
"@
}

$appManifestXml = @"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
$leanbackXml
$permXml
    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:banner="@drawable/app_banner"
        android:theme="@style/Theme.AppCompat.DayNight"
        android:extractNativeLibs="false"
        android:usesCleartextTraffic="true"
        tools:replace="android:allowBackup,android:theme,android:icon,android:label">
$leanbackActivity
    </application>

</manifest>
"@
$appManifestFile = Join-Path $projDir 'app\src\main\AndroidManifest.xml'
Write-Utf8NoBom $appManifestFile $appManifestXml
Write-Ok ("权限 " + $(if ($permXml) { '已写入' } else { '无' }) + "；Leanback 电视入口 " + $(if ($leanback) { '已合并' } else { '无' }))

# 按 manifest.json 调整 minSdk / targetSdk
$appGradleFile = Join-Path $projDir 'app\build.gradle'
$ag = Get-Content $appGradleFile -Raw -Encoding UTF8
$ag = [regex]::Replace($ag, '(?m)^(\s*)minSdk\s+\d+',    ('${1}minSdk ' + $minSdk))
$ag = [regex]::Replace($ag, '(?m)^(\s*)targetSdk\s+\d+', ('${1}targetSdk ' + $targetSdk))
Write-Utf8NoBom $appGradleFile $ag
Write-Info "minSdk=$minSdk targetSdk=$targetSdk"

# ---------- 6. 改 appid ----------
Write-Step '写入 appid'

$mf = Get-Content $nativeManifest -Raw -Encoding UTF8
$mf = [regex]::Replace($mf, '(DCLOUD_UNI_APPID"\s*[\r\n\s]*android:value=")[^"]*(")', ('${1}' + $projAppId + '${2}'))
Write-Utf8NoBom $nativeManifest $mf
if (-not ((Get-Content $nativeManifest -Raw) -match [regex]::Escape($projAppId))) { Fail 'appid 写入失败，请检查 AndroidManifest.xml' }
Write-Ok "DCLOUD_UNI_APPID = $projAppId"

# ---------- 7. 改 gradle.properties + 应用名 ----------
$gp = Get-Content $gradleProps -Raw -Encoding UTF8
$gp = [regex]::Replace($gp, '(?m)^appPackage=.*$',     "appPackage=$PackageName")
$gp = [regex]::Replace($gp, '(?m)^appVersionCode=.*$', "appVersionCode=$VersionCode")
$gp = [regex]::Replace($gp, '(?m)^appVersionName=.*$', "appVersionName=$VersionName")
if ($Abi) { $gp = [regex]::Replace($gp, '(?m)^abiFilters=.*$', "abiFilters=$Abi") }
Write-Utf8NoBom $gradleProps $gp
Write-Ok 'gradle.properties 已更新'

# 应用名写进 XML 字符串资源（XML 是 UTF-8，中文不会乱码；gradle.properties 是 ISO-8859-1，写中文会乱）
$stringsFile = Join-Path $projDir 'app\src\main\res\values\strings.xml'
New-Item -ItemType Directory -Force (Split-Path $stringsFile -Parent) | Out-Null
$escName = [System.Security.SecurityElement]::Escape($AppName)
$stringsXml = @"
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 应用名称：由「打包.ps1」按 manifest.json 的 name 自动写入，也可以直接改这里 -->
    <string name="app_name">$escName</string>
</resources>
"@
[IO.File]::WriteAllText($stringsFile, $stringsXml, (New-Object Text.UTF8Encoding($false)))
Write-Ok "应用名 = $AppName"

# ---------- 7.5 应用图标（桌面图标） ----------
Write-Step '生成应用图标'

$iconCandidates = @()
if ($Icon) { $iconCandidates += $Icon }
$iconCandidates += @(
    (Join-Path $ProjectPath 'static\logo.png'),
    (Join-Path $ProjectPath 'static\logo-512.png'),
    (Join-Path $ProjectPath 'static\logo-256.png'),
    (Join-Path $ProjectPath 'static\logo-128.png')
)
$iconFile = $iconCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if ($iconFile) {
    try {
        Set-AppIcon -ProjDir $projDir -IconPath $iconFile
        Write-Ok "图标来源：$iconFile"
    } catch {
        Write-Warn2 "生成图标失败：$($_.Exception.Message)"
    }
} else {
    Write-Warn2 '没找到 static\logo.png，沿用工程里现有图标（可用 -Icon 指定）'
}

# ---------- 8. 签名 ----------
if ($Variant -eq 'release') {
    Write-Step '检查签名证书'
    $ksDir  = Join-Path $projDir 'keystore'
    $ksFile = Join-Path $ksDir 'keystore.properties'
    if (-not (Test-Path $ksFile)) {
        New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
        $storePass = 'uniappx' + (Get-Random -Minimum 100000 -Maximum 999999)
        $keyPass   = $storePass
        $alias     = 'uniappx'
        $dname     = 'CN=uni-app x, OU=dev, O=dev, L=Beijing, ST=Beijing, C=CN'
        & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -v `
            -keystore (Join-Path $ksDir 'release.jks') -alias $alias `
            -keyalg RSA -keysize 2048 -validity 36500 `
            -storepass $storePass -keypass $keyPass -dname $dname 2>&1 | Out-Null
        if (-not (Test-Path (Join-Path $ksDir 'release.jks'))) { Fail '生成签名证书失败（keytool 报错）' }
        @"
storeFile=keystore/release.jks
storePassword=$storePass
keyAlias=$alias
keyPassword=$keyPass
"@ | Set-Content -Path $ksFile -Encoding ASCII
        Write-Ok "已生成签名证书 keystore\release.jks（密码存在 keystore\keystore.properties，请自己备份，丢了无法覆盖升级）"
    } else {
        Write-Ok '使用已有签名 keystore\keystore.properties'
    }
}

# ---------- 9. 编译 ----------
Write-Step "开始 Gradle 编译（$Variant）"

# 工程路径含非 ASCII（例如整个工具包放在中文目录里）时，AGP 会直接拒绝。
# 这里把工程镜像到一个纯 ASCII 的临时目录再编译，编完把 APK 拷回项目的 apk\。
$buildDir = $projDir
if ($projDir -match '[^\x00-\x7F]') {
    $stageCandidates = @(
        (Join-Path $env:TEMP "uniappx-build\$Mode"),
        (Join-Path $env:PUBLIC "uniappx-build\$Mode")
    ) | Where-Object { $_ -and ($_ -match '^[\x00-\x7F]+$') }
    if (-not $stageCandidates) {
        Fail '工程路径和系统临时目录都含非 ASCII 字符，无法编译。请把 打包脚本 目录移到纯英文路径后重试。'
    }
    $stage = $stageCandidates[0]
    Write-Info "路径含中文，镜像到 ASCII 目录编译：$stage"
    New-Item -ItemType Directory -Force $stage | Out-Null
    robocopy $projDir $stage /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
    $buildDir = $stage
}

$gradleVersion = 'gradle-9.2.1'
if ($Mode -eq 'vdom') { $gradleVersion = 'gradle-8.14.3' }
$localGradle = Join-Path $Root "tools\gradle\$gradleVersion\bin\gradle.bat"
if (Test-Path $localGradle) {
    $gradleExe = $localGradle
    $gradleArgs = @('-p', $buildDir)
} else {
    $gradleExe = Join-Path $projDir 'gradlew.bat'
    $gradleArgs = @()
    Write-Warn2 '没找到本机 Gradle，改用工程自带的 gradlew（首次会自动下载，慢）'
}

$logFile = Join-Path $LogDir ("build-{0}-{1:yyyyMMdd-HHmmss}.log" -f $Mode, (Get-Date))
if ($Clean) {
    & $gradleExe @gradleArgs 'clean' --console=plain 2>&1 | Tee-Object -FilePath $logFile | Out-Null
}

$task = ":app:assemble" + ($Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1))
Write-Info "执行：$task"
& $gradleExe @gradleArgs $task --console=plain --stacktrace 2>&1 | Tee-Object -FilePath $logFile -Append | ForEach-Object {
    $line = "$_"
    if ($line -match '^(BUILD|FAILURE|e: |> Task .*(FAILED))') { Write-Host "    $line" }
}
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host ''
    Write-Host "构建失败，完整日志：$logFile" -ForegroundColor Red
    Write-Host '常见原因：' -ForegroundColor Yellow
    Write-Host '  * 资源没导出 / 导出不完整（先在 HBuilderX 里重新「生成本地打包App资源」）'
    Write-Host '  * 用了 JDK 17（必须 JDK 21）'
    Write-Host '  * 首次编译没联网，依赖下载失败（看日志里的 maven 报错）'

    # 针对性提示：导出代码里的 Kotlin 错误
    $buildLog = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
    if ($buildLog -match 'Unresolved reference') {
        Write-Host ''
        Write-Host '检测到 Unresolved reference（Kotlin 编译不过），通常是这两种原因：' -ForegroundColor Yellow
        Write-Host '  1) 导出资源比源码旧：改完代码没重新导出，导出的 kt 和当前代码不匹配；'
        Write-Host '  2) .uvue 里「先使用、后声明」：被函数引用的 let 变量声明在该函数之后。'
        Write-Host '     uni-app x 生成的 Kotlin 不允许先用后声明 —— 把该变量声明挪到使用它的函数之前'
        Write-Host '     （建议把状态变量统一放在 <script setup> 顶部）。'
        $bad = [regex]::Matches($buildLog, 'Unresolved reference ''([^'']+)''') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
        if ($bad) { Write-Host ('     涉及的符号：' + ($bad -join ', ')) -ForegroundColor Yellow }
    } elseif ($buildLog -match 'mergeDex.*|另一个程序正在使用此文件') {
        Write-Host ''
        Write-Host '检测到文件占用（残留 Gradle/Kotlin daemon）。关掉 java 进程后重跑，或加 -Clean。' -ForegroundColor Yellow
    }
    exit 1
}

# ---------- 10. 输出 APK ----------
Write-Step '收集 APK'

$apkDir = Join-Path $buildDir ("app\build\outputs\apk\{0}" -f $Variant)
$apk = Get-ChildItem $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Fail "构建完成但没找到 APK，检查 $apkDir" }

$safeName = ($AppName -replace '[\\/:*?"<>|]', '_')
$apkName  = ("{0}-{1}-{2:yyyyMMdd-HHmm}.apk" -f $safeName, $Variant, (Get-Date))

# APK 放到 <项目>\apk 目录
$outApk = $null
$destDir = Join-Path $ProjectPath 'apk'
if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Force -Path $destDir | Out-Null }
if (Test-Path $destDir) {
    # 同应用同类型只保留最新一份
    Get-ChildItem $destDir -Filter ("{0}-{1}-*.apk" -f $safeName, $Variant) -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
    $outApk = Join-Path $destDir $apkName
    Copy-Item $apk.FullName $outApk -Force
    if (-not (Test-Path $outApk)) { $outApk = $null }
}

if (-not $outApk) {
    Write-Warn2 '写不进项目 apk 目录，改为放桌面'
    $outApk = Join-Path ([Environment]::GetFolderPath('Desktop')) $apkName
    Copy-Item $apk.FullName $outApk -Force
}

Write-Host ''
Write-Host '====================================================' -ForegroundColor Green
Write-Host " 打包成功：$outApk" -ForegroundColor Green
if ($Variant -eq 'release') { Write-Host ' （正式签名包）' -ForegroundColor Green }
Write-Host (" 大小：{0:N1} MB" -f ((Get-Item $outApk).Length / 1MB)) -ForegroundColor Green
Write-Host '====================================================' -ForegroundColor Green

if ($Install) {
    Write-Step '安装到手机'
    $adb = Join-Path $sdkDir 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { Write-Warn2 '没找到 adb，跳过安装'; exit 0 }
    $devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
    if (-not $devices) { Write-Warn2 '没有检测到已授权调试的手机，跳过安装'; exit 0 }
    & $adb install -r $outApk
    Write-Ok '安装完成'
}
