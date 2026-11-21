package io.ecucore.safety

import io.ecucore.model.AfrTable
import io.ecucore.model.DwellTable
import io.ecucore.model.IgnitionTable
import io.ecucore.model.VeTable
import io.ecucore.transport.EcuTransport
import kotlinx.coroutines.CancellationException

/** Para que serve a gravação: edição normal (registra desfazer) ou o próprio desfazer (remove o topo). */
enum class TableWriteMode { EDIT, UNDO }

/** Resultado tipado da gravação; cada app traduz para a sua mensagem. */
sealed class TableWriteOutcome<out T : Any> {
    abstract val key: UndoKey

    /**
     * Gravou. [previous] é o que a ECU tinha antes (null se não deu para ler ou se era desfazer);
     * [undoRecorded] diz se virou ponto de retorno.
     */
    data class Written<T : Any>(
        override val key: UndoKey,
        val table: T,
        val previous: T?,
        val undoRecorded: Boolean,
    ) : TableWriteOutcome<T>()

    /** A gravação falhou; a pilha de desfazer não mudou. */
    data class Failed(override val key: UndoKey, val error: Exception) : TableWriteOutcome<Nothing>()
}

/**
 * Caminho único de gravação de tabelas (VE, Ignição, AFR, Dwell) com "Desfazer", usado pelos dois apps:
 *
 * 1. [beforeWrite] (ex.: backup de segurança do MS3);
 * 2. lê da ECU o ponto de retorno — da ECU e não da tela, que pode mostrar uma prévia nunca gravada.
 *    Se a leitura falhar, segue sem ponto de retorno ([onPreviousUnreadable]);
 * 3. grava;
 * 4. só depois do sucesso: em [TableWriteMode.EDIT] empilha o anterior (se diferente do gravado);
 *    em [TableWriteMode.UNDO] tira o topo da pilha.
 *
 * Não pausa o stream nem mostra mensagens: isso continua com cada app (chamar dentro do seu
 * "stream pausado"). Confirmação de mudança grande e trava de segurança ficam no transporte.
 */
class TableWriteWithUndo(
    private val undoStore: TableUndoStore,
    private val beforeWrite: suspend (UndoKey) -> Unit = {},
    private val onPreviousUnreadable: (UndoKey, Exception) -> Unit = { _, _ -> },
) {
    suspend fun writeVe(transport: EcuTransport, table: VeTable, mapIndex: Int = 1, mode: TableWriteMode = TableWriteMode.EDIT) =
        write(UndoKey(UndoTableKind.VE, mapIndex), table, mode, { transport.readVeTable(mapIndex) }) {
            transport.writeVeTable(it, mapIndex)
        }

    suspend fun writeIgnition(transport: EcuTransport, table: IgnitionTable, mapIndex: Int = 1, mode: TableWriteMode = TableWriteMode.EDIT) =
        write(UndoKey(UndoTableKind.IGNITION, mapIndex), table, mode, { transport.readIgnitionTable(mapIndex) }) {
            transport.writeIgnitionTable(it, mapIndex)
        }

    suspend fun writeAfr(transport: EcuTransport, table: AfrTable, mode: TableWriteMode = TableWriteMode.EDIT) =
        write(UndoKey(UndoTableKind.AFR), table, mode, { transport.readAfrTable() }) { transport.writeAfrTable(it) }

    suspend fun writeDwell(transport: EcuTransport, table: DwellTable, mode: TableWriteMode = TableWriteMode.EDIT) =
        write(UndoKey(UndoTableKind.DWELL), table, mode, { transport.readDwellTable() }) { transport.writeDwellTable(it) }

    /** Regrava o topo da pilha de [key]; null se não há nada para desfazer. */
    suspend fun undo(transport: EcuTransport, key: UndoKey): TableWriteOutcome<*>? = when (key.kind) {
        UndoTableKind.VE -> undoStore.peek<VeTable>(key)?.let { writeVe(transport, it, key.mapIndex, TableWriteMode.UNDO) }
        UndoTableKind.IGNITION -> undoStore.peek<IgnitionTable>(key)?.let { writeIgnition(transport, it, key.mapIndex, TableWriteMode.UNDO) }
        UndoTableKind.AFR -> undoStore.peek<AfrTable>(key)?.let { writeAfr(transport, it, TableWriteMode.UNDO) }
        UndoTableKind.DWELL -> undoStore.peek<DwellTable>(key)?.let { writeDwell(transport, it, TableWriteMode.UNDO) }
    }

    suspend fun <T : Any> write(
        key: UndoKey,
        table: T,
        mode: TableWriteMode,
        readPrevious: suspend () -> T,
        doWrite: suspend (T) -> Unit,
    ): TableWriteOutcome<T> {
        try {
            beforeWrite(key)
            val previous = if (mode == TableWriteMode.EDIT) readPreviousOrNull(key, readPrevious) else null
            doWrite(table)
            var recorded = false
            when (mode) {
                TableWriteMode.EDIT -> if (previous != null && previous != table) {
                    undoStore.push(key, previous)
                    recorded = true
                }
                TableWriteMode.UNDO -> undoStore.pop(key)
            }
            return TableWriteOutcome.Written(key, table, previous, recorded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return TableWriteOutcome.Failed(key, e)
        }
    }

    private suspend fun <T : Any> readPreviousOrNull(key: UndoKey, read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onPreviousUnreadable(key, e)
        null
    }
}
