# Add project specific ProGuard rules here.
-keep class com.main.agent.llm.LlamaEngine { native <methods>; }
-keep class com.main.agent.llm.LlamaEngine$InferenceCallback { *; }
-keep class com.main.agent.voice.WhisperSTT { native <methods>; }
-keep class com.main.agent.voice.WhisperSTT$TranscriptionCallback { *; }
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontwarn org.slf4j.**
