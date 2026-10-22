package com.local.focuslock.domain
object DurationInput { fun parse(text: String, maxMinutes: Int): Int? = text.trim().takeIf { it.matches(Regex("[0-9]{1,4}")) }?.toIntOrNull()?.takeIf { it in 1..maxMinutes } }
