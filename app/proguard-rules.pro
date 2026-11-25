# Release builds are shrunk with R8 (Compose runs much faster that way).

# The native RAW decoder (fbraw.cpp) fills these fields by name through JNI.
-keep class com.bromide.engine.RawDecoder { native <methods>; }
-keep class com.bromide.engine.RawDecoder$Image { *; }

# Enum names are stored in settings and camera JSON (Brand, Link, PreviewSource...):
# they must stay the same from one update to the next.
-keepclassmembers enum com.bromide.** { <fields>; }

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
