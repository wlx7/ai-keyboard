# Keep the IME service, setup activity, and JNI entry points.
-keep class app.aikb.ime.ImeService { *; }
-keep class app.aikb.ime.ui.SetupActivity { *; }
-keep class app.aikb.ime.engine.Llm { *; }
-keep class app.aikb.ime.engine.Rime { *; }
-keepclasseswithmembernames class * { native <methods>; }
