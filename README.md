<p align="center">
  <a href="https://mhpdev.com" target="_blank">
    <img src="./docs/banner.png" alt="React Native Full Responsive Banner" style="max-width:100%;height:auto;" />
  </a>
</p>

A high-performance text-to-speech library built for bare React Native and Expo, compatible with Android and iOS. It enables seamless speech management and provides events for detailed synthesis management.

<div align="center">
  <a href="./docs/USAGE.md">Documentation</a> · <a href="./example/">Example</a>
</div>
<br/>

> **Only New Architecture**: This library is only compatible with the new architecture. If you're using React Native 0.76 or higher, it is already enabled. However, if your React Native version is between 0.68 and 0.75, you need to enable it first. [Click here if you need help enabling the new architecture](https://github.com/reactwg/react-native-new-architecture/blob/main/docs/enable-apps.md)

## Preview

|                                                                                                          <center>Android</center>                                                                                                           |                                                                                                          <center>iOS</center>                                                                                                           |
| :-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------: | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------: |
| <video src="https://github.com/user-attachments/assets/0601b827-a87a-4eb0-be28-273aa2ec5942" controls width="100%" height="500"></video> [Android Preview](https://github.com/user-attachments/assets/0601b827-a87a-4eb0-be28-273aa2ec5942) | <video src="https://github.com/user-attachments/assets/1579639e-049b-42f4-9795-bc56956541bd" width="100%" height="500" controls></video> [iOS Preview](https://github.com/user-attachments/assets/1579639e-049b-42f4-9795-bc56956541bd) |

## Features

- 🚀 &nbsp;**High Performance** - Built on Turbo Modules for a fast, native-like experience on Android & iOS

- 🎛️ &nbsp;**Full Control** - Complete set of methods for comprehensive speech synthesis management

- 🪄 &nbsp;**Consistent Playback** - Offers `pause` and `resume` support for iOS and Android. Since this functionality isn’t natively available on Android, the library provides a custom implementation (API 26+) designed to emulate the iOS experience

- 🔊 &nbsp;**Optional Audio Ducking** - Automatically lowers other app audio to ensure clear, uninterrupted speech

- 📡 &nbsp;**Rich Events** - Comprehensive event system for precise synthesis lifecycle monitoring

- 💅 &nbsp;**Visual Feedback** - Customizable [HighlightedText](./docs/USAGE.md#highlightedtext) component for real-time speech visualization

- ✅ &nbsp;**Type Safety** - Fully written in TypeScript with complete type definitions

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

For iOS, navigate to the ios directory and install the pods:

```sh
cd ios && pod install
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

## Usage

To learn how to use the library, check out the [usage section](./docs/USAGE.md).

## Quick Start

```tsx
import React from 'react';
import Speech from '@mhpdev/react-native-speech';
import {SafeAreaView, StyleSheet, Text, TouchableOpacity} from 'react-native';

const App: React.FC = () => {
  const onSpeakPress = async () => {
    const id = await Speech.speak('Hello World!');
    console.log('Speech queued with id:', id);
  };

  return (
    <SafeAreaView style={styles.container}>
      <TouchableOpacity style={styles.button} onPress={onSpeakPress}>
        <Text style={styles.buttonText}>Speak</Text>
      </TouchableOpacity>
    </SafeAreaView>
  );
};

export default App;

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  button: {
    padding: 12.5,
    borderRadius: 5,
    backgroundColor: 'skyblue',
  },
  buttonText: {
    fontSize: 22,
    fontWeight: '600',
  },
});
```

To become more familiar with the usage of the library, check out the [example project](./example/).

## Testing

To mock the package's methods and components using the default mock configuration provided, follow these steps:

- Create a file named `@mhpdev/react-native-speech.ts` inside your `__mocks__` directory.

- Copy the following code into that file:

  ```js
  module.exports = require('@mhpdev/react-native-speech/jest');
  ```

## Contributing

See the [contributing guide](./docs/CONTRIBUTING.md) to learn how to contribute to the repository and the development workflow.

## License

MIT
