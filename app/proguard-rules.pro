# Vynox release rules.
# Keep .vnx model/reflection entry points and any engine class used by name.
-keep class com.vynox.core.** { *; }
-keepclassmembers class com.vynox.core.** { *; }
-keep class com.vynox.app.** { *; }
-dontwarn kotlinx.**
-dontwarn org.jetbrains.**
