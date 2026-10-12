package ar.camalerta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Guarda y carga las cámaras:
 *  - osm_cameras.json  -> descargadas de OpenStreetMap (todo Argentina)
 *  - user_cameras.json -> las que marcás vos con el botón
 */
object CameraRepository {
    private const val OSM_FILE = "osm_cameras.json"
    private const val USER_FILE = "user_cameras.json"

    private val OVERPASS_SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    // Cámaras de velocidad (nodos) + dispositivos de relaciones "enforcement"
    // (semáforo, velocidad promedio, etc.) dentro de Argentina.
    private const val QUERY = """
        [out:json][timeout:180];
        area["ISO3166-1"="AR"][admin_level=2]->.ar;
        node["highway"="speed_camera"](area.ar)->.cams;
        rel["enforcement"](area.ar)->.enf;
        node(r.enf:"device")->.dev;
        (.cams; .dev; .enf;);
        out body;
    """

    @Volatile var osm: List<Camera> = emptyList(); private set
    @Volatile var user: List<Camera> = emptyList(); private set
    @Volatile var all: List<Camera> = emptyList(); private set

    fun load(ctx: Context) {
        osm = readFile(File(ctx.filesDir, OSM_FILE), false)
        user = readFile(File(ctx.filesDir, USER_FILE), true)
        rebuild()
    }

    fun lastUpdate(ctx: Context): Long {
        val f = File(ctx.filesDir, OSM_FILE)
        return if (f.exists()) f.lastModified() else 0L
    }

    /** Descarga todas las cámaras de Argentina. Bloqueante: llamar desde un hilo aparte. */
    fun download(ctx: Context): Int {
        var lastError: Exception? = null
        for (server in OVERPASS_SERVERS) {
            try {
                val json = post(server, "data=" + URLEncoder.encode(QUERY.trimIndent(), "UTF-8"))
                val cams = parseOverpass(json)
                if (cams.isEmpty()) throw IllegalStateException("El servidor no devolvió cámaras")
                writeFile(File(ctx.filesDir, OSM_FILE), cams)
                osm = cams
                rebuild()
                return cams.size
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Sin servidores disponibles")
    }

    /** Agrega una cámara tuya. Devuelve false si ya había una a menos de 30 m. */
    fun addUser(ctx: Context, lat: Double, lon: Double): Boolean {
        if (all.any { Geo.distance(lat, lon, it.lat, it.lon) < 30 }) return false
        val cam = Camera("user${System.currentTimeMillis()}", lat, lon, null, "Cámara marcada", true)
        user = user + cam
        writeFile(File(ctx.filesDir, USER_FILE), user)
        rebuild()
        return true
    }

    fun removeLastUser(ctx: Context): Boolean {
        if (user.isEmpty()) return false
        user = user.dropLast(1)
        writeFile(File(ctx.filesDir, USER_FILE), user)
        rebuild()
        return true
    }

    fun clearUser(ctx: Context) {
        user = emptyList()
        writeFile(File(ctx.filesDir, USER_FILE), user)
        rebuild()
    }

    private fun rebuild() {
        all = osm + user
    }

    // ---------- Red ----------

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 200_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "CamAlerta/1.0 (Android)")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Parseo ----------

    private fun parseOverpass(json: String): List<Camera> {
        val elements = JSONObject(json).getJSONArray("elements")
        val nodes = HashMap<Long, JSONObject>()
        // id de nodo -> (tipo de control, velocidad máxima)
        val devices = HashMap<Long, Pair<String, Int?>>()

        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val type = e.optString("type")
            if (type == "node") {
                nodes[e.getLong("id")] = e
            } else if (type == "relation") {
                val tags = e.optJSONObject("tags")
                val enforcement = tags?.optString("enforcement").orEmpty()
                val members = e.optJSONArray("members")
                if (enforcement.isNotEmpty() && enforcement != "check" && members != null) {
                    val max = parseSpeed(tags?.optString("maxspeed"))
                    for (m in 0 until members.length()) {
                        val mem = members.getJSONObject(m)
                        if (mem.optString("role") == "device" && mem.optString("type") == "node") {
                            devices[mem.getLong("ref")] = enforcement to max
                        }
                    }
                }
            }
        }

        val result = ArrayList<Camera>()
        for ((id, n) in nodes) {
            val tags = n.optJSONObject("tags")
            val isSpeedCam = tags?.optString("highway") == "speed_camera"
            val dev = devices[id]
            if (!isSpeedCam && dev == null) continue
            val max = parseSpeed(tags?.optString("maxspeed")) ?: dev?.second
            val label = if (dev != null) typeLabel(dev.first) else "Cámara de velocidad"
            result.add(Camera("osm$id", n.getDouble("lat"), n.getDouble("lon"), max, label, false))
        }
        return result
    }

    private fun parseSpeed(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        return Regex("\\d+").find(s)?.value?.toIntOrNull()
    }

    private fun typeLabel(enforcement: String) = when (enforcement) {
        "maxspeed" -> "Cámara de velocidad"
        "average_speed" -> "Control de velocidad promedio"
        "traffic_signals" -> "Cámara de semáforo"
        else -> "Control de tránsito"
    }

    // ---------- Archivos ----------

    private fun readFile(f: File, isUser: Boolean): List<Camera> {
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Camera(
                    o.getString("id"),
                    o.getDouble("lat"),
                    o.getDouble("lon"),
                    if (o.has("max")) o.getInt("max") else null,
                    o.optString("type", "Cámara"),
                    isUser
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeFile(f: File, list: List<Camera>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("lat", c.lat)
                put("lon", c.lon)
                c.maxSpeed?.let { put("max", it) }
                put("type", c.type)
            })
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }
}
