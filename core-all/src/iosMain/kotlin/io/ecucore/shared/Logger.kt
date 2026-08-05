package io.ecucore.shared
	
import platform.Foundation.NSLog

actual object Logger {
    // NSLog treats its first argument as a printf-style format string — logging dynamic content
    // (firmware signatures, connection error text, hex payload dumps, ...) directly as that
    // argument means any stray '%' gets interpreted as a format directive, reading past the
    // (nonexistent) varargs and crashing with EXC_BAD_ACCESS.
    //
    // Passing a separate "%@" format with the message as a vararg looks like the textbook fix,
    // but Kotlin/Native's cinterop bridging of a Kotlin String into an Objective-C variadic
    // NSObject slot isn't reliable — that made this crash unconditionally instead of only on a
    // stray '%'. Escaping '%' to '%%' in the message itself and logging it as the sole/format
    // argument sidesteps both problems: no vararg bridging involved, and no directive left for
    // NSLog to (mis)interpret.
    private fun sanitize(message: String): String = message.replace("%", "%%")

    actual fun d(tag: String, message: String) {
        NSLog(sanitize("D/$tag: $message"))
    }

    actual fun i(tag: String, message: String) {
        NSLog(sanitize("I/$tag: $message"))
    }

    actual fun w(tag: String, message: String) {
        NSLog(sanitize("W/$tag: $message"))
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        NSLog(sanitize("E/$tag: $message"))
        throwable?.let {
            NSLog(sanitize("Exception: ${it.stackTraceToString()}"))
        }
    }
}
