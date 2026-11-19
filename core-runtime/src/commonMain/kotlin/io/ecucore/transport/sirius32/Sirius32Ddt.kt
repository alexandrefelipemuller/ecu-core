package io.ecucore.transport.sirius32

import io.ecucore.transport.DtcCode
import io.ecucore.transport.DtcStatus
import kotlin.math.roundToInt

/**
 * Dialeto de diagnóstico da Siemens Sirius 32 (EMS3132) segundo o perfil DDT2000
 * `SIRIUS32 - EMS3132 - Soft RE0_A30` (ver `tools/gen_sirius32_ddt.py`).
 *
 * ATENÇÃO: é uma referência de ferramenta comercial, NÃO uma captura real. Dois pontos
 * assumidos e ainda não confirmados em carro:
 *  - posição de byte = índice 1-based no quadro de resposta INCLUINDO o SID positivo
 *    (ex.: `61 A2 ...` -> byte 1 = 0x61, byte 2 = 0xA2, byte 3 = 1º dado);
 *  - `bitOffset` conta a partir do bit mais significativo (0 = bit 7).
 * O modo de teste de compatibilidade do app registra os bytes crus para conferir isso.
 */
internal fun hex2(v: Int): String = v.toString(16).uppercase().padStart(2, '0')

class DdtItem(
    val name: String,
    val firstByte: Int,
    val bitOffset: Int,
    val bitsCount: Int,
    val signed: Boolean,
    val step: Double,
    val offset: Double,
    val divideBy: Double,
    val unit: String,
    val labels: Map<Long, String>,
) {
    /** Valor cru (inteiro) ou null se o quadro for curto demais. */
    fun raw(frame: List<Int>): Long? {
        val start = (firstByte - 1) * 8 + bitOffset
        val end = start + bitsCount
        if (start < 0 || end > frame.size * 8 || bitsCount <= 0 || bitsCount > 32) return null
        var value = 0L
        for (bit in start until end) {
            val b = (frame[bit / 8] shr (7 - bit % 8)) and 1
            value = (value shl 1) or b.toLong()
        }
        if (signed && (value shr (bitsCount - 1)) and 1L == 1L) value -= 1L shl bitsCount
        return value
    }

    /** Valor físico: (cru * step + offset) / divideBy. */
    fun value(frame: List<Int>): Double? = raw(frame)?.let { (it * step + offset) / divideBy }

    fun display(frame: List<Int>): String {
        val raw = raw(frame) ?: return "--"
        labels[raw]?.let { return it }
        val v = (raw * step + offset) / divideBy
        val text = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else ((v * 100.0).roundToInt() / 100.0).toString()
        return if (unit.isBlank()) text else "$text $unit"
    }
}

class DdtFrame(val id: Int, val title: String, val items: List<DdtItem>) {
    /** Comando de leitura: `21 <id>`. */
    val request: String get() = "21${hex2(id)}"
}

/** Condição de uma falha: item (por nome) e valor associado no perfil. */
class DdtFault(val frenchName: String, val conditions: List<Pair<String, Int>>)

/** Código de falha calculado a partir dos bits de status dos trames. */
data class Sirius32Fault(
    val index: Int,
    val name: String,
    val present: Boolean,
    val stored: Boolean,
    val milOn: Boolean,
    val symptoms: List<String>,
) {
    val code: String get() = "S32-${index.toString().padStart(2, '0')}"
    val description: String
        get() = if (symptoms.isEmpty()) name else "$name (${symptoms.joinToString(", ")})"
}

/** Funções de ação (reset, atuadores) - códigos conforme o perfil DDT. */
object Sirius32Actions {
    enum class AdaptiveReset(val code: Int, val label: String) {
        IDLE(0x80, "Marcha lenta (RR)"),
        EGR(0x81, "EGR"),
        TI(0x82, "TI"),
        TORQUE_METER(0x83, "Torquímetro"),
        ALL(0xFF, "Todos os aprendizados"),
    }

    enum class SpecialFunction(val code: Int, val label: String, val risky: Boolean) {
        INJECTOR_INHIBIT(0xA0, "Inibir injetores", true),
        POWER_LATCH_OFF(0xA1, "Desligar power latch", true),
        OBDFC_TESTS(0xA2, "Iniciar testes OBDFC", false),
        CLEAR_SAVED_MEMORY(0xA3, "Reiniciar memória salva", true),
        STATIC_TEST(0xA6, "Teste estático", false),
        OBDSAV_TESTS(0xA7, "Testes OBDSAV", false),
    }

