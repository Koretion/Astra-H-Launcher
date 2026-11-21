# Señales CAN del Opel Astra H

[English](CAN_SIGNALS.md) · **Español**

Tabla única de las tramas CAN que usa el launcher, para adaptarlo a otros coches o ampliarlo.
Revisión: **21 de noviembre de 2026**, versión del código **0.5.2 (63)**. Coche de referencia:
Astra H GTC (3 puertas), capturas propias con el GMLAN-USB entre septiembre y noviembre de 2026.

El launcher **solo escucha**: ninguna de estas tramas se transmite al coche.

## Cómo leer la tabla

- **Bus**: `HS` = HS-CAN 500 kbit/s (OBD 6/14), `MS` = MS-CAN 95,238 kbit/s (OBD 3/11),
  `SW` = SW/LS-CAN 33,333 kbit/s (OBD 1). Todas las IDs son de 11 bits.
- **Bytes**: índice desde 0. `b2` = tercer byte; `b2..3` = bytes 2 y 3, big-endian.
- **Máscara**: bit dentro del byte en hexadecimal (`0x40` = bit 6).
- **Estado**:
  - **Confirmado**: reproducido en el coche con una acción física aislada y etiquetada.
  - **Provisional**: coincide con las capturas, pero falta repetir la prueba o cubrir todos los
    casos.
  - **Candidato**: dato de la comunidad o de capturas sin etiquetar; el launcher puede leerlo, pero no depende de él.
- **Código**: archivo de `app/src/main/java/com/ts10/inspector/vehicle/` que lo decodifica.
- Las capturas se hicieron con el GMLAN-USB, un capturador propio en solo escucha que se
  publicará aparte.

## HS-CAN

Decodificador: `can/HsCanVehicleDecoder.kt`.

| ID | Bytes / máscara | Codificación | Señal | Estado |
| --- | --- | --- | --- | --- |
| `0x110` | `b1..2` | `uint16 / 4` | RPM del motor | Confirmado |
| `0x180` | `b2..3` | `int16 / 16` grados, izquierda positivo | Ángulo de volante | Confirmado |
| `0x180` | `b0 & 0xC0 == 0x40` o ángulo `0x7FFF` | Trama sin inicializar tras el contacto: se descarta | Validez del ángulo | Confirmado |
| `0x280` | `b0..1`, `b2..3`, `b4..5`, `b6..7` | `uint16 × 15/512` km/h | Velocidad de rueda: del. izq., del. dcha., tras. izq., tras. dcha. | Confirmado |
| `0x380` | `b7 & 0x80` | 1 = puesto | Freno de mano | Confirmado |
| `0x381` | `b0 & 0xE0` | `0x20`/`0x60` izquierda, `0x40`/`0x80` derecha (flanco/mantenido) | Intermitentes | Confirmado |
| `0x381` | `b0 & 0x08` | 1 = encendidas | Luces exteriores | Candidato |
| `0x381` | `b4 & 0x80` | 1 = encendida | Luz larga | Confirmado |
| `0x381` | `b5` | Byte bruto del aforador (8 bits; `0x5D` lleno, `0xA1` medio, `0xBB` reserva) | Combustible | Provisional |
| `0x410` | `b1..2` | `uint16 / 128` km/h | Velocidad del vehículo | Confirmado |
| `0x4A0` | `b2..4` | `uint24 / 64` km | Cuentakilómetros total | Confirmado |
| `0x510` | `b1` | `valor − 40` °C | Temperatura del refrigerante | Confirmado |

El combustible necesita calibración por coche (`fuel/HsFuelGauge.kt`): la relación byte–litros
depende del aforador y del depósito, y el bit `0x80` es dato, no un aviso de reserva.

## MS-CAN

Decodificadores: `can/MsCanVehicleDecoder.kt` y `can/MsCanLightingDecoder.kt`.

| ID | Bytes / máscara | Codificación | Señal | Estado |
| --- | --- | --- | --- | --- |
| `0x450` | `b0 = 0x46`, `b1 = 0x07`, `b2 ∈ {0x04, 0x06}`, `b3` | `b3 ∈ {0x80, 0x99}` = noche | Alumbrado / modo nocturno | Confirmado |
| `0x4E8` | `b0 = 0x46`; `b2..3` | `uint16 / 4` | RPM | Confirmado |
| `0x4E8` | `b4..5` | `uint16 / 128` km/h | Velocidad | Confirmado |
| `0x4E8` | `b6 & 0x04` | 1 = puesta | Marcha atrás | Provisional |
| `0x6C8` | Mensaje ISO-TP de varias tramas | Ver abajo | Pantalla del climatizador | Confirmado |

