# Historial de cambios

Notas de cada versión publicada del launcher, de la más reciente a la más antigua. La 0.5.0 es
la primera versión pública.

Cada versión publicada en GitHub Releases usa como notas su sección de este archivo.

## 0.5.10 — 2026-11-28

Versión Android: `versionCode 71`, `versionName 0.5.10`.

- OSM más estable en la misma vía: la señal de velocidad y los carriles ya no desaparecen unos
  segundos en enlaces o vías paralelas. Seguir la misma carretera pesa más al elegir la vía, un
  salto a otra carretera solo se acepta si dura 3 segundos, el límite se conserva hasta un minuto
  en tramos (o rotondas) que no lo tienen y se mantienen los carriles reales si el tramo nuevo
  solo los estima.
- La posición de carril ya no vuelve al carril derecho cuando OSM pierde los carriles un momento:
  menos carriles solo se aplican si se mantienen 10 segundos. Antes un cambio real a la derecha
  se rechazaba después de esa pérdida.
- El cambio de carril se anima: la carretera se desliza al carril nuevo en 1,8 segundos, en 3D y
  en 2D, en vez de saltar.
- Los vehículos detectados se colocan en los carriles de la vía, centrados en su carril, sin
  solaparse; se descartan los que quedan fuera de la calzada y las pistas duplicadas del mismo
  coche.
- En una rotonda aparece su señal azul junto al límite de velocidad.
- Instalación de actualizaciones más robusta sin root: se descartan sesiones anteriores sin
  terminar, la pantalla de confirmación se abre en una tarea limpia, cada paso queda en el log y,
  si Android no responde en 20 segundos, se avisa para volver a pulsar instalar.
- Las imágenes de detecciones (recortes de señales y semáforos en `Pictures/AstraVision`) ya no se
  guardan por defecto: se activan en Ajustes › Avanzado solo para reunir datos de reconocimiento.
  Al arrancar se borran las de más de 14 días y nunca quedan más de 400; también se pueden borrar
  todas desde el mismo apartado.
- Prueba de la ventana de Waze en Ajustes › Avanzado: actual, alto completo, más ancha (5 % más
  hacia la izquierda) o ambas. Si Waze está en el panel se aplica al momento sin reiniciarlo, y el
  log registra la elegida.

## 0.5.9 — 2026-11-28

Versión Android: `versionCode 70`, `versionName 0.5.9`.

- Al pararse, la vista ya no pasa enseguida a «coche parado»: espera 10 segundos (30 si la cámara
  ve un semáforo en rojo), así que un stop o un paso de cebra no gira el coche para volver a
  girarlo segundos después. Con el freno de mano o una puerta, el capó o el maletero abiertos
  cambia al momento. Vale para el coche 3D, la escena 2D y el límite de velocidad.
- Unidades y formatos en Ajustes › Pantalla: velocidad en km/h o mph, temperatura en °C o °F,
  hora automática, 12 h (AM/PM) o 24 h, y fecha automática, española, inglesa o americana. Con
  mph todo pasa a imperial: distancias, autonomía y cuentakilómetros en millas y consumo en mpg.
- La fecha del panel muestra el día de la semana y el número («Viernes 27»).
- Ajustes con estilo Tesla: las opciones son controles segmentados, todos los botones comparten
  el mismo aspecto y las listas largas (idioma, formato de fecha) se eligen en un desplegable.
- Color del coche 3D con círculos de color y una paleta para elegir cualquier tono.
- Nuevo color del suelo 3D: suelo de estudio, negro, gris, cinco colores o la paleta.
- Escena 3D tipo sala de exposición: el coche está sobre un disco con reflejo nítido, delante
  de una pared de estudio que se funde con el panel, y con una sombra de contacto más definida.
  Faros, haces y luces sobre el suelo se ven mucho más al encenderlos.
- Rendimiento: el coche 3D ya no ocupa la GPU sin pausa. Limita su uso al 60 % (en la TS10, unos
  11 fps al circular) y, al abrir una app en el panel derecho, baja a 4 fps durante 6 segundos
  para que arranque antes. Corrige que Spotify o la radio tardaran en aparecer y que la barra
  superior de QF se quedara en blanco.
