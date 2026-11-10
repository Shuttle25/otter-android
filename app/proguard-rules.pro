# Project-specific R8 rules (none yet).
# libsignal is called through JNI; protobuf-lite uses reflection on generated classes.
-keep class org.signal.libsignal.** { *; }
-keep class app.ottermsg.proto.** { *; }
