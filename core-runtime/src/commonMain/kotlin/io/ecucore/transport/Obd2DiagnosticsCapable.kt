package io.ecucore.transport

/**
 * Transportes que conseguem falar OBD2 SAE J1979 diretamente (Mode 03/04/07) implementam esta
 * interface para expor leitura/limpeza de DTCs. Nem todo [EcuTransport] a implementa - apenas
 * quem fala OBD2 genérico nativamente ou delega para um [Obd2Transport] interno.
 */
interface Obd2DiagnosticsCapable {
    /** Mode 03 - códigos de falha ativos (confirmados). */
    suspend fun readDtcCodes(): List<DtcCode>

    /** Mode 07 - códigos de falha pendentes (ainda não confirmados). */
    suspend fun readPendingDtcCodes(): List<DtcCode>

    /** Mode 04 - limpa DTCs e a MIL. Retorna true se a ECU confirmou (ack "44"). */
    suspend fun clearDtcCodes(): Boolean
}