**Climatizador `0x6C8`**: el decodificador reensambla ISO-TP (primera trama `0x1L`, siguientes
`0x2N`). La carga útil empieza por `C0 00 xx 0A` y sigue una lista de campos
`[id][n.º de caracteres][caracteres de 16 bits]`:

| Campo | Contenido |
| --- | --- |
| `0x23` | Texto de temperatura, por ejemplo `25°` |
| `0x24` | Distribución del aire: `E052`…`E058` posiciones, `E059` automático |
| `0x25` | `ECO` si el aire acondicionado está apagado |
| `0x26` | Icono de ventilador + nivel `0`–`7`, o `A` de automático |

## SW-CAN (LS-CAN)

Decodificador: `can/SwCanVehicleDecoder.kt`.

| ID | Bytes / máscara | Codificación | Señal | Estado |
| --- | --- | --- | --- | --- |
| `0x115` | `b0 & 0x02` (DLC 1) | 1 = pisado | Embrague | Confirmado |
| `0x115` | `b0` completo | `0x04`/`0x08` parecen contactos de freno redundantes | Contactos de pedal (bruto) | Candidato |
| `0x135` | `BC 00 EA A1` / `7C 00 EA A1` (DLC 4) | Pulso de cierre / apertura | Orden del cierre centralizado | Confirmado |
| `0x160` | `b1 = 0x10` / `0x40` | Abriendo / cerrando; no siempre indica el lado | Orden de elevalunas | Confirmado |
| `0x175` | `b0 & 0x08` (tirar) o `& 0x20` (empujar) | 1 = activa | Palanca de luz larga | Confirmado |
| `0x175` | `b1` | `00` parado, `A0`/`B0`/`D0` velocidad 1/2/3, `AA` lavado | Limpiaparabrisas | Confirmado |
| `0x190` | `b2..4` | `uint24 / 64` km | Cuentakilómetros (respaldo de HS `0x4A0`) | Confirmado |
| `0x230` | `b2 & 0x40` | 1 = abierta | Puerta del conductor | Confirmado |
| `0x230` | `b2 & 0x10` | 1 = abierta | Puerta del acompañante | Confirmado |
| `0x230` | `b2 & 0x04` | 1 = abierto | Maletero | Confirmado |
| `0x230` | Trama completa `05 00 00 00 00 09 00` | Cerrado (`00 …` abierto); se repite cada ~1,5 s | Estado del cierre centralizado | Confirmado |
| `0x260` | `b0..2` | `25 43 7F` izq., `3A 43 7F` dcha., `1F 43 7F` emergencia, `00 00 00` apagado | Destellos efectivos | Confirmado |
| `0x260` | `3A 21 7C` cada ~320 ms | Hiperparpadeo | Fallo del intermitente trasero derecho | Confirmado |
| `0x305` | `00 00 00 08 3B 51 00` | Pulsación | Botón ESP | Confirmado |
| `0x305` | `00 00 00 00 3A 51 00` | Pulsación; falta repetir activar/desactivar | Botón SPORT | Provisional |
| `0x305` | `b2 & 0x20` / `b2 & 0x10` | Pulsación | Botón antiniebla delantero / trasero | Provisional (no se usa) |
| `0x350` | `b0 & 0x20` | 1 = encendidos | Antinieblas delanteros | Confirmado |
| `0x360` | `b2 & 0x40` | 1 = pisado | Freno de servicio | Confirmado |
| `0x370` | `b1 & 0x01` | 1 = puesto | Freno de mano | Confirmado |
| `0x370` | `b1 & 0x80` | 1 = encendido | Antiniebla trasero | Confirmado |
| `0x375` | `b1` | Mismo byte que HS `0x381 b5` | Combustible (respaldo) | Provisional |
| `0x425` | `b0` (DLC 1), valores `1`/`2` | Índice de llave | Llave de contacto (perfil de conductor) | Confirmado |
| `0x445` | `b1` | `valor / 2 − 40` °C | Temperatura exterior | Confirmado |
| `0x500` | `b1` | `valor / 8` V | Tensión de batería | Confirmado |

