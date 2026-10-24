package fr.maxence.gpsperso

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Voix de l'appli : synthèse du téléphone (n'importe quel moteur installé) ou voix naturelles
 * Google Cloud Text-to-Speech (avec la clé Google, mises en cache). Gère aussi la sourdine.
 */
object Voice : TextToSpeech.OnInitListener {

    enum class Kind { GUIDANCE, ALERT }

    /** Modes de sourdine. */
    val modes = listOf(
        "all" to "Tout annoncer",
        "alerts" to "Alertes seulement (radars, dangers)",
        "guidance" to "Trajet seulement (consignes de route)",
        "mute" to "Muet",
    )

    class CloudVoice(val name: String, val gender: String) {
        val label: String
            get() {
                val family = name.removePrefix("fr-FR-").substringBeforeLast('-')
                val g = if (gender == "MALE") "homme" else if (gender == "FEMALE") "femme" else ""
                return "${name.substringAfterLast('-')} · $family${if (g.isNotEmpty()) " · $g" else ""}"
            }
    }

    private lateinit var app: Context
    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private val cloudIo = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var focusRequest: AudioFocusRequest? = null
    private val queue = LinkedList<File>()
    private var player: MediaPlayer? = null
    /** Dernière erreur de la voix Google (affichée dans les réglages). */
    var lastCloudError: String? = null; private set

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun init(context: Context, s: Settings) {
        if (::app.isInitialized) return
        app = context.applicationContext
        settings = s
        createTts()
    }

    private fun createTts() {
        ready = false
        tts?.shutdown()
        val engine = settings.ttsEngine
        tts = if (engine.isBlank()) TextToSpeech(app, this) else TextToSpeech(app, this, engine)
    }

    /** Moteur de synthèse changé dans les réglages. */
    fun reloadEngine() = createTts()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        t.language = Locale.FRANCE
        t.setAudioAttributes(attributes)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { maybeAbandonFocus() } }
            @Deprecated("Requis par l'interface")
            override fun onError(utteranceId: String?) { main.post { maybeAbandonFocus() } }
        })
        applyDeviceVoice()
        ready = true
    }

    /** Voix, vitesse et hauteur choisies dans les réglages. */
    fun applyDeviceVoice() {
        val t = tts ?: return
        val wanted = settings.ttsVoice
        if (wanted.isNotBlank()) {
            try { t.voices?.firstOrNull { it.name == wanted }?.let { t.voice = it } } catch (e: Exception) { }
        }
        t.setSpeechRate(settings.ttsRate)
        t.setPitch(settings.ttsPitch)
    }

    fun allowed(kind: Kind): Boolean = when (settings.voiceMode) {
        "mute" -> false
        "alerts" -> kind == Kind.ALERT
        "guidance" -> kind == Kind.GUIDANCE
        else -> true
    }

    fun say(text: String, kind: Kind = Kind.GUIDANCE) {
        if (!::app.isInitialized || !allowed(kind)) return
        speak(text)
    }

    /** Essai depuis les réglages (ignore la sourdine). */
    fun preview(text: String = "Dans 300 mètres, tournez à droite. Radar fixe dans 500 mètres, limité à 80.") = speak(text)

    private fun speak(text: String) {
        if (settings.voiceSource == "google" && settings.hasGoogle) cloudSay(text) else deviceSay(text)
    }

    private fun deviceSay(text: String) {
        if (!ready) return
        requestFocus()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun stop() {
        tts?.stop()
        queue.clear()
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        player = null
        abandonFocus()
    }

    // ---------- Voix du téléphone ----------

    /** Voix françaises du moteur actuel. */
    fun deviceVoices(): List<android.speech.tts.Voice> = try {
        tts?.voices.orEmpty().filter { it.locale.language == "fr" }.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
    } catch (e: Exception) { emptyList() }

    fun engines(): List<TextToSpeech.EngineInfo> = tts?.engines.orEmpty()

    fun currentEngine(): String = tts?.defaultEngine ?: ""

    // ---------- Voix Google Cloud ----------

    /** Liste des voix françaises proposées par Google Cloud Text-to-Speech. */
    fun cloudVoices(key: String): List<CloudVoice> {
        val json = JSONObject(Net.request("https://texttospeech.googleapis.com/v1/voices?languageCode=fr-FR", headers = mapOf("X-Goog-Api-Key" to key)))
        val arr = json.optJSONArray("voices") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { v -> (0 until (v.optJSONArray("languageCodes")?.length() ?: 0)).any { v.getJSONArray("languageCodes").getString(it) == "fr-FR" } }
            .map { CloudVoice(it.getString("name"), it.optString("ssmlGender")) }
            .sortedWith(compareBy({ familyRank(it.name) }, { it.name }))
    }

    private fun familyRank(name: String) = when {
        "Chirp3-HD" in name -> 0
        "Neural2" in name -> 1
        "Wavenet" in name -> 2
        "Studio" in name -> 3
        else -> 4
    }

    private fun cloudSay(text: String) {
        val key = settings.googleKey
        val voice = settings.googleVoice
        val rate = settings.ttsRate
        val pitch = settings.ttsPitch
        requestFocus()
        cloudIo.execute {
            try {
                val file = cloudFile(key, voice, rate, pitch, text)
                main.post {
                    lastCloudError = null
                    queue.add(file)
                    if (player == null) playNext()
                }
            } catch (e: Exception) {
                main.post {
                    lastCloudError = e.message
                    deviceSay(text)
                }
            }
        }
    }

    /** MP3 synthétisé par Google, gardé en cache : « Radar dans 500 mètres » ne coûte qu'une fois. */
    private fun cloudFile(key: String, voice: String, rate: Float, pitch: Float, text: String): File {
        val dir = File(app.cacheDir, "voix").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1").digest("$voice|$rate|$pitch|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(dir, "$hash.mp3")
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val audioConfig = JSONObject().put("audioEncoding", "MP3").put("speakingRate", rate.toDouble())
        // Les voix Chirp ne gèrent pas la hauteur
        if ("Chirp" !in voice && pitch != 1f) audioConfig.put("pitch", ((pitch - 1f) * 10f).toDouble())
        val body = JSONObject()
            .put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", "fr-FR").put("name", voice))
            .put("audioConfig", audioConfig)
        val json = JSONObject(Net.request(
            "https://texttospeech.googleapis.com/v1/text:synthesize", body.toString(),
            headers = mapOf("X-Goog-Api-Key" to key),
        ))
        file.writeBytes(Base64.decode(json.getString("audioContent"), Base64.DEFAULT))
        // On garde les 400 annonces les plus récentes
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(400)?.forEach { it.delete() }
        return file
    }

    private fun playNext() {
        val f = queue.poll()
        if (f == null) {
            player = null
            maybeAbandonFocus()
            return
        }
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(attributes)
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { it.release(); player = null; playNext() }
            p.setOnErrorListener { mp, _, _ -> mp.release(); player = null; playNext(); true }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            p.release()
            player = null
            playNext()
        }
    }

    // ---------- Priorité audio : la musique baisse pendant l'annonce ----------

    private fun requestFocus() {
        if (focusRequest != null) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        am.requestAudioFocus(req)
        focusRequest = req
    }

    private fun maybeAbandonFocus() {
        if (tts?.isSpeaking == true || player != null || queue.isNotEmpty()) return
        abandonFocus()
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req)
        focusRequest = null
    }
}
