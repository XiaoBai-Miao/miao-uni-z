# miao-uni-z 迁移说明 (Migration Guide)

> 本文档与 `FILE_MANIFEST.md`（文件清单）、`pack.sh`（打包脚本）一起构成完整的迁移记录。
> 配套归档：`miao-uni-z-migration-YYYYMMDD.tar.gz`

---

## 1. 项目概述

| 项 | 值 |
| --- | --- |
| 项目名称 | miao-uni-z（小米/车机仪表聚合桌面） |
| 包名 | `com.example.miao` |
| 类型 | **系统签名 App**（`android.uid.system` + platform 签名，需预装或 root 刷入） |
| 当前版本 | `versionName = 1.9.11`，`versionCode = 21` |
| 最新代码 | commit `9f8f323c2cf01bb7f262c3cc069687dcb85b76a8`（master 分支，2026-08-20） |
| 源码语言 | Kotlin（由 AGP 9.x 内置 Kotlin 支持，无需单独 Kotlin Gradle 插件） |
| 构建工具 | Gradle Wrapper（自动下载，无需预装） |

**核心能力**：在车机主屏显示车速/功率/电量/音乐仪表，并通过 `VirtualDisplay` + `IInputForwarder` 将任意 App 内嵌到虚拟屏并转发触摸；v1.9.11 新增"虚拟屏专属布局"——主屏 1920×1080 时启用，虚拟屏固定 1920×720 投放到物理副屏。

---

## 2. 本地环境要求

| 组件 | 版本 / 说明 | 备注 |
| --- | --- | --- |
| **操作系统** | Linux / macOS / Windows（WSL2 亦可） | 构建与 OS 无关 |
| **JDK** | **Temurin JDK 17 已实测通过 CI**；AGP 9.x 官方推荐 **JDK 21** | 本地建议 JDK 21 以避免潜在告警；CI 使用 17 |
| **Android SDK** | Platform `android-37`（compileSdk 37） | 需安装 SDK Platform 37 |
| **Android SDK Build-Tools** | 35.0.0 或更高 | `sdkmanager "build-tools;35.0.0"` |
| **Android SDK Platform-Tools** | 最新 | 提供 `adb` |
| **Gradle** | 由 Wrapper 自动下载 **9.6.1** | 执行 `./gradlew` 首次会自动下载，无需预装 |
| **网络** | 首次构建需访问 Maven / Google 仓库拉取依赖 | 离线环境需预先准备依赖缓存 |

### 环境变量
```bash
export ANDROID_HOME=/path/to/android-sdk   # 或 ANDROID_SDK_ROOT
export JAVA_HOME=/path/to/jdk-21           # 建议 JDK 21
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin
```

---

## 3. 依赖与版本（来自 `gradle/libs.versions.toml`）

| 依赖 | 版本 |
| --- | --- |
| Android Gradle Plugin (AGP) | **9.3.1** |
| Gradle (wrapper) | **9.6.1** |
| core-ktx | 1.10.1 |
| appcompat | 1.6.1 |
| material | 1.10.0 |
| activity-ktx | 1.8.0 |
| constraintlayout | 2.1.4 |
| junit (test) | 4.13.2 |
| espresso-core (androidTest) | 3.5.1 |
| androidx-junit (androidTest) | 1.1.5 |

> **Kotlin 版本**：本工程 `plugins` 仅声明 `alias(libs.plugins.android.application)`，Kotlin 由 AGP 9.3.1 内置支持，编译版本随 AGP 走（Kotlin 2.x）。无需在 `libs.versions.toml` 中单独声明 Kotlin 插件。

---

## 4. 应用关键配置（`app/build.gradle.kts`）

| 配置项 | 值 | 说明 |
| --- | --- | --- |
| `namespace` | `com.example.miao` | |
| `compileSdk` | **37** | 需 Android SDK Platform 37 |
| `minSdk` | **28**（Android 9） | 触摸依赖 `IInputForwarder`（AOSP P 系统级 binder） |
| `targetSdk` | **37** | |
| `versionCode` | **21** | |
| `versionName` | **1.9.11** | |
| `sourceCompatibility` | Java 11 | |
| `release.isMinifyEnabled` | `false` | 未开启混淆 |
| `signingConfig("platform")` | `platform.jks` / 密码 `android` / alias `platform` | 见第 6 节 |

### AndroidManifest 关键声明
- `android:sharedUserId="android.uid.system"` —— 必须以系统用户运行
- 系统级权限：`INJECT_EVENTS`、`ADD_TRUSTED_DISPLAY`、`CAPTURE_VIDEO_OUTPUT`、`MEDIA_CONTENT_CONTROL`、`INTERNAL_SYSTEM_WINDOW`、`SYSTEM_ALERT_WINDOW`、`READ_LOGS`、`FORCE_STOP_PACKAGES`
- 注册为 `HOME` / `LAUNCHER` —— 可作为车机默认桌面
- 内置 `MiaoAccessibilityService` 与 `MainActivity$NotificationListener`

---

## 5. 解包与恢复步骤

```bash
# 1) 将归档解压到本地工作目录
mkdir -p ~/dev && cd ~/dev
tar -xzf miao-uni-z-migration-YYYYMMDD.tar.gz
cd miao-uni-z

# 2) 校验关键文件存在
ls -l app/platform.jks            # 平台签名密钥（必需）
cat gradle/wrapper/gradle-wrapper.properties   # 应显示 gradle-9.6.1

# 3) 赋予 gradlew 执行权限
chmod +x gradlew

# 4) 准备 Android SDK（如尚未安装）
sdkmanager "platforms;android-37" "build-tools;35.0.0" "platform-tools"

# 5) 构建 Release APK（首次会自动下载 Gradle 9.6.1 与全部依赖）
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-release.apk
```

