package io.ecucore

import io.ecucore.definition.IniLayoutMismatchException
import io.ecucore.definition.IniParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Antes de gravar, o EcuClient confere a página/offset/bits que o código usa contra o .ini ATIVO do firmware conectado.
 * Campo definido em outro lugar = firmware que o código não conhece: a gravação é recusada e nada vai à ECU.
 * Campo ausente no .ini é ignorado (firmware que ainda não o tem).
 */
class EcuClientIniLayoutGuardTest {
    private fun ini(constants: String) = IniParser.parse(
        "teste.ini",
        """
        [MegaTune]
        signature = "speeduino 202501"

        [TunerStudio]
        queryCommand = "Q"
        versionInfo = "S"

        [Constants]
        nPages = 6
        pageSize = 128, 288, 288, 128, 288, 128

        $constants
        """.trimIndent(),
    )

    private suspend fun connected(constants: String?): Pair<EcuClient, ClientFakeEcu> {
        val ecu = ClientFakeEcu(ClientFakeEcu.Kind.SPEEDUINO_2025)
        val c = EcuClient(connection = ecu, onDataReceived = {}, onConnectionStateChanged = {}, onError = {})
        c.connect()
        if (constants != null) c.applyIniDefinition(ini(constants))
        return c to ecu
    }

    @Test fun refusesWhenTheIniPutsAFieldElsewhere_andSendsNothing() = runBlocking {
        // egoKP está no offset 1 da página 6 no código; este .ini o põe no 9.
        val (c, ecu) = connected("page = 6\n egoKP = scalar, U08, 9, \"%\", 1.0, 0.0, 0.0, 200, 0")
        val config = c.readClosedLoopCorrectionConfig()
        val p6 = ecu.mem(6).copyOfRange(0, 128)
        ecu.writes.clear()

        val e = assertFailsWith<IniLayoutMismatchException> { c.writeClosedLoopCorrectionConfig(config, burn = true) }

        assertTrue("egoKP" in e.message.orEmpty(), e.message)
        assertTrue(ecu.writes.isEmpty(), "nada pode ser gravado")
        assertTrue(ecu.burns.isEmpty(), "nenhum burn")
        assertContentEquals(p6, ecu.mem(6).copyOfRange(0, 128))
    }

    @Test fun refusesWhenBitsDisagree() = runBlocking {
        // TrigPattern é [3:7] no código; este .ini o declara [0:4].
        val (c, ecu) = connected("page = 4\n TrigPattern = bits, U08, 5, [0:4], \"A\", \"B\"")
        val trigger = c.readTriggerSettings().copy(primaryBaseTeeth = 36, missingTeeth = 1)
        ecu.writes.clear()
        assertFailsWith<IniLayoutMismatchException> { c.writeTriggerSettings(trigger, burn = true) }
        assertTrue(ecu.writes.isEmpty())
    }

    @Test fun writesWhenTheIniAgrees() = runBlocking {
        val (c, ecu) = connected("page = 6\n egoKP = scalar, U08, 1, \"%\", 1.0, 0.0, 0.0, 200, 0")
        val config = c.readClosedLoopCorrectionConfig()
        ecu.writes.clear()
        c.writeClosedLoopCorrectionConfig(config, burn = false)
        assertTrue(ecu.writes.any { it.id == 6 }, "a gravação deveria ter ocorrido")
    }

    @Test fun ignoresFieldsTheIniDoesNotDefine_andWorksWithoutAnyIni() = runBlocking {
        val (withUnrelated, _) = connected("page = 6\n outroCampo = scalar, U08, 99, \"\", 1.0, 0.0, 0.0, 255, 0")
        withUnrelated.writeClosedLoopCorrectionConfig(withUnrelated.readClosedLoopCorrectionConfig(), burn = false)
        val (noIni, _) = connected(null)
        noIni.writeClosedLoopCorrectionConfig(noIni.readClosedLoopCorrectionConfig(), burn = false)
    }
}
