package fr.silex.hermesmontre

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Les capteurs de la montre, relevés pour Hermes (`state.sensors`) :
 * batterie, cardio, pas, lumière, pression, et le geste « secouer ».
 *
 * Chaque capteur est facultatif : une montre qui n'en a pas ne l'annonce simplement pas.
 * Les relevés partent au plus une fois par minute, ou tout de suite quand la batterie bouge.
 */
class Capteurs(private val context: Context, private val surSecousse: () -> Unit) : SensorEventListener {

    private val gestionnaire = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val valeurs = JSONObject()
    private var actif = false
    private var derniereSecousse = 0L
    @Volatile var secousseActive = false

    val disponibles: List<String>
        get() = buildList {
            add("battery")
            if (gestionnaire.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null) add("heart_rate")
            if (gestionnaire.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null) add("steps")
            if (gestionnaire.getDefaultSensor(Sensor.TYPE_LIGHT) != null) add("light")
            if (gestionnaire.getDefaultSensor(Sensor.TYPE_PRESSURE) != null) add("pressure")
            if (gestionnaire.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) add("motion")
        }

    fun demarrer(cardio: Boolean, pas: Boolean) {
        if (actif) return
        actif = true
        val lent = SensorManager.SENSOR_DELAY_NORMAL
        if (cardio) gestionnaire.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { gestionnaire.registerListener(this, it, lent) }
        if (pas) gestionnaire.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { gestionnaire.registerListener(this, it, lent) }
        gestionnaire.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { gestionnaire.registerListener(this, it, lent) }
        gestionnaire.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { gestionnaire.registerListener(this, it, lent) }
        gestionnaire.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { gestionnaire.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun arreter() {
        if (!actif) return
        actif = false
        gestionnaire.unregisterListener(this)
    }

    /** Un instantané complet (batterie relue à l'instant). */
    @Synchronized
    fun releve(): JSONObject {
        lireBatterie()
        return JSONObject(valeurs.toString())
    }

    private fun lireBatterie() {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val niveau = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val echelle = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (niveau >= 0 && echelle > 0) {
            val pct = niveau * 100 / echelle
            valeurs.put("battery_pct", pct)        // nom du protocole Gadget
            valeurs.put("battery_percent", pct)    // nom lu par hote/pont_gadget.py
        }
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (mv > 0) valeurs.put("battery_mv", mv)
        val branche = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        valeurs.put("charging", branche != 0)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temp != Int.MIN_VALUE) valeurs.put("battery_temp_c", temp / 10.0)
    }

    @Synchronized
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_HEART_RATE -> if (e.values[0] > 0f) valeurs.put("heart_rate_bpm", e.values[0].toInt())
            Sensor.TYPE_STEP_COUNTER -> valeurs.put("steps_since_boot", e.values[0].toLong())
            Sensor.TYPE_LIGHT -> valeurs.put("light_lux", Math.round(e.values[0]))
            Sensor.TYPE_PRESSURE -> valeurs.put("pressure_hpa", Math.round(e.values[0] * 10) / 10.0)
            Sensor.TYPE_ACCELEROMETER -> secousse(e.values)
        }
    }

    private fun secousse(v: FloatArray) {
        if (!secousseActive) return
        val g = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / SensorManager.GRAVITY_EARTH
        val maintenant = System.currentTimeMillis()
        if (abs(g - 1f) > 1.6f && maintenant - derniereSecousse > 2500) {
            derniereSecousse = maintenant
            surSecousse()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
