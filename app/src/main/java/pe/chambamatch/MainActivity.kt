package pe.chambamatch

import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import java.net.URLEncoder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFFF0808), secondary = Color(0xFF087F63), background = Color(0xFFF4F5FD), surface = Color.White, onSurface = Color(0xFF101728))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { JobsScreen() }
            }
        }
    }
}
private val sources = listOf("practicas" to "Prácticas.pe", "convocatorias" to "Convocatorias de Trabajo", "linkedin" to "LinkedIn", "manual" to "Importadas")
private val types = listOf("" to "Cualquiera", "empleo" to "Empleo", "practicas" to "Todas las prácticas", "preprofesional" to "Preprofesionales", "profesional" to "Profesionales")

@Composable private fun JobsScreen(vm: JobsViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refreshTracking() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(s.ready) { if (s.ready) while (true) { kotlinx.coroutines.delay(60_000); vm.refreshTracking() } }
    var extra by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().testTag(if (s.ready) "app-ready" else "app-loading")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) { Text("CM", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(8.dp)) }
            Text("ChambaMatch", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        }
        if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.message !in listOf("Lista. Tu perfil y tus anuncios se guardan en este celular.", "Cargando tus datos del celular…")) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.message, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 2.dp))
            if (s.searching) TextButton(onClick = vm::cancelSearch) { Text("Cancelar") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (extra.isNotBlank()) Column {
                TextButton(onClick = { extra = "" }) { Text("Volver al perfil") }
                Box(Modifier.weight(1f)) { if (extra == "datos") LocalData(s, vm) else Import(s, vm) }
            } else when (tab) {
                0 -> Offers(s, vm) { tab = 1 }
                1 -> Search(s, vm) { tab = 0; vm.search() }
                2 -> Tracking(s, vm)
                3 -> Profile(s, vm) { extra = it }
            }
        }
        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            listOf("Inicio", "Buscar", "Seguimiento", "Perfil").forEachIndexed { i, label ->
                NavigationBarItem(selected = tab == i, onClick = { extra = ""; tab = i }, icon = { LineIcon(listOf("work", "search", "calendar", "people")[i]) }, label = { Text(label, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFFFE8E8), selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
            }
        }
    }
}
@Composable private fun Form(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable private fun Field(label: String, value: String, enabled: Boolean = true, update: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = update, label = { Text(label) }, enabled = enabled, shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White, unfocusedBorderColor = Color(0xFFDAE0E8)), modifier = Modifier.fillMaxWidth())
}
@Composable private fun Choice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, update: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }, shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFDAE0E8))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("$label: ${options.find { it.first == value }?.second ?: value}", Modifier.weight(1f), fontWeight = FontWeight.Medium, color = if (enabled) Color(0xFF101728) else Color.Gray); Text("⌄", color = Color(0xFF63748B)) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { update(key); open = false }) } }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun GeoSelector(label: String, value: String, options: List<Pair<String,String>>, enabled: Boolean, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val displayed = options.firstOrNull { it.first == value }?.second ?: options.first().second
    Surface(Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }, shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFDAE0E8))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineIcon("pin")
            Text("$label: $displayed", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = if (enabled) Color(0xFF101728) else Color.Gray)
            Text("⌄", color = Color(0xFF63748B))
        }
    }
    if (open) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = Color.White) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)) {
                Text("Seleccionar ${label.lowercase()}", Modifier.padding(horizontal = 20.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                if (options.size > 40) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ('A'..'Z').forEach { letter -> TextButton(onClick = {
                        val index = options.indexOfFirst { RecommendationEngine.canon(it.second).startsWith(letter.lowercaseChar()) }
                        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                    }) { Text(letter.toString()) } }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).testTag("geo-options"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(options, key = { it.first }) { (key,name) ->
                        Row(Modifier.fillMaxWidth().clickable { select(key); open = false }.padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), fontSize = 17.sp)
                            RadioButton(selected = value == key, onClick = { select(key); open = false })
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Check(label: String, value: Boolean, enabled: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, enabled = enabled, role = Role.Checkbox, onValueChange = update)) { Checkbox(checked = value, onCheckedChange = null, enabled = enabled); Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp)) }
}
@Composable private fun LocalData(s: JobsState, vm: JobsViewModel) {
    var pending by remember { mutableStateOf<Uri?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.export(uri) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pending = uri }
    Form {
        Text("Todo queda en tu celular", style = MaterialTheme.typography.titleLarge)
        Text("Perfil, filtros, anuncios y valoraciones se guardan en Android. Las recomendaciones se calculan aquí. No necesitas una computadora, un servidor ni una cuenta de ChambaMatch.")
        Text("Internet se usa al buscar nuevos anuncios o abrir sus enlaces. Tu perfil y tus valoraciones no se envían a los portales; las consultas contienen el cargo y la ubicación de búsqueda.")
        Text("Al desinstalar la app se borran los datos locales. Guarda una copia si deseas conservarlos o trasladarlos a otro celular.")
        Button(onClick = { export.launch("chambamatch-copia.json") }, enabled = s.ready && !s.busy) { Text("Guardar copia de mis datos") }
        OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !s.busy) { Text("Restaurar una copia") }
        Text("Datos de países y regiones: Countries States Cities Database · ODbL 1.0.", style = MaterialTheme.typography.bodySmall)
        Text("También puedes seleccionar data/app.json de la versión para computadora. La restauración reemplaza los datos de este celular. La copia contiene datos personales: elige dónde guardarla.")
    }
    if (pending != null) AlertDialog(onDismissRequest = { pending = null }, title = { Text("¿Restaurar esta copia?") }, text = { Text("Reemplazará el perfil, los anuncios y las valoraciones actuales de este celular.") },
        confirmButton = { TextButton(onClick = { pending?.let(vm::restore); pending = null }) { Text("Restaurar") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancelar") } })
}
@Composable private fun Search(s: JobsState, vm: JobsViewModel, search: () -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    fun set(key: String, value: Any) { vm.filters(JSONObject(s.filters.toString()).put(key, value)) }
    val enabled = s.ready && !s.busy
    Column(Modifier.fillMaxSize()) {
    Form(Modifier.weight(1f)) {
        Field("Cargo o palabras clave", s.filters.text("cargo"), enabled) { set("cargo", it.take(200)) }
        val country = WorldLocations.selected(s.countries, s.filters)
        val countryOptions = listOf("" to "Todo el mundo") + s.countries.map { it.name to it.label }
        GeoSelector("País", if (s.filters.text("ambito") == "global") "" else country?.name ?: "Peru", countryOptions, enabled) { value ->
            vm.filters(WorldLocations.select(s.filters, s.countries.firstOrNull { it.name == value }))
        }
        GeoSelector("Región", s.filters.text("ubicacion"), listOf("" to "Cualquier región") + country?.regions.orEmpty().map { it to it }, enabled && country != null) { value ->
            vm.filters(s.filters.copyJson().put("ubicacion", value).put("region_seleccionada", true))
        }
        Choice("Modalidad", s.filters.text("modalidad"), listOf("" to "Cualquiera", "presencial" to "Presencial", "hibrido" to "Híbrido", "remoto" to "Remoto"), enabled) { set("modalidad", it) }
        if (CountryScope.canonicalName(s.filters.text("pais")) in listOf("United States", "Canada")) Text("Prueba cargos en inglés, como software engineer o data analyst, para encontrar los títulos usados en esos países.", style = MaterialTheme.typography.bodySmall)
        if (s.filters.text("ambito") in listOf("pais", "global")) Text("LinkedIn busca fuera de Perú. Remoto puede exigir residencia o permiso de trabajo en el país del anuncio; revisa los requisitos.", style = MaterialTheme.typography.bodySmall)
        Check("Ajustar a mi condición y experiencia", s.filters.optBoolean("ajustar_perfil", true), enabled) { set("ajustar_perfil", it) }
        Choice("Tipo de oportunidad", s.filters.text("tipo"), types, enabled) { set("tipo", it) }

        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Cerrar opciones adicionales" else "Más filtros y fuentes") }
        if (advanced) {
            Choice("Publicadas en", s.filters.text("dias"), listOf("" to "Cualquier fecha", "1" to "24 horas", "3" to "3 días", "7" to "7 días", "14" to "14 días", "30" to "30 días"), enabled) { set("dias", it) }
            Field("Sueldo mínimo en soles al mes", s.filters.text("sueldo"), enabled) { if (it.length <= 8 && it.all(Char::isDigit)) set("sueldo", it) }
            Choice("Orden", s.filters.text("orden").ifBlank { "compatibilidad" }, listOf("compatibilidad" to "Compatibilidad", "recientes" to "Más recientes", "cierre" to "Próximos a cerrar"), enabled) { set("orden", it) }
            Text("Fuentes", style = MaterialTheme.typography.titleMedium)
            sources.filter { s.filters.text("ambito") !in listOf("pais", "global") || it.first in listOf("linkedin", "manual") }.forEach { (key, name) -> Check(name, key in s.filters.strings("fuentes"), enabled) { selected ->
                val current = s.filters.strings("fuentes").toMutableSet(); if (selected) current.add(key) else current.remove(key); set("fuentes", JSONArray(current.toList()))
            } }
            Check("Mostrar sólo requisitos comprobados con mi perfil", s.filters.optBoolean("requisitos_confirmados"), enabled) { set("requisitos_confirmados", it) }
            Check("Incluir fechas desconocidas", s.filters.optBoolean("sinfecha", true), enabled) { set("sinfecha", it) }
            Check("Incluir anuncios con datos incompletos", s.filters.optBoolean("desconocidos", true), enabled) { set("desconocidos", it) }
            Check("Mostrar vencidas", s.filters.optBoolean("vencidas"), enabled) { set("vencidas", it) }
        }
    }
    Button(onClick = search, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(54.dp), shape = RoundedCornerShape(16.dp)) { LineIcon("search", Color.White); Spacer(Modifier.width(10.dp)); Text("Buscar trabajos", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun Profile(s: JobsState, vm: JobsViewModel, more: (String) -> Unit) {
    var draft by rememberSaveable(s.profile.toString()) { mutableStateOf(s.profile.toString()) }
    val profile = JSONObject(draft)
    fun set(key: String, value: String) { draft = JSONObject(draft).put(key, value).toString() }
    Form {
        Text("Mi perfil · 2.6.0", style = MaterialTheme.typography.titleLarge)
        Text("Describe tu experiencia (ej. «Tengo seis meses de experiencia»), o indica tus meses totales. Los requisitos del anuncio se comparan con tu perfil guardado.", style = MaterialTheme.typography.bodySmall)
        listOf("carrera" to "Carrera o especialidad", "cargos_objetivo" to "Cargos que te interesan", "habilidades" to "Habilidades separadas por comas", "meses_experiencia" to "Meses de experiencia (0 si aún no tienes)", "ciclo" to "Ciclo actual", "experiencia" to "Experiencia, idiomas y otros datos").forEach { (key, name) ->
            Field(name, profile.text(key), !s.busy) { if (key !in listOf("meses_experiencia", "ciclo") || it.all(Char::isDigit)) set(key, it) }
        }
        val detected = RecommendationEngine.experienceMonths(profile.text("experiencia"))
        if (profile.text("meses_experiencia").isBlank() && detected != null) Text("Experiencia reconocida en tu texto: $detected meses", color = MaterialTheme.colorScheme.secondary)
        Choice("Situación académica", profile.text("situacion"), listOf("" to "Sin indicar") + listOf("Estudiante", "Egresado", "Bachiller", "Titulado").map { it to it }, !s.busy) { set("situacion", it) }
        Text("La compatibilidad es orientativa. Revisa las bases completas antes de postular. Tus valoraciones ajustan el orden sin cambiar tus filtros.")
        Button(onClick = { vm.saveProfile(JSONObject(draft)) }, enabled = s.ready && !s.busy, modifier = Modifier.fillMaxWidth()) { Text("Guardar perfil") }
        TextButton(onClick = { more("añadir") }) { Text("Añadir una oferta") }
        TextButton(onClick = { more("datos") }) { Text("Mis datos y copia de seguridad") }
    }
}
@Composable private fun Tracking(s: JobsState, vm: JobsViewModel) {
    val jobs = s.result.rows("seguimiento")
    var mode by rememberSaveable { mutableStateOf("activa") }
    val shown = jobs.filter { when(mode) { "activa" -> it.text("estado") !in listOf("vencida", "cerrada") && it.text("postulacion") !in listOf("postulada", "descartada"); "postulada" -> it.text("postulacion") == "postulada"; else -> true } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Tu agenda", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            OutlinedButton(onClick = vm::reviewAgenda, enabled = !s.busy && jobs.isNotEmpty()) { Text("Revisar fechas en las fuentes") }
            s.result.strings("revision_agenda").forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Choice("Ver", mode, listOf("activa" to "Por postular", "postulada" to "Postuladas", "todas" to "Todas, incluidas vencidas")) { mode = it }
            if (shown.isEmpty()) Text("Marca Me interesa o guarda una oferta para seguir sus fechas y preparar los requisitos.", modifier = Modifier.padding(vertical = 20.dp))
        }
        items(shown, key = { "track:${it.text("id")}" }) { job ->
            var expanded by rememberSaveable(job.text("id")) { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFDBE1F1))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val days = RecommendationEngine.calendarDaysRemaining(job)
                    val label = when { job.text("estado") in listOf("vencida", "cerrada") -> "Plazo cerrado"; days == 0 -> "Postula hoy"; days != null -> "Quedan $days días"; else -> "Fecha de cierre por confirmar" }
                    Surface(color = if (days != null && days in 0..3) Color(0xFFFFE5A1) else Color(0xFFEDE8FF), shape = RoundedCornerShape(12.dp)) { Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp)) }
                    Text(job.text("titulo"), fontSize = 21.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                    Text(job.text("empresa"), color = Color(0xFF63748B))
                    InfoLine("calendar", "Cierre: ", job.text("cierre").ifBlank { "No publicado; consulta las bases" })
                    if (job.text("cierre_evidencia").isNotBlank()) Text(job.text("cierre_evidencia"), style = MaterialTheme.typography.bodySmall)
                    Choice("Estado de postulación", job.text("postulacion").ifBlank { "pendiente" }, listOf("pendiente" to "Pendiente", "preparando" to "Preparando", "postulada" to "Postulada", "descartada" to "Descartada"), !s.busy) { vm.track(job.text("id"), state = it) }
                    val checks = job.rows("requisitos")
                    val ready = job.strings("preparados")
                    Text("${checks.count { it.text("clave") in ready }} de ${checks.size} requisitos preparados", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Cerrar mi lista" else "Organizar requisitos") }
                    if (expanded) {
                        Text("Última lectura: ${job.text("revisado").take(10).ifBlank { "sin registrar" }}", style = MaterialTheme.typography.bodySmall)
                        Text("Preparado significa que revisaste o reuniste la evidencia. La casilla no cambia la compatibilidad de tu perfil.", style = MaterialTheme.typography.bodySmall)
                        if (checks.isEmpty()) Text("No se reconocieron requisitos. Revisa la descripción y las bases del anuncio.")
                        checks.forEach { check ->
                            Column {
                                Check("${check.text("nombre")} · ${check.text("estado")}", check.text("clave") in ready, !s.busy) { vm.track(job.text("id"), requirement = check.text("clave")) }
                                Text(check.text("evidencia"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    val uri = LocalUriHandler.current
                    var error by remember { mutableStateOf(false) }
                    Button(onClick = { error = runCatching { uri.openUri(job.text("url")) }.isFailure }, enabled = job.text("url").isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Abrir anuncio y bases") }
                    if (error) Text("No se pudo abrir el anuncio.")
                }
            }
        }
    }
}
@Composable private fun Offers(s: JobsState, vm: JobsViewModel, editSearch: () -> Unit) {
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var alternatives by rememberSaveable { mutableStateOf(false) }
    var shown by remember(s.result) { mutableIntStateOf(40) }
    val jobs = remember(s.result) { s.result.rows("ofertas") }
    val otherJobs = remember(s.result) { s.result.rows("alternativas") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Para ti", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${jobs.size} coincidencias", style = MaterialTheme.typography.labelMedium, color = Color(0xFF63748B))
                TextButton(onClick = { vm.search(refresh = true) }, enabled = s.ready && !s.busy) { Text("Actualizar") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(WorldLocations.selected(s.countries, s.filters)?.label ?: "Todo el mundo", style = MaterialTheme.typography.labelMedium, color = Color(0xFF63748B))
                if (s.filters.text("ubicacion").isNotBlank()) Text(s.filters.text("ubicacion"), style = MaterialTheme.typography.labelMedium, color = Color(0xFF63748B), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                FilterChip(selected = s.filters.optBoolean("solo_guardadas"), onClick = { vm.filters(s.filters.copyJson().put("solo_guardadas", !s.filters.optBoolean("solo_guardadas"))) }, enabled = !s.busy, label = { Text("Guardadas") })
            }
            if (jobs.isEmpty()) {
                Text(if (s.searching) "Consultando las fuentes… Puedes cancelar sin perder tus ofertas guardadas." else if (s.result.optInt("total") == 0) "Busca trabajos para cargar tus primeras recomendaciones." else "Sin coincidencias con tus filtros actuales.")
                OutlinedButton(onClick = editSearch) { Text("Buscar nuevos trabajos") }
                if (!s.searching) {
                    s.result.optJSONObject("ocultas")?.let { hidden -> if (hidden.length() > 0) Text("Se ocultaron por: " + hidden.keys().asSequence().joinToString(" · ") { "$it (${hidden.optInt(it)})" }, color = Color(0xFF63748B)) }
                    s.result.optJSONObject("busquedas")?.let { searches -> sources.filter { it.first in s.filters.strings("fuentes") }.forEach { (key, _) -> searches.optJSONObject(key)?.strings("avisos")?.filter { "no pudo" in it || "no permitió" in it || "no devolvió" in it || "incompleta" in it }?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } } }
                }
            }
        }
        items(jobs.take(shown), key = { "visible:${it.text("id")}" }) { JobCard(it, s.busy, vm) }
        if (jobs.size > shown) item { OutlinedButton(onClick = { shown += 40 }) { Text("Mostrar más ofertas") } }
        item {
            TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Ocultar estado de fuentes" else "Estado de fuentes y filtros") }
            if (diagnostics) {
                s.result.optJSONObject("fuentes")?.let { counts -> sources.forEach { (key, name) -> counts.optJSONObject(key)?.let { Text("$name: ${it.optInt("visibles")} visibles / ${it.optInt("guardadas")} guardadas") } } }
                s.result.optJSONObject("ocultas")?.let { hidden -> if (hidden.length() > 0) Text("Ocultas por tus filtros: " + hidden.keys().asSequence().joinToString(" · ") { "$it: ${hidden.optInt(it)}" }) }
                val searches = s.result.optJSONObject("busquedas")
                sources.filter { it.first != "manual" }.forEach { (key, name) -> searches?.optJSONObject(key)?.let { search ->
                    Text(name, fontWeight = FontWeight.Bold)
                    search.strings("avisos").forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                } }
                val uri = LocalUriHandler.current
                var openError by remember { mutableStateOf(false) }
                OutlinedButton(onClick = {
                    val query = s.filters.text("cargo").ifBlank { s.profile.text("cargos_objetivo").ifBlank { s.profile.text("carrera") } }
                    val location = searchPlace(s.filters.text("ubicacion"), searchCountry(s.filters))
                    val link = "https://www.linkedin.com/jobs/search/?keywords=${URLEncoder.encode(query, "UTF-8")}&location=${URLEncoder.encode(location, "UTF-8")}" + if (s.filters.text("tipo") in listOf("practicas", "preprofesional", "profesional")) "&f_JT=I" else ""
                    val remote = if (s.filters.text("modalidad") == "remoto") "&f_WT=2" else ""
                    openError = runCatching { uri.openUri(link + remote) }.isFailure
                }) { Text("Buscar directamente en LinkedIn") }
                if (openError) Text("No se pudo abrir el navegador.")
            }
        }
        if (otherJobs.isNotEmpty()) {
            item { TextButton(onClick = { alternatives = !alternatives }) { Text(if (alternatives) "Ocultar alternativas" else "Alternativas fuera de tus filtros") } }
            if (alternatives) items(otherJobs, key = { "alternative:${it.text("id")}" }) { JobCard(it, s.busy, vm, true) }
        }
    }
}
@Composable private fun JobCard(job: JSONObject, busy: Boolean, vm: JobsViewModel, alternative: Boolean = false) {
    var details by rememberSaveable(job.text("id")) { mutableStateOf(false) }
    var description by remember(job.text("id")) { mutableStateOf<String?>(null) }
    LaunchedEffect(details, job.text("id")) {
        if (details && description == null) {
            try { description = vm.description(job.text("id")) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { description = "No se pudo leer la descripción guardada." }
        }
    }
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    var linkError by remember { mutableStateOf("") }
    val ink = Color(0xFF101728)
    val days = RecommendationEngine.calendarDaysRemaining(job)
    val closed = job.text("estado") in listOf("vencida", "cerrada")
    val deadline = when { closed -> "Cerrada"; days == 0 -> "Finaliza hoy"; days != null -> "Cierra en $days días"; else -> "Cierre sin confirmar" }
    val salary = job.number("salario")?.let { "${if (job.text("moneda") == "PEN") "S/" else job.text("moneda")} ${java.text.DecimalFormat("#,##0.##").format(it)}" } ?: "Sueldo sin indicar"
    val region = job.text("region").ifBlank { job.text("ubicacion") }.ifBlank { "Ubicación sin indicar" }
    val contract = types.find { it.first == job.text("tipo") }?.second ?: job.text("tipo")
    val checks = job.rows("requisitos")
    val summary = listOf("compatible" to "compatibles", "brecha" to "por revisar", "por confirmar" to "por confirmar").mapNotNull { (key, label) -> checks.count { it.text("estado") == key }.takeIf { it > 0 }?.let { "$it $label" } }.joinToString(" · ")
    Card(Modifier.fillMaxWidth().testTag("job-card"), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFE1E5F1))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = when { closed -> Color(0xFFEDF1F5); days == 0 -> Color(0xFFFFE5A1); days != null -> Color(0xFFE6F8EE); else -> Color(0xFFEDF1F5) }, shape = RoundedCornerShape(10.dp)) { Text(deadline, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) }
                Spacer(Modifier.weight(1f))
                if (!job.isNull("puntaje")) Text("${job.optInt("puntaje")}% afinidad", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { vm.saveJob(job.text("id")) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = if (job.optBoolean("guardada")) "Quitar de guardadas" else "Guardar oferta" }) { LineIcon("bookmark", if (job.optBoolean("guardada")) MaterialTheme.colorScheme.primary else ink) }
            }
            Text(job.text("empresa").ifBlank { "Empresa sin indicar" }, color = Color(0xFF63748B), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(job.text("titulo"), fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = if (details) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { details = !details })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(region, modifier = Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF52647C), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(salary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(listOf(contract, job.text("modalidad"), job.text("vacantes").takeIf { it.isNotBlank() }?.let { "$it vacantes" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFF63748B), fontSize = 12.sp)
            if (summary.isNotBlank()) Text(summary, fontSize = 12.sp, color = Color(0xFF52647C))
            if (job.text("evaluacion") == "Sin descripción") Text("Sin descripción: requisitos pendientes", color = Color(0xFF9E6000), fontSize = 12.sp)
            if (alternative) Text("Fuera de tus filtros: ${job.strings("excluida_por").joinToString()}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Ocultar requisitos" else "Requisitos") }
                Spacer(Modifier.weight(1f))
                FilterChip(selected = job.text("valoracion") == "interesa", onClick = { vm.vote(job.text("id"), if (job.text("valoracion") == "interesa") "" else "interesa") }, enabled = !busy, label = { Text("Me interesa") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (job.text("url").startsWith("https://") || job.text("url").startsWith("http://")) linkError = runCatching { uri.openUri(job.text("url")) }.exceptionOrNull()?.let { "No se pudo abrir el anuncio." }.orEmpty() else details = !details }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Ver anuncio", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                OutlinedIconButton(onClick = {
                    val message = "${job.text("titulo")}\n${job.text("empresa")} · ${job.text("ubicacion")}\n${job.text("url")}"
                    linkError = runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, message) }, "Compartir oferta")) }.exceptionOrNull()?.let { "No se pudo compartir." }.orEmpty()
                }, modifier = Modifier.semantics { contentDescription = "Compartir oferta" }, shape = RoundedCornerShape(12.dp)) { LineIcon("share", ink) }
            }
            AnimatedVisibility(details) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val closing = RecommendationEngine.date(job.text("cierre"), true)?.atZone(RecommendationEngine.lima)?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("es")))
                    InfoLine("calendar", "Cierre: ", closing ?: "Por confirmar")
                    Text(job.number("antiguedad")?.let { "Publicado hace ${it.toInt()} días" } ?: "Fecha de publicación sin indicar", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FactTile("Sueldo", salary, Modifier.weight(1f)); FactTile("Región", region, Modifier.weight(1f)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FactTile("Vacantes", job.text("vacantes").ifBlank { "Sin indicar" }, Modifier.weight(1f)); FactTile("Contrato", contract, Modifier.weight(1f)) }
                    Text((sources.find { it.first == job.text("fuente") }?.second ?: job.text("fuente")), style = MaterialTheme.typography.labelSmall)
                    Text(if (job.isNull("puntaje")) "Completa tu perfil para comparar" else "${job.optInt("puntaje")}/100 · compatibilidad orientativa", fontWeight = FontWeight.Bold)
                    job.strings("razones").forEach { Text("• $it") }
                    checks.forEach { check -> Surface(color = when (check.text("estado")) { "compatible" -> Color(0xFFEAF8EF); "brecha" -> Color(0xFFFFEDEA); else -> Color(0xFFF0F3F7) }, shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(12.dp)) { Text("${check.text("nombre")} · ${if (check.optBoolean("obligatorio", true)) "Obligatorio" else "Deseable"} · ${check.text("estado")}", fontWeight = FontWeight.Bold); Text(check.text("evidencia")) } } }
                    Text(job.text("aviso"), style = MaterialTheme.typography.bodySmall)
                    Text(description?.ifBlank { "Descripción no proporcionada por la fuente." } ?: "Cargando descripción…")
                    FilterChip(selected = job.text("valoracion") == "no_interesa", onClick = { vm.vote(job.text("id"), "no_interesa") }, enabled = !busy, label = { Text("No me interesa") })
                    if (job.text("valoracion").isNotBlank()) TextButton(onClick = { vm.vote(job.text("id"), "") }, enabled = !busy) { Text("Deshacer valoración") }
                }
            }
            if (linkError.isNotBlank()) Text(linkError)
        }
    }
}
@Composable private fun FactTile(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = Color(0xFFF0F4F8), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { LineIcon(when (label) { "Contrato" -> "document"; "Sueldo" -> "money"; "Región" -> "pin"; else -> "people" }); Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF63748B)) }
            Text(value, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 23.sp)
        }
    }
}

