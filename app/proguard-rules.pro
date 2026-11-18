# Keep the IME service and JNI entry points.
-keep class app.aikb.ime.ImeService { *; }
-keepclasseswithmembernames class * { native <methods>; }