- La señal de límite de velocidad ya no lleva poste.
- Nuevos idiomas: neerlandés y polaco. Se eligen en Ajustes › Pantalla › Idioma o se activan solos
  si Android está en uno de ellos; se guardan por perfil de conductor como los demás.

## 0.5.8 — 2026-11-25

Versión Android: `versionCode 69`, `versionName 0.5.8`.

- El coche 3D muestra los vehículos, motos y peatones que detecta la cámara, sobre la carretera
  y en su carril: rojos los de tu carril y azules los demás. Se mueven suavemente entre
  detecciones y aparecen y desaparecen con un fundido. Antes solo los veía la escena 2D.
- El coche 3D ya no copia cada fotograma desde la GPU: se dibuja en un búfer que la pantalla
  muestra directamente. La copia costaba unos 49 ms por fotograma en la radio. Si el sistema no
  lo admite, vuelve solo al método anterior; el informe indica cuál usa (`Frame output`).
- Los intermitentes de la pantalla (flechas de la barra superior y coche 3D) siguen a los reales:
  se sincronizan con la trama SW-CAN `0x260` que la centralita envía en cada destello, con 380 ms
  encendidos y 300 ms apagados. Antes parpadeaban con un temporizador propio de 900 ms.
- El indicador del freno de mano ya no parpadea con el coche parado. La caja CAN de la ROM envía
  en este coche paquetes vacíos que lo apagaban; ahora, mientras el GMLAN-USB da el dato, esos
  paquetes no pisan el freno de mano, los intermitentes ni las luces.
- Nueva detección de cambio de carril: el intermitente abre la ventana y la confirma la forma del
  giro de volante (hacia el lado indicado y contravolanteo) o la cámara (cruce de línea). OSM
  limita el carril a los que tiene la vía y los giros en cruces se descartan. Funciona también con
  el panel oculto. Cada intento queda en el informe como `LANE_CHANGE` para afinarlo.
- Botón GRABAR en Log y diagnóstico: graba la pantalla completa sin sonido en
  `Movies/AstraLauncher`, en H.265 si la radio lo admite (si no, H.264) a 24 fps. Se para desde
  el mismo botón, que muestra el tiempo, o desde la notificación.
- Buscador en Ajustes: escribe, por ejemplo, «log» y aparecen las pantallas que contienen esa
  palabra; al tocar una se abre directamente. No distingue mayúsculas ni acentos.

## 0.5.7 — 2026-11-22

Versión Android: `versionCode 68`, `versionName 0.5.7`.

- El coche 3D anima con cadencia fija: el siguiente fotograma se cuenta desde el inicio del
  anterior y no desde que termina el render, así que gana fluidez cuando el render es lento.
- El informe QF mide el coche 3D en el último minuto: fotogramas, FPS reales, render medio, p95 y
  máximo, y el reparto entre dibujo, copia desde la GPU y conversión a bitmap. Cada animación
  terminada deja una línea `VEHICLE_3D_OFFSCREEN animation` con sus FPS.
- Nueva sección `QF WINDOW POLICY` en el informe: el launcher que la ROM tiene registrado
  (`persist.sys.qf.launcher`) y si coincide con este, el rectángulo freeform forzado
  (`sys.qf.freeform.bound`) y las listas de `/system/config` que deciden qué apps admiten ventana
  y cuáles se restauran al despertar.

## 0.5.6 — 2026-11-22

Versión Android: `versionCode 67`, `versionName 0.5.6`.

- Waze vuelve a su hueco del panel derecho cuando queda a pantalla completa. Sin root no se puede
  cambiar el modo de una tarea ajena, así que el launcher cierra esa tarea de Waze y la abre de
  nuevo en ventana libre, como al cerrarlo y abrirlo a mano. Lo hace al abrir Waze y cuando la ROM
  lo pasa a pantalla completa después, como máximo una vez por minuto. El informe QF indica si las
  actividades de Waze son redimensionables.
