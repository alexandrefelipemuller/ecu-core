package io.ecucore

import io.ecucore.model.TableChangeAssessment

/**
 * A mudança é grande em relação à tabela da ECU e o [SpeeduinoClient.tableChangeGuard] não a confirmou.
 * Lançada antes de qualquer escrita: a ECU permanece intacta.
 */
class TableChangeRejectedException(val assessment: TableChangeAssessment) :
    Exception("Large table change not confirmed: ${assessment.summary()}")
