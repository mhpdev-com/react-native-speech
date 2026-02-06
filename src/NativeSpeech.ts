import {TurboModuleRegistry, type TurboModule} from 'react-native';
import type {EventEmitter} from 'react-native/Libraries/Types/CodegenTypesNamespace';

export type VoiceQuality = 'Default' | 'Enhanced';

export interface EventProps {
  /**
   * The utterance ID
   */
  id: string;
}
export interface ProgressEventProps extends EventProps {
  /**
   * The text being spoken length
   */
  length: number;
  /**
   * The current position in the spoken text
   */
  location: number;
}
export interface VoiceProps {
  /** The name of the voice */
  name: string;
  /** The quality level of the voice */
  quality: VoiceQuality;
  /** The language code of the voice (e.g., 'en-US', 'fr-FR') */
  language: string;
  /** The unique identifier for the voice */
  identifier: string;
}
export interface VoiceOptions {
  /**
   * If `true`, audio from other apps will be temporarily lowered (ducked) while speech is active.
   * This is for critical announcements (e.g., navigation) and takes priority over `silentMode` on iOS.
   * @default false
   */
  ducking?: boolean;
  /**
   * Determines how speech audio interacts with the device's silent (ringer) switch.
   * This option is ignored if `ducking` is `true`.
   * @platform iOS
   *
   * - `obey`: (Default) Does not change the app's audio session. Speech follows the system default.
   * - `respect`: Speech will be silenced by the ringer switch. Use for non-critical audio.
   * - `ignore`: Speech will play even if the ringer is off. Use for critical audio when ducking is not desired.
   */
  silentMode?: 'obey' | 'respect' | 'ignore';
  /** The language code to use (e.g., 'en', 'fr', 'en-US', 'fr-FR') */
  language?: string;
  /** Volume level from 0.0 to 1.0 */
  volume?: number;
  /** Specific voice identifier to use */
  voice?: string;
  /**
   * Pitch multiplier from 0.5 to 2.0
   * - `Android`: (0.1 - 2.0)
   * - `iOS`: (0.5 - 2.0)
   */
  pitch?: number;
  /**
   * Speech rate
   * - `Android`: (0.1 - 2.0)
   * - `iOS`: (`AVSpeechUtteranceMinimumSpeechRate` - `AVSpeechUtteranceMaximumSpeechRate`)
   */
  rate?: number;
}
export interface EngineProps {
  /**
   * The unique system identifier for the engine.
   * This is typically the package name (e.g., "com.google.android.tts")
   */
  name: string;
  /**
   * The human-readable display name for the engine (e.g., "Google Text-to-Speech Engine").
   */
  label: string;
  /**
   * A boolean flag indicating if this is the default engine
   */
  isDefault: boolean;
}

interface ConstantsProps {
  /**
   * The maximum number of characters allowed in a single call to `speak()` or `speakWithOptions()`.
   * @platform Android
   */
  maxInputLength?: number;
}

export interface Spec extends TurboModule {
  getConstants: () => ConstantsProps;
  //Methods
  reset: () => void;
  stop: () => Promise<void>;
  pause: () => Promise<boolean>;
  resume: () => Promise<boolean>;
  isSpeaking: () => Promise<boolean>;
  speak: (text: string) => Promise<string>;
  getEngines: () => Promise<EngineProps[]>;
  initialize: (options: VoiceOptions) => void;
  openVoiceDataInstaller: () => Promise<void>;
  setEngine: (engineName: string) => Promise<void>;
  getAvailableVoices: (language?: string) => Promise<VoiceProps[]>;
  speakWithOptions: (text: string, options: VoiceOptions) => Promise<string>;
  //Listeners
  readonly onError: EventEmitter<EventProps>;
  readonly onStart: EventEmitter<EventProps>;
  readonly onFinish: EventEmitter<EventProps>;
  readonly onPause: EventEmitter<EventProps>;
  readonly onResume: EventEmitter<EventProps>;
  readonly onStopped: EventEmitter<EventProps>;
  readonly onProgress: EventEmitter<ProgressEventProps>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNSpeech');