- Se retira la referencia manual del cuentakilómetros de Ajustes › OBD: el kilometraje se lee de
  la trama HS-CAN.
- Nuevo botón FRAMEWORK en Diagnóstico: copia en un ZIP, en el USB u otra ubicación elegida, el
  framework y las aplicaciones del sistema legibles sin root, para analizar la ROM en el PC. El
  botón muestra el porcentaje, Diagnóstico indica los MB copiados del total y una notificación
  lleva la barra de progreso; al acabar se indica si el ZIP está completo.
- Tras actualizar el GMLAN-USB por OTA, el launcher reabre el USB y comprueba la versión que está
  en marcha. Antes seguía mostrando la versión anterior hasta apagar la radio.

## 0.5.4 — 2026-11-21

Versión Android: `versionCode 65`, `versionName 0.5.4`.

- Las ventanas de meteorología, Aplicaciones, Teléfono, cambio de conductor y fuentes multimedia
  tienen el mismo tamaño: el máximo, con 16 px de margen a los cuatro lados y esquinas
  redondeadas, para que se vea la aplicación de detrás. Ajustes sigue a pantalla completa.
- El carrusel del panel izquierdo ya no pasa por debajo de los botones de teléfono, micro, mapa,
  aplicaciones y cámara ni de los puntos de página. Parado, el coche 3D llena el hueco bajo los
  botones y se funde con el fondo, sin el recuadro de la escena. Al conducir, en 3D y en 2D, la
  carretera sube por debajo de los botones y se difumina hasta la línea de la fecha.
- El volante dibujado baja a la esquina inferior izquierda de la escena y la temperatura junto al
  icono del tiempo es más grande.
- Las páginas del carrusel van sin card, la de neumáticos con el título arriba como las demás, y
  la línea del climatizador baja un poco.
- Abrir Motor, Interior o Combustible en Ajustes desde otra página del carrusel lleva al coche 3D
  para ver la animación; al cerrar Ajustes se vuelve a la página anterior.
- En la página de neumáticos, una rueda sin datos muestra `--` (antes salía "SIN" cortado).
- En Teléfono, el aviso de conexión Bluetooth ya no queda tapado bajo el título.
- Con tema oscuro, la vista interior (Confort) muestra un atardecer por los cristales en vez de
  negro, para que el salpicadero contraste.
- Ajustes > Neumáticos nombra las ruedas en el idioma elegido (salía siempre en español).
- README: capturas nuevas con el diseño actual, más el coche circulando (3D claro, 3D oscuro y 2D)
  y la apertura de puertas, maletero y capó.

## 0.5.3 — 2026-11-21

Versión Android: `versionCode 64`, `versionName 0.5.3`.

- Las tramas CAN ya no están fijadas en el código: IDs, bits y escalas se leen del perfil de
  vehículo `vehicle_profiles/opel_astra_h_gtc.json`, para adaptar el launcher a otros coches. El
  formato se documenta en `docs/CAN_SIGNALS.md`.
- Nuevo **Ajustes > Sistema > Perfil del vehículo**: exporta el perfil activo, importa uno propio
  (se valida al importarlo) y elige cuál usar. Por defecto, el del Opel Astra H GTC. El cambio se aplica
  al momento, sin reiniciar. Borrar un perfil importado pide confirmación y
  un enlace abre en GitHub la explicación del formato.
- El perfil incluido se llama **Opel Astra H GTC**: sus tramas son del GTC de 3 puertas y no
  incluye puertas ni elevalunas traseros.
- Las etiquetas de origen de Ajustes (`SW-CAN 0x230`…) salen del perfil activo en vez de estar
  fijadas, y se quitan los avisos "(en español)" de Log y diagnóstico y Configuración avanzada.

## 0.5.2 — 2026-11-21

Versión Android: `versionCode 63`, `versionName 0.5.2`.

### Actualizaciones

- Al pulsar **Descargar** o **Instalar**, la lista vuelve arriba para ver el estado y el botón
  muestra el porcentaje de descarga.
