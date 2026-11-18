# Named in the manifest and built by the system, so R8 has no call site to trace.
-keep class com.rewind.app.MainActivity { *; }
-keep class com.rewind.app.RecorderService { *; }
-keep class com.rewind.app.SaveActivity { *; }
-keep class com.rewind.app.SaveTile { *; }
