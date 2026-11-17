package com.voicepilot.agent

/** Actions the assistant can perform on the phone. Implemented by the accessibility service. */
interface PhoneController {
    /** Text dump of the current screen; each actionable element is prefixed with [id]. */
    fun describeScreen(): String
    fun tap(elementId: Int): Boolean
    fun longPress(elementId: Int): Boolean
    fun typeText(elementId: Int?, text: String, submit: Boolean): Boolean
    fun scroll(direction: String, elementId: Int?): Boolean
    fun globalAction(action: String): Boolean
    fun openApp(name: String): Boolean
    fun tapText(text: String): Boolean
}
