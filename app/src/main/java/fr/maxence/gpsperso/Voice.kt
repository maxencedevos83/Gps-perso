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
 * Voix de l'appli : synthèse du téléphone (n'importe quel moteur installé), voix naturelles
 * Google Cloud Text-to-Speech (clé Google) ou voix ElevenLabs (clé ElevenLabs), ces deux dernières
 * mises en cache. Si la voix en ligne échoue ou tarde, la voix du téléphone prend le relais.
 * Gère aussi la sourdine.
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

    /** Voix ElevenLabs : [shared] = voix de la bibliothèque publique, à ajouter au compte avant usage. */
    class ElevenVoice(val id: String, val name: String, val details: String, val gender: String, val shared: Boolean, val ownerId: String = "")

    private const val ELEVEN = "https://api.elevenlabs.io/v1"
    /** Modèle le plus naturel en français ; le cache évite de payer deux fois la même phrase. */
    private const val ELEVEN_MODEL = "eleven_multilingual_v2"

    private lateinit var app: Context
    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private val cloudIo = Bg.pool("voice")
    private var tts: TextToSpeech? = null
    private var ready = false
    private var focusRequest: AudioFocusRequest? = null
    private val queue = LinkedList<File>()
    private var player: MediaPlayer? = null
    /** Dernière erreur de la voix Google (affichée dans les réglages). */
    var lastCloudError: String? = null; private set
    /** Prévient l'écran (au plus une fois par minute) quand Google refuse de parler. */
    var onCloudError: ((String) -> Unit)? = null
    private var lastErrorShownAt = 0L

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
        when {
            settings.voiceSource == "eleven" && settings.hasEleven && settings.elevenVoice.isNotBlank() -> cloudSay(text)
            settings.voiceSource == "google" && settings.hasGoogle -> cloudSay(text)
            else -> deviceSay(text)
        }
    }

    /** Nom du service de voix en ligne utilisé (pour les messages d'erreur). */
    fun cloudName(): String = if (settings.voiceSource == "eleven") "ElevenLabs" else "Google"

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
        val eleven = settings.voiceSource == "eleven"
        val key = if (eleven) settings.elevenKey else settings.googleKey
        val voice = if (eleven) settings.elevenVoice else settings.googleVoice
        val rate = settings.ttsRate
        val pitch = settings.ttsPitch
        requestFocus()
        cloudIo.execute {
            try {
                val file = if (eleven) elevenFile(key, voice, rate, text) else cloudFile(key, voice, rate, pitch, text)
                main.post {
                    lastCloudError = null
                    queue.add(file)
                    if (player == null) playNext()
                }
            } catch (e: Exception) {
                main.post {
                    lastCloudError = if (eleven) elevenErrorLabel(e) else e.message
                    deviceSay(text)
                    val now = System.currentTimeMillis()
                    if (now - lastErrorShownAt > 60_000) {
                        lastErrorShownAt = now
                        onCloudError?.invoke(lastCloudError ?: "erreur inconnue")
                    }
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
        val audioConfig = JSONObject().put("audioEncoding", "MP3")
        // Certaines voix (Chirp) refusent les réglages : on ne les envoie que s'ils sont modifiés.
        if (rate != 1f) audioConfig.put("speakingRate", rate.toDouble())
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
        val audio = Base64.decode(json.optString("audioContent"), Base64.DEFAULT)
        if (audio.size < 200) throw IllegalStateException("réponse audio vide de Google")
        file.writeBytes(audio)
        // On garde les 400 annonces les plus récentes
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(400)?.forEach { it.delete() }
        return file
    }

    // ---------- Voix ElevenLabs ----------

    /** Voix du compte ElevenLabs (voix prêtes à l'emploi et voix ajoutées). */
    fun elevenAccountVoices(key: String): List<ElevenVoice> {
        val json = JSONObject(Net.request("$ELEVEN/voices", headers = mapOf("xi-api-key" to key), timeoutMs = 15_000))
        val arr = json.optJSONArray("voices") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { v ->
            val labels = v.optJSONObject("labels")
            val lang = labels?.optString("language").orEmpty()
            val details = listOf(lang.uppercase(), labels?.optString("accent").orEmpty(), labels?.optString("descriptive").orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · ")
            ElevenVoice(v.getString("voice_id"), v.optString("name").substringBefore(" - ").trim(), details,
                labels?.optString("gender").orEmpty(), shared = false)
        }.sortedWith(compareBy({ !it.details.startsWith("FR") }, { it.name }))
    }

    /** Voix françaises natives de la bibliothèque publique ElevenLabs. */
    fun elevenFrenchVoices(key: String): List<ElevenVoice> {
        val json = JSONObject(Net.request("$ELEVEN/shared-voices?language=fr&page_size=40&sort=usage_character_count_1y",
            headers = mapOf("xi-api-key" to key), timeoutMs = 15_000))
        val arr = json.optJSONArray("voices") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { v ->
            val details = listOf(v.optString("accent"), v.optString("age"), v.optString("descriptive"))
                .filter { it.isNotBlank() && it != "null" }.joinToString(" · ").replace('_', ' ')
            ElevenVoice(v.getString("voice_id"), v.optString("name").substringBefore(" - ").substringBefore(" | ").trim(), details,
                v.optString("gender"), shared = true, ownerId = v.optString("public_owner_id"))
        }.distinctBy { it.id }
    }

    /** Ajoute une voix de la bibliothèque au compte (obligatoire pour la faire parler). Renvoie son identifiant. */
    fun elevenAddShared(key: String, v: ElevenVoice): String {
        val body = JSONObject().put("new_name", v.name).toString()
        return try {
            JSONObject(Net.request("$ELEVEN/voices/add/${v.ownerId}/${v.id}", body, headers = mapOf("xi-api-key" to key)))
                .optString("voice_id").ifBlank { v.id }
        } catch (e: Exception) {
            // Déjà ajoutée au compte : on peut l'utiliser telle quelle
            if ("already" in (e.message ?: "")) v.id else throw e
        }
    }

    /** MP3 synthétisé par ElevenLabs, gardé en cache comme pour Google. */
    private fun elevenFile(key: String, voice: String, rate: Float, text: String): File {
        val speed = rate.coerceIn(0.7f, 1.2f)
        val file = cacheFile("eleven|$ELEVEN_MODEL|$voice|$speed|$text")
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val body = JSONObject()
            .put("text", text)
            .put("model_id", ELEVEN_MODEL)
            .put("voice_settings", JSONObject()
                .put("stability", 0.6)
                .put("similarity_boost", 0.8)
                .put("speed", speed.toDouble()))
        // Une consigne qui arrive trop tard ne sert à rien : au-delà de 6 s, voix du téléphone
        val audio = Net.requestBytes(
            "$ELEVEN/text-to-speech/$voice?output_format=mp3_44100_128", body.toString(),
            headers = mapOf("xi-api-key" to key, "Accept" to "audio/mpeg"), timeoutMs = 6_000,
        )
        if (audio.size < 200) throw IllegalStateException("réponse audio vide d'ElevenLabs")
        file.writeBytes(audio)
        trimCache()
        return file
    }

    /** Message d'erreur ElevenLabs compréhensible. */
    fun elevenErrorLabel(e: Exception): String {
        val m = e.message ?: return "erreur inconnue"
        return when {
            "quota_exceeded" in m -> "crédits ElevenLabs du mois épuisés (ils reviennent à la date de renouvellement)"
            "paid_plan_required" in m || "payment_required" in m || "HTTP 402" in m -> "cette voix demande un abonnement ElevenLabs payant : choisissez une voix de « Vos voix »"
            "voice_not_found" in m || "HTTP 404" in m -> "voix introuvable sur votre compte : choisissez-en une autre"
            "missing_permissions" in m -> "la clé n'a pas le droit « Text to Speech » : modifiez ses autorisations sur elevenlabs.io"
            "invalid_api_key" in m || "HTTP 401" in m -> "clé ElevenLabs refusée (vérifiez qu'elle est bien copiée)"
            "detected_unusual_activity" in m -> "ElevenLabs a bloqué l'offre gratuite pour ce réseau (VPN ?)"
            "voice_limit_reached" in m -> "nombre maximal de voix atteint sur le compte : supprimez-en une sur elevenlabs.io"
            "HTTP 429" in m -> "trop de demandes à la fois, réessayez"
            e is java.net.SocketTimeoutException || "timed out" in m || "timeout" in m.lowercase() -> "ElevenLabs a mis trop de temps à répondre"
            e is java.net.UnknownHostException -> "pas d'Internet"
            else -> m.take(140)
        }
    }

    private fun cacheFile(id: String): File {
        val dir = File(app.cacheDir, "voix").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.mp3")
    }

    /** On garde les 400 annonces les plus récentes. */
    private fun trimCache() {
        File(app.cacheDir, "voix").listFiles()?.sortedByDescending { it.lastModified() }?.drop(400)?.forEach { it.delete() }
    }

    /** Test pas à pas de la voix ElevenLabs, résultat lisible pour l'utilisateur. */
    private fun diagnoseEleven(done: (String) -> Unit) {
        val key = settings.elevenKey
        val voice = settings.elevenVoice
        val rate = settings.ttsRate
        val lines = ArrayList<String>()
        lines += if (settings.voiceSource == "eleven") "✅ Voix ElevenLabs sélectionnée" else "❌ ElevenLabs n'est pas sélectionnée : choisissez une voix dans « Voix ElevenLabs »"
        lines += if (key.isNotBlank()) "✅ Clé ElevenLabs présente" else "❌ Clé ElevenLabs absente"
        lines += if (voice.isNotBlank()) "✅ Voix choisie : ${settings.elevenVoiceName}" else "❌ Aucune voix choisie"
        cloudIo.execute {
            var file: File? = null
            try {
                val voices = elevenAccountVoices(key)
                lines += "✅ Compte joint : ${voices.size} voix disponibles"
                if (voice.isNotBlank() && voices.none { it.id == voice }) lines += "⚠️ La voix choisie n'est pas (ou plus) sur votre compte"
            } catch (e: Exception) {
                lines += "❌ Compte : ${elevenErrorLabel(e)}"
            }
            try {
                val text = "Test de la voix ElevenLabs. Radar fixe dans 500 mètres."
                cacheFile("eleven|$ELEVEN_MODEL|$voice|${rate.coerceIn(0.7f, 1.2f)}|$text").delete()
                file = elevenFile(key, voice, rate, text)
                lines += "✅ Synthèse : ${file.length() / 1024} Ko reçus"
            } catch (e: Exception) {
                lines += "❌ Synthèse : ${elevenErrorLabel(e)}"
            }
            main.post {
                val f = file
                if (f != null) {
                    requestFocus()
                    queue.add(f)
                    if (player == null) playNext()
                    lines += "🔊 Lecture lancée : vous devez entendre la voix ElevenLabs maintenant"
                }
                done(lines.joinToString("\n"))
            }
        }
    }

    /** Test pas à pas de la voix en ligne choisie, résultat lisible pour l'utilisateur. */
    fun diagnose(done: (String) -> Unit) {
        if (settings.voiceSource == "eleven") return diagnoseEleven(done)
        val key = settings.googleKey
        val voice = settings.googleVoice
        val rate = settings.ttsRate
        val pitch = settings.ttsPitch
        val lines = ArrayList<String>()
        lines += if (settings.voiceSource == "google") "✅ Voix Google sélectionnée" else "❌ La voix du téléphone est sélectionnée : choisissez une voix dans « Voix naturelles Google »"
        lines += if (key.isNotBlank()) "✅ Clé Google présente" else "❌ Clé Google absente"
        cloudIo.execute {
            var file: File? = null
            try {
                val voices = cloudVoices(key)
                lines += "✅ Liste des voix : ${voices.size} voix françaises"
                if (voices.none { it.name == voice }) lines += "⚠️ La voix « $voice » n'existe plus chez Google : choisissez-en une autre"
            } catch (e: Exception) {
                lines += "❌ Liste des voix : ${e.message}"
            }
            try {
                File(app.cacheDir, "voix").listFiles()?.forEach { it.delete() }
                file = cloudFile(key, voice, rate, pitch, "Test de la voix Google. Radar fixe dans 500 mètres.")
                lines += "✅ Synthèse avec $voice : ${file.length() / 1024} Ko reçus"
            } catch (e: Exception) {
                lines += "❌ Synthèse avec $voice : ${e.message}"
            }
            main.post {
                val f = file
                if (f != null) {
                    requestFocus()
                    queue.add(f)
                    if (player == null) playNext()
                    lines += "🔊 Lecture lancée : vous devez entendre la voix Google maintenant"
                }
                done(lines.joinToString("\n"))
            }
        }
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
            p.setOnErrorListener { mp, what, extra ->
                mp.release(); player = null
                onCloudError?.invoke("lecture audio impossible ($what/$extra)")
                playNext(); true
            }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            p.release()
            player = null
            onCloudError?.invoke("lecture audio impossible : ${e.message}")
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
