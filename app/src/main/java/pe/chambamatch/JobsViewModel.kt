package pe.chambamatch

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class JobsState(
    val ready: Boolean = false, val busy: Boolean = false, val searching: Boolean = false, val message: String = "Cargando tus datos del celular…",
    val countries: List<WorldCountry> = emptyList(), val profile: JSONObject = JSONObject(), val filters: JSONObject = defaultFilters(), val result: JSONObject = JSONObject()
)
class JobsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalJobsRepository(File(application.filesDir, "chambamatch.json"))
    private val mutable = MutableStateFlow(JobsState())
    val state = mutable.asStateFlow()
    private var running: Job? = null
    private var filtering: Job? = null
    private var generation = 0
    init {
        // Removes unused credentials from the former server-dependent version.
        application.getSharedPreferences("connection", 0).edit().clear().apply()
        task {
            try {
                val countries = WorldLocations.load(application)
                val result = repository.load(); accept(result, true)
                val filters = WorldLocations.migrate(countries, mutable.value.filters)
                accept(repository.filter(filters))
                mutable.update { it.copy(countries = countries, filters = filters) }
                if (hasProfile() && mutable.value.result.rows("recomendaciones").isEmpty()) {
                    withTimeoutOrNull(12_000) {
                        repository.search(homeQuery(), persistPreferences = false, onSource = { accept(it) }) { }
                    }
                    accept(repository.filter(filters))
                }
                mutable.update { it.copy(message = "Lista. Tu perfil y tus anuncios se guardan en este celular.") }
            } finally { mutable.update { it.copy(ready = true) } }
        }
    }
    private fun accept(result: JSONObject, preferences: Boolean = false) {
        mutable.update { current ->
            val profile = result.optJSONObject("perfil") ?: current.profile
            current.copy(profile = profile, result = result, filters = if (preferences) profile.optJSONObject("preferencias") ?: defaultFilters() else current.filters)
        }
    }
    suspend fun description(id: String): String = repository.description(id)
    private fun task(searching: Boolean = false, operation: suspend () -> Unit) {
        if (mutable.value.busy) return
        filtering?.cancel()
        val run = ++generation
        mutable.update { it.copy(busy = true, searching = searching, message = "Procesando…") }
        running = viewModelScope.launch {
            try { operation() }
            catch (e: CancellationException) {
                if (run == generation) mutable.update { it.copy(message = "Búsqueda detenida. Las ofertas recibidas se conservan.") }
                throw e
            } catch (e: Exception) {
                if (run == generation) mutable.update { it.copy(message = e.message ?: "No se pudo completar la operación. Tus ofertas guardadas se conservan.") }
            } finally { if (run == generation) mutable.update { it.copy(busy = false, searching = false) } }
        }
    }
    fun cancelSearch() { if (mutable.value.searching) { mutable.update { it.copy(message = "Deteniendo búsqueda…") }; running?.cancel() } }
    fun filters(value: JSONObject) {
        if (mutable.value.busy) return
        val current = value.copyJson()
        mutable.update { it.copy(filters = current) }
        filtering?.cancel()
        filtering = viewModelScope.launch {
            delay(400)
            try {
                val result = repository.filter(current)
                if (!mutable.value.busy && mutable.value.filters.toString() == current.toString()) accept(result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "No se pudieron aplicar los filtros; las ofertas guardadas se conservan.") } }
        }
    }
    fun applyFilters() = task { accept(repository.filter(mutable.value.filters)); mutable.update { it.copy(message = "Filtros aplicados en el celular.") } }
    fun search(refresh: Boolean = false) = task(searching = true) {
        val filters = mutable.value.filters.copyJson()
        val run = generation
        val result = repository.search(filters, refresh = refresh, onSource = { partial ->
            withContext(Dispatchers.Main) { ensureActive(); if (run == generation) accept(partial) }
        }) { message -> if (run == generation) mutable.update { it.copy(message = message) } }
        accept(result)
        mutable.update { it.copy(message = "${result.rows("ofertas").size} coincidencias · ${result.optInt("cantidad")} anuncios revisados. Tus filtros se guardaron.") }
    }
    private fun hasProfile(): Boolean = listOf("carrera", "habilidades", "cargos_objetivo").any { mutable.value.profile.text(it).isNotBlank() }
    private fun homeQuery(): JSONObject = mutable.value.filters.copyJson().apply {
        val profile = mutable.value.profile
        put("cargo", profile.text("cargos_objetivo").ifBlank { profile.text("carrera") }.ifBlank { profile.text("habilidades").split(',').take(2).joinToString(" ") })
        put("ajustar_perfil", false); put("requisitos_confirmados", false)
    }
    fun refreshHome() = task(searching = true) {
        if (!hasProfile()) { accept(repository.filter(mutable.value.filters)); mutable.update { it.copy(message = "Completa y guarda tu perfil para personalizar las ofertas.") }; return@task }
        val result = repository.search(homeQuery(), refresh = true, persistPreferences = false, onSource = { accept(it) }) { }
        accept(repository.filter(mutable.value.filters))
        mutable.update { it.copy(message = if (result.optInt("cantidad") == 0) "No se recibieron anuncios nuevos. Las ofertas guardadas se conservaron." else "Recomendaciones actualizadas según tu perfil.") }
    }
    fun saveProfile(profile: JSONObject) = task {
        accept(repository.saveProfile(profile, mutable.value.filters)); mutable.value = mutable.value.copy(message = "Perfil y preferencias guardados en este celular.")
    }
    fun saveJob(id: String) = task {
        accept(repository.saveJob(id, mutable.value.filters))
        mutable.update { it.copy(message = "Lista de guardadas actualizada.") }
    }
    fun reviewAgenda() = task(searching = true) {
        mutable.update { it.copy(message = "Revisando fechas y descripciones de tus ofertas…") }
        val filters = mutable.value.filters.copyJson()
        val result = repository.refreshAgenda(filters) { accept(it) }
        accept(result)
        mutable.update { it.copy(message = "Revisión de ${result.optInt("revisadas_agenda")} ofertas terminada. Consulta los avisos de cada fuente en tu agenda.") }
    }
    fun track(id: String, state: String? = null, requirement: String? = null) = task { accept(repository.track(id, state, requirement, mutable.value.filters)); mutable.update { it.copy(message = "Seguimiento actualizado en este celular.") } }
    fun refreshTracking() {
        if (mutable.value.busy || !mutable.value.ready) return
        val current = mutable.value.filters.copyJson()
        viewModelScope.launch { runCatching { repository.filter(current) }.onSuccess { if (!mutable.value.busy && mutable.value.filters.toString() == current.toString()) accept(it) } }
    }
    fun vote(id: String, value: String) = task {
        accept(repository.vote(id, value, mutable.value.filters)); mutable.value = mutable.value.copy(message = "Preferencia actualizada. Aprendizaje procesado en el celular.")
    }
    fun importJob(job: JSONObject) = task {
        accept(repository.importJob(job, mutable.value.filters)); mutable.value = mutable.value.copy(message = "Oferta guardada en este celular.")
    }
    fun export(uri: Uri) = task {
        val json = repository.export()
        withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("No se pudo guardar la copia.") }
        mutable.value = mutable.value.copy(message = "Copia guardada. Incluye tu perfil, anuncios y valoraciones.")
    }
    fun restore(uri: Uri) = task {
        val text = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; bytes.write(buffer, 0, size); require(bytes.size() <= 8_000_000) { "La copia supera 8 MB." } }
                bytes.toString("UTF-8")
            } ?: error("No se pudo abrir la copia.")
        }
        accept(repository.restore(text), true); mutable.value = mutable.value.copy(ready = true, message = "Copia restaurada en este celular.")
    }
}
