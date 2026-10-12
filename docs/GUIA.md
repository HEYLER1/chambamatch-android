# ChambaMatch Perú — Android autónomo en Kotlin

La app funciona en Android 8 o posterior **sin computadora, sin servidor y sin Python**. Todo el perfil, filtros, anuncios y valoraciones se guarda en el almacenamiento privado del celular. Kotlin procesa la compatibilidad y el aprendizaje local. Internet sólo es necesario para leer nuevos anuncios y abrir los enlaces originales.

## Instalar y usar

Compila o instala el APK de prueba (`apk/ChambaMatch-2.8.0.apk`, o `app/build/outputs/apk/debug/app-debug.apk` después de compilar) y abre **ChambaMatch Perú**. No hay pantalla de conexión ni claves de servidor.

Instala la actualización sobre la app existente, sin desinstalar, para conservar tus datos.

1. Completa **Perfil** con tu carrera, condición académica, experiencia y habilidades.
2. En **Buscar**, selecciona **País** entre 250 países y territorios, incluyendo Brasil, o **Todo el mundo**. Selecciona **Región** o **Cualquier región**; ambos controles son listas, sin escribir ubicaciones. Elige cargo, modalidad y tipo; **Más filtros y fuentes** contiene los ajustes adicionales.
3. Pulsa **Buscar trabajos**. Se abre **Inicio** y aparecen los resultados conforme termina cada fuente. Puedes cancelar la búsqueda.
4. En **Inicio**, revisa las evidencias del anuncio y los requisitos obligatorios o deseables. **Me interesa / No me interesa** personalizan el orden; puedes deshacer cada valoración. **Me interesa** y el marcador incorporan la oferta a **Seguimiento**, donde organizas la postulación y preparas cada requisito.
5. Al cambiar filtros, las ofertas guardadas se filtran automáticamente. No necesita Internet. **Actualizar** vuelve a consultar las fuentes.

La app empieza sin anuncios simulados. Los filtros no se amplían automáticamente cuando no hay coincidencias; las alternativas se identifican como fuera de los filtros.

## Fuentes

- **Prácticas.pe y Convocatorias de Trabajo:** lectura HTTPS desde Android de las páginas públicas, secciones relacionadas observadas y detalles de anuncios. Cada búsqueda tiene un límite de 12 anuncios por fuente, con un máximo de dos consultas HTTP simultáneas en toda la app. La cobertura es parcial; no revisa todo el portal.
- **LinkedIn:** consulta acotada de listados y detalles públicos, sin cuenta, cookies, proxies ni evasión de bloqueos. Puede restringir la lectura o devolver anuncios sin descripción. Los avisos lo explican y no se inventan requisitos ni fechas. El botón **Abrir mi búsqueda en LinkedIn** permite buscar en el navegador; después puedes pegar una descripción en **Perfil → Añadir una oferta**. Los enlaces de LinkedIn importados quedan identificados como esa fuente.
- **Añadir:** permite analizar localmente una descripción que pegues de cualquier anuncio. Las fechas son opcionales y deben corresponder a las que figure en el anuncio.

Cuando no hay conexión o falla una fuente, los anuncios guardados se conservan. Se puede revisar el perfil, aplicar filtros, importar descripciones y valorar anuncios sin red. Las fuentes pueden cambiar su HTML o limitar las consultas; en ese caso hará falta actualizar el lector o añadir anuncios manualmente.

## Países y condiciones personales

Prácticas.pe y Convocatorias de Trabajo cubren Perú. Fuera de Perú, la app consulta LinkedIn y permite añadir anuncios manualmente. La búsqueda internacional no agrega Perú al país solicitado. **Todo el mundo** consulta Worldwide; elige **Remoto** para buscar esa modalidad; LinkedIn puede limitar o cambiar esa búsqueda pública. Un puesto remoto puede exigir residencia o autorización laboral en un país: la app no certifica esa elegibilidad.

