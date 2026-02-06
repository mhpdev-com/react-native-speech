import TurboSpeech from './NativeSpeech';
import type {VoiceProps, VoiceOptions, EngineProps} from './NativeSpeech';

export default class Speech {
  /**
   * The *maximum number of characters allowed in a single call to the speak methods.
   *
   * On `Android`, this value is determined by `TextToSpeech.getMaxSpeechInputLength`.
   * Text exceeding this length must be manually split into smaller utterances on the JavaScript side.
   *
   * On `iOS`, there is no synthesis system limit, and by default, the speech class returns `Number.MAX_VALUE`.
   */
  static readonly maxInputLength =
    TurboSpeech.getConstants().maxInputLength ?? Number.MAX_VALUE;
  /**
   * Gets a list of all available voices on the device
   * @param language - Optional language code to filter voices (e.g., 'en', 'fr', 'en-US', 'fr-FR').
   *                  If not provided, returns all available voices.
   * @returns Promise<VoiceProps[]> Array of voice properties matching the language filter
   * @example
   * // Get all available voices
   * const allVoices = await Speech.getAvailableVoices();
   * // Get only English voices
   * const englishVoices = await Speech.getAvailableVoices('en-US');
   * // or
   * const englishVoices = await Speech.getAvailableVoices('en');
   */
  public static getAvailableVoices(language?: string): Promise<VoiceProps[]> {
    return TurboSpeech.getAvailableVoices(language);
  }
  /**
   * Gets a list of all available text-to-speech engines on the device
   * @returns Promise<EngineProps[]> Array of engine properties including name, label, and isDefault flag
   * @platform Android
   * @example
   * const engines = await Speech.getEngines();
   * engines.forEach(engine => {
   *   console.log(`Engine: ${engine.label} (${engine.name})`);
   *   if (engine.isDefault) {
   *     console.log('This is the default engine');
   *   }
   * });
   */
  public static getEngines(): Promise<EngineProps[]> {
    return TurboSpeech.getEngines();
  }
  /**
   * Sets the text-to-speech engine to use for speech synthesis
   * @param engineName - The name of the engine to use (obtained from getEngines())
   * @returns Promise<void> Resolves when engine is set
   * @platform Android
   * @example
   * // First, get available engines
   * const engines = await Speech.getEngines();
   * // Then set a specific engine
   * await Speech.setEngine(engines[0].name);
   * // Or set by known engine name
   * await Speech.setEngine('com.google.android.tts');
   */
  public static setEngine(engineName: string): Promise<void> {
    return TurboSpeech.setEngine(engineName);
  }
  /**
   * Opens the system UI to install or update TTS voice data.
   * @returns Promise<void> Resolves when the installer activity has been launched.
   * @throws If the installer activity cannot be opened on the device.
   * @platform Android
   */
  public static openVoiceDataInstaller(): Promise<void> {
    return TurboSpeech.openVoiceDataInstaller();
  }
  /**
   * Sets the global options for all subsequent speak() calls
   * @param options - Voice configuration options
   * @example
   * Speech.initialize({
   *   pitch: 1.2,
   *   rate: 0.8,
   *   volume: 1.0,
   *   language: 'en-US'
   * });
   */
  public static initialize(options: VoiceOptions): void {
    TurboSpeech.initialize(options);
  }
  /**
   * Resets all speech options to their default values
   * @example
   * Speech.reset();
   */
  public static reset(): void {
    TurboSpeech.reset();
  }
  /**
   * Immediately stops any ongoing or in queue speech synthesis
   * @returns Promise<void> Resolves when speech is stopped
   * @example
   * await Speech.stop();
   */
  public static stop(): Promise<void> {
    return TurboSpeech.stop();
  }
  /**
   * Pauses the current speech at the next word boundary
   * @note on Android, API 26+ required due to missing onRangeStart support
   * @returns Promise<boolean> Resolves to true if speech was paused, false if nothing to pause
   * @example
   * const isPaused = await Speech.pause();
   * console.log(isPaused ? 'Speech paused' : 'Nothing to pause');
   */
  public static pause(): Promise<boolean> {
    return TurboSpeech.pause();
  }
  /**
   * Resumes previously paused speech
   * @note on Android, API 26+ required due to missing onRangeStart support
   * @returns Promise<boolean> Resolves to true if speech was resumed, false if nothing to resume
   * @example
   * const isResumed = await Speech.resume();
   * console.log(isResumed ? 'Speech resumed' : 'Nothing to resume');
   */
  public static resume(): Promise<boolean> {
    return TurboSpeech.resume();
  }
  /**
   * Checks if speech is currently being synthesized
   * @returns Promise<boolean> Resolves to true if speaking or paused, false otherwise
   * @example
   * const speaking = await Speech.isSpeaking();
   * console.log(speaking ? 'Speaking' : 'Not speaking');
   */
  public static isSpeaking(): Promise<boolean> {
    return TurboSpeech.isSpeaking();
  }
  /**
   * Speaks text using current global options
   * @param text - The text to synthesize
   * @returns Promise<string> Resolves with utterance ID when the utterance is queued
   * @throws If text is null or undefined
   * @example
   * const id = await Speech.speak('Hello, world!');
   * // Later, use this ID to track events:
   * Speech.onFinish(({id: eventId}) => {
   *   if (eventId === id) console.log('My speech finished');
   * });
   */
  public static speak(text: string): Promise<string> {
    return TurboSpeech.speak(text);
  }
  /**
   * Speaks text with custom options for this utterance only. Uses global options for any settings not provided.
   * @param text - The text to synthesize
   * @param options - Voice options overriding global settings
   * @returns Promise<string> Resolves with utterance ID when the utterance is queued
   * @throws If text is null or undefined
   * @example
   * const id = await Speech.speakWithOptions('Hello!', {
   *   pitch: 1.5,
   *   rate: 0.8,
   *   language: 'en-US'
   * });
   * // Later, use this ID to track events:
   * Speech.onFinish(({id: eventId}) => {
   *   if (eventId === id) console.log('My speech finished');
   * });
   */
  public static speakWithOptions(
    text: string,
    options: VoiceOptions,
  ): Promise<string> {
    return TurboSpeech.speakWithOptions(text, options);
  }

