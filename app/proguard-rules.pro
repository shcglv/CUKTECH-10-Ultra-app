# Keep Room database classes and constructors
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep BluetoothGatt hidden refresh() invoked via reflection
-keepclassmembers class android.bluetooth.BluetoothGatt {
    public boolean refresh();
}

# OkHttp / JVM optional provider warnings
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coroutines debugging / FastServiceLoader
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Preserve stack traces with obfuscated source filename
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve zero-arg constructors for Jetpack Glance ActionCallback classes
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