- Durante la instalación aparece el aviso flotante "Instalando, por favor espere…".
- Con root real (Magisk con `su` concedido), la actualización se instala sin diálogo, el launcher
  se reabre solo y se compila AOT para que el 3D no vaya lento tras actualizar; al terminar la
  compilación se reinicia una vez. Sin root se mantiene el instalador de Android.

### Diagnóstico

- La app de Magisk instalada sin binario `su` ya no se muestra como **Root detectado**, sino
  como **Root posible**.

## 0.5.1 — 2026-11-20

Versión Android: `versionCode 62`, `versionName 0.5.1`. Primera actualización publicada para
probar la descarga desde GitHub en la TS10.

- El coche del panel ya no abre la configuración al tocarlo. Con la vista 3D toda la escena era
  zona táctil y un toque que fallaba por poco el botón de **Aplicaciones** abría Ajustes. La
  configuración sigue disponible desde **Aplicaciones**.
- La aplicación se llama **Astra H Launcher** también en el instalador (quedaba "Astra H
  Launcher Lab").

## 0.5.0 — 2026-11-20

Versión Android: `versionCode 61`, `versionName 0.5.0`. Primera versión pública.

### Idiomas

- Interfaz en español, inglés, alemán, francés, italiano y portugués. Por defecto sigue el idioma
  de Android (inglés si no está soportado); se puede fijar en **Pantalla** y se guarda por perfil
  de conductor. El cambio es inmediato, sin reiniciar.
- Todo lo visible está traducido, incluidos Log y diagnóstico, Configuración avanzada y la prueba
  de capacidades OBD2. Los informes exportados, la telemetría y los logs pasan a inglés.

### Actualizaciones desde GitHub

- El launcher busca nuevas versiones en GitHub Releases. Modos: no comprobar, solo avisar,
  descargar y avisar, o instalar sin avisar. Verifica SHA-256, paquete, versión y firma, e instala
  solo con el coche parado; al terminar vuelve a HOME sin pedir conductor.
- El firmware del GMLAN-USB se actualiza igual desde su propio repositorio al conectarlo.

### Rendimiento y estabilidad

- Al abrir una aplicación a pantalla completa el panel se oculta en vez de destruirse: la vuelta
  al launcher es inmediata. La vista 3D y la animación de la carretera se pausan mientras está
  oculto.
- La caché OSM se carga fuera del hilo principal y las animaciones se detienen en reposo.
- El APK de entrega pasa al build `release` con R8: unos 27 MB frente a 113 MB.

### Limpieza

- Retirados recursos y código sin uso (Filament, imágenes, modelos y guías de marcha atrás).
- Retirados del repositorio los datos personales y del vehículo de pruebas.
- Documentación unificada; README en inglés y español con capturas y licencia GPL-3.0.
- Icono nuevo de la app, renderizado desde el modelo 3D y sin logotipos de marca.

### Correcciones

- Una fila de Ajustes con un valor largo ya no aplasta su etiqueta.
- El kilometraje y el historial de trayectos muestran el nombre actual del perfil, en el idioma
  elegido, en lugar del guardado al registrarlos.

### APK entregado

- Archivo: `astra-h-launcher-0.5.0.apk`, build `release` con R8, solo `arm64-v8a` y `armeabi-v7a`.
- Versión verificada con `aapt`: `versionCode 61`, `versionName 0.5.0`.
- Firma verificada con `apksigner`: esquema V2, un firmante y certificado SHA-256
  `3baa3785b4cf5e83d854f5adf6969195b97e1fb296f4ea440c185ca0c5d321c5`, idéntico a 0.4.47.
- Tamaño: `28042581` bytes (0.4.47, build debug: `112861712`). Compilación limpia
  (`clean testDebugUnitTest assembleRelease`); 301 pruebas unitarias sin fallos.
- SHA-256 del APK: `eddd9ba736524499db856698e97e871eb434557b5a6ed45b586fbeaf6f65c9c1`.
