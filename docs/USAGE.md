# React Native Speech Usage Guide

- [React Native Speech Usage Guide](#react-native-speech-usage-guide)
  - [Installation](#installation)
    - [Bare React Native](#bare-react-native)
    - [Expo](#expo)
  - [API Overview](#api-overview)
    - [Constants](#constants)
    - [Getting Available Voices](#getting-available-voices)
    - [Engine Management (Android)](#engine-management-android)
      - [Get Available Engines](#get-available-engines)
      - [Set Speech Engine](#set-speech-engine)
      - [Open Voice Data Installer](#open-voice-data-installer)
    - [Initializing Global Speech Options](#initializing-global-speech-options)
    - [Resetting Speech Options](#resetting-speech-options)
    - [Speaking Text](#speaking-text)
    - [Speaking Text with Custom Options](#speaking-text-with-custom-options)
    - [Controlling Speech](#controlling-speech)
      - [**Stop Speech**](#stop-speech)
      - [**Pause Speech**](#pause-speech)
      - [**Resume Speech**](#resume-speech)
      - [**Check if Speaking**](#check-if-speaking)
    - [Event Callbacks](#event-callbacks)
      - [**onError**](#onerror)
      - [**onStart**](#onstart)
      - [**onFinish**](#onfinish)
      - [**onPause**](#onpause)
      - [**onResume**](#onresume)
      - [**onStopped**](#onstopped)
      - [**onProgress**](#onprogress)
  - [HighlightedText](#highlightedtext)
    - [Importing the Component](#importing-the-component)
    - [Properties](#properties)
    - [Example](#example)
  - [Example Application](#example-application)

---

## Installation

### Bare React Native

Install the package using either npm or Yarn:

```sh
npm install @mhpdev/react-native-speech
```

Or with Yarn:

```sh
yarn add @mhpdev/react-native-speech
```

### Expo

For Expo projects, follow these steps:

1. Install the package:

   ```sh
   npx expo install @mhpdev/react-native-speech
   ```

2. Since it is not supported on Expo Go, run:

   ```sh
   npx expo prebuild
   ```

---

## API Overview

For text-to-speech, the library exports the `Speech` class, which provides methods for speech synthesis and event handling:

```tsx
import Speech from '@mhpdev/react-native-speech';
```

---

### Constants

The `Speech` class static constants.

**Values**

`maxInputLength`

The **maximum number of characters** allowed in a single call to the speak methods.

Android enforces this limit, which is determined by `TextToSpeech.getMaxSpeechInputLength`. If your text exceeds this limit, you must manually split it into smaller utterances on the JavaScript side. (_iOS has no synthesis system limit, and by default, the speech class returns `Number.MAX_VALUE`_)

### Getting Available Voices

Retrieve a list of all available voices on the device. Optionally, you can filter voices by providing a language code or tag ([IETF BCP 47 language tag](https://www.techonthenet.com/js/language_tags.php)).

**API Definition:**

```ts
Speech.getAvailableVoices(language?: string): Promise<VoiceProps[]>
```

**VoiceProps:**

- `name`: The name of the voice.
- `identifier`: The unique identifier for the voice.
- `language`: The language tag (e.g., `'en-US'`, `'fr-FR'`).
- `quality`: The quality level of the voice (`'Default'` or `'Enhanced'`).

**Example Usage:**

```ts
// Retrieve all voices
Speech.getAvailableVoices().then(voices => {
  console.log('Available voices:', voices);
});

// Retrieve only English voices
Speech.getAvailableVoices('en').then(voices => {
  console.log('English voices:', voices);
});

// Retrieve only English (US) voices
Speech.getAvailableVoices('en-US').then(voices => {
  console.log('English (US) voices:', voices);
});
```

---

### Engine Management (Android)

These methods are available only on the Android platform and allow you to manage the underlying text-to-speech engine.

#### Get Available Engines

Gets a list of all available text-to-speech engines installed on the device.

**API Definition**

```ts
Speech.getEngines(): Promise<EngineProps[]>
```

**Engine Properties:**

- `name`: The unique system identifier for the engine (e.g., "com.google.android.tts").
- `label`: The human-readable display name (e.g., "Google Text-to-Speech Engine").
- `isDefault`: A boolean flag indicating if this is the default engine.

**Example Usage:**

```ts
Speech.getEngines().then(engines => {
  engines.forEach(engine => {
    console.log(`Engine: ${engine.label} (${engine.name})`);
    if (engine.isDefault) {
      console.log('This is the default engine.');
    }
  });
});
```

#### Set Speech Engine

Sets the text-to-speech engine to use for all subsequent synthesis.

**API Definition**

```ts
Speech.setEngine(engineName: string): Promise<void>
```

**Example Usage:**

```ts
// First, get available engines
const engines = await Speech.getEngines();

if (engines.length > 0) {
  // Then set a specific engine by its name
  await Speech.setEngine(engines[0].name);
}
```

#### Open Voice Data Installer

Opens the system activity that allows the user to install or manage TTS voice data.

**API Definition**

```ts
Speech.openVoiceDataInstaller(): Promise<void>
```

**Example Usage:**

```ts
Speech.openVoiceDataInstaller().catch(error => {
  console.error('Failed to open voice data installer.', error);
});
```

---

### Initializing Global Speech Options

Set global speech options that apply to all speech synthesis calls.

**API Definition:**

```ts
Speech.initialize(options: VoiceOptions): void
```

**VoiceOptions Properties:**

| Property     | Type                              | Description                                                                                     | Platform Support |
| ------------ | --------------------------------- | ----------------------------------------------------------------------------------------------- | ---------------- |
| `language`   | `string`                          | Language code or IETF BCP 47 language tag (e.g., `'en-US'`, `'fr-FR'`)                          | Both             |
| `volume`     | `number`                          | Volume level from `0.0` (silent) to `1.0` (maximum)                                             | Both             |
| `voice`      | `string`                          | Specific voice identifier to use (obtained from `getAvailableVoices()`)                         | Both             |
| `pitch`      | `number`                          | Pitch multiplier: Android `0.1`–`2.0`, iOS `0.5`–`2.0`                                          | Both             |
| `rate`       | `number`                          | Speech rate: Android `0.1`–`2.0`, iOS varies based on `AVSpeechUtterance` limits                | Both             |
| `ducking`    | `boolean`                         | If `true`, temporarily lowers audio from other apps while speech is active. Defaults to `false` | Both             |
| `silentMode` | `'obey' \| 'respect' \| 'ignore'` | Controls how speech interacts with the device's silent switch. Ignored if `ducking` is `true`   | iOS only         |

**silentMode Options (iOS only):**

- **`obey`** (default): Does not change the app's audio session. Speech follows the system default behavior.
- **`respect`**: Speech will be silenced by the ringer switch. Use for non-critical audio content.
- **`ignore`**: Speech will play even if the ringer is off. Use for critical audio when ducking is not desired.

**Example Usage:**

```ts
Speech.initialize({
  language: 'en-US',
  volume: 1.0,
  pitch: 1.2,
  rate: 0.8,
  ducking: false,
  silentMode: 'obey', // iOS only; ignored if ducking is true
});
```

---

### Resetting Speech Options

Reset all global speech options to their default values.

**API Definition:**

```ts
Speech.reset(): void
```

**Example Usage:**

```ts
Speech.reset();
```

---

### Speaking Text

Speak a given text using the current global settings.

**API Definition:**

```ts
Speech.speak(text: string): Promise<string>
```

**Returns:**

- A unique utterance ID (string) returned immediately when the utterance is queued. Use it to filter events for this specific speech operation.

**Example Usage:**

```ts
const id = await Speech.speak('Hello, world!');

// Track events specific to this utterance
Speech.onFinish(({id: eventId}) => {
  if (eventId === id) {
    console.log('Speech finished');
  }
});
```

---

### Speaking Text with Custom Options

Override global options for a specific utterance.

**API Definition:**

```ts
Speech.speakWithOptions(text: string, options: VoiceOptions): Promise<string>
```

**Returns:**

- A unique utterance ID (string) returned immediately when the utterance is queued. Use it to filter events for this specific speech operation.

**Example Usage:**

```ts
const id = await Speech.speakWithOptions('Hello!', {
  language: 'en-US',
  pitch: 1.5,
  rate: 0.8,
});

// Track events specific to this utterance
Speech.onProgress(({id: eventId, location, length}) => {
  if (eventId === id) {
    console.log(`Progress: ${location}/${length}`);
  }
});
```

---

### Controlling Speech

#### **Stop Speech**

Immediately stops any ongoing or in queue speech synthesis.

```ts
Speech.stop().then(() => console.log('Speech stopped'));
```

#### **Pause Speech**

> **Note:** On Android, API 26+ (Android 8+) required.

```ts
Speech.pause().then(isPaused => {
  console.log(isPaused ? 'Speech paused' : 'Nothing to pause');
});
```

#### **Resume Speech**

> **Note:** On Android, API 26+ (Android 8+) required.

```ts
Speech.resume().then(isResumed => {
  console.log(isResumed ? 'Speech resumed' : 'Nothing to resume');
});
```

#### **Check if Speaking**

Determine if speech synthesis is currently active.

```ts
Speech.isSpeaking().then(isSpeaking => {
  console.log(isSpeaking ? 'Currently speaking or paused' : 'Not speaking');
});
```

---

### Event Callbacks

Subscribe to event callbacks for speech synthesis lifecycle monitoring. Each callback receives an `id` property that corresponds to the utterance ID returned by `speak()` or `speakWithOptions()`. This allows you to handle events for specific speech operations independently.

**Why use utterance IDs?**

Utterance IDs enable precise event tracking when handling multiple concurrent or sequential speech operations:

```ts
// Start first speech
const id1 = await Speech.speak('Hello');

// Start second speech
const id2 = await Speech.speak('World');

// Handle events only for the first speech
Speech.onFinish(({id}) => {
  if (id === id1) {
    console.log('First speech finished');
  } else if (id === id2) {
    console.log('Second speech finished');
  }
});
```

#### **onError**

Triggers when an error occurs.

```ts
const errorSubscription = Speech.onError(({id}) => {
  console.error(`Speech error (ID: ${id})`);
});

//Cleanup
errorSubscription.remove();
```

#### **onStart**

Triggers when speech starts.

```ts
const startSubscription = Speech.onStart(({id}) => {
  console.log(`Speech started (ID: ${id})`);
});

//Cleanup
startSubscription.remove();
```

#### **onFinish**

Triggers when speech completes.

```ts
const finishSubscription = Speech.onFinish(({id}) => {
  console.log(`Speech finished (ID: ${id})`);
});

//Cleanup
finishSubscription.remove();
```

#### **onPause**

Triggers when speech paused.

> **Note:** On Android, API 26+ (Android 8+) required.

```ts
const pauseSubscription = Speech.onPause(({id}) => {
  console.log(`Speech paused (ID: ${id})`);
});

//Cleanup
pauseSubscription.remove();
```

#### **onResume**

Triggers when speech resumed.

> **Note:** On Android, API 26+ (Android 8+) required.

```ts
const resumeSubscription = Speech.onResume(({id}) => {
  console.log(`Speech resumed (ID: ${id})`);
});

//Cleanup
resumeSubscription.remove();
```

#### **onStopped**

Triggers when speech is stopped.

```ts
const stoppedSubscription = Speech.onStopped(({id}) => {
  console.log(`Speech stopped (ID: ${id})`);
});

//Cleanup
stoppedSubscription.remove();
```

#### **onProgress**

> **Note:** On Android, API 26+ (Android 8+) required.

**Callback Parameters:**

- `id`: The utterance identifier
- `length`: The text being spoken length
- `location`: The current position in the spoken text

```ts
const progressSubscription = Speech.onProgress(({id, location, length}) => {
  console.log(
    `Speech ${id} progress, current word length: ${length}, current char position: ${location}`,
  );
});

//Cleanup
progressSubscription.remove();
```

---

## HighlightedText

The `HighlightedText` component allows you to display text with customizable highlighted segments. This is especially useful for emphasizing parts of text (e.g., the currently synthesized text). In addition to the specialized properties listed below, the component accepts all standard React Native `<Text>` props.

### Importing the Component

```tsx
import {HighlightedText} from '@mhpdev/react-native-speech';
```

### Properties

- **text**  
  _Type:_ `string`  
  The full text content to be displayed.

- **highlightedStyle**  
  _Type:_ `StyleProp<TextStyle>`  
  The base style applied to all highlighted segments. This style can be overridden by segment-specific styles defined in the `highlights` prop.

- **highlights**  
  _Type:_ `Array<{ start: number; end: number; style?: StyleProp<TextStyle> }>`  
  An array of objects that define which parts of the text should be highlighted. Each object must include:

  - **start**: The starting character index of the segment.
  - **end**: The ending character index of the segment.
  - **style** (optional): Custom style for this particular segment.

- **onHighlightedPress**  
  _Type:_ `(segment: { text: string; start: number; end: number }) => void`  
  A callback function that is invoked when a highlighted segment is pressed. The function receives an object containing:
  - **text**: The text content of the pressed segment.
  - **start**: The starting index of the segment.
  - **end**: The ending index of the segment.

### Example

```tsx
import React from 'react';
import {
  HighlightedText,
  type HighlightedSegmentProps,
  type HighlightedSegmentArgs,
} from '@mhpdev/react-native-speech';
import {Alert, SafeAreaView, StyleSheet} from 'react-native';

const TEXT = 'This is a sample text where some parts are highlighted.';

const ExampleHighlightedText: React.FC = () => {
  const highlights: Array<HighlightedSegmentProps> = [
    {start: 10, end: 21},
    {start: 43, end: 54, style: styles.customHighlightedStyle},
  ];

  const onHighlightedPress = React.useCallback(
    ({text, start, end}: HighlightedSegmentArgs) =>
      Alert.alert(
        'Highlighted Segment',
        `Segment "${text}" starts at ${start} and ends at ${end}`,
      ),
    [],
  );

  return (
    <SafeAreaView style={styles.container}>
      <HighlightedText
        text={TEXT}
        style={styles.text}
        highlights={highlights}
        highlightedStyle={styles.highlighted}
        onHighlightedPress={onHighlightedPress}
      />
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    padding: 16,
  },
  text: {
    fontSize: 16,
  },
  highlighted: {
    backgroundColor: 'yellow',
    fontWeight: 'bold',
  },
  customHighlightedStyle: {
    color: 'white',
    backgroundColor: 'blue',
  },
});

export default ExampleHighlightedText;
```

To learn more about how to use the component, [check out here](../example/src/views/RootView.tsx).

## Example Application

Check out the [example project](../example/).