## Candidatos sin usar

Datos de la comunidad o vistos en capturas que el launcher aún no decodifica. Varias fuentes
comparten material, así que una coincidencia entre ellas no cuenta como validación.

| Bus | ID | Significado probable |
| --- | --- | --- |
| MS | `0x180` | Fecha y hora (distinta de HS `0x180`) |
| MS | `0x201` / `0x206` / `0x208` | Botones de la radio CD30 / mandos del volante / mando del climatizador |
| MS | `0x2B0` | Ventana de menú y avisos del display GID |
| MS | `0x4EE` | Autonomía `/2` y aviso de autonomía baja |
| MS | `0x682` / `0x683` | Temperatura exterior |
| MS | `0x684` / `0x685` | Presión y batería TPMS |
| MS | `0x68C` | Nivel de combustible estimado |
| MS | `0x6C1` | Texto de la radio en el display (ISO-TP) |
| SW | `0x108` | Velocidad y RPM |
| SW | `0x130` | Contador de combustible inyectado (escala según motor) |
| SW | `0x145` | Refrigerante, `b3 − 40` |
| SW | `0x170` | Posición de la llave |
| SW | `0x235` | Brillo de la iluminación interior |
| SW | `0x350` `b0 & 0x10` | Marcha atrás |
| SW | `0x350` `b0 & 0x40` | Ráfaga de largas |
| SW | `0x440` | Hora del sistema |
| SW | `0x530` | TPMS |

## Señales buscadas

El coche de referencia es de 3 puertas y no tiene algunos equipos. Si tu coche los tiene, estas
capturas son las más útiles:

- Puertas traseras izquierda y derecha (probablemente en SW `0x230`, junto a las delanteras).
- Elevalunas traseros y posición de cada ventanilla.
- Capó abierto.
- Sensores de aparcamiento.
- Luces de cruce y de posición por separado.
- Avisos del cuadro: aceite, líquido de frenos, lavaparabrisas, otras bombillas.

## Cómo contribuir una señal

1. Captura con el GMLAN-USB haciendo **una sola acción física** cada vez, por ejemplo abrir y
   cerrar la puerta trasera izquierda tres veces, y anota la hora de cada acción.
2. Busca la ID y el bit que cambian exactamente en esos momentos y en ningún otro.
3. Abre un *issue* o un *pull request* con: coche (modelo, año, carrocería, motor), bus, ID,
   bytes y máscara, codificación, y el fragmento de log que lo demuestra.
4. Si cambias un decodificador, añade un test en `app/src/test/` con las tramas capturadas.

Para otro modelo, como el Astra J, no basta con cambiar números: es otra plataforma de GM y
pueden cambiar las IDs y hasta el bus en el que viaja cada dato. Usa esta tabla como guía de qué
buscar, no como garantía de que la trama sea la misma.

## Fuentes de la comunidad

- [Opel-Astra-H-opc-CAN-Gauge](https://github.com/sepp89117/Opel-Astra-H-opc-CAN-Gauge) y su
  [versión V2](https://github.com/sepp89117/Opel_Astra_H_opc_CAN-GaugeV2): diagnóstico activo por
  HS-CAN (`0x7E0`/`0x7E8`/`0x5E8`).
- [Elektroda: Astra H CAN](https://www.elektroda.com/rtvforum/topic3918511.html): candidatos
  generales.
- [Club Astra H](https://clubastrah.net/showthread.php?tid=9930): los bytes finales de `0x160`
  cambian entre coches; no hay una orden universal segura.
- [Vauxhall Owners Network](https://www.vauxhallownersnetwork.co.uk/threads/canbus-information-for-project.434211/):
  topología, velocidades y tabla MS-CAN.
- [AstraH_climate](https://github.com/megadrifter/AstraH_climate/blob/master/AstraH_climate.ino):
  mando `0x208` y climatizador `0x6C8`.
- [Car-CAN-Message-DB](https://github.com/JJToB/Car-CAN-Message-DB): tablas MS-CAN y LS-CAN.
- [Astra-H-Public](https://github.com/megadrifter/Astra-H-Public): implementaciones LS/SW-CAN y
  MS-CAN; es un fork que reúne material de las anteriores.