    /** Atuadores do "Output Control" (30 D0 <id> 00 <ciclos>). */
    enum class Actuator(val id: Int, val label: String, val moderateRisk: Boolean = false) {
        FUEL_PUMP_RELAY(0x80, "Relé da bomba de combustível", true),
        A_C_OUTPUT(0x82, "Saída do ar-condicionado"),
        RR_VALVE(0x83, "Válvula RR"),
        CANISTER_PURGE(0x86, "Válvula de purga do canister"),
        O2_UPSTREAM_HEATER(0x88, "Aquecimento da sonda O2 montante"),
        CAMSHAFT_1(0x8F, "Comando de válvulas 1 (VVT)"),
        MIL_LAMP(0x94, "Lâmpada MIL"),
        WATER_TEMP_LAMP(0x9E, "Luz de alerta de temperatura da água"),
        FAN_LOW(0x9F, "Ventoinha - velocidade baixa"),
        FAN_HIGH(0xA0, "Ventoinha - velocidade alta"),
        O2_DOWNSTREAM_HEATER(0xA7, "Aquecimento da sonda O2 jusante"),
        HEATER_RELAY_1(0xAA, "Termo-mergulhador (relé 1)"),
        SHIFT_LAMP(0xAC, "Luz de troca de marcha"),
        CTP_RELAY_1(0xB7, "Relé CTP 1"),
        CTP_RELAY_2(0xB8, "Relé CTP 2"),
        CTP_RELAY_3(0xB9, "Relé CTP 3"),
    }

    enum class Session(val code: Int, val label: String) {
        STUDIES(0xA0, "Estudos"),
        AFTER_SALES(0xC0, "Pós-venda"),
        CHAIN_CONTROL(0xB0, "Controle em cadeia"),
        SUPPLIER(0x80, "Fornecedor"),
    }

    fun adaptiveResetRequest(r: AdaptiveReset): String = "11${hex2(r.code)}"
    fun specialFunctionRequest(f: SpecialFunction): String = "31A0${hex2(f.code)}"
    fun stopFunctionsRequest(): String = "32A000"

    /**
     * Output Control (perfil DDT): `30 <atuador> 00 <ciclos>` liga; `30 <atuador> 11 00` para;
     * `30 <atuador> 01 00` consulta o estado (resposta `70 ... <status>`: 1 = ligado,
     * 2 = parado, 0x11 = parado manualmente). O "D0" nos exemplos do perfil é só um valor
     * padrão do campo de atuador (byte 2), não parte do comando.
     */
    fun outputStartRequest(a: Actuator, cycles: Int): String = "30${hex2(a.id)}00${hex2(cycles.coerceIn(1, 255))}"
    fun outputStopRequest(a: Actuator): String = "30${hex2(a.id)}1100"
    fun outputStatusRequest(a: Actuator): String = "30${hex2(a.id)}0100"
}

object Sirius32Ddt {
    private const val SEP_LABEL = '¦'

    val frames: Map<Int, DdtFrame> by lazy {
        Sirius32DdtData.FRAME_ITEMS_TEXT.mapValues { (id, text) ->
            DdtFrame(id, Sirius32DdtData.FRAME_TITLES[id] ?: "Trame ${hex2(id)}", parseItems(text))
        }
    }

    val faults: List<DdtFault> by lazy {
        Sirius32DdtData.FAULTS_TEXT.split('\n').filter { it.isNotBlank() }.map { line ->
            val (name, conds) = line.split('\u0001', limit = 2)
            DdtFault(
                frenchName = name,
                conditions = conds.split('\t').filter { it.contains('=') }.map {
                    val i = it.lastIndexOf('=')
                    it.substring(0, i) to (it.substring(i + 1).trim().toIntOrNull() ?: 0)
                },
            )
        }
    }

    /** Trames que contêm bits de falha (Pannes Entrées/Sorties + diagnósticos OBD). */
    val FAULT_FRAME_IDS = listOf(0xA0, 0xA1, 0xAC, 0xAD, 0xAF)

    val LIVE_FRAME_IDS = listOf(0xA2, 0xA4)

