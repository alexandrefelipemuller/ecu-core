package io.ecucore.units

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.currentLocale

actual fun currentLocaleCountryCode(): String {
    return NSLocale.currentLocale.objectForKey(NSLocaleCountryCode) as? String ?: ""
}
