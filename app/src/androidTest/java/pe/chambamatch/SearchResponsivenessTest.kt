package pe.chambamatch

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class SearchResponsivenessTest {
    @Test fun thousandsOfLinksDoNotBlockMainThreadAndSearchCanBeCancelled() = runBlocking {
        val mainTicks = AtomicInteger()
        val fakeReader = JobSources { url ->
            assertNotEquals("El análisis continúa en el hilo de pantalla", Looper.getMainLooper(), Looper.myLooper())
            if (url.endsWith(".html")) delay(5_000)
            Jsoup.parse((1..4000).joinToString("") { "<article><p>Ingeniería de sistemas Lima</p><a href='/oferta-convocatoria-$it.html'>Practicante sistemas</a></article>" }, url)
        }
        val ticker = launch(Dispatchers.Main) { while (isActive) { mainTicks.incrementAndGet(); delay(20) } }
        val search = async(Dispatchers.Main) { fakeReader.search("practicas", "sistemas", "Lima", "practicas") }
        delay(700)
        assertTrue("La pantalla se bloqueó durante el análisis", mainTicks.get() >= 8)
        val cancelTime = measureTimeMillis { search.cancelAndJoin() }
        ticker.cancelAndJoin()
        assertTrue(search.isCancelled)
        assertTrue("Cancelar tardó demasiado: $cancelTime ms", cancelTime < 1500)
    }
}
