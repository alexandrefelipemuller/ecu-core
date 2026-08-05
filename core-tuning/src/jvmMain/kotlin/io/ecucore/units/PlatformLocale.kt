package io.ecucore.units

import java.util.Locale

actual fun currentLocaleCountryCode(): String = Locale.getDefault().country