@Composable private fun Import(s: JobsState, vm: JobsViewModel) {
    var draft by rememberSaveable { mutableStateOf("{}") }
    val job = JSONObject(draft)
    LaunchedEffect(s.message) { if (s.message == "Oferta guardada en este celular.") draft = "{}" }
    Form {
        Text("Añade una oferta por su descripción", style = MaterialTheme.typography.titleLarge)
        Text("Pega el título, enlace y requisitos de un anuncio. Los enlaces de LinkedIn se guardan con esa fuente.")
        listOf("titulo" to "Título", "empresa" to "Empresa", "url" to "Enlace original", "ubicacion" to "Ubicación", "publicado" to "Publicación (AAAA-MM-DD, opcional)", "cierre" to "Cierre (AAAA-MM-DD, opcional)", "descripcion" to "Descripción y requisitos").forEach { (key, name) -> Field(name, job.text(key), !s.busy) { draft = JSONObject(draft).put(key, it).toString() } }
        Choice("Tipo", job.text("tipo").ifBlank { "empleo" }, types.filter { it.first.isNotBlank() }, !s.busy) { draft = JSONObject(draft).put("tipo", it).toString() }
        Choice("Modalidad", job.text("modalidad"), listOf("" to "Sin indicar", "presencial" to "Presencial", "hibrido" to "Híbrido", "remoto" to "Remoto"), !s.busy) { draft = JSONObject(draft).put("modalidad", it).toString() }
        Button(onClick = { vm.importJob(JSONObject(draft).apply { if (text("tipo").isBlank()) put("tipo", "empleo") }) }, enabled = s.ready && !s.busy) { Text("Guardar oferta") }
    }
}

