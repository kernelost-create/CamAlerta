package ar.camalerta

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Servicio en primer plano: lee el GPS cada segundo y avisa (voz + pitido + notificación)
 * cuando te acercás a una cámara que tenés adelante.
 */
class CameraWatchService : Service(), LocationListener, TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "ar.camalerta.START"
        const val ACTION_STOP = "ar.camalerta.STOP"
        const val ACTION_MARK = "ar.camalerta.MARK"

        private const val CHANNEL_STATUS = "estado"
        private const val CHANNEL_ALERT = "alertas"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_ALERT = 2

        /** Distancia del segundo aviso ("cámara cerca"). */
        private const val NEAR_DIST = 150.0
        /** Ángulo máximo entre tu rumbo y la cámara para considerarla "adelante". */
        private const val AHEAD_ANGLE = 45.0

        @Volatile var running = false; private set
        @Volatile var lastLocation: Location? = null; private set
        @Volatile var lastStatus = "Detenido"; private set

        /** La MainActivity se engancha acá para mostrar el estado en vivo. */
        var statusListener: ((String) -> Unit)? = null
    }

    private class AlertState {
        var lastDist = Double.MAX_VALUE
        var farDone = false
        var nearDone = false
    }

    private lateinit var lm: LocationManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null

    private val states = HashMap<String, AlertState>()
    private var prevLoc: Location? = null
    private var heading: Double? = null
    private var lastNotifText = ""

    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (CameraRepository.all.isEmpty()) CameraRepository.load(this)
        createChannels()
        tts = TextToSpeech(this, this)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: RuntimeException) { null }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        val res = t.setLanguage(Locale("es", "AR"))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale("es"))
        }
        // Así la voz baja el volumen de la música (como un GPS) en vez de cortarla.
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ttsReady = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MARK -> {
                if (running) markHere() else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> startWatching()
        }
        return START_NOT_STICKY
    }

    private fun startWatching() {
        if (running) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_STATUS, buildStatusNotification("Buscando GPS…"), type)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
            // Sin permiso de ubicación o Android no dejó arrancar el servicio
            setStatus("No pude arrancar: ${e.message}")
            stopSelf()
            return
        }
        running = true
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            setStatus("Activá el GPS del celular")
        } else {
            setStatus("Vigilando — ${CameraRepository.all.size} cámaras cargadas")
        }
    }

    override fun onDestroy() {
        running = false
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        tts?.stop()
        tts?.shutdown()
        tone?.release()
        NotificationManagerCompat.from(this).cancel(NOTIF_ALERT)
        setStatus("Detenido")
        super.onDestroy()
    }

    // ---------------- GPS ----------------

    override fun onLocationChanged(loc: Location) {
        lastLocation = loc
        val speedKmh: Double? = if (loc.hasSpeed()) loc.speed * 3.6 else null

        // Rumbo: el del GPS si vas andando; si no, calculado con la posición anterior.
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2.5f) {
            heading = loc.bearing.toDouble()
        } else {
            prevLoc?.let { p ->
                if (p.distanceTo(loc) > 15f) {
                    heading = Geo.bearing(p.latitude, p.longitude, loc.latitude, loc.longitude)
                }
            }
        }
        val p = prevLoc
        if (p == null || p.distanceTo(loc) > 15f) prevLoc = loc

        val alertDist = prefs.getInt("alert_distance", 500)
        val range = alertDist + 300.0
        val degRange = range / 111_000.0
        val cosLat = cos(Math.toRadians(loc.latitude))

        var closest: Camera? = null
        var closestDist = Double.MAX_VALUE
        val seen = HashSet<String>()

        for (c in CameraRepository.all) {
            // Filtro rápido por cuadrado antes de calcular la distancia real
            if (abs(c.lat - loc.latitude) > degRange) continue
            if (abs(c.lon - loc.longitude) * cosLat > degRange) continue
            val d = Geo.distance(loc.latitude, loc.longitude, c.lat, c.lon)
            if (d > range) continue

            seen.add(c.id)
            val st = states.getOrPut(c.id) { AlertState() }
            val firstReading = st.lastDist == Double.MAX_VALUE
            val closing = !firstReading && d < st.lastDist - 1
            val h = heading
            val ahead = if (h != null) {
                Geo.angleDiff(Geo.bearing(loc.latitude, loc.longitude, c.lat, c.lon), h) < AHEAD_ANGLE
            } else closing
            val approaching = ahead && (closing || firstReading)

            if (approaching) {
                if (!st.farDone && d <= alertDist && d > NEAR_DIST) {
                    st.farDone = true
                    alertFar(c, d)
                } else if (!st.nearDone && d <= NEAR_DIST) {
                    st.nearDone = true
                    st.farDone = true
                    alertNear(c, speedKmh)
                }
                if (d < closestDist) {
                    closest = c
                    closestDist = d
                }
            }
            st.lastDist = d
        }
        // Olvidar cámaras que ya quedaron lejos (así vuelve a avisar la próxima vez)
        states.keys.retainAll(seen)

        val speedTxt = speedKmh?.let { " · ${it.roundToInt()} km/h" } ?: ""
        val c = closest
        if (c != null && closestDist <= alertDist) {
            val maxTxt = c.maxSpeed?.let { " (máx $it)" } ?: ""
            setStatus("⚠ ${c.type}$maxTxt a ${roundTo(closestDist, 25)} m$speedTxt")
        } else {
            setStatus("Vigilando — sin cámaras adelante$speedTxt")
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) setStatus("GPS desactivado — activalo para recibir avisos")
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) setStatus("GPS activado, buscando señal…")
    }

    @Deprecated("Requerido en Android 8-10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---------------- Avisos ----------------

    private fun alertFar(c: Camera, d: Double) {
        val maxTxt = c.maxSpeed?.let { ". Máximo $it" } ?: ""
        val text = "${c.type} a ${roundTo(d, 50)} metros$maxTxt"
        beep()
        speak(text)
        showAlertNotification(text)
    }

    private fun alertNear(c: Camera, speedKmh: Double?) {
        val max = c.maxSpeed
        val text = when {
            max != null && speedKmh != null && speedKmh > max + 5 ->
                "Cuidado, vas a ${speedKmh.roundToInt()}. Máximo $max"
            max != null -> "Cámara adelante. Máximo $max"
            else -> "Cámara adelante"
        }
        beep()
        speak(text)
        showAlertNotification(text)
    }

    private fun speak(text: String) {
        if (!prefs.getBoolean("voice", true) || !ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cam")
    }

    private fun beep() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400) } catch (_: Exception) {}
    }

    private fun markHere() {
        val l = lastLocation
        if (l == null) {
            speak("Todavía no tengo señal de GPS")
            return
        }
        val ok = CameraRepository.addUser(this, l.latitude, l.longitude)
        speak(if (ok) "Cámara marcada" else "Ya hay una cámara marcada ahí")
        setStatus(if (ok) "Cámara marcada ✔" else "Ya había una cámara ahí")
    }

    private fun roundTo(d: Double, step: Int): Int = ((d / step).roundToInt() * step).coerceAtLeast(step)

    // ---------------- Notificaciones ----------------

    private fun setStatus(text: String) {
        lastStatus = text
        statusListener?.invoke(text)
        if (running && text != lastNotifText) {
            lastNotifText = text
            try {
                NotificationManagerCompat.from(this).notify(NOTIF_STATUS, buildStatusNotification(text))
            } catch (_: SecurityException) { /* sin permiso de notificaciones */ }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Estado de la vigilancia", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Avisos de cámaras", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // el sonido lo hacemos nosotros (pitido + voz)
            }
        )
    }

    private fun pending(action: String?, code: Int): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (action == null) {
            PendingIntent.getActivity(this, code, Intent(this, MainActivity::class.java), flags)
        } else {
            PendingIntent.getService(this, code, Intent(this, CameraWatchService::class.java).setAction(action), flags)
        }
    }

    private fun buildStatusNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_cam)
            .setContentTitle("CamAlerta")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending(null, 0))
            .addAction(0, "Marcar cámara aquí", pending(ACTION_MARK, 1))
            .addAction(0, "Detener", pending(ACTION_STOP, 2))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun showAlertNotification(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_cam)
            .setContentTitle("📷 Cámara adelante")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(pending(null, 0))
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ALERT, n)
        } catch (_: SecurityException) {}
    }
}
