package com.speeduino.manager.shared

actual fun sleepMillis(millis: Long) {
    Thread.sleep(millis)
}