> **关于 .git**：归档已包含全部当前源码/资源/配置，可直接构建运行。如需恢复 Git 历史或协同，执行：
> ```bash
> git remote add origin https://github.com/XiaoBai-Miao/miao-uni-z.git
> git fetch origin && git checkout master   # 用远端历史覆盖本地（注意会替换未提交的本地改动）
> ```
> 当前 remote（含 token，迁移后建议替换为自己的）：
> `https://x-access-token:***@gh-proxy.org/https://github.com/XiaoBai-Miao/miao-uni-z.git`

---

## 6. 签名配置（重要）

App 是**系统签名**，无 `platform.jks` 无法安装到车机（普通 `adb install` 会因 `sharedUserId=android.uid.system` 失败）。

| 参数 | 值 |
| --- | --- |
| 密钥文件 | `app/platform.jks`（已打包在归档内） |
| Keystore 密码 | `android` |
| Key alias | `platform` |
| Key 密码 | `android` |
| 启用签名方案 | V1 + V2 + V3 + V4 全开 |

> ⚠️ **安全提示**：`platform.jks` 是平台级签名密钥，等同于车机系统证书。
> - 迁移后请存放于受控环境，勿提交到公开仓库（`.gitignore` 已忽略 `*.jks`）。
> - 如密钥泄露需吊销，须重新生成 platform 密钥对并用新密钥重签系统镜像，影响面极大。

---

## 7. 运行 / 部署

```bash
# 系统签名 App 的安装方式（需车机已 root 或已解锁 system 分区）
adb root
adb remount
adb push app/build/outputs/apk/release/app-release.apk /system/priv-app/Miao/Miao.apk
adb reboot

# 或（已作为系统 App 安装后）覆盖更新
adb install -r -g app/build/outputs/apk/release/app-release.apk
```

### 虚拟屏模式启用（v1.9.11 新增）
1. 启动 miao（设为车机默认桌面）。
2. 点击悬浮球 → 设置 → 样式选择 **「虚拟屏」**。
3. 仅当主屏为 **1920×1080** 时该布局生效；其它分辨率自动回退到双翼/单翼布局。
4. 虚拟屏固定 **1920×720**，自动尝试投放到物理副屏（Display ≠ 0）；若车机仅单屏，则留在主屏预览区显示。
5. 顶栏显示时间与速度/电量，底栏显示功率与音乐卡片，预览区左右各留 20dp 防误触。

### 触摸转发原理（车机 Android 9 关键）
- Android 9 (API 28) Java 层**没有** `MotionEvent.setDisplayId` / `InputEvent.mDisplayId`（API 29 才有）。
- 因此采用 AOSP P 官方 `InputManager.createInputForwarder(displayId)` → `IInputForwarder.forwardEvent(event)`，由服务端 native 按 displayId 注入，仅需 `INJECT_EVENTS` 权限。
- 进程早期调用 `VMRuntime.setHiddenApiExemptions("L")` 豁免 non-SDK 限制。

---

## 8. 关键架构要点（迁移后续维护参考）

| 模块 | 文件 | 说明 |
| --- | --- | --- |
| 主界面 / 布局切换 | `app/src/main/java/com/example/miao/MainActivity.kt` | 双翼/单翼/虚拟屏三布局；`useVirtualLayout` 标志控制 |
| 虚拟屏布局 | `app/src/main/res/layout/activity_main_virtual.xml` | 1920×720 预览区 + 顶/底栏信息 |
| 标准布局 | `app/src/main/res/layout/activity_main.xml` | 双翼/单翼通用布局 |
| 无障碍服务 | `MiaoAccessibilityService.kt` | 触摸分发辅助 |
| MediaSession 音乐 | `MainActivity` 内 `updateActiveMediaSession()` | 网易云/QQ 走标准 MediaSession；原车酷狗走 logcat `KuGouPlayImpl` 私有通路 |
| 副屏投放 | `mirrorToSecondaryDisplay()` | Presentation + 反射 `VirtualDisplay.setSurface` |
| CI/CD | `.github/workflows/android-build.yml` | push 触发，输出 release（注意 `continue-on-error` 会掩盖编译失败，失败时开 Issue 捕获错误） |

---

## 9. 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `compileReleaseKotlin` 报 `Conflicting declarations: val prefs` | 同一作用域重复声明 `prefs`；已修复（v1.9.11 commit `9f8f323`） |
| `adb install` 报 `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` | 缺系统签名或未以系统 App 安装；需 `platform.jks` 且放入 `/system/priv-app` |
| 触摸无效 | 确认车机为 Android 9+；日志应出现 `IInputForwarder ready for display N` |
| 虚拟屏布局不出现 | 主屏分辨率非 1920×1080，或设置未选「虚拟屏」后未重启 Activity |
| CI 显示成功但无 Release | `steps.build.outcome` 因 `continue-on-error` 误判；检查 Issues 是否有 "CI Build Error" |

---

## 10. 文件清单摘要

完整清单见 `FILE_MANIFEST.md`（共 60 个有效文件，约 508 KB；归档含目录项共 96 条，336 KB）。
最关键的几个文件：

| 文件 | 作用 |
| --- | --- |
| `app/platform.jks` | 🔑 系统签名密钥（迁移必需） |
| `app/build.gradle.kts` | 应用构建与签名配置 |
| `gradle/libs.versions.toml` | 依赖版本集中管理 |
| `app/src/main/java/com/example/miao/MainActivity.kt` | 主逻辑（~74 KB） |
| `app/src/main/res/layout/activity_main_virtual.xml` | 虚拟屏布局 |
| `.github/workflows/android-build.yml` | CI 自动构建发布 |

---

*生成于 2026-08-20，对应代码 commit `9f8f323`（v1.9.11）。*
