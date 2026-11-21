# Historial de cambios

Notas de cada versión publicada del launcher, de la más reciente a la más antigua. La 0.5.0 es
la primera versión pública.

Cada versión publicada en GitHub Releases usa como notas su sección de este archivo.

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