@Composable private fun InfoLine(icon: String, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LineIcon(icon)
        Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }; append(value) }, fontSize = 16.sp, lineHeight = 23.sp)
    }
}
/** Small native outline icons: no remote artwork or bitmap requests. */
@Composable private fun LineIcon(kind: String, color: Color = Color(0xFF71829B)) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24; val stroke = Stroke(1.8f * u)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x*u,y*u), Offset(x2*u,y2*u), 1.8f*u)
        when(kind) {
            "search" -> { drawCircle(color, 7*u, Offset(10*u,10*u), style=stroke); line(15f,15f,22f,22f) }
            "people" -> { drawCircle(color, 3.5f*u, Offset(9*u,6*u), style=stroke); drawArc(color, 180f, 180f, false, Offset(2*u,12*u), Size(14*u,16*u), style=stroke); drawArc(color, -70f, 140f, false, Offset(13*u,2*u), Size(8*u,8*u), style=stroke); line(18f,13f,22f,18f) }
            "money" -> { drawCircle(color, 10*u, center, style=stroke); drawContext.canvas.nativeCanvas.apply { val paint=android.graphics.Paint().apply { this.color=color.toArgb(); textSize=16*u; textAlign=android.graphics.Paint.Align.CENTER }; drawText("$",12*u,18*u,paint) } }
            "pin" -> { val path=Path().apply { moveTo(12*u,22*u); cubicTo(0f,11*u,3*u,2*u,12*u,2*u); cubicTo(21*u,2*u,24*u,11*u,12*u,22*u) }; drawPath(path,color,style=stroke); drawCircle(color,3*u,Offset(12*u,9*u),style=stroke) }
            "bookmark" -> { val path=Path().apply { moveTo(5*u,3*u); lineTo(19*u,3*u); lineTo(19*u,22*u); lineTo(12*u,18*u); lineTo(5*u,22*u); close() }; drawPath(path,color,style=stroke) }
            "share" -> { val path=Path().apply { moveTo(3*u,20*u); cubicTo(4*u,10*u,7*u,7*u,14*u,7*u); lineTo(14*u,2*u); lineTo(22*u,10*u); lineTo(14*u,17*u); lineTo(14*u,12*u); cubicTo(9*u,12*u,6*u,14*u,3*u,20*u) }; drawPath(path,color,style=stroke) }
            "arrow" -> { line(2f,12f,21f,12f); line(15f,6f,21f,12f); line(15f,18f,21f,12f) }
            else -> { drawRoundRect(color, Offset(3*u,5*u),Size(18*u,16*u), androidx.compose.ui.geometry.CornerRadius(2*u), style=stroke); if(kind=="calendar") { line(3f,10f,21f,10f); line(8f,2f,8f,7f); line(16f,2f,16f,7f) } else { line(7f,10f,17f,10f); line(7f,15f,15f,15f) } }
        }
    }
}
