# miao-uni-z 修复报告

## 修复概览

| # | 严重度 | 问题 | 状态 |
|---|--------|------|------|
| 1 | 🔴 严重 | logcat TAG 变量引用缺少 `$` | ✅ 已修复 |
| 2 | 🔴 严重 | NotificationListener 未在 Manifest 注册 | ✅ 已修复 |
| 3 | 🔴 严重 | 虚拟屏触摸不工作（多重原因） | ✅ 已修复 |
| 4 | 🔴 严重 | 签名密钥 `platform.jks` 提交到 Git | ✅ 已移除跟踪 |
| 5 | 🟡 中等 | `onDestroy` 资源泄漏 | ✅ 已修复 |
| 6 | 🟡 中等 | `createVirtualDisplay` 无意义 try-catch | ✅ 已修复 |
| 7 | 🟡 中等 | logcat `isOverlay` 在循环外读取 | ✅ 已修复 |
| 8 | 🟢 轻微 | `proguard-rules.pro` 缺失 | ✅ 已创建 |
| 9 | 🟢 轻微 | `.gitignore` 不完整 | ✅ 已修复 |

---

## 🔴 问题 3 详解：虚拟屏触摸不工作

### 原因分析（3 层 bug 叠加）

#### Bug 3.1：VirtualDisplay 缺少 `VIRTUAL_DISPLAY_FLAG_TRUSTED`

**原代码：**
```kotlin
val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
```

**问题：** 没有 `TRUSTED` flag，Android 系统不允许向虚拟屏注入输入事件。这是触摸完全不工作的**根本原因**。

**修复：**
```kotlin
val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
        (1 shl 10) // VIRTUAL_DISPLAY_FLAG_TRUSTED
```

> `VIRTUAL_DISPLAY_FLAG_TRUSTED` 是隐藏 API（flag 值 `1 << 10 = 1024`），需要系统签名权限。你的应用是 `android.uid.system` + platform 签名，所以可以使用。

#### Bug 3.2：坐标映射方向错误

**原代码：**
```kotlin
val mappedX = event.x * (vd.display.width.toFloat() / v.width)
val mappedY = event.y * (vd.display.height.toFloat() / v.height)
```

**问题：** `vd.display.width` 返回的是虚拟屏的**逻辑宽度**，而不是渲染分辨率。当虚拟屏的 density 与物理屏不同时，坐标会偏移。

**修复：** 使用 SurfaceView 的实际像素尺寸（即虚拟屏的渲染分辨率）做映射：
```kotlin
val targetW = if (surfaceView.width > 0) surfaceView.width else display.width
val targetH = if (surfaceView.height > 0) surfaceView.height else display.height
val scaleX = targetW.toFloat() / viewW
val scaleY = targetH.toFloat() / viewH
```

#### Bug 3.3：注入事件缺少 `deviceId`

**原代码：** 只设置了 `source = SOURCE_TOUCHSCREEN`，没有设置 `deviceId`。

**问题：** 很多车机系统的 InputManager 会检查事件的 `deviceId`，如果为 -1（默认值）则拒绝注入。

**修复：** 遍历输入设备列表，找到真实触摸屏设备的 ID 并设置到事件上：
```kotlin
val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
val deviceIds = inputManager.inputDeviceIds
var touchDeviceId = -1
for (id in deviceIds) {
    val dev = inputManager.getInputDevice(id)
    if (dev != null && (dev.sources and InputDevice.SOURCE_TOUCHSCREEN) != 0) {
        touchDeviceId = id
        break
    }
}
if (touchDeviceId != -1) {
    MotionEvent::class.java.getMethod("setDeviceId", Int::class.javaPrimitiveType).invoke(te, touchDeviceId)
}
```

#### Bug 3.4：`injectInputEvent` 返回值未检查

**原代码：** 直接 `invoke()` 不检查返回值。

**修复：** 检查返回值并在失败时记录警告日志，方便调试：
```kotlin
val result = injectMethod.invoke(...) as Boolean
if (!result) {
    Log.w("Miao", "Touch inject returned false for action=${event.action}")
}
```

---

## 修改的文件

| 文件 | 修改内容 |
|------|----------|
| `app/src/main/java/com/example/miao/MainActivity.kt` | logcat TAG 修复、触摸转发重写、onDestroy 补全、uiUpdateRunnable 提取、isOverlay 循环内读取、createVirtualDisplay 简化+TRUSTED flag |
| `app/src/main/AndroidManifest.xml` | 注册 NotificationListenerService |
| `app/proguard-rules.pro` | 新建，包含反射方法保留规则 |
| `.gitignore` | 排除 `*.jks`、`.idea/`、`.artifacts/` |

### Git 操作
- `git rm --cached app/platform.jks` — 从跟踪中移除签名密钥
- `git rm -r --cached .idea` — 从跟踪中移除 IDE 配置
- `git rm -r --cached .artifacts` — 从跟踪中移除开发中间产物

---

## ⚠️ 车机部署注意事项

1. **NotificationListener 权限**：安装后需要在 `设置 → 通知访问` 中手动启用 `NotificationListener`，否则 MediaSession 音乐回调仍不工作
2. **触摸注入**：如果触摸仍然不工作，查看 logcat 中 `Miao` tag 的警告日志：
   - `Touch inject returned false` → 系统拒绝注入，检查 `INJECT_EVENTS` 权限
   - `Touch inject failed` → 反射调用异常，检查 API 兼容性
3. **`VIRTUAL_DISPLAY_FLAG_TRUSTED`**：需要 platform 签名，你的 `platform.jks` 必须与车机系统签名一致
4. **签名密钥**：`platform.jks` 已从 Git 移除但本地文件仍保留，GitHub Actions 中应使用 `secrets.SIGNING_KEY` 注入
