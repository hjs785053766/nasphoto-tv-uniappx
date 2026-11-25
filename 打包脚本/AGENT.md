# AGENT.md · uni-app x 本地打包（Android）

> 本文件是给 **AI Agent / 新接手的人** 看的操作说明。
> 读完你应该能：从零安装环境 → 把 HBuilderX 导出的资源打成已签名 APK。
>
> **硬性约束（务必遵守）**
> 1. 本目录内所有脚本都用**相对路径**（以脚本自身位置为根），**不要**改写成绝对路径。
> 2. 所有组件都装在**本目录的 `tools\` 下**，不要装到系统目录、不要改系统环境变量。
> 3. Android Gradle Plugin 拒绝**含非 ASCII 的工程路径**。本工具包已自动处理：检测到 `native\` 路径含中文时，会把工程镜像到系统临时目录（`%TEMP%\uniappx-build\<模式>`）编译，编完再把 APK 拷回项目。若 `%TEMP%` 也含中文，会退到 `%PUBLIC%\uniappx-build`；都不可用才报错，届时需把本目录移到纯英文路径。
> 4. **HBuilderX 版本必须与 `tools\uniappx-sdk\` 里的 SDK 版本一致**（当前 5.34）。版本不一致会编译失败或运行异常。

---

## 1. 这是什么

uni-app x 项目支持「云打包」和「本地打包」。本工具包解决**本地打包**里最烦的一段：把 HBuilderX 导出的资源塞进原生 Android 工程、配好 appid/包名/图标/签名、编译出 APK。

**分工**：

| 步骤 | 谁做 | 说明 |
|---|---|---|
| ① 编译 uvue/uts → kotlin + www | **HBuilderX**（只能手动点） | 菜单：发行 → 原生App-本地打包 → 生成本地打包App资源 |
| ② 资源 + 原生工程 → 签名 APK | **本工具包** | 自动化完成 |

HBuilderX 没有提供"导出本地打包资源"的命令行（`cli.exe` 只有云打包），所以第 ① 步无法自动化。

## 2. 目录结构

```
打包脚本\
├── AGENT.md            本文件
├── setup.ps1           环境安装（下载 JDK/SDK/Gradle/uni-app x SDK 并生成 native 工程）
├── setup.bat           双击版 setup
├── 打包.ps1            打包主脚本
├── 打包.bat            双击版打包入口
├── templates\          原生工程模板（vapor / vdom 两套，纯文本）
├── native\             安装后生成：真正参与编译的 Android 工程（vapor / vdom）
├── tools\              安装后生成：jdk-21 / android-sdk / gradle / uniappx-sdk / _downloads
└── logs\               每次编译的日志
```

## 3. 环境要求

- Windows 10/11 x64
- Windows PowerShell 5.1（系统自带）即可
- 磁盘 ≥ 4 GB（tools 约 2.5 GB，native 约 0.2 GB，下载缓存可删）
- 联网（首次安装约下载 1.5 GB；之后可完全离线）
- .NET Framework 4.8（系统自带，脚本用 System.Drawing 生成图标）

## 4. 安装环境（AI 执行这一步）

```powershell
cd <本目录>
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1
```

`setup.ps1` 会依次完成：

1. 下载 **JDK 21**（Corretto）→ `tools\jdk-21`
   > 必须 21：uni-app x 5.34 的 Gradle 插件是 Java 21 编译的，JDK 17 会报 `UnsupportedClassVersionError ... class file version 65.0`
2. 下载 **Android cmdline-tools** → 安装 `platform-tools`、`platforms;android-36`、`build-tools;36.0.0`、`platforms;android-35`、`build-tools;35.0.0` → `tools\android-sdk`
3. 下载 **Gradle 9.2.1**（vapor 用）和 **8.14.3**（vdom 用）→ `tools\gradle`
4. 下载 **uni-app x 原生 SDK**（vapor + vdom）→ `tools\uniappx-sdk\<版本>`
5. 按 `templates\` 生成 `native\vapor` 与 `native\vdom`，并把 SDK 里的 aar、gradle 插件拷进对应工程

**验证安装成功**（应当全部提示"已存在，跳过"）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1
```

检查点：

| 检查项 | 期望 |
|---|---|
| `tools\jdk-21\bin\java.exe` | 存在 |
| `tools\android-sdk\platforms\android-36` | 存在 |
| `tools\gradle\gradle-9.2.1\bin\gradle.bat` | 存在 |
| `tools\uniappx-sdk\5.34\vapor`、`...\vdom` | 存在 |
| `native\vdom\uniappx\libs\*.aar` | 20+ 个 |
| `native\vdom\plugins\*.jar` | 2 个；`native\vapor\plugins\*.jar` 4 个 |

