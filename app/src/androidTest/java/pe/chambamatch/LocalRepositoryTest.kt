package pe.chambamatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalRepositoryTest {
    private fun file() = File.createTempFile("chambamatch-test-", ".json", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).apply { delete() }
    @Test fun likedAgendaPersistsStateChecklistAndBackupAcrossFilters() = runBlocking {
        val a = file(); val b = file()
        try {
            val repository = LocalJobsRepository(a)
            val prefs = defaultFilters()
            val id = repository.importJob(JSONObject().put("titulo", "Developer").put("descripcion", "Requisitos: Python obligatorio. Plazo para postular: 19 de octubre de 2026"), prefs).rows("ofertas").single().text("id")
            repository.vote(id, "interesa", prefs)
            val tracked = repository.filter(prefs.copyJson().put("ubicacion", "Puno")).rows("seguimiento").single()
            val key = tracked.rows("requisitos").first().text("clave")
            repository.track(id, "preparando", key, prefs)
            val target = LocalJobsRepository(b)
            target.restore(repository.export())
            val restored = target.load().rows("seguimiento").single()
            assertEquals("preparando", restored.text("postulacion"))
            assertTrue(key in restored.strings("preparados"))
            assertTrue(restored.rows("requisitos").any { it.text("estado") == "por confirmar" })
            assertEquals("2026-10-19", restored.text("cierre"))
        } finally { a.delete(); b.delete() }
    }
    @Test fun worldCatalogAndBookmarksPersistWithoutTraining() = runBlocking {
        val countries = WorldLocations.load(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(250, countries.size)
        assertEquals(250, countries.map { it.code }.distinct().size)
        assertTrue(countries.sumOf { it.regions.size } > 5000)
        val brazil = countries.single { it.code == "BR" }
        assertTrue("São Paulo" in brazil.regions)
        assertTrue("Puno" in countries.single { it.code == "PE" }.regions)
        val selected = WorldLocations.select(defaultFilters().put("ubicacion", "Lima"), brazil)
        assertEquals("", selected.text("ubicacion"))
        assertTrue(CountryScope.matches(searchCountry(selected), "Brasil"))
        val file = file()
        try {
            val repository = LocalJobsRepository(file)
            val prefs = defaultFilters()
            val id = repository.importJob(JSONObject().put("titulo", "Oferta guardable").put("descripcion", "Python"), prefs).rows("ofertas").single().text("id")
            repository.saveJob(id, prefs)
            val reloaded = LocalJobsRepository(file).load()
            assertTrue(reloaded.rows("ofertas").single().optBoolean("guardada"))
            assertEquals(0, reloaded.getInt("valoraciones"))
            repository.saveJob(id, prefs)
            assertTrue(repository.filter(prefs.put("solo_guardadas", true)).rows("ofertas").isEmpty())
        } finally { file.delete() }
    }
    @Test fun restartKeepsProfileJobsPreferencesAndFeedback() = runBlocking {
        val file = file()
        try {
            val repository = LocalJobsRepository(file, JobSources { error("No network available") })
            val prefs = defaultFilters()
            repository.saveProfile(JSONObject().put("habilidades", "Python").put("carrera", "Sistemas").put("meses_experiencia", "6"), prefs)
            val imported = repository.importJob(JSONObject().put("titulo", "Desarrollador Python").put("descripcion", "Python y SQL").put("tipo", "empleo"), prefs)
            val id = imported.rows("ofertas").single().text("id")
            repository.vote(id, "interesa", prefs)
            val reloaded = LocalJobsRepository(file).load()
            assertEquals("Python", reloaded.getJSONObject("perfil").text("habilidades"))
            assertEquals("Sistemas", reloaded.getJSONObject("perfil").text("carrera"))
            assertEquals("6", reloaded.getJSONObject("perfil").text("meses_experiencia"))
            assertEquals("interesa", reloaded.rows("ofertas").single().text("valoracion")); assertEquals(1, reloaded.getInt("valoraciones"))
            val undo = LocalJobsRepository(file).vote(id, "", prefs)
            assertEquals(0, undo.getInt("valoraciones"))
        } finally { file.delete() }
    }
    @Test fun networkFailureKeepsExistingOffersAndReportsNotes() = runBlocking {
        val file = file()
        try {
            val repository = LocalJobsRepository(file, JobSources { error("Sin Internet") })
            val prefs = defaultFilters()
            repository.importJob(JSONObject().put("titulo", "Oferta local").put("descripcion", "Python"), prefs)
            val result = repository.search(prefs) { }
            assertEquals(1, result.getInt("total")); assertEquals(0, result.getInt("cantidad"))
            assertTrue(result.strings("avisos").any { "Sin Internet" in it })
            assertEquals(1, LocalJobsRepository(file).load().getInt("total"))
        } finally { file.delete() }
    }
    @Test fun exportRestoreIncludesLearningAndInvalidCopyDoesNotEraseData() = runBlocking {
        val a = file(); val b = file()
        try {
            val original = LocalJobsRepository(a); val prefs = defaultFilters()
            val imported = original.importJob(JSONObject().put("titulo", "Practicante TI").put("descripcion", "Estudiante. Python").put("url", "https://www.linkedin.com/jobs/view/12345"), prefs)
            original.vote(imported.rows("ofertas").single().text("id"), "interesa", prefs)
            val copy = original.export(); val target = LocalJobsRepository(b)
            val restored = target.restore(copy)
            assertEquals("linkedin", restored.rows("ofertas").single().text("fuente")); assertEquals(1, restored.getInt("valoraciones"))
            assertTrue(runCatching { target.restore("{\"perfil\":{}}") }.isFailure)
            assertEquals(1, target.load().getInt("total"))
        } finally { a.delete(); b.delete() }
    }
    @Test fun repeatedImportByLinkDeduplicatesAndBadDatesAreRejected() = runBlocking {
        val file = file()
        try {
            val repository = LocalJobsRepository(file); val prefs = defaultFilters()
            val job = JSONObject().put("titulo", "Oferta").put("descripcion", "Python").put("url", "https://example.com/job")
            repository.importJob(job, prefs); repository.importJob(job, prefs)
            assertEquals(1, repository.load().getInt("total"))
            assertTrue(runCatching { repository.importJob(job.copyJson().put("publicado", "2026-02-30"), prefs) }.isFailure)
            assertEquals(1, repository.load().getInt("total"))
        } finally { file.delete() }
    }    @Test fun cacheAvoidsRepeatedReadsAndRefreshBypassesIt() = runBlocking {
        val file = file(); val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            val adapter = JobSources { url ->
                calls.incrementAndGet()
                Jsoup.parse(if (url.endsWith(".html")) "<h1>Practicante sistemas</h1><p>Requisitos Python</p>" else "<a href='/oferta-convocatoria-test.html'>Practicante sistemas</a>", url)
            }
            val repository = LocalJobsRepository(file, adapter)
            val filters = defaultFilters().put("fuentes", org.json.JSONArray(listOf("practicas")))
            repository.search(filters) { }; val first = calls.get()
            repository.search(filters) { }; assertEquals(first, calls.get())
            repository.search(filters, refresh = true) { }; assertTrue(calls.get() > first)
            val beforeAgeChange = calls.get()
            repository.search(filters.copyJson().put("dias", "7")) { }; assertTrue(calls.get() > beforeAgeChange)
        } finally { file.delete() }
    }
    @Test fun sourceResultsAreDeliveredBeforeSlowerSourceCompletes() = runBlocking {
        val file = file(); val snapshots = mutableListOf<Int>(); var slowFinished = false
        try {
            val adapter = JobSources { url ->
                if ("convocatoriasdetrabajo" in url) { delay(800); slowFinished = true }
                Jsoup.parse(if (url.endsWith(".html")) "<h1>Practicante de sistemas</h1><p>Requisitos Python</p>" else "<a href='/oferta-convocatoria-test.html'>Practicante</a>", url)
            }
            val repository = LocalJobsRepository(file, adapter)
            val filters = defaultFilters().put("fuentes", org.json.JSONArray(listOf("practicas", "convocatorias")))
            repository.search(filters, onSource = { result ->
                snapshots.add(result.getInt("total"))
                if (snapshots.size == 1) assertFalse("La fuente rápida esperó a la lenta", slowFinished)
            }) { }
            assertEquals(listOf(1, 2), snapshots)
        } finally { file.delete() }
    }
    @Test fun duplicateIdsInOldCopyCannotCrashResultList() = runBlocking {
        val file = file()
        try {
            val repository = LocalJobsRepository(file)
            val copy = """{"perfil":{},"feedback":{},"ofertas":[{"id":"same","titulo":"Uno","descripcion":"Python","fuente":"manual"},{"id":"same","titulo":"Dos","descripcion":"SQL","fuente":"manual"}]}"""
            val result = repository.restore(copy)
            assertEquals(1, result.rows("ofertas").size)
            assertEquals(1, LocalJobsRepository(file).load().getInt("total"))
        } finally { file.delete() }
    }
    @Test fun descriptionsLoadOnDemandInsteadOfEveryResultSnapshot() = runBlocking {
        val file = file()
        try {
            val repository = LocalJobsRepository(file)
            val text = "Python y SQL. ".repeat(1000)
            val result = repository.importJob(JSONObject().put("titulo", "Oferta extensa").put("descripcion", text), defaultFilters())
            val job = result.rows("ofertas").single()
            assertFalse(job.has("descripcion")); assertEquals(text, repository.description(job.text("id")))
            assertTrue(repository.export().contains("Python y SQL"))
        } finally { file.delete() }
    }

}
