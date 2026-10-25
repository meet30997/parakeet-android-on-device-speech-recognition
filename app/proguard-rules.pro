# JNI uses these exact binary names and this constructor signature.
-keep class com.example.offlinetranscription.nemo.NemoSpeechJni { native <methods>; }
-keep class com.example.offlinetranscription.nemo.TranscriptionResult {
    public <init>(java.lang.String, float, float, long, boolean);
}
