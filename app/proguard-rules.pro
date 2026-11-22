# Keep Room database classes, entities, DAOs, and constructors
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.TypeConverter <methods>;
}
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
-dontwarn javax.annotation.**

# Coroutines debugging / FastServiceLoader
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Preserve stack traces with obfuscated source filename
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Jetpack Glance widgets, receivers, and ActionCallback zero-arg constructors
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
-keep class * extends androidx.glance.appwidget.GlanceAppWidget {
    public <init>(...);
}
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver {
    public <init>(...);
}
-keep class com.example.widget.** { *; }
