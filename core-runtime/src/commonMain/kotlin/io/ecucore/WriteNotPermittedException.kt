package io.ecucore

/**
 * [SpeeduinoClient.writeGuard] negou a operação (ex.: usuário recusou gravar com o veículo em
 * movimento). Lançada antes de qualquer envio: a ECU permanece intacta.
 */
class WriteNotPermittedException(val operation: String) :
    Exception("Write not permitted: $operation cancelled before sending anything to the ECU")