**Ajustar a mi condición y experiencia** aparta ofertas con brechas detectadas en los requisitos. Los datos por confirmar permanecen visibles salvo que actives **Mostrar sólo requisitos comprobados con mi perfil**. En Perfil puedes escribir «Tengo seis meses de experiencia»; una duración clara se utiliza si el campo numérico está vacío. El campo numérico tiene prioridad. Múltiples duraciones o experiencia específica quedan por confirmar; no se suman ni se usan los requisitos del anuncio como experiencia del postulante.

Las tarjetas muestran la empresa, el título grande, el cierre y cuatro bloques: **Sueldo, Región, Vacantes y Contrato**. Los datos ausentes se indican sin inventarlos. El marcador guarda ofertas; **Guardadas** permite filtrarlas, independientemente del aprendizaje. La comparación de requisitos y las valoraciones se despliegan al pulsar **Requisitos**. **Compartir** abre el selector Android y **Ver anuncio original** abre la fuente.

## Rendimiento y búsquedas

El análisis HTML y la clasificación se ejecutan fuera del hilo de la pantalla. Cada fuente tiene un presupuesto de 45 segundos; si se agota, conserva los anuncios ya recibidos y muestra un aviso. Las consultas completas con resultados se reutilizan durante diez minutos cuando sus parámetros coinciden; **Actualizar** omite esa caché. Las respuestas están limitadas a 8 MB y las descripciones completas se cargan al abrir los detalles. La lista muestra 40 tarjetas inicialmente y permite cargar más.

## Recomendaciones y aprendizaje

El motor Kotlin compara habilidades, afinidad de títulos y formación, condición académica y algunos requisitos explícitos de experiencia general y ciclo. Desconocido no significa compatible. La puntuación es un índice orientativo, no una probabilidad de contratación. Una brecha detectada limita la puntuación; el aprendizaje no modifica los filtros.

La regresión logística local se entrena con el texto y características de anuncios valorados. Se reconstruye después de cada cambio; deshacer una valoración elimina su influencia. No necesita un modelo remoto, una clave de IA ni enviar tu perfil a ningún servicio. La afinidad se basa en vocabulario y grupos de áreas explícitos, no en un modelo semántico preentrenado.

Los meses totales no prueban experiencia específica, y la app no certifica documentos, colegiatura, equivalencias académicas o todos los requisitos. Revisa el anuncio y sus bases antes de postular.

## Mis datos y migración

**Perfil → Mis datos y copia de seguridad → Guardar copia de mis datos** exporta un JSON con perfil, preferencias, anuncios y valoraciones al lugar que elijas. **Restaurar una copia** pide confirmación porque reemplaza los datos del celular. También acepta `data/app.json` de la versión Python: transfiere ese archivo al celular y selecciónalo en la app. No hay sincronización automática con la computadora.

Los datos quedan en `files/chambamatch.json`, escrito de forma atómica para conservar el archivo anterior si falla una escritura. Se deshabilita la copia automática Android. Al desinstalar se borran los datos; utiliza la exportación para conservarlos. Tu perfil y tus valoraciones no se envían a los portales; las consultas de búsqueda incluyen cargo y ubicación. Los enlaces originales se abren en el navegador o app elegida por Android.

## Desarrollo

Abre la raíz de este repositorio en Android Studio. Usa su JDK, SDK 37 y Build Tools 36.0.0. AGP 9.4.1, Gradle 9.6.0, Kotlin integrado de AGP y compilador Compose 2.2.10 están fijados en el proyecto. La lectura HTML utiliza jsoup 1.23.2, OkHttp 4.12.0 y desugaring con NIO para compatibilidad Android. Todo el código de funcionamiento es Kotlin. La versión web anterior se conserva en el repositorio peru-job-finder.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

El APK de prueba se genera en `app/build/outputs/apk/debug/app-debug.apk`. El cliente sólo realiza consultas HTTPS a las fuentes permitidas, también en la versión de prueba. No incluye firma de publicación ni está publicado en Google Play.

Con un emulador abierto:

```sh
./gradlew connectedDebugAndroidTest
```

