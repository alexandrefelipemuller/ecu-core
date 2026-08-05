package io.ecucore.shared

actual fun sleepMillis(millis: Long) {
    Thread.sleep(millis)
}
