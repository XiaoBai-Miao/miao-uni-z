UNI-Z仪表车速+镜像主屏幕

## 触摸功能状态（对齐 GitHub master / v1.9.11，2026-08-20 核实）

- **主屏画中画（虚拟屏内嵌 App）触摸：已修复（上游）。**
  GitHub `master` 的 `setupTouchForwarding()` 自 **v1.9.8** 起改用系统级 `InputManager.createInputForwarder(displayId)`（`IInputForwarder`），配合 **v1.9.7** 的 `bypassHiddenApi()` 豁免 hidden API 限制，可将触摸事件按 `displayId` 定向注入虚拟屏。本仓库归档内的 `setupTouchForwarding()` 与 GitHub master **逐字一致**，因此主屏画中画触摸在归档基线中即已实现。
  - 结论：归档 `README` 中"画中画触摸功能尚不可用"为**过时标注**，实际主屏触摸早已可用；本任务中LOCAL 手写补丁与此段为等价重写，已撤销以对齐上游，避免偏离。

- **物理副屏（Presentation 镜像）触摸：仍未处理（上游缺口）。**
  `mirrorToSecondaryDisplay()` 经 `VirtualDisplay.setSurface(holder.surface)` 把虚拟屏渲染目标改挂到副屏 `Presentation` 的 `SurfaceView`，但**未给该 `SurfaceView` 挂任何触摸监听**，副屏触摸无人转发。此缺口在 GitHub master（v1.9.11）**同样存在**。
  - 若后续需要副屏可触控，应在副屏 `SurfaceView` 上附加与主屏等价的转发器（`IInputForwarder` + `displayId` 注入），并修正副屏异分辨率下的坐标映射。该能力为新增项，非 GitHub 已修复内容。

## 构建/部署
见 `MIGRATION/MIGRATION_GUIDE.md`（`JDK 21` + `android-37`，`./gradlew assembleRelease`，系统签名 `platform.jks`）。

## 虚拟屏模式优化（2026-08-20）
虚拟屏模式（`useVirtualLayout`，仅 1920×1080 主屏生效，布局 `activity_main_virtual.xml`）的优化点：

- **锁定 1920×720**：`createVirtualDisplay()` 虚拟模式写死 `1920×720`；`updateVirtualDisplaySize()` 在虚拟模式下提前 `return`，虚拟屏分辨率不被 surfaceView 尺寸变化拖动。
- **占满左右 + 防误触边距**：`activity_main_virtual.xml` 中 `center_container` 改为 `ConstraintLayout`，`center_card` 用 `app:layout_constraintDimensionRatio="1920:720"` 居中（8:3，避免拉伸变形），左右各留 20dp 边距即"误触距离"，上下由顶/底栏自然隔开。
- **主屏显示 + 复制一份到 HDMI（真·复制）**：
  - 原实现 `mirrorToSecondaryDisplay()` 用 `VirtualDisplay.setSurface(HDMI Surface)` 把渲染目标**改挂**到 HDMI，导致主屏虚拟屏变空。
  - 新实现：虚拟屏渲染进 `SurfaceTexture`，由新增 `HdmiMirrorer`（GLES/EGL）把同一帧同时画到**主屏 `surfaceView`** 与 **HDMI `Presentation` 的 `SurfaceView`** 两个 surface —— 主屏与 HDMI 同时显示同一画面。
  - 若 GLES 复制器初始化失败，自动回退到旧 `setSurface` 行为（HDMI 仍显示、主屏变空），不引入回归。
- **隔离（其他项目不影响虚拟屏）**：虚拟模式下拖拽手柄/resize 本就禁用（`applyUiStyle` 隐藏、`setupResizing` 不调用）；浮动球 `initFloatingBall` 在虚拟模式锁在右下角、禁拖动，避免遮挡虚拟屏。设置浮层为按需弹出（临时覆盖，非持续干扰）。
- ⚠️ **验证状态**：本机无 JDK/Android SDK，未编译；`HdmiMirrorer` 为新增 GLES 合成逻辑，需车机真机（Android 9，双屏 1920×1080 主 + 1920×720 副）构建安装后验证。关注 logcat `Miao` tag：`HdmiMirrorer EGL ready` / `copied to HDMI via GLES mirrorer` / `drawFrame failed`。

