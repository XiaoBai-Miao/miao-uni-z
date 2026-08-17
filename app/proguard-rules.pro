# ProGuard / R8 规则
# 当前 minifyEnabled = false，此文件暂不生效
# 开启混淆时需要添加以下规则保证反射调用的方法不被移除

# 保留 MotionEvent.setDisplayId (反射调用)
-keepclassmembers class android.view.MotionEvent {
    public void setDisplayId(int);
    public void setDeviceId(int);
    public int getDeviceId();
}

# 保留 InputManager.injectInputEvent (反射调用)
-keepclassmembers class android.hardware.input.InputManager {
    public boolean injectInputEvent(android.view.InputEvent, int);
}

# 保留 VirtualDisplay.setSurface (反射调用)
-keepclassmembers class android.hardware.display.VirtualDisplay {
    public void setSurface(android.view.Surface);
}

# 保留 NotificationListenerService 子类
-keep class com.example.miao.MainActivity$NotificationListener { *; }
-keep class com.example.miao.MainActivity$HintPresentation { *; }
