package pe.chambamatch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Recorrido sin servidor ni conexión de red. Los datos ficticios sólo existen durante la prueba. */
@RunWith(AndroidJUnit4::class)
class AndroidFlowTest {
    private val name = org.junit.rules.TestName()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = object : ExternalResource() {
        private lateinit var file: File
        private var backup: ByteArray? = null
        override fun before() {
            file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "chambamatch.json")
            backup = if (file.exists()) file.readBytes() else null
            if (name.methodName == "cachedOffersAppearOnHomeBeforeOpeningSearch") {
                val today = java.time.LocalDate.now(java.time.ZoneId.of("America/Lima"))
                file.writeText(org.json.JSONObject().put("perfil", org.json.JSONObject().put("carrera", "Sistemas").put("habilidades", "Kotlin, SQL, Python").put("situacion", "Estudiante")).put("ofertas", org.json.JSONArray(listOf(
                    org.json.JSONObject().put("id", "cached-one").put("fuente", "manual").put("titulo", "Practicante preprofesional de desarrollo Kotlin").put("empresa", "Equipo de prueba").put("descripcion", "Requisitos: Kotlin y SQL.").put("ubicacion", "Lima").put("salario", 1300).put("moneda", "PEN").put("vacantes", 2).put("cierre", today.plusDays(14).toString()),
                    org.json.JSONObject().put("id", "cached-two").put("fuente", "manual").put("titulo", "Analista de datos junior").put("empresa", "Laboratorio de prueba").put("descripcion", "Requisitos: Python y SQL.").put("ubicacion", "Remoto").put("modalidad", "remoto").put("salario", 2600).put("moneda", "PEN").put("cierre", today.plusDays(6).toString())
                ))).put("feedback", org.json.JSONObject()).put("busquedas", org.json.JSONObject()).toString())
            } else file.writeText("""{"perfil":{},"ofertas":[],"feedback":{},"busquedas":{}}""")
        }
        override fun after() { backup?.let { file.writeBytes(it) } ?: file.delete() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(name).around(fixture).around(compose)
    @Test fun cachedOffersAppearOnHomeBeforeOpeningSearch() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("app-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Para ti").assertExists()
        compose.onNodeWithText("2 coincidencias").assertExists()
        compose.onNodeWithText("Practicante preprofesional de desarrollo Kotlin").assertIsDisplayed()
        compose.onNodeWithText("Analista de datos junior").assertIsDisplayed()
        compose.onNodeWithText("Cargo o palabras clave").assertDoesNotExist()
        compose.onNodeWithText("Encuentra tu\npróxima oportunidad.").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/chamba-home-loaded.png").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.onNodeWithText("Buscar").performClick()
        compose.onNodeWithText("Cargo o palabras clave").assertExists()
        compose.onNodeWithText("Buscar trabajos").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/chamba-search-preview.png").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.onNodeWithText("Inicio").performClick()
        compose.onNodeWithText("2 coincidencias").assertExists()
    }
    @Test fun profileImportFilterAndLearnWithoutServer() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("app-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Conexión").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/chamba-home-preview.png").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.onNodeWithText("Perfil").performClick()
        compose.onNodeWithText("Carrera o especialidad").performTextInput("Sistemas")
        compose.onNodeWithText("Habilidades separadas por comas").performTextInput("Python, SQL")
        compose.onNodeWithText("Guardar perfil").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Perfil y preferencias guardados en este celular.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Añadir una oferta").performScrollTo().performClick()
        compose.onNodeWithText("Título").performTextInput("Oferta de prueba: desarrollador")
        compose.onNodeWithText("Empresa").performScrollTo().performTextInput("Entidad de prueba")
        compose.onNodeWithText("Ubicación").performScrollTo().performTextInput("Lima")
        compose.onNodeWithText("Cierre (AAAA-MM-DD, opcional)").performScrollTo().performTextInput(java.time.LocalDate.now(java.time.ZoneId.of("America/Lima")).toString())
        compose.onNodeWithText("Descripción y requisitos").performScrollTo().performTextInput("Requisitos: Python y SQL para desarrollo de software")
        compose.onNodeWithText("Guardar oferta").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Oferta guardada en este celular.").fetchSemanticsNodes().isNotEmpty() }
        val dataFile = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "chambamatch.json")
        val data = org.json.JSONObject(dataFile.readText())
        data.optJSONArray("ofertas")!!.getJSONObject(0).put("vacantes", 1).put("salario", 1130).put("moneda", "PEN").put("periodo", "mensual")
        dataFile.writeText(data.toString())
        compose.onNodeWithText("Buscar").performClick()
        compose.onNodeWithText("Cargo o palabras clave").performTextInput("sistemas")
        compose.onNodeWithText("Publicadas en: Cualquier fecha").assertDoesNotExist()
        compose.onNodeWithText("Más filtros y fuentes").assertExists()
        compose.waitForIdle()
        Thread.sleep(650)
        compose.onNodeWithText("Inicio").performClick()
        compose.onNodeWithText("1 coincidencias").assertExists()
        compose.onNodeWithText("Oferta de prueba: desarrollador").assertExists()
        compose.onNodeWithTag("job-card").performScrollTo()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/chamba-card-preview.png").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.onNodeWithText("Requisitos").performScrollTo().performClick()
        compose.onNodeWithText("Me interesa").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Preferencia actualizada. Aprendizaje procesado en el celular.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Seguimiento").performClick()
        compose.onNodeWithText("Tu agenda").assertExists()
        compose.onNodeWithText("Postula hoy").assertExists()
        compose.onNodeWithText("Estado de postulación: Pendiente").performScrollTo().performClick()
        compose.onNodeWithText("Preparando").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Estado de postulación: Preparando").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/chamba-agenda-preview.png").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        compose.onNodeWithText("Organizar requisitos").performScrollTo().performClick()
        compose.onNodeWithText("Habilidad: Python · compatible").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 de 2 requisitos preparados").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Inicio").performClick()
        compose.onNodeWithText("Requisitos").performScrollTo().performClick()
        compose.onNodeWithText("Deshacer valoración").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Deshacer valoración").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun internationalControlsAndProfileTextWork() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("app-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Buscar").performClick()
        compose.onNodeWithText("País: Perú").performClick()
        compose.onNodeWithTag("geo-options").performScrollToNode(hasText("Brasil"))
        compose.onNodeWithText("Brasil").performClick()
        compose.onNodeWithText("País: Brasil").assertExists()
        compose.onNodeWithText("Región: Cualquier región").performClick()
        compose.onNodeWithTag("geo-options").performScrollToNode(hasText("São Paulo"))
        compose.onNodeWithText("São Paulo").performClick()
        compose.onNodeWithText("Región: São Paulo").assertExists()
        compose.onNodeWithText("País: Brasil").performClick()
        compose.onNodeWithText("Todo el mundo").performClick()
        compose.onNodeWithText("País: Todo el mundo").assertExists()
        compose.onNodeWithText("Región: Cualquier región").assertExists()
        compose.onNodeWithText("País de búsqueda (ej. España, United States)").assertDoesNotExist()
        compose.onNodeWithText("Perfil").performClick()
        compose.onNodeWithText("Experiencia, idiomas y otros datos").performScrollTo().performTextInput("Tengo seis meses de experiencia")
        compose.onNodeWithText("Experiencia reconocida en tu texto: 6.0 meses").performScrollTo().assertExists()
        compose.onNodeWithText("Guardar perfil").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Perfil y preferencias guardados en este celular.").fetchSemanticsNodes().isNotEmpty() }
    }

}
