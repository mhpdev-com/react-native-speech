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
import java.util.concurrent.Executors
import android.media.AudioFocusRequest
import android.annotation.SuppressLint
import android.speech.tts.TextToSpeech
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.Arguments
import java.util.concurrent.ExecutorService
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

  private data class SpeakWork(
    val item: SpeechQueueItem,
    val text: String,
    val queueMode: Int
  )

  private val initLock = Any()
  private val queueLock = Any()

  private val mainHandler = Handler(Looper.getMainLooper())

  private val maxInputLength = TextToSpeech.getMaxSpeechInputLength()
  private val isSupportedPausing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

  private lateinit var synthesizer: TextToSpeech

  private var selectedEngine: String? = null
  @Volatile private var cachedEngines: List<TextToSpeech.EngineInfo>? = null

  @Volatile private var initGeneration = 0
  @Volatile private var isInitialized = false
  @Volatile private var isInitializing = false

  private var initRetryCount = 0
  private var isRetryScheduled = false
  private var initRetryRunnable: Runnable? = null
  private var initTimeoutRunnable: Runnable? = null
  private var initExecutor: ExecutorService? = null

  private val pendingOperations = mutableListOf<Pair<() -> Unit, Promise>>()

  @Volatile private var globalOptions: MutableMap<String, Any> = defaultOptions.toMutableMap()
  private var isPaused = false
  private var isResuming = false
  private var currentUtteranceId: String? = null
  private val speechQueue = LinkedHashMap<String, SpeechQueueItem>()

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
      currentUtteranceId = null
      isPaused = false
      isResuming = false
    }
  }

  private fun getItemDucking(item: SpeechQueueItem): Boolean {
    return (item.options["ducking"] as? Boolean)
      ?: (globalOptions["ducking"] as? Boolean)
      ?: false
  }

  private fun cleanupQueueHeadLocked() {
    val iterator = speechQueue.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      val status = entry.value.status
      if (status == SpeechStatus.COMPLETED || status == SpeechStatus.ERROR) {
        if (currentUtteranceId == entry.key) {
          currentUtteranceId = null
        }
        iterator.remove()
      } else {
        break
      }
    }
    if (speechQueue.isEmpty()) {
      currentUtteranceId = null
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

  private fun getInitExecutor(): ExecutorService = synchronized(initLock) {
    initExecutor ?: Executors.newSingleThreadExecutor { r ->
      Thread(r, "RNSpeech-Init").apply { isDaemon = true }
    }.also { initExecutor = it }
  }

  private fun onInitFailure() {
    val hadSynthesizer = synchronized(initLock) {
      initGeneration++
      isInitializing = false
      isInitialized = false
      val had = ::synthesizer.isInitialized
      initRetryCount++
      if (initRetryCount <= MAX_INIT_RETRIES) {
        val delay = 1000L * (1 shl (initRetryCount - 1))
        val runnable = Runnable {
          synchronized(initLock) {
            isRetryScheduled = false
            initRetryRunnable = null
          }
          createTTSInstance()
        }
        initRetryRunnable = runnable
        isRetryScheduled = true
        mainHandler.postDelayed(runnable, delay)
      } else {
        initRetryCount = 0
        rejectPendingOperations()
      }
      had
    }
    if (hadSynthesizer) {
      try {
        synthesizer.shutdown()
      } catch (e: Exception) {}
    }
  }

  private fun resetSynthesizer() {
    val hadSynthesizer = synchronized(initLock) {
      initGeneration++
      val had = ::synthesizer.isInitialized
      isInitialized = false
      isInitializing = false
      if (isRetryScheduled) {
        initRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        initRetryRunnable = null
        isRetryScheduled = false
      }
      clearInitTimeout()
      had
    }
    if (hadSynthesizer) {
      try { synthesizer.stop() } catch (e: Exception) {}
      try { synthesizer.shutdown() } catch (e: Exception) {}
    }
    resetQueueState()
  }

  private fun createTTSInstance() {
    synchronized(initLock) {
      if (isInitializing) return
      isInitializing = true
      scheduleInitTimeout()
      val generation = initGeneration
      mainHandler.post {
        try {
          synthesizer = TextToSpeech(reactApplicationContext, { status ->
            clearInitTimeout()
            if (generation != initGeneration) {
              return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS) {
              synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                  synchronized(queueLock) {
                    speechQueue[utteranceId]?.let { item ->
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
                  var shouldAdvance = false
                  synchronized(queueLock) {
                    speechQueue[utteranceId]?.let { item ->
                      item.status = SpeechStatus.COMPLETED
                      deactivateDuckingSession()
                      emitOnFinish(getEventData(utteranceId))
                      if (!isPaused) {
                        currentUtteranceId = null
                        cleanupQueueHeadLocked()
                        shouldAdvance = true
                      }
                    }
                  }
                  if (shouldAdvance) processNextQueueItem()
                }
                override fun onError(utteranceId: String) {
                  var shouldAdvance = false
                  synchronized(queueLock) {
                    speechQueue[utteranceId]?.let { item ->
                      item.status = SpeechStatus.ERROR
                      deactivateDuckingSession()
                      emitOnError(getEventData(utteranceId))
                      if (!isPaused) {
                        currentUtteranceId = null
                        cleanupQueueHeadLocked()
                        shouldAdvance = true
                      }
                    }
                  }
                  if (shouldAdvance) processNextQueueItem()
                }
                override fun onStop(utteranceId: String, interrupted: Boolean) {
                  synchronized(queueLock) {
                    speechQueue[utteranceId]?.let { item ->
                      if (isPaused) {
                        item.status = SpeechStatus.PAUSED
                        emitOnPause(getEventData(utteranceId))
                      } else {
                        item.status = SpeechStatus.COMPLETED
                        deactivateDuckingSession()
                        emitOnStopped(getEventData(utteranceId))
                        currentUtteranceId = null
                        cleanupQueueHeadLocked()
                      }
                    }
                  }
                }
                override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                  synchronized(queueLock) {
                    speechQueue[utteranceId]?.let { item ->
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
              getInitExecutor().execute {
                if (generation != initGeneration) return@execute
                try {
                  cachedEngines = try { synthesizer.engines } catch (e: Throwable) { null }
                  applyGlobalOptionsInternal()
                  var opened = false
                  synchronized(initLock) {
                    if (generation == initGeneration) {
                      isInitialized = true
                      isInitializing = false
                      initRetryCount = 0
                      opened = true
                    }
                  }
                  if (opened) processPendingOperations()
                } catch (t: Throwable) {
                  if (generation == initGeneration) onInitFailure()
                }
              }
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
      if (isInitializing || isInitialized || isRetryScheduled) return
      initRetryCount = 0
      createTTSInstance()
    }
  }

  private fun ensureInitialized(promise: Promise, operation: () -> Unit) {
    val action: Int = synchronized(initLock) {
      when {
        isInitialized -> 0
        isInitializing -> {
          pendingOperations.add(Pair(operation, promise))
          1
        }
        else -> {
          pendingOperations.add(Pair(operation, promise))
          2
        }
      }
    }
    when (action) {
      0 -> try {
        operation()
      } catch (e: Exception) {
        promise.reject("speech_error", e.message ?: "Unknown error")
      }
      2 -> initializeTTS()
    }
  }

  private fun applyGlobalOptions() {
    if (!isInitialized) return
    applyGlobalOptionsInternal()
  }

  private fun applyGlobalOptionsInternal() {
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
    } catch (e: Throwable) {}
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
    } catch (e: Throwable) {}
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
    val work = synchronized(queueLock) {
      if (isPaused || !isInitialized) return
      var item = currentUtteranceId?.let { speechQueue[it] }
      if (item == null || (item.status != SpeechStatus.PENDING && item.status != SpeechStatus.PAUSED)) {
        item = speechQueue.values.firstOrNull { it.status == SpeechStatus.PENDING || it.status == SpeechStatus.PAUSED }
        currentUtteranceId = item?.utteranceId
      }
      if (item == null) return@synchronized null
      isDucking = getItemDucking(item)
      val text = if (item.status == SpeechStatus.PAUSED) {
        item.offset = item.position
        isResuming = true
        item.text.substring(item.offset)
      } else {
        item.offset = 0
        item.text
      }
      val queueMode = if (isResuming) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
      SpeakWork(item, text, queueMode)
    }
    if (work == null) {
      applyGlobalOptions()
      return
    }
    activateDuckingSession()
    applyOptions(work.item.options)
    try {
      synthesizer.speak(work.text, work.queueMode, getSpeechParams(), work.item.utteranceId)
    } catch (e: Exception) {
      synchronized(queueLock) {
        work.item.status = SpeechStatus.ERROR
        currentUtteranceId = null
        cleanupQueueHeadLocked()
      }
      processNextQueueItem()
    }
  }

  override fun configure(options: ReadableMap) {
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
      val paused = synchronized(queueLock) { isPaused }
      promise.resolve(isEngineSpeaking || paused)
    }
  }

  override fun stop(promise: Promise) {
    ensureInitialized(promise) {
      val isEngineSpeaking = try { synthesizer.isSpeaking } catch (e: Exception) { false }
      val wasPaused = synchronized(queueLock) { isPaused }
      if (isEngineSpeaking || wasPaused) {
        try { synthesizer.stop() } catch (e: Exception) {}
        deactivateDuckingSession()
        synchronized(queueLock) {
          currentUtteranceId?.let { emitOnStopped(getEventData(it)) }
          resetQueueState()
        }
      }
      promise.resolve(null)
    }
  }

  override fun pause(promise: Promise) {
    ensureInitialized(promise) {
      val isEngineSpeaking = try { synthesizer.isSpeaking } catch (e: Exception) { false }
      val shouldStop = synchronized(queueLock) {
        if (isSupportedPausing && !isPaused && isEngineSpeaking && speechQueue.isNotEmpty()) {
          isPaused = true
          true
        } else {
          false
        }
      }
      if (shouldStop) {
        try { synthesizer.stop() } catch (e: Exception) {}
        deactivateDuckingSession()
      }
      promise.resolve(shouldStop)
    }
  }

  override fun resume(promise: Promise) {
    ensureInitialized(promise) {
      var shouldProcess = false
      synchronized(queueLock) {
        if (!isSupportedPausing || !isPaused || speechQueue.isEmpty() || currentUtteranceId == null) {
          return@synchronized
        }
        val pausedItem = speechQueue.values.firstOrNull { it.status == SpeechStatus.PAUSED }
        if (pausedItem != null) {
          currentUtteranceId = pausedItem.utteranceId
          isDucking = getItemDucking(pausedItem)
          isPaused = false
          shouldProcess = true
        } else {
          isPaused = false
        }
      }
      if (shouldProcess) processNextQueueItem()
      promise.resolve(shouldProcess)
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
      val utteranceId = getUniqueID()
      val item = SpeechQueueItem(text = text, options = emptyMap(), utteranceId = utteranceId)
      val engineBusy = try { synthesizer.isSpeaking } catch (e: Exception) { false }
      var shouldProcess = false
      synchronized(queueLock) {
        speechQueue[utteranceId] = item
        if (!engineBusy && !isPaused) {
          currentUtteranceId = utteranceId
          shouldProcess = true
        }
      }
      if (shouldProcess) processNextQueueItem()
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
      val utteranceId = getUniqueID()
      val item = SpeechQueueItem(text = text, options = validated, utteranceId = utteranceId)
      val engineBusy = try { synthesizer.isSpeaking } catch (e: Exception) { false }
      var shouldProcess = false
      synchronized(queueLock) {
        speechQueue[utteranceId] = item
        if (!engineBusy && !isPaused) {
          currentUtteranceId = utteranceId
          shouldProcess = true
        }
      }
      if (shouldProcess) processNextQueueItem()
      promise.resolve(utteranceId)
    }
  }

  override fun getEngines(promise: Promise) {
    ensureInitialized(promise) {
      val enginesArray = Arguments.createArray()
      val engines = cachedEngines ?: try { synthesizer.engines } catch (e: Exception) { null }
      val defaultEngine = try { synthesizer.defaultEngine } catch (e: Exception) { "" }
      engines?.forEach { engine ->
        enginesArray.pushMap(Arguments.createMap().apply {
          putString("name", engine.name)
          putString("label", engine.label)
          putBoolean("isDefault", engine.name == defaultEngine)
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
      resetSynthesizer()
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
    resetSynthesizer()
    synchronized(initLock) {
      initExecutor?.shutdownNow()
      initExecutor = null
    }
    rejectPendingOperations()
  }
}