> 换 HBuilderX 版本时：改 `setup.ps1` 顶部的 `$HBuilderXVersion` 和 `$sdkUrls`（URL 在
> <https://doc.dcloud.net.cn/uni-app-x/native/download/android.html> 上，带 `@build号`，每个版本不同）。

## 5. 打包

```powershell
# ① 先在 HBuilderX 里导出资源（这一步无 CLI，必须手动）
#    发行 → 原生App-本地打包 → 生成本地打包App资源
#    产物：<项目>\unpackage\resources\app-android\
# 如果 HBuilderX 正在编辑，导出后继续。

# ② 打包（项目路径可以是绝对路径或相对路径，中文没关系——只是读取）
.\打包.bat "..\..\nasphoto-tv-uniappx"
# 或
powershell -NoProfile -ExecutionPolicy Bypass -File .\打包.ps1 -ProjectPath "..\..\nasphoto-tv-uniappx"
```

产物：**`<项目>\apk\<应用名>-<release|debug>-<年月日-时分>.apk`**（同应用同类型只保留最新一份）。

参数：

| 参数 | 说明 | 默认 |
|---|---|---|
| `-ProjectPath` | HBuilderX 项目目录（含 manifest.json）；不填则沿用上次（`.lastproject`） | — |
| `-Variant` | `release` / `debug` | `release` |
| `-PackageName` | 覆盖包名 | 读 manifest.json 的 `app-android.distribute.packagename` |
| `-AppName` | 覆盖应用名 | 读 manifest.json 的 `name` |
| `-Abi` | CPU 架构 | 读 manifest.json 的 `abiFilters`，否则 `arm64-v8a` |
| `-VersionCode` / `-VersionName` | 覆盖版本 | 读 manifest.json |
| `-Icon` | 图标源文件 | 自动找 `<项目>\static\logo.png` |
| `-Resources` | 手动指定导出资源目录 | 自动找 `unpackage\resource(s)\app-android` |
| `-Mode` | `auto` / `vapor` / `vdom` | `auto` |
| `-Clean` | 编译前 clean | 关 |
| `-Force` | 资源比源码旧时不询问，直接继续 | 关 |
| `-Install` | 打包后 adb 安装 | 关 |

## 6. 脚本做了什么（排错时看）

1. 找 JDK 21（`tools\jdk-21` → 环境变量 `JAVA_HOME`）
2. 读项目 `manifest.json`：`appid`、`name`、`versionName/Code`、`app-android.distribute.packagename`
3. 找导出资源（自动识别 5.34 的 `app-android\{uniappx, uni_modules, __UNI__xxx\www}` 结构）
4. **比对源码与导出资源时间**，资源旧了会警告并拦下（`-Force` 跳过）
5. 判断渲染模式（有 `uni-app-x-framework-vapor` → vapor；有 `uniappx\app-android\src` → vdom）
6. 拷 `www` → `uniappx\src\main\assets\apps\<appid>\`；VDOM 另拷导出的 kotlin → `uniappx\src\main\java\`
7. UTS 插件（`uni_modules\*\utssdk\app-android`）→ 自动生成 `uts-<名字>` 子模块并接入 settings/依赖
8. 生成 app 的 `AndroidManifest.xml`（权限、Leanback 电视入口、banner）
9. 写 appid / 包名 / 版本；应用名写进 `strings.xml`（避免 gradle.properties 的 ISO-8859-1 乱码）
10. 生成图标：`static\logo.png` → 5 套 mipmap（48/72/96/144/192，方形+圆形）+ 4 套 320×180 电视 banner
11. 首次 release 自动生成签名 `native\<模式>\keystore\release.jks`（RSA2048，100 年）
12. Gradle 编译 → APK 复制到 `<项目>\apk\`

## 7. 验收（AI 应做的自检）

```powershell
# 包名 / 版本 / 图标 / banner / ABI / 入口 Activity
& ".\tools\android-sdk\build-tools\36.0.0\aapt2.exe" dump badging "<产出的APK路径>" | Select-String "^package:|^application:|^launchable-activity|^native-code"

