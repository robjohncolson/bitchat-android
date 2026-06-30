# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
-keep class com.bitchat.android.protocol.** { *; }
-keep class com.bitchat.android.crypto.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Dogecoin SPV: bitcoinj + libdohj + their transitive deps (protobuf, guava, spongycastle, slf4j).
# bitcoinj relies on reflection/protobuf for wallet+message (de)serialization, and pulls optional
# deps not present at runtime — keep its classes and silence missing-class warnings.
-keep class org.bitcoinj.** { *; }
-keep class org.libdohj.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class org.spongycastle.** { *; }
-dontwarn org.bitcoinj.**
-dontwarn org.libdohj.**
-dontwarn com.google.protobuf.**
-dontwarn org.spongycastle.**
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**

# Keep SecureIdentityStateManager from being obfuscated to prevent reflection issues
-keep class com.bitchat.android.identity.SecureIdentityStateManager {
    private android.content.SharedPreferences prefs;
    *;
}

# Keep all classes that might use reflection
-keep class com.bitchat.android.favorites.** { *; }
-keep class com.bitchat.android.nostr.** { *; }
-keep class com.bitchat.android.identity.** { *; }

# Keep Tor implementation (always included)
-keep class com.bitchat.android.net.RealTorProvider { *; }

# Arti (Custom Tor implementation in Rust) ProGuard rules
-keep class info.guardianproject.arti.** { *; }
-keep class org.torproject.arti.** { *; }
-keepnames class org.torproject.arti.**
-dontwarn info.guardianproject.arti.**
-dontwarn org.torproject.arti.**

# Fix for AbstractMethodError on API < 29 where LocationListener methods are abstract
-keepclassmembers class * implements android.location.LocationListener {
    public <methods>;
}
