package io.ecucore.shared

/**
 * Sleep bloqueante usado em pontos do protocolo que precisam de uma pausa
 * síncrona curta fora de contexto suspend (ex.: flush após resposta ASCII legacy).
 */
expect fun sleepMillis(millis: Long)
