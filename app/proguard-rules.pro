# Add project specific ProGuard rules here.

# MSAL
-keep class com.microsoft.identity.** { *; }
-keep interface com.microsoft.identity.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }

# Retrofit + OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class retrofit2.** { *; }

# iText
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory

# Data classes
-keep class com.docscanner.app.model.** { *; }
