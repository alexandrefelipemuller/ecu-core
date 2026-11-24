package io.ecucore.transport.exploration

import kotlin.math.abs

/**
 * Valida um sinal OEM proposto pelo planner comparando o valor decodificado com a fórmula
 * dele contra a grandeza equivalente do modo 01, lida no mesmo instante.
 */
object PlausibilityChecker {

    /** Decodifica [data] (payload após serviço+id) com a fórmula linear do sinal. */
    fun decode(signal: CandidateSignal, data: List<Int>): Double? {
        if (signal.byteOffset < 0 || signal.byteLength !in 1..4) return null
        if (signal.byteOffset + signal.byteLength > data.size) return null
        var raw = 0L
        for (i in 0 until signal.byteLength) raw = (raw shl 8) or data[signal.byteOffset + i].toLong()
        if (signal.signed) {
            val bits = signal.byteLength * 8
            if (raw and (1L shl (bits - 1)) != 0L) raw -= (1L shl bits)
        }
        val value = raw * signal.scale + signal.offset
        return value.takeIf { it.isFinite() }
    }

    fun verdict(
        reference: ReferenceQuantity,
        oemValue: Double?,
        referenceValue: Double?,
    ): SignalVerification.Verdict {
        if (oemValue == null) return SignalVerification.Verdict.DECODE_FAILED
        if (referenceValue == null) return SignalVerification.Verdict.UNVERIFIABLE
        // RPM/velocidade em zero (motor desligado, carro parado) não discriminam fórmula.
        val trivialZero = reference == ReferenceQuantity.RPM || reference == ReferenceQuantity.VEHICLE_SPEED
        if (trivialZero && referenceValue == 0.0) return SignalVerification.Verdict.UNVERIFIABLE
        val allowed = maxOf(abs(referenceValue) * reference.tolerance, reference.absoluteTolerance)
        return if (abs(oemValue - referenceValue) <= allowed) {
            SignalVerification.Verdict.VERIFIED
        } else {
            SignalVerification.Verdict.MISMATCH
        }
    }
}
