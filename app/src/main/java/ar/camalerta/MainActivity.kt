package ar.camalerta

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var txtCount: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtDistance: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnUpdate: Button

    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocationPermission()) startWatch()
        else toast("Sin permiso de ubicación no puedo avisarte de las cámaras")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtCount = findViewById(R.id.txtCount)
        txtStatus = findViewById(R.id.txtStatus)
        txtDistance = findViewById(R.id.txtDistance)
        btnToggle = findViewById(R.id.btnToggle)
        btnUpdate = findViewById(R.id.btnUpdate)

        CameraRepository.load(this)
        refreshCount()

        btnToggle.setOnClickListener {
            if (CameraWatchService.running) {
                startService(Intent(this, CameraWatchService::class.java).setAction(CameraWatchService.ACTION_STOP))
            } else {
                requestPermsAndStart()
            }
        }

        findViewById<Button>(R.id.btnMark).setOnClickListener { markHere() }
        btnUpdate.setOnClickListener { downloadCameras() }

        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            toast(if (CameraRepository.removeLastUser(this)) "Borré tu última cámara" else "No tenés cámaras marcadas")
            refreshCount()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Borrar mis cámaras")
                .setMessage("¿Borrar las ${CameraRepository.user.size} cámaras que marcaste vos?")
                .setPositiveButton("Borrar") { _, _ -> CameraRepository.clearUser(this); refreshCount() }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // Distancia del primer aviso: 200 a 1000 m, de a 50
        val seek = findViewById<SeekBar>(R.id.seekDistance)
        val current = prefs.getInt("alert_distance", 500)
        seek.max = 16
        seek.progress = (current - 200) / 50
        showDistance(current)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val meters = 200 + p * 50
                prefs.edit().putInt("alert_distance", meters).apply()
                showDistance(meters)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        val switchVoice = findViewById<SwitchMaterial>(R.id.switchVoice)
        switchVoice.isChecked = prefs.getBoolean("voice", true)
        switchVoice.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("voice", checked).apply() }

        // Primera vez: bajar las cámaras automáticamente
        if (CameraRepository.osm.isEmpty()) downloadCameras()
    }

    override fun onResume() {
        super.onResume()
        CameraWatchService.statusListener = { text -> runOnUiThread { txtStatus.text = text; updateToggle() } }
        txtStatus.text = CameraWatchService.lastStatus
        updateToggle()
        refreshCount()
    }

    override fun onPause() {
        CameraWatchService.statusListener = null
        super.onPause()
    }

    private fun updateToggle() {
        btnToggle.text = if (CameraWatchService.running) "Detener vigilancia" else "Iniciar vigilancia"
    }

    private fun showDistance(m: Int) {
        txtDistance.text = "Avisar a $m m de la cámara"
    }

    private fun refreshCount() {
        val last = CameraRepository.lastUpdate(this)
        val lastTxt = if (last == 0L) "nunca"
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
        txtCount.text = "Cámaras cargadas: ${CameraRepository.osm.size} de OpenStreetMap + " +
                "${CameraRepository.user.size} tuyas\nÚltima actualización: $lastTxt"
    }

    // ---------------- Permisos / servicio ----------------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startWatch() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startWatch() {
        if (CameraRepository.all.isEmpty()) {
            toast("Ojo: no hay cámaras cargadas todavía. Tocá \"Actualizar cámaras\".")
        }
        ContextCompat.startForegroundService(
            this, Intent(this, CameraWatchService::class.java).setAction(CameraWatchService.ACTION_START)
        )
    }

    // ---------------- Acciones ----------------

    private fun markHere() {
        if (!hasLocationPermission()) {
            requestPermsAndStart()
            return
        }
        val loc: Location? = CameraWatchService.lastLocation ?: try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { null }

        if (loc == null) {
            toast("Todavía no tengo tu ubicación. Iniciá la vigilancia y esperá señal de GPS.")
            return
        }
        val ok = CameraRepository.addUser(this, loc.latitude, loc.longitude)
        toast(if (ok) "Cámara marcada en tu ubicación" else "Ya hay una cámara a menos de 30 m")
        refreshCount()
    }

    private fun downloadCameras() {
        btnUpdate.isEnabled = false
        btnUpdate.text = "Descargando… (puede tardar 1 min)"
        val app = applicationContext
        Thread {
            val result = runCatching { CameraRepository.download(app) }
            runOnUiThread {
                btnUpdate.isEnabled = true
                btnUpdate.text = "Actualizar cámaras"
                refreshCount()
                result.onSuccess { toast("Listo: $it cámaras de todo Argentina") }
                result.onFailure { toast("No pude descargar (${it.message}). Probá de nuevo con buena señal.") }
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
