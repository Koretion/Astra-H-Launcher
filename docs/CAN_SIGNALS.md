# Opel Astra H CAN signals

**English** · [Español](CAN_SIGNALS.es.md)

A single table of the CAN frames the launcher uses, to adapt it to other cars or extend it.
Revision: **21 November 2026**, code version **0.5.2 (63)**. Reference car: Astra H GTC
(3-door), own captures taken between September and November 2026.

The launcher **only listens**: none of these frames is ever transmitted to the car.

## How to read the table

- **Bus**: `HS` = HS-CAN 500 kbit/s (OBD 6/14), `MS` = MS-CAN 95.238 kbit/s (OBD 3/11),
  `SW` = SW/LS-CAN 33.333 kbit/s (OBD 1). All IDs are 11-bit.
- **Bytes**: 0-based index. `b2` = third byte; `b2..3` = bytes 2 and 3, big-endian.
- **Mask**: bit within the byte in hexadecimal (`0x40` = bit 6).
- **Status**:
  - **Confirmed**: reproduced on the car with an isolated, labelled physical action.
  - **Provisional**: matches the captures, but the test still has to be repeated or does not
    cover every case yet.
  - **Candidate**: community data or unlabelled captures; the launcher may read it but does not rely on it.
- **Code**: file under `app/src/main/java/com/ts10/inspector/vehicle/` that decodes it.
- Captures were taken with GMLAN-USB, our own listen-only sniffer, which will be published
  separately.

## HS-CAN

Decoder: `can/HsCanVehicleDecoder.kt`.

| ID | Bytes / mask | Encoding | Signal | Status |
| --- | --- | --- | --- | --- |
| `0x110` | `b1..2` | `uint16 / 4` | Engine RPM | Confirmed |
| `0x180` | `b2..3` | `int16 / 16` degrees, left positive | Steering angle | Confirmed |
| `0x180` | `b0 & 0xC0 == 0x40` or angle `0x7FFF` | Not initialised after ignition: frame discarded | Steering angle validity | Confirmed |
| `0x280` | `b0..1`, `b2..3`, `b4..5`, `b6..7` | `uint16 × 15/512` km/h | Wheel speed: front left, front right, rear left, rear right | Confirmed |
| `0x380` | `b7 & 0x80` | 1 = applied | Handbrake | Confirmed |
| `0x381` | `b0 & 0xE0` | `0x20`/`0x60` left, `0x40`/`0x80` right (edge/held) | Indicators | Confirmed |
| `0x381` | `b0 & 0x08` | 1 = on | Exterior lights | Candidate |
| `0x381` | `b4 & 0x80` | 1 = on | High beam | Confirmed |
| `0x381` | `b5` | Raw fuel sender byte (8 bits; `0x5D` full, `0xA1` half, `0xBB` reserve) | Fuel | Provisional |
| `0x410` | `b1..2` | `uint16 / 128` km/h | Vehicle speed | Confirmed |
| `0x4A0` | `b2..4` | `uint24 / 64` km | Total odometer | Confirmed |
| `0x510` | `b1` | `value − 40` °C | Coolant temperature | Confirmed |

Fuel needs a per-car calibration (`fuel/HsFuelGauge.kt`): the byte-to-litres relation depends on
the sender and the tank, and bit `0x80` is data, not a reserve flag.

## MS-CAN

Decoders: `can/MsCanVehicleDecoder.kt` and `can/MsCanLightingDecoder.kt`.

| ID | Bytes / mask | Encoding | Signal | Status |
| --- | --- | --- | --- | --- |
| `0x450` | `b0 = 0x46`, `b1 = 0x07`, `b2 ∈ {0x04, 0x06}`, `b3` | `b3 ∈ {0x80, 0x99}` = night | Lighting / night mode | Confirmed |
| `0x4E8` | `b0 = 0x46`; `b2..3` | `uint16 / 4` | RPM | Confirmed |
| `0x4E8` | `b4..5` | `uint16 / 128` km/h | Speed | Confirmed |
| `0x4E8` | `b6 & 0x04` | 1 = engaged | Reverse gear | Provisional |
| `0x6C8` | Multi-frame ISO-TP message | See below | Climate display | Confirmed |

**Climate `0x6C8`**: the decoder reassembles ISO-TP (first frame `0x1L`, consecutive frames
`0x2N`). The payload starts with `C0 00 xx 0A` followed by a list of fields
`[id][character count][16-bit characters]`:

| Field | Content |
| --- | --- |
| `0x23` | Temperature text, for example `25°` |
| `0x24` | Air distribution: `E052`…`E058` positions, `E059` automatic |
| `0x25` | `ECO` when the air conditioning is off |
| `0x26` | Fan icon + level `0`–`7`, or `A` for automatic |

## SW-CAN (LS-CAN)

Decoder: `can/SwCanVehicleDecoder.kt`.

