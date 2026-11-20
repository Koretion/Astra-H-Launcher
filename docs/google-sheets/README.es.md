# Registro de trayectos con Google Sheets

[English](README.md) · **Español**

Esta guía prepara un sistema completo para:

1. Registrar trayectos con el GPS de la radio.
2. Guardar los puntos y las sesiones en Google Sheets.
3. Reintentar las subidas cuando la radio se queda sin Internet.
4. Avisar por Telegram al iniciar y finalizar un trayecto.

El código que debe copiarse en Google Apps Script es [`Code.gs`](Code.gs), en esta carpeta.

El script sirve tanto para una hoja nueva como para actualizar la instalación de una
versión anterior del receptor. Conserva las columnas y los datos existentes,
añade al final los campos V4 que falten y crea el diario `batches`.

## 1. Requisitos

- El launcher Astra instalado en la radio.
- Permiso de ubicación precisa concedido al launcher.
- GPS habilitado en Android.
- Conexión a Internet en la radio mediante Wi-Fi, hotspot o módem 4G.
- Una cuenta de Google.
- Opcionalmente, una cuenta de Telegram para recibir avisos.

Si otro dispositivo sube también a la misma hoja, cada uno debe usar un Device ID
distinto.

## 2. Crear la hoja de cálculo

1. Abre [Google Sheets](https://sheets.google.com).
2. Crea una hoja de cálculo vacía.
3. Ponle un nombre reconocible, por ejemplo `Trayectos Astra`.
4. Dentro de la hoja, abre `Extensiones > Apps Script`.
5. Borra el contenido inicial de `Code.gs`.
6. Copia allí todo el contenido de [`Code.gs`](Code.gs).
7. Guarda el proyecto.

No es necesario crear manualmente las pestañas ni sus columnas. El script crea
y prepara `points`, `sessions` y `batches`.

## 3. Crear la API key compartida

La API key impide que cualquiera que descubra la URL pueda escribir en la hoja.

1. Genera una cadena aleatoria de al menos 32 caracteres con un gestor de
   contraseñas.
2. En Apps Script abre `Configuración del proyecto`.
3. En `Propiedades de la secuencia de comandos`, añade:

| Propiedad | Valor |
| --- | --- |
| `API_KEY` | La cadena aleatoria generada |
| `TZ` | `Europe/Madrid` |
| `TELEGRAM_ENABLED` | `false` inicialmente |

La misma `API_KEY` se introducirá después en el TS10. No debe publicarse,
enviarse en capturas ni escribirse directamente dentro de `Code.gs`.

### Propiedades del script

Toda la configuración se guarda en `Configuración del proyecto > Propiedades de la
secuencia de comandos`, una fila por propiedad; el código no lleva ningún bloque con
valores privados.

Solo `API_KEY` es obligatoria. Las propiedades omitidas usan estos valores:

| Propiedad | Predeterminado | Uso |
| --- | ---: | --- |
| `API_KEY` | sin valor | Clave compartida con el TS10 |
| `SHEET_POINTS` | `points` | Pestaña de puntos |
| `SHEET_SESSIONS` | `sessions` | Pestaña de sesiones |
| `SHEET_BATCHES` | `batches` | Diario idempotente de reintentos |
| `TZ` | `Europe/Madrid` | Hora peninsular española con cambio automático de verano/invierno |
| `DRIVE_GPX_FOLDER_ID` | vacío | ID de la carpeta de Drive para GPX |
| `GPX_UPDATE_ON_EVERY_POST` | `false` | Regenera GPX en cada lote |
| `MAPS_MAX_POINTS` | `20` | Máximo de hitos de Maps (límite interno 25) |
| `MAPS_MAX_URL_LENGTH` | `1800` | Longitud máxima de la URL |
| `MAPS_STOP_MINUTES` | `5` | Duración mínima de una parada |
| `MAPS_STOP_MAX_SPEED_KMH` | `10` | Velocidad máxima de una parada |
| `MAPS_STOP_RADIUS_M` | `40` | Radio para agrupar una parada |
| `MAPS_TURN_MIN_DEG` | `30` | Giro mínimo que se conserva |
| `MAPS_TURN_MIN_SEGMENT_M` | `60` | Segmento mínimo contra ruido GPS |
| `MAPS_KEY_POINT_INTERVAL_KM` | `10` | Hito mínimo por distancia en rutas rectas |
| `TRACK_MAX_ACCURACY_M` | `60` | Rechaza puntos históricos con peor precisión |
| `TRACK_MAX_SEGMENT_SPEED_KMH` | `180` | Rechaza saltos cuya velocidad espacial sea imposible |
| `TRACK_MAX_REPORTED_SPEED_KMH` | `220` | Techo de una lectura de velocidad recibida |
| `TRACK_MOVING_MIN_SPEED_KMH` | `3` | Umbral para clasificar tiempo en movimiento |
| `TRACK_MAX_NORMAL_GAP_SEC` | `120` | A partir de aquí el tiempo queda sin clasificar |
| `RECALC_SESSIONS_PER_RUN` | `25` | Máximo de sesiones procesadas por cada ejecución histórica |
| `RECALC_TIME_BUDGET_SEC` | `240` | Margen de trabajo antes de programar una continuación |
| `TELEGRAM_ENABLED` | `false` | Activa avisos de inicio y fin |
| `TELEGRAM_BOT_TOKEN` | vacío | Token privado del bot |
| `TELEGRAM_CHAT_IDS` | vacío | Chat ID, o varios separados por comas |
| `TELEGRAM_DISABLE_PREVIEW` | `true` | Desactiva vistas previas |
| `TELEGRAM_MAP_ENABLED` | `true` | Envía una imagen del trazado exacto al cerrar el trayecto |
| `GEOAPIFY_API_KEY` | vacío | Clave privada para generar el mapa estático; sin ella se envía solo texto |
| `GEOAPIFY_MAP_WIDTH` | `900` | Anchura de la imagen enviada a Telegram |
| `GEOAPIFY_MAP_HEIGHT` | `600` | Altura de la imagen enviada a Telegram |
| `GEOAPIFY_MAP_STYLE` | `osm-bright` | Estilo de mapa estático de Geoapify |
| `STALE_CLOSE_MINUTES` | `20` | Minutos para cerrar una sesión abandonada |
| `STALE_SCAN_LOOKBACK_HOURS` | `72` | Ventana revisada por mantenimiento |

`TELEGRAM_CHAT_ID` sigue admitido por compatibilidad, pero se recomienda
`TELEGRAM_CHAT_IDS`. Los booleanos se escriben como `true` o `false`.

## 4. Preparar las hojas y conceder permisos

1. En el editor de Apps Script selecciona la función `setupTrackerSheets`.
2. Pulsa `Ejecutar`.
3. Google solicitará autorización para modificar la hoja.
4. Acepta los permisos usando la cuenta propietaria de la hoja.
5. Vuelve a Google Sheets y comprueba que existen:

   - `points`: una fila por cada posición GPS.
   - `sessions`: una fila por trayecto con distancia validada, tiempos, velocidades,
     anomalías, calidad y enlaces.
   - `batches`: diario de lotes recibidos para impedir duplicados en reintentos.

Al recargar la hoja también aparecerá el menú `Tracker TS10`.

Ejecuta una vez `Tracker TS10 > Instalar mantenimiento`. Se creará un
disparador cada cinco minutos para cerrar sesiones que quedaron abiertas por
un apagado brusco o pérdida prolongada de red. La función no instala
disparadores duplicados.

Si quieres archivos GPX, crea una carpeta en Google Drive, copia el texto que
aparece entre `/folders/` en su URL y guárdalo como
`DRIVE_GPX_FOLDER_ID`. La primera generación de un GPX solicitará además
permiso de Drive. Si dejas la propiedad vacía, el resto del tracking funciona
normalmente sin crear archivos.

## 5. Crear el bot de Telegram

Este paso es opcional. Si no se desean avisos, puede mantenerse
`TELEGRAM_ENABLED=false`.

1. Abre una conversación en Telegram con `@BotFather`.
2. Envía `/newbot`.
3. Elige el nombre y el usuario del bot.
4. Guarda el token entregado por BotFather.
5. Abre una conversación con el bot recién creado y envíale cualquier mensaje.
6. Obtén el `chat_id` consultando en un navegador:

   ```text
   https://api.telegram.org/bot<TU_TOKEN>/getUpdates
   ```

7. Busca `"chat":{"id":...}` en la respuesta.

Para avisar a un grupo, añade el bot al grupo, envía un mensaje y repite
`getUpdates`. Los identificadores de grupos suelen ser negativos.

Añade ahora estas propiedades en Apps Script:

| Propiedad | Valor |
| --- | --- |
| `TELEGRAM_ENABLED` | `true` |
| `TELEGRAM_BOT_TOKEN` | Token entregado por BotFather |
| `TELEGRAM_CHAT_IDS` | Un `chat_id` o varios separados por comas |

Ejemplo para dos destinatarios:

```text
123456789,-1001234567890
```

Después recarga Google Sheets y ejecuta
`Tracker TS10 > Probar Telegram`. Debe llegar el mensaje de prueba.

El token del bot solo queda guardado en las propiedades privadas de Apps
Script. No se introduce en la radio.

### Añadir el mapa exacto al resumen

El mapa de Telegram se genera con Geoapify a partir de la polilínea de puntos GPS
validados. No se solicita una ruta ni se corrige contra carreteras, por lo que la línea
representa el mismo recorrido utilizado para el GPX y las métricas.

1. Crea una cuenta gratuita en [Geoapify](https://myprojects.geoapify.com/).
2. Crea un proyecto y copia su API key.
3. Añade estas propiedades en Apps Script:

   | Propiedad | Valor |
   | --- | --- |
   | `TELEGRAM_MAP_ENABLED` | `true` |
   | `GEOAPIFY_API_KEY` | La API key de Geoapify |

   Si Apps Script no permite crear `GEOAPIFY_API_KEY` porque hay muchas propiedades
   `tg_notified:*`, sustituye primero el código por la versión actual y ejecuta
   `Tracker TS10 > Limpiar marcas antiguas de Telegram`. Esas entradas son marcas de
   antirrepetición heredadas, no configuración. La limpieza no modifica ninguna clave,
   token ni propiedad ajena a Telegram y conserva hasta 200 marcas V4 recientes.

4. Para que el enlace interactivo de gpx.studio pueda leer el GPX, comparte la carpeta
   configurada en `DRIVE_GPX_FOLDER_ID` como `Cualquier persona con el enlace > Lector`.
   Esto hace accesibles los recorridos a quien reciba el enlace; no uses una carpeta con
   otros documentos privados.
5. Ejecuta `Tracker TS10 > Enviar resumen V4 de la última sesión` para probarlo con el
   último trayecto cerrado. Deben llegar el resumen y una imagen con inicio verde, final
   rojo y la línea azul del recorrido.

Si Geoapify no está configurado, supera su cuota o devuelve un error, el resumen de texto
se sigue enviando. La API key permanece en las propiedades privadas de Apps Script y no se
incluye en Telegram ni en los enlaces de gpx.studio.

## 6. Desplegar el Web App

1. En Apps Script pulsa `Implementar > Nueva implementación`.
2. Selecciona el tipo `Aplicación web`.
3. Configura:

   - `Ejecutar como`: **Yo**.
   - `Quién tiene acceso`: **Cualquier usuario**.

4. Pulsa `Implementar` y acepta los permisos solicitados.
5. Copia la URL que termina en `/exec`.

La URL `/dev` solo sirve para pruebas desde el editor y no debe configurarse en
el coche.

Abre la URL `/exec` en un navegador. Una instalación correcta devuelve un JSON
similar a:

```json
{
  "ok": true,
  "service": "Astra Launcher vehicle tracker V4",
  "server_version": 4,
  "sheets_ready": true,
  "api_key_configured": true,
  "telegram_enabled": true,
  "telegram_map_enabled": true,
  "geoapify_configured": true
}
```

Cada vez que se modifique `Code.gs`, hay que crear una nueva versión desde
`Implementar > Gestionar implementaciones > Editar > Nueva versión`. Guardar el
código no actualiza automáticamente el Web App publicado.

## 7. Configurar el TS10

En el launcher abre:

```text
Ajustes > Registro de trayectos
```

Introduce:

| Campo | Valor recomendado |
| --- | --- |
| URL de Apps Script | URL HTTPS terminada en `/exec` |
| API key | La misma propiedad `API_KEY` |
| Device ID | `RADIO-TS10` |
| Muestreo | `5` segundos |
| Distancia mínima | `5` metros |
| Subida | `15` segundos |
| Puntos por lote | `10` |
| Cierre por inactividad | `15` minutos |

Activa `Registro de trayectos` y pulsa `Guardar`.

El `Device ID` debe ser único para cada dispositivo que suba a la hoja; si no, dos
dispositivos podrían generar el mismo `session_id`.

## 8. Realizar la primera prueba

1. Sitúa el coche al aire libre y espera a que Android tenga posición GPS.
2. Comprueba que la radio tiene Internet.
3. Conduce al menos unos cientos de metros.
4. En los ajustes del tracker debe aparecer una sesión y aumentar el número de
   puntos.
5. Debe llegar el aviso de inicio por Telegram.
6. Apaga el contacto o permanece parado durante el tiempo de inactividad.
7. Debe llegar el aviso de finalización y, si Geoapify está configurado, la imagen del
   recorrido exacto.
8. Revisa Google Sheets:

   - `points` debe contener coordenadas sin duplicados.
   - `sessions` debe mostrar el estado `closed`, distancia validada, máxima confirmada,
     puntos válidos/descartados, calidad, GPX y enlace de Google Maps.
9. Abre el enlace de gpx.studio desde Telegram y confirma que dibuja el mismo recorrido
   que la imagen y el GPX, sin recalcularlo por calles.

El botón `Subir ahora` fuerza un intento inmediato si existen elementos
pendientes.

## 9. Funcionamiento sin Internet

Los puntos se guardan primero en una base de datos SQLite privada dentro del
launcher. Si no hay red:

- El trayecto continúa registrándose.
- El contador `pendientes` aumenta.
- No se bloquea la interfaz.
- Las subidas se reintentan posteriormente con los mismos identificadores.

El Apps Script usa `batch_uid` como diario de recepción, pero reconcilia siempre cada
`point_uid = device_id + session_id + point_seq`. Así un reintento no duplica filas y
tampoco pierde puntos si el diario del lote existe pero una ejecución anterior quedó a
medio escribir.

### Actualización desde una versión anterior

1. Sustituye todo el contenido del proyecto por el nuevo [`Code.gs`](Code.gs).
2. Ejecuta otra vez `setupTrackerSheets`; no borra datos y añade las columnas V4.
3. Ejecuta `Tracker TS10 > Recalcular todas las sesiones (V4)`. El proceso corrige las
   métricas históricas desde los puntos almacenados y reconstruye sus rutas sin enviar
   Telegram de nuevo. Trabaja por lotes y, si no termina en la primera ejecución,
   programa continuaciones automáticas hasta completar todas las filas. No vuelvas a
   pulsarlo mientras haya continuaciones. `Tracker TS10 > Cancelar recalculo V4`
   detiene el proceso y borra su cursor si necesitas interrumpirlo.
4. En `Implementar > Gestionar implementaciones`, edita la Web App y selecciona `Nueva version`.
5. Conserva la URL `/exec` y la misma `API_KEY`.
6. Abre la URL `/exec` y comprueba `server_version: 4`.
7. Pulsa `Subir ahora` y verifica que el diagnóstico muestra `ACK`.
8. Si quieres comprobar el nuevo formato con datos reales, usa
   `Tracker TS10 > Enviar resumen V4 de la última sesión`. Es una acción manual y puede
   reenviar esa sesión; la recalculación por sí sola nunca manda mensajes.

## 10. Diagnóstico de problemas

### El tracker indica que falta configurar la nube

Comprueba que:

- La URL empieza por `https://`.
- Termina en `/exec`.
- La API key no está vacía.
- El Device ID no está vacío.

### Apps Script responde `unauthorized`

La propiedad `API_KEY` de Apps Script y la API key introducida en el TS10 no
coinciden. Evita espacios al principio o al final.

### No aparecen puntos

Comprueba:

- Permiso de ubicación precisa.
- GPS activado.
- Que el coche realmente se ha desplazado.
- Precisión GPS suficiente.
- Que el tracker está activado y guardado.

El sistema no abre una sesión con el vehículo parado para evitar registrar
deriva GPS.

### Aumenta el contador de pendientes

La cola se envia por sesiones en orden. Tras subir el ultimo lote de una sesion cerrada,
la radio envia su cierre antes de empezar los puntos de la siguiente; asi Apps Script no la
confunde con una sesion abierta abandonada ni genera el texto `sesion recuperada por
mantenimiento`. Una cola antigua puede producir resumenes antiguos mientras se vacia, pero el
contador debe descender y WorkManager programa continuaciones automaticas sin entrar en backoff
salvo que exista un error HTTP real.

La radio está registrando correctamente, pero no puede completar la subida.

### Apps Script responde `partial_batch_detected`

Una ejecución anterior alcanzó a escribir parte de un lote, pero no su confirmación. Las
versiones antiguas rechazaban después todos los reintentos de ese lote y bloqueaban la cola.
Sustituye el script por [`Code.gs`](Code.gs) y crea una nueva versión de la
implementación Web App conservando la misma URL `/exec`. La versión actual reconoce los
puntos ya presentes, inserta solamente los que faltan y confirma el lote completo.
Comprueba Internet y abre la URL `/exec` en un navegador. Después usa
`Subir ahora`.

### Apps Script responde `point_uid_conflict` o `batch_uid_conflict`

La versión 4 del receptor reconcilia en la misma fila un punto histórico cuyo
`device_id + session_id + point_seq` ya existe con datos distintos. Tambien deja de usar
un cambio de tamano de lote como motivo para bloquear toda la cola. La SQLite de la radio
se mantiene como fuente duradera, se conserva un unico `point_uid` y el ACK informa
`reconciled_points`.

Sustituye todo el Apps Script por [`Code.gs`](Code.gs) y publica una nueva version
de la implementación existente. Abrir la URL `/exec` debe mostrar `server_version: 4`.
Despues pulsa `Subir ahora`; no borres los datos de la aplicacion ni las hojas.

### No llega Telegram

1. Ejecuta `Tracker TS10 > Probar Telegram`.
2. Revisa `TELEGRAM_ENABLED=true`.
3. Revisa el token y los chat IDs.
4. Confirma que el usuario inició una conversación con el bot.
5. En Apps Script abre `Ejecuciones` para consultar el error de Telegram.

Si las propiedades del script se han llenado con entradas `tg_notified:*`, ejecuta
`Tracker TS10 > Limpiar marcas antiguas de Telegram`. La versión actual también limita
automáticamente estas marcas para que no vuelvan a crecer indefinidamente.

### Se cambió el script pero continúa el comportamiento anterior

Crea y publica una nueva versión del Web App. No es necesario cambiar la URL
del TS10 si se edita la implementación existente.

## 11. Seguridad y privacidad

- Las coordenadas permiten reconstruir desplazamientos personales.
- Comparte la hoja únicamente con personas autorizadas.
- No publiques la URL junto con la API key.
- Usa una API key distinta a otras contraseñas.
- Si se filtra, cambia `API_KEY` en Apps Script y en el TS10.
- Si se filtra el token de Telegram, revócalo desde BotFather.
- En una radio rooteada, un usuario con acceso root podría leer las preferencias
  privadas; utiliza siempre credenciales revocables.

## 12. Copias de seguridad y eliminación

Google Sheets conserva los datos recibidos. La base SQLite de la radio funciona
como cola y conserva las sesiones recientes, pero no sustituye a una copia de
seguridad de la hoja.

Para dejar de registrar:

1. Desactiva el tracker en el TS10 y pulsa `Guardar`.
2. Si se desea invalidar definitivamente el acceso, cambia `API_KEY`.
3. Para detener Telegram configura `TELEGRAM_ENABLED=false`.
