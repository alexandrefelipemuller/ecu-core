package io.ecucore

/** O guard de segurança do teste de saídas recusou o comando (nada foi enviado à ECU). */
class MegaSpeedTestRefusedException(val reason: String) : IllegalStateException(reason)
