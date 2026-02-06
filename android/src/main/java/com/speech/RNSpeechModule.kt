package com.mhpdev.speech

import java.util.UUID
import java.util.Locale
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.content.Intent
import android.content.Context
import android.speech.tts.Voice
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.annotation.SuppressLint
import android.speech.tts.TextToSpeech
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.ReadableMap
import android.speech.tts.UtteranceProgressListener
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule

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
    private const val MAX_INIT_RETRIES = 3
    private const val INIT_TIMEOUT_MS = 5000L

    private val defaultOptions: Map<String, Any> = mapOf(
      "rate" to 0.5f,
      "pitch" to 1.0f,
      "volume" to 1.0f,
      "ducking" to false,
      "language" to Locale.getDefault().toLanguageTag()
    )
  }
  private val initLock = Any()
  private val queueLock = Any()

  private val mainHandler = Handler(Looper.getMainLooper())

  private val maxInputLength = TextToSpeech.getMaxSpeechInputLength()
  private val isSupportedPausing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

  private lateinit var synthesizer: TextToSpeech

  private var selectedEngine: String? = null
  private var cachedEngines: List<TextToSpeech.EngineInfo>? = null

  @Volatile private var isInitialized = false
  @Volatile private var isInitializing = false

  private var initRetryCount = 0
  private var initTimeoutRunnable: Runnable? = null

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
    val operations = synchronized(initLock) {
      val list = ArrayList(pendingOperations)
      pendingOperations.clear()
      list
    }
    for ((operation, promise) in operations) {
      try {
        operation()
      } catch (e: Exception) {
        promise.reject("speech_error", e.message ?: "Unknown error")
      }
    }
  }

  private fun rejectPendingOperations() {
    val operations = synchronized(initLock) {
      val list = ArrayList(pendingOperations)
      pendingOperations.clear()
      list
    }
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
      putString("id", utteranceId)
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

  private fun scheduleInitTimeout() {
    initTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    val runnable = Runnable {
      if (!isInitialized) {
        onInitFailure()
      }
    }
    initTimeoutRunnable = runnable
    mainHandler.postDelayed(runnable, INIT_TIMEOUT_MS)
  }

  private fun clearInitTimeout() {
    initTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    initTimeoutRunnable = null
  }

  private fun onInitFailure() {
    synchronized(initLock) {
      isInitializing = false
      isInitialized = false
      if (::synthesizer.isInitialized) {
        try {
          synthesizer.shutdown()
        } catch (e: Exception) {}
      }
      initRetryCount++
      if (initRetryCount <= MAX_INIT_RETRIES) {
        val delay = 1000L * (1 shl (initRetryCount - 1))
        mainHandler.postDelayed({ createTTSInstance() }, delay)
      } else {
        initRetryCount = 0
        rejectPendingOperations()
      }
    }
  }

  private fun createTTSInstance() {
    synchronized(initLock) {
      if (isInitializing) return
      isInitializing = true
      scheduleInitTimeout()
      mainHandler.post {
        try {
          synthesizer = TextToSpeech(reactApplicationContext, { status ->
            clearInitTimeout()
            if (status == TextToSpeech.SUCCESS) {
              synchronized(initLock) {
                isInitialized = true
                isInitializing = false
                initRetryCount = 0
              }
              cachedEngines = try { synthesizer.engines } catch (e: Exception) { null }
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
                        putString("id", utteranceId)
                        putInt("length", end - start)
                        putInt("location", item.position)
                      }
                      emitOnProgress(data)
                    }
                  }
                }
              })
              applyGlobalOptions()
              processPendingOperations()
            } else {
              onInitFailure()
            }
          }, selectedEngine)
        } catch (e: Exception) {
          clearInitTimeout()
          onInitFailure()
        }
      }
    }
  }

  private fun initializeTTS() {
    synchronized(initLock) {
      if (isInitializing || isInitialized) return
      initRetryCount = 0
      createTTSInstance()
    }
  }

  private fun ensureInitialized(promise: Promise, operation: () -> Unit) {
    synchronized(initLock) {
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
  }

  private fun applyGlobalOptions() {
    if (!isInitialized) return
    try {
      globalOptions["language"]?.let {
        synthesizer.setLanguage(Locale.forLanguageTag(it as String))
      }
      globalOptions["pitch"]?.let {
        synthesizer.setPitch(it as Float)
      }
      globalOptions["rate"]?.let {
        synthesizer.setSpeechRate(it as Float)
      }
      globalOptions["voice"]?.let { voiceId ->
        synthesizer.voices?.find { it.name == voiceId }?.let { synthesizer.voice = it }
      }
    } catch (e: Exception) {}
  }

  private fun applyOptions(options: Map<String, Any>) {
    if (!isInitialized) return
    try {
      val temp = globalOptions.toMutableMap().apply { putAll(options) }
      temp["language"]?.let { synthesizer.setLanguage(Locale.forLanguageTag(it as String)) }
      temp["pitch"]?.let { synthesizer.setPitch(it as Float) }
      temp["rate"]?.let { synthesizer.setSpeechRate(it as Float) }
      temp["voice"]?.let { voiceId ->
        synthesizer.voices?.find { it.name == voiceId }?.let { synthesizer.voice = it }
      }
    } catch (e: Exception) {}
  }

  private fun getValidatedOptions(options: ReadableMap): Map<String, Any> {
    val validated = globalOptions.toMutableMap()
    if (options.hasKey("ducking")) validated["ducking"] = options.getBoolean("ducking")
    if (options.hasKey("voice")) options.getString("voice")?.let { validated["voice"] = it }
    if (options.hasKey("language")) validated["language"] = options.getString("language") ?: Locale.getDefault().toLanguageTag()
    if (options.hasKey("pitch")) validated["pitch"] = options.getDouble("pitch").toFloat().coerceIn(0.1f, 2.0f)
    if (options.hasKey("volume")) validated["volume"] = options.getDouble("volume").toFloat().coerceIn(0f, 1.0f)
    if (options.hasKey("rate")) validated["rate"] = options.getDouble("rate").toFloat().coerceIn(0.1f, 2.0f)
    return validated
  }

  private fun processNextQueueItem() {
    synchronized(queueLock) {
      if (isPaused || !isInitialized) return
      if (currentQueueIndex in 0 until speechQueue.size) {
        val item = speechQueue[currentQueueIndex]
        if (item.status == SpeechStatus.PENDING || item.status == SpeechStatus.PAUSED) {
          applyOptions(item.options)
          val textToSpeak = if (item.status == SpeechStatus.PAUSED) {
            item.offset = item.position
            isResuming = true
            item.text.substring(item.offset)
          } else {
            item.offset = 0
            item.text
          }
          val queueMode = if (isResuming) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
          try {
            synthesizer.speak(textToSpeak, queueMode, getSpeechParams(), item.utteranceId)
          } catch (e: Exception) {
            item.status = SpeechStatus.ERROR
            currentQueueIndex++
            processNextQueueItem()
          }
          if (currentQueueIndex == speechQueue.size - 1) applyGlobalOptions()
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
      val voices = try { synthesizer.voices } catch (e: Exception) { null }
      if (voices == null) {
        promise.resolve(voicesArray)
        return@ensureInitialized
      }
      val lowercaseLanguage = language?.lowercase()
      voices.forEach { voice ->
        if (lowercaseLanguage == null || voice.locale.toLanguageTag().lowercase().startsWith(lowercaseLanguage)) {
          voicesArray.pushMap(getVoiceItem(voice))
        }
      }
      promise.resolve(voicesArray)
    }
  }

  override fun isSpeaking(promise: Promise) {
    ensureInitialized(promise) {
      val isEngineSpeaking = try { synthesizer.isSpeaking } catch (e: Exception) { false }
      promise.resolve(isEngineSpeaking || isPaused)
    }
  }

  override fun stop(promise: Promise) {
    ensureInitialized(promise) {
      val isEngineSpeaking = try { synthesizer.isSpeaking } catch (e: Exception) { false }
      if (isEngineSpeaking || isPaused) {
        try { synthesizer.stop() } catch (e: Exception) {}
        deactivateDuckingSession()
        synchronized(queueLock) {
          if (currentQueueIndex in speechQueue.indices) {
            emitOnStopped(getEventData(speechQueue[currentQueueIndex].utteranceId))
          }
          resetQueueState()
        }
      }
      promise.resolve(null)
    }
  }

  override fun pause(promise: Promise) {
    ensureInitialized(promise) {
      val isEngineSpeaking = try { synthesizer.isSpeaking } catch (e: Exception) { false }
      if (!isSupportedPausing || isPaused || !isEngineSpeaking || speechQueue.isEmpty()) {
        promise.resolve(false)
      } else {
        isPaused = true
        try { synthesizer.stop() } catch (e: Exception) {}
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
        val pausedIdx = speechQueue.indexOfFirst { it.status == SpeechStatus.PAUSED }
        if (pausedIdx >= 0) {
          currentQueueIndex = pausedIdx
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
      val item = SpeechQueueItem(text = text, options = emptyMap(), utteranceId = utteranceId)
      synchronized(queueLock) {
        speechQueue.add(item)
        val engineBusy = try { synthesizer.isSpeaking } catch (e: Exception) { false }
        if (!engineBusy && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
          processNextQueueItem()
        }
      }
      promise.resolve(utteranceId)
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
      val validated = getValidatedOptions(options)
      isDucking = validated["ducking"] as? Boolean ?: false
      activateDuckingSession()
      val utteranceId = getUniqueID()
      val item = SpeechQueueItem(text = text, options = validated, utteranceId = utteranceId)
      synchronized(queueLock) {
        speechQueue.add(item)
        val engineBusy = try { synthesizer.isSpeaking } catch (e: Exception) { false }
        if (!engineBusy && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
          processNextQueueItem()
        }
      }
      promise.resolve(utteranceId)
    }
  }

  override fun getEngines(promise: Promise) {
    ensureInitialized(promise) {
      val enginesArray = Arguments.createArray()
      val engines = cachedEngines ?: try { synthesizer.engines } catch (e: Exception) { null }
      engines?.forEach { engine ->
        enginesArray.pushMap(Arguments.createMap().apply {
          putString("name", engine.name)
          putString("label", engine.label)
          putBoolean("isDefault", engine.name == try { synthesizer.defaultEngine } catch (e: Exception) { "" })
        })
      }
      promise.resolve(enginesArray)
    }
  }

  override fun setEngine(engineName: String, promise: Promise) {
    ensureInitialized(promise) {
      val engines = try { synthesizer.engines } catch (e: Exception) { emptyList() }
      if (engines.none { it.name == engineName }) {
        promise.reject("engine_error", "Engine '$engineName' is not available")
        return@ensureInitialized
      }
      val active = selectedEngine ?: try { synthesizer.defaultEngine } catch (e: Exception) { "" }
      if (active == engineName) {
        promise.resolve(null)
        return@ensureInitialized
      }
      selectedEngine = engineName
      invalidate()
      synchronized(initLock) { pendingOperations.add(Pair({ promise.resolve(null) }, promise)) }
      initializeTTS()
    }
  }

  override fun openVoiceDataInstaller(promise: Promise) {
    try {
      val activity = currentActivity ?: throw Exception("The current activity is not available to launch the installer.")
      val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
      if (intent.resolveActivity(activity.packageManager) != null) {
        activity.startActivity(intent)
        promise.resolve(null)
      } else {
        promise.reject("UNSUPPORTED_OPERATION", "No activity found to handle TTS voice data installation on this device.")
      }
    } catch (e: Exception) {
      promise.reject("INSTALLER_ERROR", e.message, e)
    }
  }

  override fun invalidate() {
    super.invalidate()
    synchronized(initLock) {
      try {
        if (::synthesizer.isInitialized) {
          try { synthesizer.stop() } catch (e: Exception) {}
          try { synthesizer.shutdown() } catch (e: Exception) {}
          resetQueueState()
        }
      } catch (e: Exception) {}
      isInitialized = false
      isInitializing = false
      clearInitTimeout()
    }
  }
}
