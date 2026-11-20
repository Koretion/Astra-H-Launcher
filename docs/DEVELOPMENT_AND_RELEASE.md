# Desarrollo, compilación y entrega

## Entorno

- Java 17.
- Android SDK 34.
- Gradle Wrapper incluido en el repositorio.
- Android mínimo 29 y objetivo 34.
- ABI por defecto: `arm64-v8a` y `armeabi-v7a`.

La versión se define una sola vez en `app/build.gradle.kts` mediante
`astraVersionCode` y `astraVersionName`. El código revisado está en `0.5.1 (62)`.

## Comandos habituales

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
.\gradlew.bat assembleDebug --no-daemon
```

Android Studio incluye por defecto x86/x86_64 en `debug` para poder usar el emulador. La entrega
para la TS10 es el build `release`: solo ARM, optimizado con R8 (código y recursos sin uso
eliminados), no depurable y firmado con la misma clave que las versiones anteriores:

```powershell
.\gradlew.bat clean testDebugUnitTest assembleRelease --no-daemon
```

Artefacto de entrega:

```text
app/build/outputs/apk/release/app-release.apk
```

Para probar ese mismo build en el emulador se compila para x86 con `-PtargetAbi=x86`; ese APK no
se entrega. Las reglas de R8 están en `app/proguard-rules.pro` y conservan completo el código de
`com.ts10.inspector`. El lint automático del release está desactivado porque falla con JDK 25 y
AGP 8.5.

Hasta 0.4.47 se entregaba el build debug (unos 113 MB); el release equivalente ocupa unos 27 MB y
es más rápido en la radio porque no es depurable.

GitHub Actions compila el APK debug en cada push o pull request a `main` y conserva el
artefacto `ts10-debug-apk` durante 14 días. La CI no sustituye la verificación de firma
necesaria para instalar sobre la copia existente en la radio.

## Regla obligatoria para cada APK entregado

Una nueva versión y su APK solo se preparan cuando el usuario lo solicita expresamente. Los
cambios intermedios, las comprobaciones y las preguntas pendientes se acumulan en la versión
de trabajo existente: no se incrementan `versionCode` ni `versionName` y no se genera ni se
copia un APK de entrega por iniciativa propia. Cuando llegue la petición explícita de crear
la versión o el APK, se aplica el procedimiento completo siguiente.

1. Localizar el último APK realmente entregado y leer su versión y certificado.
2. Incrementar siempre `versionCode` y `versionName` respecto de ese APK.
3. Ejecutar pruebas y compilar con la misma clave compatible; no generar ni sustituir el
   almacén.
4. Verificar el APK anterior y el nuevo con
   `apksigner verify --verbose --print-certs`.
5. Confirmar que ambas huellas SHA-256 son exactamente:

   ```text
   3baa3785b4cf5e83d854f5adf6969195b97e1fb296f4ea440c185ca0c5d321c5
   ```

6. Confirmar con `aapt dump badging` que paquete, `versionCode` y `versionName` son los
   nuevos.
7. Mantener APK Signature Scheme V2 habilitado y V1/V3/V4 deshabilitados, tal como exige
   el instalador modificado de esta TS10.
8. Comparar el tamaño con el APK anterior. Si crece de forma anormal, ejecutar una
   compilación limpia y volver a generar para eliminar entradas ZIP incrementales
   huérfanas.
9. Verificar por última vez firma, tamaño y ruta exacta antes de comunicar la entrega.
10. Añadir las notas de la versión al principio de [CHANGELOG.md](../CHANGELOG.md).

Resumen esperado:

```text
package = com.ts10.inspector
versionCode > último versionCode entregado
certificate SHA-256 = 3baa3785...d321c5
V2 = true
V1/V3/V4 = false
```

## Configuración de firma

El build debug busca `ts10Keystore` en las propiedades de Gradle y, como fallback local,
`%USERPROFILE%/.android/debug.keystore`. La clave compatible debe respaldarse fuera del
repositorio. Nunca se debe copiar al proyecto, publicar en Git ni sustituir por una clave
temporal del sandbox.

Una firma distinta provoca:

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE
Package signatures do not match previously installed version
```

Ese error no se corrige cambiando modelos, ABI o tamaño del APK. Si aparece, se detiene la
entrega y se verifica el certificado.

## Tamaño y compilación limpia

Una limpieza elimina salidas generadas, por lo que solo debe usarse cuando sea necesario:

```powershell
.\gradlew.bat clean assembleRelease --no-daemon
```

Después se repiten todas las comprobaciones. No se entrega un APK solo porque exista en la
carpeta de salida.

## Publicar una actualización en GitHub

El launcher consulta `releases/latest` del repositorio configurado en `updateRepository`
(por defecto `Koretion/Astra-H-Launcher`) y solo funciona cuando el repositorio es público.
Una release es válida si cumple todo lo siguiente:

- etiqueta `v<versionName>`, por ejemplo `v0.5.2`, mayor que la versión instalada;
- un único `.apk`, el build `release` firmado con la clave compatible;
- junto a él, `<nombre del apk>.sha256` con su suma SHA-256:

```powershell
$apk = "astra-h-launcher-0.5.2.apk"
(Get-FileHash $apk -Algorithm SHA256).Hash.ToLower() + "  " + $apk |
    Out-File -Encoding ascii "$apk.sha256"
```

Sin el `.sha256`, o con un APK de otro paquete, de versión igual o inferior o firmado con otro
certificado, el launcher rechaza la descarga. Android 10 siempre pide confirmar la instalación;
el launcher solo la propone con el coche parado y vuelve a HOME al terminar.

## Material local

APKs propietarias, capturas `debug*`, DEX, volcados, claves y logs del vehículo están
ignorados por Git. El código, las pruebas y la documentación no deben depender de que esos
ficheros estén presentes para compilar.
