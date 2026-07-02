# Add project specific ProGuard rules here.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

-keep class com.sipedas.ponorogo.model.** { *; }
-keep class com.sipedas.ponorogo.data.** { *; }

# For Kotlin serialization/Moshi
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Room
-keep class androidx.room.** { *; }