# 签名
$env:JAVA_HOME = (Resolve-Path ".\tools\jdk-21").Path
& ".\tools\android-sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs "<产出的APK路径>"
```

期望：`package: name='...'` 与 manifest 一致；`application: ... icon='...' banner='...'` 都有值；`native-code` 含 `arm64-v8a`；`apksigner` 输出 `Signer #1 certificate DN`。

## 8. 故障排查（都是实际踩过的）

| 现象 | 原因 | 处理 |
|---|---|---|
| `UnsupportedClassVersionError ... 65.0` | 用了 JDK 17 | 必须 JDK 21（`tools\jdk-21`） |
| `Your project path contains non-ASCII characters` | 工程路径含中文（`native\` 所在目录带中文） | 把整个 `打包脚本` 目录移到纯英文路径 |
| `Unexpected character: '﻿'`（build.gradle 第 1 行） | 文件被写成带 BOM 的 UTF-8 | 脚本内部统一无 BOM 写入；手改文件后另存为无 BOM |
| `Could not find io.dcloud.uts:auto-register-processor:1.0.0` | vapor 的 `plugins\` 缺 jar | 重跑 `setup.ps1 -Force`（应拷 4 个 jar） |
| `Manifest merger failed ... requires a placeholder substitution` | 引入了推送/广告 aar（带 `${PUSH_APPID}` 占位符） | 只用 `setup.ps1` 里那份精选 aar 清单 |
| `Lint found fatal errors ... ExpiredTargetSdkVersion` | targetSdk 低于 Play 要求 | 离线包不走 Play，模板已关 `lintVital` |
| 运行即崩 `NoClassDefFoundError: com.bumptech.glide.GlideBuilder` | 缺 Glide（uni-app x VDOM 运行时依赖） | 模板已加 `glide:4.16.0` |
| 安装后白屏 | appid 与资源不符 / 没拷 www / 没拷 kt | 看日志里这三步是否都是 `[OK]` |
| `mergeDexRelease ... 文件正被另一程序使用` | 残留 Gradle/Kotlin daemon 占用 | 关掉所有 java 进程后重跑，或加 `-Clean` |
| manifest.json 写了包名但没生效 | 字段名写成 `package`，uni-app x 只认 `packagename` | 改成 `app-android.distribute.packagename` |
| 电视桌面图标/名称不显示 | 缺 Leanback 入口或 banner | 项目需有 `nativeResources\android\AndroidManifest.xml` 声明 `LEANBACK_LAUNCHER`；banner 由脚本自动生成 |

## 9. 给 AI 的操作建议

- **先 `setup.ps1` 再 `打包.ps1`**，不要跳步；`setup.ps1` 幂等，可重复运行。
- 打包前**必须先确认** `<项目>\unpackage\resources\app-android` 存在且比源码新，否则打出来是旧版本。
- `native\` 和 `tools\` 是生成物，可删除重装；`native\<模式>\keystore\` **必须备份**（签名丢了无法覆盖升级已发布的应用）。
- 不要修改 `templates\` 里的 SDK/AGP/Kotlin 版本号，除非 HBuilderX 与 uni-app x SDK 一起升级。
- 不要给脚本加回绝对路径；需要定位本机文件时用 `$Root`（= 脚本所在目录）。
- 如果用户的项目用了额外的 uni 内置模块（地图/推送/广告等），需要往 `native\<模式>\uniappx\libs\` 补对应 `uni-*.aar`，并在 app 的 `build.gradle` 里调整 aar 包含列表。
---

## 10. 自检清单（装好后跑一遍，全部应通过）

```powershell
# 1) 环境是否齐
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1      # 应全部"已存在，跳过"

# 2) 工具链版本
.\tools\jdk-21\bin\java.exe -version                                  # 21.x
.\tools\gradle\gradle-9.2.1\bin\gradle.bat --version                  # 9.2.1

# 3) 打包一次（先用 HBuilderX 导出资源）
.\打包.bat "..\..\nasphoto-tv-uniappx"
```

实测基线（供比对）：

| 项 | 期望值 |
|---|---|
| `setup.ps1` 全量安装耗时 | 约 10~20 分钟（1.5 GB 下载） |
| `tools\` 体积 | 约 2.5 GB（`_downloads` 可删，省 0.7 GB） |
| vdom 首次 release 编译 | 1.5~4 分钟 |
| 产物体积 | 该项目约 17.6 MB |
| APK 校验 | `package: com.grfntv`、`icon`/`banner` 都有值、`native-code: arm64-v8a`、`apksigner verify` 通过 |