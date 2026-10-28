# Mi versión de Free My TV

Esta copia (rama `mi-version`) cambia lo siguiente respecto al original:

| Cambio | Qué hace |
|---|---|
| **No desactiva nada** | Ya no apaga el launcher original ni otras apps, ni usa ADB para cambiar la pantalla de inicio. En *Free My TV → Pantalla de inicio → Free My TV*, es Android quien pregunta qué launcher usar (eliges Free My TV y «Siempre»). |
| **Botón Inicio** | Nueva opción en *Pantalla de inicio → Botón Inicio*: abre *Ajustes de Android → Accesibilidad*, donde activas «Free My TV». Desde entonces el botón Inicio del control siempre abre Free My TV (como el «Home Button Fix» de LTvLauncher), y al encender la tele se abre solo. |
| **Seguir viendo (Stremio)** | Una pestaña nueva arriba, junto a Explorar / Películas / Series, con lo último que viste en Stremio (lo más reciente primero). OK abre el título en Stremio. |
| **TV Cable en la estantería** | `com.cvte.tv.setting/.TvSettingActivity` aparece como app, con un icono al estilo del de Apple TV. |
| **Modo claro y oscuro** | Ya venía incluido: *Ajustes de inicio → Tema* (Automático según el atardecer, Claro u Oscuro). |

«Original» vuelve a la pantalla de inicio de la tele. Si una versión anterior de Free My TV había desactivado apps,
«Original» también las vuelve a activar (eso sí usa ADB, una sola vez).

## Antes de instalar

Si en la tele ya está instalado otro Free My TV (o la versión de GitHub), **ábrelo y elige *Pantalla de inicio →
Original*** para que reactive lo que haya apagado. Después desinstálalo: esta versión se firma con otra clave y Android
no deja instalarla encima.

## 1. Herramientas en Windows (una sola vez)

1. **Git for Windows** (trae *Git Bash*): https://git-scm.com/download/win
2. **JDK 17** (Temurin): https://adoptium.net → al instalar, marca *Set JAVA_HOME variable*.
3. **Android command-line tools**: en https://developer.android.com/studio#command-line-tools-only descarga el zip de
   Windows y descomprímelo para que quede `C:\Android\cmdline-tools\latest\bin\sdkmanager.bat`
   (renombra la carpeta `cmdline-tools` interior a `latest`).
4. Abre **Git Bash** y ejecuta:
   ```bash
   setx ANDROID_HOME 'C:\Android'
   export ANDROID_HOME=/c/Android
   yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root='C:\Android' "build-tools;34.0.0" "platforms;android-34" "platform-tools"
   ```

## 2. Configurar y compilar

En Git Bash, dentro de esta carpeta:

```bash
./build.sh            # la primera vez crea config.properties
notepad config.properties
```

En `config.properties` pon:

- `latitude`, `longitude` y `city`: tu ciudad (clima y modo noche).
- `tmdb_key`: tu clave gratuita de TMDB (themoviedb.org → Ajustes → API), para las recomendaciones. Opcional: sin ella,
  solo aparece la pestaña de Stremio.
- `stremio_auth_key`: tu clave de Stremio. Entra en https://web.stremio.com con tu cuenta, pulsa **F12 → Consola** y
  escribe `JSON.parse(localStorage.getItem("profile")).auth.key` y Enter. Copia lo que sale (sin comillas). Es privada:
  abre tu cuenta de Stremio. No la compartas.
- `stremio_autoplay=true` si quieres que OK siga reproduciendo donde lo dejaste (en vez de abrir la ficha).

La pestaña *Seguir viendo* muestra tantas tarjetas como *Ajustes de inicio → Recomendaciones por pestaña* (3 de serie,
hasta 6) y se actualiza cada 5 minutos mientras ves la pantalla de inicio.

Luego:

```bash
./build.sh            # debe terminar con «Built FreeMyTV.apk»
```

Guarda `freemytv.keystore`: las próximas actualizaciones deben firmarse con la misma clave.

## 3. Instalar en la tele

Con la depuración USB activada en la tele y la PC en la misma Wi‑Fi:

```bash
export PATH="$PATH:/c/Android/platform-tools"
adb connect IP_DE_LA_TELE:5555      # acepta «¿Permitir depuración USB?» en la tele
adb install -r FreeMyTV.apk
```

## 4. En la tele

1. Abre **Free My TV → Pantalla de inicio → Free My TV**. Si Android pregunta, elige Free My TV y «Siempre».
2. En la misma pantalla, **Botón Inicio** → se abre Accesibilidad → activa **Free My TV** y acepta. Esto hace que el
   botón Inicio siempre lleve a Free My TV aunque la tele insista en su launcher.
3. Para volver atrás: *Pantalla de inicio → Original*, y desactiva Free My TV en Accesibilidad si quieres.