    private fun parseItems(text: String): List<DdtItem> = text.lines().filter { it.isNotBlank() }.map { line ->
        val f = line.split('\t')
        DdtItem(
            name = f[0],
            firstByte = f[1].toInt(),
            bitOffset = f[2].toInt(),
            bitsCount = f[3].toInt(),
            signed = f[4] == "1",
            step = f[5].toDouble(),
            offset = f[6].toDouble(),
            divideBy = f[7].toDouble(),
            unit = f.getOrElse(8) { "" },
            labels = f.getOrElse(9) { "" }.split(SEP_LABEL).filter { it.contains('=') }.associate {
                val i = it.indexOf('=')
                it.substring(0, i).toLong() to it.substring(i + 1)
            },
        )
    }

    /** Extrai os bytes hex de uma resposta ELM (ignora espaços, `>` e linhas de eco). */
    fun parseBytes(response: String): List<Int> =
        Regex("[0-9A-Fa-f]{2}").findAll(response.replace("SEARCHING...", "").replace("BUS INIT: OK", "")).map { it.value.toInt(16) }.toList()

    /** Garante que o quadro começa em `61 <id>` (descarta ruído antes do SID positivo). */
    fun trameBytes(id: Int, response: String): List<Int>? {
        val bytes = parseBytes(response)
        val i = bytes.windowed(2).indexOfFirst { it[0] == 0x61 && it[1] == id }
        return if (i < 0) null else bytes.drop(i)
    }

    fun decodeFrame(frame: DdtFrame, bytes: List<Int>): Map<String, String> =
        frame.items.associate { it.name to it.display(bytes) }

    // ---- Falhas ------------------------------------------------------------------

    private val PT_NAMES = mapOf(
        "Capteur signal dent / Cible volant moteur" to "Sensor de rotação (dente) / roda fônica",
        "Capteur position papillon" to "Sensor de posição da borboleta (TPS)",
        "Capteur cliquetis" to "Sensor de detonação (knock)",
        "OBD : Misfire Catalyseur" to "OBD: falha de combustão (dano ao catalisador)",
        "Commande purge canister" to "Válvula de purga do canister",
        "OBD : Misfire Pollution" to "OBD: falha de combustão (emissões)",
        "Capteur pression collecteur" to "Sensor de pressão do coletor (MAP)",
        "Alimentation : Entrée + Actuateurs" to "Alimentação: entrada + atuadores",
        "CTPH" to "Aquecedor adicional (CTP)",
        "Capteur température air" to "Sensor de temperatura do ar (IAT)",
        "Alimentation : Entrée + Après contact" to "Alimentação: entrada + pós-contato",
        "Masse moteur sonde 1/3 fil" to "Massa do motor / sonda 1 ou 3 fios",
        "Climatisation : Capteur pression" to "Ar-condicionado: sensor de pressão",
        "Commande chauffage sonde aval" to "Aquecimento da sonda lambda jusante",
        "Commande chauffage sonde aval : Puissance" to "Aquecimento da sonda jusante: potência",
        "Commande décaleur arbre à cames" to "Defasador do comando de válvulas (VVT)",
        "Commande voyant alerte température eau" to "Luz de alerta de temperatura da água",
        "OBD : Fuel System" to "OBD: sistema de combustível",
        "Commande chauffage sonde amont : Puissance" to "Aquecimento da sonda montante: potência",
        "Commande chauffage sonde amont" to "Aquecimento da sonda lambda montante",
        "GPL : Liaison inter-système" to "GLP: ligação inter-sistemas",
        "Commande allumage 1 et 4" to "Ignição cilindros 1 e 4 (bobina)",
        "Climatisation : Liaison inter-système" to "Ar-condicionado: ligação inter-sistemas",
        "Capteur température eau" to "Sensor de temperatura da água",
        "Sonde aval" to "Sonda lambda jusante",
        "BVA : Liaison multiplexée" to "Câmbio automático: ligação multiplexada",
        "Capteur vitesse véhicule" to "Sensor de velocidade do veículo",
        "Sonde amont" to "Sonda lambda montante",
        "Calculateur" to "Central de injeção (ECU)",
        "Commande allumage 2 et 3" to "Ignição cilindros 2 e 3 (bobina)",
        "ABS : Liaison multiplexée" to "ABS: ligação multiplexada",
        "Pannes génériques Entrées/ Sorties" to "Falhas genéricas de entradas/saídas",
        "Commande voyant MIL" to "Lâmpada MIL",
        "Alternator Borne DF" to "Alternador (borne DF)",
        "Verrou logiciel : Liaison inter-système" to "Bloqueio de software: ligação inter-sistemas",
        "OBD : Sonde amont" to "OBD: sonda montante",
        "Commande relais pompe à essence" to "Relé da bomba de combustível",
        "Commande régulateur de ralenti : Stepper" to "Atuador de marcha lenta (motor de passo)",
        "OBD : Catalyseur" to "OBD: catalisador",
        "Commande relais actuateur" to "Relé dos atuadores",
        "Commande injecteur 1" to "Injetor 1",
        "Commande injecteur 2" to "Injetor 2",
        "Commande injecteur 3" to "Injetor 3",
        "Commande injecteur 4" to "Injetor 4",
    )

