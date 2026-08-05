package io.ecucore.shared

import platform.posix.usleep

actual fun sleepMillis(millis: Long) {
    usleep((millis * 1000).toUInt())
}
