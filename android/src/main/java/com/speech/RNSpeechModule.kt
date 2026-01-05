package com.mhpdev.speech

import java.util.UUID
import java.util.Locale
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.content.Context
import android.speech.tts.Voice
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.speech.tts.TextToSpeech
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.ReadableMap
import android.speech.tts.UtteranceProgressListener
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import android.util.Log

@ReactModule(name = RNSpeechModule.NAME)
class RNSpeechModule(reactContext: ReactApplicationContext) :
  NativeSpeechSpec(reactContext) {

  override fun getName(): String {
    return NAME
  }

  override fun getTypedExportedConstants(): MutableMap<String, Any> {
    return mutableMapOf(
      "maxInputLength" to maxInputLength
    )
  }

  companion object {
    const val NAME = "RNSpeech"
    private const val TAG = "RNSpeech"

    private val defaultOptions: Map<String, Any> = mapOf(
      "rate" to 0.5f,
      "pitch" to 1.0f,
      "volume" to 1.0f,
      "ducking" to false,
      "language" to Locale.getDefault().toLanguageTag()
    )
  }
  
  private val queueLock = Any()
  private val maxInputLength = TextToSpeech.getMaxSpeechInputLength()
  private val isSupportedPausing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
  private val mainHandler = Handler(Looper.getMainLooper())

  private lateinit var synthesizer: TextToSpeech

  private var selectedEngine: String? = null
  private var cachedEngines: List<TextToSpeech.EngineInfo>? = null

  private var isInitialized = false
  private var isInitializing = false
  private val pendingOperations = mutableListOf<Pair<() -> Unit, Promise>>()

  private var globalOptions: MutableMap<String, Any> = defaultOptions.toMutableMap()

  private var isPaused = false
  private var isResuming = false
  private var currentQueueIndex = -1
  private val speechQueue = mutableListOf<SpeechQueueItem>()

  private val audioManager: AudioManager by lazy {
    reactApplicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  }
  private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
  private var audioFocusRequest: AudioFocusRequest? = null
  private var isDucking = false

  init {
    initializeTTS()
  }

  private fun activateDuckingSession() {
    if (!isDucking) return

    audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
      val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(audioFocusChangeListener!!)
        .build()
      audioFocusRequest = focusRequest
      audioManager.requestAudioFocus(focusRequest)
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        audioFocusChangeListener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
      )
    }
  }

  private fun deactivateDuckingSession() {
    if (!isDucking) return
    audioFocusChangeListener ?: return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest?.let { request ->
        audioManager.abandonAudioFocusRequest(request)
      }
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(audioFocusChangeListener)
    }
    audioFocusChangeListener = null
    audioFocusRequest = null
  }

  private fun processPendingOperations() {
    val operations = ArrayList(pendingOperations)
    pendingOperations.clear()
    for ((operation, promise) in operations) {
      try {
        operation()
      } catch (e: Exception) {
        promise.reject("speech_error", e.message ?: "Unknown error")
      }
    }
  }

  private fun rejectPendingOperations() {
    val operations = ArrayList(pendingOperations)
    pendingOperations.clear()
    for ((_, promise) in operations) {
      promise.reject("speech_error", "Failed to initialize TTS engine")
    }
  }

  private fun getSpeechParams(): Bundle {
    val params = Bundle()
    val volume = (globalOptions["volume"] as? Number)?.toFloat() ?: 1.0f
    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
    return params
  }

  private fun getEventData(utteranceId: String): ReadableMap {
    return Arguments.createMap().apply {
      putInt("id", utteranceId.hashCode())
    }
  }

  private fun getVoiceItem(voice: Voice): ReadableMap {
    val quality = if (voice.quality > Voice.QUALITY_NORMAL) "Enhanced" else "Default"
    return Arguments.createMap().apply {
      putString("quality", quality)
      putString("name", voice.name)
      putString("identifier", voice.name)
      putString("language", voice.locale.toLanguageTag())
    }
  }

  private fun getUniqueID(): String {
    return UUID.randomUUID().toString()
  }

  private fun resetQueueState() {
    synchronized(queueLock) {
      speechQueue.clear()
      currentQueueIndex = -1
      isPaused = false
      isResuming = false
    }
  }

  // NEW: Verify that TTS is actually ready with voices loaded
  private fun verifyTTSReady(retryCount: Int = 0) {
    val maxRetries = 10
    
    mainHandler.postDelayed({
      try {
        val voices = synthesizer.voices
        val engines = synthesizer.engines
        
        if (voices != null && voices.isNotEmpty() && engines != null && engines.isNotEmpty()) {
          // Success - TTS is ready
          Log.d(TAG, "TTS verified ready with ${voices.size} voices and ${engines.size} engines")
          cachedEngines = engines
          
          synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
              synchronized(queueLock) {
                speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
                  item.status = SpeechStatus.SPEAKING
                  if (isResuming && item.position > 0) {
                    emitOnResume(getEventData(utteranceId))
                    isResuming = false
                  } else {
                    emitOnStart(getEventData(utteranceId))
                  }
                }
              }
            }
            override fun onDone(utteranceId: String) {
              synchronized(queueLock) {
                speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
                  item.status = SpeechStatus.COMPLETED
                  deactivateDuckingSession()
                  emitOnFinish(getEventData(utteranceId))
                  if (!isPaused) {
                    currentQueueIndex++
                    processNextQueueItem()
                  }
                }
              }
            }
            override fun onError(utteranceId: String) {
              synchronized(queueLock) {
                speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
                  item.status = SpeechStatus.ERROR
                  deactivateDuckingSession()
                  emitOnError(getEventData(utteranceId))
                  if (!isPaused) {
                    currentQueueIndex++
                    processNextQueueItem()
                  }
                }
              }
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) {
              synchronized(queueLock) {
                speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
                  if (isPaused) {
                    item.status = SpeechStatus.PAUSED
                    emitOnPause(getEventData(utteranceId))
                  } else {
                    item.status = SpeechStatus.COMPLETED
                    emitOnStopped(getEventData(utteranceId))
                  }
                }
              }
            }
            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
              synchronized(queueLock) {
                speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
                  item.position = item.offset + start
                  val data = Arguments.createMap().apply {
                    putInt("id", utteranceId.hashCode())
                    putInt("length", end - start)
                    putInt("location", item.position)
                  }
                  emitOnProgress(data)
                }
              }
            }
          })
          
          applyGlobalOptions()
          isInitialized = true
          isInitializing = false
          processPendingOperations()
          
        } else if (retryCount < maxRetries) {
          // Retry - voices not ready yet
          Log.w(TAG, "TTS not ready yet (retry ${retryCount + 1}/$maxRetries), voices=${voices?.size ?: 0}, engines=${engines?.size ?: 0}")
          verifyTTSReady(retryCount + 1)
        } else {
          // Failed after all retries
          Log.e(TAG, "TTS initialization failed after $maxRetries retries")
          isInitialized = false
          isInitializing = false
          rejectPendingOperations()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error verifying TTS", e)
        if (retryCount < maxRetries) {
          verifyTTSReady(retryCount + 1)
        } else {
          isInitialized = false
          isInitializing = false
          rejectPendingOperations()
        }
      }
    }, if (retryCount == 0) 500L else 1000L) // Initial delay 500ms, then 1s between retries
  }

  private fun initializeTTS() {
    if (isInitializing) return
    isInitializing = true

    synthesizer = TextToSpeech(reactApplicationContext, { status ->
      if (status == TextToSpeech.SUCCESS) {
        // Don't set isInitialized yet - wait for verification
        Log.d(TAG, "TTS engine callback SUCCESS, verifying voices...")
        verifyTTSReady()
      } else {
        Log.e(TAG, "TTS engine initialization failed with status: $status")
        isInitialized = false
        isInitializing = false
        rejectPendingOperations()
      }
    }, selectedEngine)
  }

  private fun ensureInitialized(promise: Promise, operation: () -> Unit) {
    when {
      isInitialized -> {
        try {
          operation()
        } catch (e: Exception) {
          promise.reject("speech_error", e.message ?: "Unknown error")
        }
      }
      isInitializing -> {
        pendingOperations.add(Pair(operation, promise))
      }
      else -> {
        pendingOperations.add(Pair(operation, promise))
        initializeTTS()
      }
    }
  }

  private fun applyGlobalOptions() {
    globalOptions["language"]?.let {
      val locale = Locale.forLanguageTag(it as String)
      synthesizer.setLanguage(locale)
    }
    globalOptions["pitch"]?.let {
      synthesizer.setPitch(it as Float)
    }
    globalOptions["rate"]?.let {
      synthesizer.setSpeechRate(it as Float)
    }
    globalOptions["voice"]?.let { voiceId ->
      synthesizer.voices?.forEach { voice ->
        if (voice.name == voiceId) {
          synthesizer.voice = voice
          return@forEach
        }
      }
    }
  }

  private fun applyOptions(options: Map<String, Any>) {
    val tempOptions = globalOptions.toMutableMap().apply {
      putAll(options)
    }
    tempOptions["language"]?.let {
      val locale = Locale.forLanguageTag(it as String)
      synthesizer.setLanguage(locale)
    }
    tempOptions["pitch"]?.let {
      synthesizer.setPitch(it as Float)
    }
    tempOptions["rate"]?.let {
      synthesizer.setSpeechRate(it as Float)
    }
    tempOptions["voice"]?.let { voiceId ->
      synthesizer.voices?.forEach { voice ->
        if (voice.name == voiceId) {
          synthesizer.voice = voice
          return@forEach
        }
      }
    }
  }

  private fun getValidatedOptions(options: ReadableMap): Map<String, Any> {
    val validated = globalOptions.toMutableMap()

    if (options.hasKey("ducking")) {
      validated["ducking"] = options.getBoolean("ducking")
    }
    if (options.hasKey("voice")) {
      options.getString("voice")?.let { validated["voice"] = it }
    }
    if (options.hasKey("language")) {
      validated["language"] = options.getString("language")
        ?: Locale.getDefault().toLanguageTag()
    }
    if (options.hasKey("pitch")) {
      validated["pitch"] = options.getDouble("pitch").toFloat().coerceIn(0.1f, 2.0f)
    }
    if (options.hasKey("volume")) {
      validated["volume"] = options.getDouble("volume").toFloat().coerceIn(0f, 1.0f)
    }
    if (options.hasKey("rate")) {
      validated["rate"] = options.getDouble("rate").toFloat().coerceIn(0.1f, 2.0f)
    }
    return validated
  }

  private fun processNextQueueItem() {
    synchronized(queueLock) {
      if (isPaused) return

      if (currentQueueIndex in 0 until speechQueue.size) {
        val item = speechQueue[currentQueueIndex]
        if (item.status == SpeechStatus.PENDING || item.status == SpeechStatus.PAUSED) {
          applyOptions(item.options)
          val params = getSpeechParams()
          val textToSpeak: String

          if (item.status == SpeechStatus.PAUSED) {
            item.offset = item.position
            textToSpeak = item.text.substring(item.offset)
            isResuming = true
          } else {
            item.offset = 0
            textToSpeak = item.text
          }
          val queueMode = if (isResuming) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
          synthesizer.speak(textToSpeak, queueMode, params, item.utteranceId)

          if (currentQueueIndex == speechQueue.size - 1) {
            applyGlobalOptions()
          }
        } else {
          currentQueueIndex++
          processNextQueueItem()
        }
      } else {
        currentQueueIndex = -1
        applyGlobalOptions()
      }
    }
  }

  override fun initialize(options: ReadableMap) {
    val newOptions = globalOptions.toMutableMap()
    newOptions.putAll(getValidatedOptions(options))
    globalOptions = newOptions
    applyGlobalOptions()
  }

  override fun reset() {
    globalOptions = defaultOptions.toMutableMap()
    applyGlobalOptions()
  }

  override fun getAvailableVoices(language: String?, promise: Promise) {
    ensureInitialized(promise) {
      val voicesArray = Arguments.createArray()
      val voices = synthesizer.voices

      if (voices == null) {
        promise.resolve(voicesArray)
        return@ensureInitialized
      }
      if (language != null) {
        val lowercaseLanguage = language.lowercase()
        voices.forEach { voice ->
          val voiceLanguage = voice.locale.toLanguageTag().lowercase()
          if (voiceLanguage.startsWith(lowercaseLanguage)) {
            voicesArray.pushMap(getVoiceItem(voice))
          }
        }
      } else {
        voices.forEach { voice ->
          voicesArray.pushMap(getVoiceItem(voice))
        }
      }
      promise.resolve(voicesArray)
    }
  }

  override fun isSpeaking(promise: Promise) {
    ensureInitialized(promise) {
      promise.resolve(synthesizer.isSpeaking || isPaused)
    }
  }

  override fun stop(promise: Promise) {
    ensureInitialized(promise) {
      if (synthesizer.isSpeaking || isPaused) {
        synthesizer.stop()
        deactivateDuckingSession()
        synchronized(queueLock) {
          if (currentQueueIndex in speechQueue.indices) {
            val item = speechQueue[currentQueueIndex]
            emitOnStopped(getEventData(item.utteranceId))
          }
          resetQueueState()
        }
      }
      promise.resolve(null)
    }
  }

  override fun pause(promise: Promise) {
    ensureInitialized(promise) {
      if (!isSupportedPausing || isPaused || !synthesizer.isSpeaking || speechQueue.isEmpty()) {
        promise.resolve(false)
      } else {
        isPaused = true
        synthesizer.stop()
        deactivateDuckingSession()
        promise.resolve(true)
      }
    }
  }

  override fun resume(promise: Promise) {
    ensureInitialized(promise) {
      if (!isSupportedPausing || !isPaused || speechQueue.isEmpty() || currentQueueIndex < 0) {
        promise.resolve(false)
        return@ensureInitialized
      }
      synchronized(queueLock) {
        val pausedItemIndex = speechQueue.indexOfFirst { it.status == SpeechStatus.PAUSED }
        if (pausedItemIndex >= 0) {
          currentQueueIndex = pausedItemIndex
          isPaused = false
          activateDuckingSession()
          processNextQueueItem()
          promise.resolve(true)
        } else {
          isPaused = false
          promise.resolve(false)
        }
      }
    }
  }

  override fun speak(text: String?, promise: Promise) {
    if (text == null) {
      promise.reject("speech_error", "Text cannot be null")
      return
    }
    if (text.length > maxInputLength) {
      promise.reject(
        "speech_error",
        "Text exceeds the maximum input length of $maxInputLength characters"
      )
      return
    }
    ensureInitialized(promise) {
      isDucking = globalOptions["ducking"] as? Boolean ?: false
      activateDuckingSession()
      val utteranceId = getUniqueID()
      val queueItem = SpeechQueueItem(text = text, options = emptyMap(), utteranceId = utteranceId)
      synchronized(queueLock) {
        speechQueue.add(queueItem)
        if (!synthesizer.isSpeaking && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
          processNextQueueItem()
        }
      }
      promise.resolve(null)
    }
  }

  override fun speakWithOptions(text: String?, options: ReadableMap, promise: Promise) {
    if (text == null) {
      promise.reject("speech_error", "Text cannot be null")
      return
    }
    if (text.length > maxInputLength) {
      promise.reject(
        "speech_error",
        "Text exceeds the maximum input length of $maxInputLength characters"
      )
      return
    }
    ensureInitialized(promise) {
      val validatedOptions = getValidatedOptions(options)
      isDucking = validatedOptions["ducking"] as? Boolean ?: false
      activateDuckingSession()
      val utteranceId = getUniqueID()
      val queueItem = SpeechQueueItem(text = text, options = validatedOptions, utteranceId = utteranceId)
      synchronized(queueLock) {
        speechQueue.add(queueItem)
        if (!synthesizer.isSpeaking && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
          processNextQueueItem()
        }
      }
      promise.resolve(null)
    }
  }

  override fun getEngines(promise: Promise) {
    ensureInitialized(promise) {
      val enginesArray = Arguments.createArray()
      cachedEngines?.forEach { engine ->
        enginesArray.pushMap(
          Arguments.createMap().apply {
            putString("name", engine.name)
            putString("label", engine.label)
            putBoolean("isDefault", engine.name == synthesizer.defaultEngine)
          }
        )
      }
      promise.resolve(enginesArray)
    }
  }

  override fun setEngine(engineName: String, promise: Promise) {
    if (cachedEngines?.any { it.name == engineName } == false) {
      promise.reject("engine_error", "Engine '$engineName' is not available")
      return
    }
    if (isInitialized) {
      val activeEngine = selectedEngine ?: synthesizer.defaultEngine
      if (activeEngine == engineName) {
        promise.resolve(null)
        return
      }
    }
    
    // Shutdown existing TTS before switching engines
    if (::synthesizer.isInitialized) {
      try {
        synthesizer.stop()
        synthesizer.shutdown()
      } catch (e: Exception) {
        Log.w(TAG, "Error shutting down TTS before engine switch", e)
      }
    }
    
    selectedEngine = engineName
    isInitialized = false
    isInitializing = false
    resetQueueState()

    pendingOperations.add(Pair({ promise.resolve(null) }, promise))
    initializeTTS()
  }

   override fun openVoiceDataInstaller(promise: Promise) {
    try {
      val activity = currentActivity
      if (activity == null) {
        promise.reject("ACTIVITY_UNAVAILABLE", "The current activity is not available to launch the installer.")
        return
      }
      val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
      if (installIntent.resolveActivity(activity.packageManager) != null) {
        activity.startActivity(installIntent)
        promise.resolve(null)
      } else {
        promise.reject("UNSUPPORTED_OPERATION", "No activity found to handle TTS voice data installation on this device.")
      }
    } catch (e: Exception) {
      promise.reject("INSTALLER_ERROR", "An unexpected error occurred while trying to open the TTS voice installer.", e)
    }
  }

  override fun invalidate() {
    super.invalidate()
    mainHandler.removeCallbacksAndMessages(null)
    if (::synthesizer.isInitialized) {
      synthesizer.stop()
      synthesizer.shutdown()
      resetQueueState()
    }
    isInitialized = false
    isInitializing = false
  }
}