  /**
   * Called when an error occurs during speech synthesis
   * @example
   * // Add listener
   * const subscription = Speech.onError(({id}) => console.log('Speech error', id));
   * // Later, cleanup when no longer needed
   * subscription.remove();
   */
  public static onError = TurboSpeech.onError;
  /**
   * Called when speech synthesis begins
   * @example
   * // Add listener
   * const subscription = Speech.onStart(({id}) => console.log('Started speaking', id));
   * // Later, cleanup when no longer needed
   * subscription.remove();
   */
  public static onStart = TurboSpeech.onStart;
  /**
   * Called when speech synthesis completes successfully
   * @example
   * const subscription = Speech.onFinish(({id}) => console.log('Finished speaking', id));
   * // Cleanup
   * subscription.remove();
   */
  public static onFinish = TurboSpeech.onFinish;
  /**
   * Called when speech is paused
   * @note on Android, API 26+ required due to missing onRangeStart support
   * @example
   * const subscription = Speech.onPause(({id}) => console.log('Speech paused', id));
   * // Cleanup
   * subscription.remove();
   */
  public static onPause = TurboSpeech.onPause;
  /**
   * Called when speech is resumed
   * @note on Android, API 26+ required due to missing onRangeStart support
   * @example
   * const subscription = Speech.onResume(({id}) => console.log('Speech resumed', id));
   * // Cleanup
   * subscription.remove();
   */
  public static onResume = TurboSpeech.onResume;
  /**
   * Called when speech is stopped
   * @example
   * const subscription = Speech.onStopped(({id}) => console.log('Speech stopped', id));
   * // Cleanup
   * subscription.remove();
   */
  public static onStopped = TurboSpeech.onStopped;
  /**
   * Called during speech with progress information
   * @note on Android, API 26+ required due to missing onRangeStart support
   * @example
   * const subscription = Speech.onProgress(progress => {
   *   console.log(`Speaking progress`, progress);
   * });
   * // Cleanup when component unmounts or listener is no longer needed
   * subscription.remove();
   */
  public static onProgress = TurboSpeech.onProgress;
}
