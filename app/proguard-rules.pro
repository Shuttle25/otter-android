# Project-specific R8 rules (none yet).
# libsignal is called through JNI; protobuf-lite uses reflection on generated classes.
-keep class org.signal.libsignal.** { *; }
-keep class app.ottermsg.proto.** { *; }
# WebRTC (M8, D76): io.github.webrtc-sdk ships no consumer R8 rules. Its native code calls back into Java classes and
# methods by name (@CalledByNative, JNI field access), and JNI_OnLoad looks up Chromium's org.jni_zero helpers; R8
# would otherwise rename or remove them — the release build then aborts while loading libjingle_peerconnection_so.
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-dontwarn org.webrtc.**
-dontwarn org.jni_zero.**
