# Filament's Java bindings are called into from native (JNI) code, so their
# method signatures must survive obfuscation/shrinking unchanged.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**