| ID | Bytes / mask | Encoding | Signal | Status |
| --- | --- | --- | --- | --- |
| `0x115` | `b0 & 0x02` (DLC 1) | 1 = pressed | Clutch | Confirmed |
| `0x115` | Whole `b0` | `0x04`/`0x08` look like redundant brake contacts | Pedal contacts (raw) | Candidate |
| `0x135` | `BC 00 EA A1` / `7C 00 EA A1` (DLC 4) | Lock / unlock pulse | Central locking command | Confirmed |
| `0x160` | `b1 = 0x10` / `0x40` | Opening / closing; does not always tell the side | Power window command | Confirmed |
| `0x175` | `b0 & 0x08` (pull) or `& 0x20` (push) | 1 = active | High beam stalk | Confirmed |
| `0x175` | `b1` | `00` off, `A0`/`B0`/`D0` speed 1/2/3, `AA` washer | Wipers | Confirmed |
| `0x190` | `b2..4` | `uint24 / 64` km | Odometer (fallback for HS `0x4A0`) | Confirmed |
| `0x230` | `b2 & 0x40` | 1 = open | Driver door | Confirmed |
| `0x230` | `b2 & 0x10` | 1 = open | Passenger door | Confirmed |
| `0x230` | `b2 & 0x04` | 1 = open | Boot | Confirmed |
| `0x230` | Whole frame `05 00 00 00 00 09 00` | Locked (`00 …` unlocked); repeated every ~1.5 s | Central locking state | Confirmed |
| `0x260` | `b0..2` | `25 43 7F` left, `3A 43 7F` right, `1F 43 7F` hazard, `00 00 00` off | Actual indicator flashes | Confirmed |
| `0x260` | `3A 21 7C` every ~320 ms | Hyperflash | Right rear indicator failure | Confirmed |
| `0x305` | `00 00 00 08 3B 51 00` | Press | ESP button | Confirmed |
| `0x305` | `00 00 00 00 3A 51 00` | Press; on/off test still to be repeated | SPORT button | Provisional |
| `0x305` | `b2 & 0x20` / `b2 & 0x10` | Press | Front / rear fog light button | Provisional (unused) |
| `0x350` | `b0 & 0x20` | 1 = on | Front fog lights | Confirmed |
| `0x360` | `b2 & 0x40` | 1 = pressed | Service brake | Confirmed |
| `0x370` | `b1 & 0x01` | 1 = applied | Handbrake | Confirmed |
| `0x370` | `b1 & 0x80` | 1 = on | Rear fog light | Confirmed |
| `0x375` | `b1` | Same byte as HS `0x381 b5` | Fuel (fallback) | Provisional |
| `0x425` | `b0` (DLC 1), values `1`/`2` | Key index | Ignition key (driver profile) | Confirmed |
| `0x445` | `b1` | `value / 2 − 40` °C | Outside temperature | Confirmed |
| `0x500` | `b1` | `value / 8` V | Battery voltage | Confirmed |

## Unused candidates

Community data, or frames seen in captures, that the launcher does not decode yet. Several sources
share material, so agreement between them does not count as validation.

| Bus | ID | Likely meaning |
| --- | --- | --- |
| MS | `0x180` | Date and time (different from HS `0x180`) |
| MS | `0x201` / `0x206` / `0x208` | CD30 radio buttons / steering wheel controls / climate control panel |
| MS | `0x2B0` | GID display menu and warning window |
| MS | `0x4EE` | Range `/2` and low range warning |
| MS | `0x682` / `0x683` | Outside temperature |
| MS | `0x684` / `0x685` | TPMS pressure and battery |
| MS | `0x68C` | Estimated fuel level |
| MS | `0x6C1` | Radio text on the display (ISO-TP) |
| SW | `0x108` | Speed and RPM |
| SW | `0x130` | Injected fuel counter (scale depends on the engine) |
| SW | `0x145` | Coolant, `b3 − 40` |
| SW | `0x170` | Key position |
| SW | `0x235` | Interior lighting brightness |
| SW | `0x350` `b0 & 0x10` | Reverse gear |
| SW | `0x350` `b0 & 0x40` | High beam flash |
| SW | `0x440` | System time |
| SW | `0x530` | TPMS |

## Wanted signals

The reference car is a 3-door and lacks some equipment. If your car has it, these captures are
the most useful:

- Rear left and right doors (probably in SW `0x230`, next to the front ones).
- Rear power windows and the position of each window.
- Bonnet open.
- Parking sensors.
- Dipped beam and side lights separately.
- Cluster warnings: oil, brake fluid, washer fluid, other bulbs.

## How to contribute a signal

1. Capture with GMLAN-USB doing **one physical action at a time**, for example opening and closing
   the rear left door three times, and note the time of each action.
2. Find the ID and bit that change exactly at those moments and at no other time.
3. Open an *issue* or a *pull request* with: car (model, year, body, engine), bus, ID, bytes and
   mask, encoding, and the log excerpt that proves it.
4. If you change a decoder, add a test under `app/src/test/` using the captured frames.

For another model, such as the Astra J, changing numbers is not enough: it is a different GM
platform, and the IDs, and even the bus each signal travels on, may change. Use this table as a
guide to what to look for, not as a guarantee that the frame is the same.

## Community sources

- [Opel-Astra-H-opc-CAN-Gauge](https://github.com/sepp89117/Opel-Astra-H-opc-CAN-Gauge) and its
  [V2](https://github.com/sepp89117/Opel_Astra_H_opc_CAN-GaugeV2): active diagnostics over HS-CAN
  (`0x7E0`/`0x7E8`/`0x5E8`).
- [Elektroda: Astra H CAN](https://www.elektroda.com/rtvforum/topic3918511.html): general
  candidates.
- [Club Astra H](https://clubastrah.net/showthread.php?tid=9930): the last bytes of `0x160` differ
  between cars; there is no safe universal command.
- [Vauxhall Owners Network](https://www.vauxhallownersnetwork.co.uk/threads/canbus-information-for-project.434211/):
  topology, bit rates and an MS-CAN table.
- [AstraH_climate](https://github.com/megadrifter/AstraH_climate/blob/master/AstraH_climate.ino):
  `0x208` control panel and `0x6C8` climate display.
- [Car-CAN-Message-DB](https://github.com/JJToB/Car-CAN-Message-DB): MS-CAN and LS-CAN tables.
- [Astra-H-Public](https://github.com/megadrifter/Astra-H-Public): LS/SW-CAN and MS-CAN
  implementations; a fork that collects material from the sources above.