Las pruebas del motor cubren filtros, fechas, brechas y aprendizaje reversible. Las de lectores usan HTML controlado. Las pruebas instrumentadas verifican guardar, recargar, importar, restaurar y valorar sin servidor, y el recorrido de la interfaz. Los datos ficticios son exclusivamente fixtures de prueba y se eliminan al terminar.

Prueba optativa de consultas reales desde el dispositivo (requiere Internet; puede fallar por bloqueo o cambios del portal):

```sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveSources=true
```

La prueba de red muestra los conteos y títulos leídos sin guardar anuncios ni modificar el perfil. Los resultados reales no garantizan disponibilidad en cada ubicación.

Referencias técnicas: [Jetpack Compose](https://developer.android.com/develop/ui/compose), [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [jsoup](https://jsoup.org/download). Las ideas del recomendador y las fuentes investigadas están documentadas en [`../REFERENCIAS.md`](../REFERENCIAS.md).

## Cambios de 2.5.0

La pantalla principal usa una cabecera con degradado, un botón de búsqueda siempre visible, contadores reales de coincidencias y seguimiento, acceso a la agenda y aviso de ofertas que cierran en tres días. La navegación reúne **Buscar, Ofertas, Seguimiento y Perfil**.

El análisis recorre las descripciones completas guardadas, separa secciones de requisitos y funciones y reconoce un catálogo acotado de habilidades técnicas, formación y condición académica, idiomas, experiencia general y menciones de experiencia específica, documentos o elegibilidad. Cada comparación conserva la frase del anuncio. Las condiciones explícitas no reconocidas aparecen por confirmar. Las habilidades ausentes en una lista de perfil completa se presentan como brecha de evidencia; mencionar una habilidad no acredita un nivel avanzado ni un certificado. Las exigencias deseables no excluyen por sí solas una oferta. El análisis sigue siendo una ayuda basada en reglas y no interpreta exhaustivamente todos los idiomas, requisitos, equivalencias o negaciones.

El orden considera las brechas obligatorias y la evidencia pendiente: las valoraciones no convierten un incumplimiento en compatibilidad. Sin descripción accesible la puntuación se limita y se muestra la evaluación incompleta. Las fechas de cierre pueden extraerse de frases explícitas de postulación en la descripción; una duración de contrato no se utiliza como cierre.

**Seguimiento** muestra las ofertas guardadas, las marcadas Me interesa y las postulaciones organizadas, independientemente de los filtros actuales y del país. Se ordenan por cierre, con las cerradas al final; las fechas desconocidas permanecen indicadas. Puedes ver Por postular, Postuladas o Todas, incluidas vencidas, y marcar Pendiente, Preparando, Postulada o Descartada. La lista de requisitos preparados se guarda por oferta y no cambia la evaluación del perfil. Si cambia la frase que exige una habilidad, su nueva casilla queda pendiente para revisar la evidencia actual. Las fechas de postulación se organizan como fechas, no como requisitos del candidato. Las copias de seguridad conservan el seguimiento.

Los días se recalculan al volver a abrir la app y cada minuto mientras está abierta. **Revisar fechas en las fuentes** consulta hasta 12 ofertas, con dos lecturas simultáneas, un límite global de 45 segundos y cancelación. Prioriza las lecturas más antiguas; conserva actualizaciones por lote. Si falla una fuente, mantiene los datos conocidos con un aviso. La última lectura se muestra en cada oferta.

Validación final del 6 de octubre: compilación, lint, 47 pruebas unitarias y 13 pruebas instrumentadas locales aprobadas en Android 17. Se verificaron brechas de habilidades obligatorias, requisitos deseables, condición académica, idiomas, seguimiento de ofertas vencidas fuera de filtros, cierres extraídos de descripciones, actualización de fuentes y bloqueo sin pérdida de datos, aprendizaje reversible, persistencia y copia de la agenda. El recorrido Android verifica Me interesa, estado Preparando y la casilla de un requisito con avance persistente. Las cuatro pruebas optativas de red se omitieron en esta validación; la cobertura internacional validada en 2.4.0 no se presenta como una nueva prueba de red de 2.5.0.

APK: `apk/ChambaMatch-2.5.0.apk`. Capturas reales del emulador: `screenshots/inicio-2.5.0.png` y `screenshots/agenda-2.5.0.png`; la agenda utiliza una oferta ficticia de prueba.

## Cambios de 2.6.0 — 12 de octubre de 2026

La navegación reúne **Inicio, Buscar, Seguimiento y Perfil**. Inicio carga las ofertas guardadas al abrir la app, sin repetir consultas de red. Buscar queda separado, con filtros directos y botón de búsqueda visible. Se retira la cabecera promocional grande y el texto permanente de carga completada; la marca ocupa una sola fila y la versión se muestra en Perfil.

Las tarjetas compactas muestran empresa, título, ubicación, sueldo, contrato, cierre y afinidad, con guardar, Me interesa, requisitos y compartir. Los datos completos, fecha exacta, antigüedad, cuatro bloques, evidencia y descripción se despliegan mediante Requisitos. Los títulos largos se pueden expandir. La agenda reduce textos repetidos y mantiene fechas, estados y lista de preparación.

Este ajuste modifica la presentación y la navegación; conserva el análisis, los países y regiones, el aprendizaje y los datos privados del celular.

Validación de 2.6.0: 47 pruebas unitarias y 14 instrumentadas locales aprobadas, compilación y lint aprobados. APK: `apk/ChambaMatch-2.7.0.apk`. Capturas: `screenshots/inicio-2.6.0.png` y `screenshots/buscar-2.6.0.png`, con datos ficticios de prueba en Inicio. Se mantiene la firma y el identificador para actualizar sin desinstalar.

## Recomendaciones por perfil — 2.7.0

Inicio muestra exclusivamente ofertas con **al menos 80% de afinidad estimada**, descripción disponible y requisitos obligatorios sin brechas ni datos pendientes de confirmar. Se evalúan con el perfil guardado, sin convertir las palabras de una búsqueda en experiencia o habilidades del candidato. No se inflan las puntuaciones para llenar la pantalla. Las ofertas con menor afinidad siguen disponibles en los resultados de Buscar. Las preferencias de país, región y fuentes siguen aplicándose.

Completa Perfil y pulsa **Guardar perfil**: carrera, habilidades, experiencia y preferencias se conservan en el archivo privado del celular y las recomendaciones se recalculan. La prueba de reinicio verifica carrera, habilidades y meses de experiencia. Validación: compilación y lint aprobados, 48 pruebas unitarias y 14 instrumentadas locales aprobadas; consultas optativas de red omitidas.

## Cambios de 2.8.0

Se retira el umbral de 80% de 2.7.0. Inicio ordena las ofertas semejantes al perfil aunque necesiten confirmar requisitos o exista una brecha, siempre indicada en la tarjeta. No se convierten las palabras de búsqueda en habilidades del candidato.

Inicio no tiene cabecera, contadores, filtros ni botón Actualizar. Desliza hacia abajo para consultar anuncios mediante tu perfil, sin sustituir los filtros guardados de Buscar. El desplazamiento carga más tarjetas automáticamente. Los detalles y avisos de cada fuente se encuentran en las opciones de Buscar.

El arranque prepara todos los datos locales antes de mostrar la lista. Si faltan ofertas relacionadas y existe un perfil, consulta las fuentes durante un máximo de 12 segundos. No bloquea indefinidamente el inicio sin conexión y no rellena la lista con anuncios ficticios. El perfil y el seguimiento mantienen su almacenamiento privado.

Validación final de 2.8.0: compilación, lint, **50 pruebas unitarias y 16 instrumentadas locales aprobadas** en Android 17. Se verificó el gesto de deslizar para recargar, la ausencia de cabeceras y botón Actualizar, la carga de ofertas guardadas, las recomendaciones sin umbral de 80%, el orden por semejanza independiente del cumplimiento de experiencia y la conservación de las preferencias de búsqueda. Las cuatro pruebas optativas de red se omitieron. La captura de Inicio contiene ofertas ficticias utilizadas exclusivamente en pruebas.
