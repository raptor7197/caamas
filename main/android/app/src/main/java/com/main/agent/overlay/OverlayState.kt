package com.main.agent.overlay

/** All possible states of the floating overlay button. */
sealed class OverlayState {
    /** Collapsed mic bubble — default resting state. */
    object Idle       : OverlayState()
    /** Microphone is recording. */
    object Listening  : OverlayState()
    /** LLM is generating a response. */
    object Thinking   : OverlayState()
    /** TTS is speaking. */
    data class Speaking(val text: String) : OverlayState()
    /** Tool execution in progress. */
    data class RunningTool(val toolName: String) : OverlayState()
    /** An error occurred. Tap to dismiss. */
    data class Error(val message: String) : OverlayState()
}