    /** "Fin de diag" é só o marcador de fim da lista, não uma falha. */
    private val NON_FAULTS = setOf("Fin de diag")

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun symptomOf(itemName: String): String? {
        val n = norm(itemName)
        return when {
            "cc à la masse" in n || "court-circuit à la masse" in n -> "curto à massa"
            "cc au +bat" in n || "court-circuit au +bat" in n -> "curto ao +bateria"
            "circuit ouvert" in n -> "circuito aberto"
            else -> null
        }
    }

    /**
     * Avalia as 45 falhas a partir dos trames lidos. [frames] = id do trame -> bytes da
     * resposta (`61 <id> ...`). Itens são resolvidos por nome nos trames lidos.
     */
    fun evaluateFaults(frames: Map<Int, List<Int>>): List<Sirius32Fault> {
        val values = HashMap<String, Long>()
        for ((id, bytes) in frames) {
            val frame = this.frames[id] ?: continue
            for (item in frame.items) item.raw(bytes)?.let { values[norm(item.name)] = it }
        }
        fun on(name: String) = (values[norm(name)] ?: 0L) == 1L
        return Sirius32Ddt.faults.mapIndexedNotNull { index, fault ->
            if (fault.frenchName in NON_FAULTS) return@mapIndexedNotNull null
            var present = false; var stored = false; var mil = false
            val symptoms = mutableListOf<String>()
            for ((itemName, _) in fault.conditions) {
                if (!on(itemName)) continue
                val n = norm(itemName)
                when {
                    "présente" in n || "presente" in n -> present = true
                    "mémorisée" in n || "mémorisee" in n || "mémo" in n -> stored = true
                    "allum mil" in n -> mil = true
                    else -> symptomOf(itemName)?.let { symptoms += it }
                }
            }
            if (!present && !stored) return@mapIndexedNotNull null
            Sirius32Fault(
                index = index,
                name = PT_NAMES[fault.frenchName] ?: fault.frenchName,
                present = present,
                stored = stored,
                milOn = mil,
                symptoms = symptoms.distinct(),
            )
        }
    }

    fun faultsToDtcCodes(faults: List<Sirius32Fault>, status: DtcStatus): List<DtcCode> =
        faults.filter { if (status == DtcStatus.ACTIVE) it.present else it.stored && !it.present }
            .map { DtcCode(code = it.code, description = it.description, status = status) }

    // ---- Identificação (21 80) ---------------------------------------------------

    /** Campos de identificação (21 80). Quase todos são números em hex; só o fornecedor é ASCII. */
    fun identification(bytes: List<Int>): Map<String, String> {
        val labels = mapOf(
            "N° RENAULT du calculateur" to "Nº Renault da central",
            "N° diag" to "Nº diag",
            "N° fournisseur" to "Fornecedor",
            "N° hardware" to "Hardware",
            "N° programme" to "Programa",
            "N° version du logiciel" to "Versão do software",
            "N° calibration" to "Calibração",
            "N° de calibration mise au point" to "Calibração (mapeamento)",
            "N° de calibration systeme" to "Calibração (sistema)",
        )
        val frame = frames[0x80] ?: return emptyMap()
        return buildMap {
            for (item in frame.items) {
                val from = item.firstByte - 1
                val to = from + item.bitsCount / 8
                if (to > bytes.size) continue
                val raw = bytes.subList(from, to)
                val text = if (item.name.contains("fournisseur")) {
                    raw.map { it.toChar() }.joinToString("").trim()
                } else {
                    raw.joinToString("") { hex2(it) }
                }
                put(labels[item.name] ?: item.name, text)
            }
            put("Bytes crus", bytes.joinToString(" ") { hex2(it) })
        }
    }
}
