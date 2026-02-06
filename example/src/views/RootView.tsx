import React from 'react';
import {gs} from '../styles/gs';
import {
  Text,
  View,
  Alert,
  Platform,
  StyleSheet,
  useColorScheme,
} from 'react-native';
import Speech, {
  HighlightedText,
  type HighlightedSegmentArgs,
  type HighlightedSegmentProps,
} from '@mhpdev/react-native-speech';
import Button from '../components/Button';
import {SafeAreaView} from 'react-native-safe-area-context';

const isAndroidLowerThan26 = Platform.OS === 'android' && Platform.Version < 26;

const Introduction =
  "This high-performance text-to-speech library is built for bare React Native and Expo, compatible with Android and iOS's new architecture (default from React Native 0.76). It enables seamless speech management with start, pause, resume, and stop controls, and provides events for detailed synthesis management.";

const RootView: React.FC = () => {
  const scheme = useColorScheme();

  const textColor = scheme === 'dark' ? 'white' : 'black';

  const [isPaused, setIsPaused] = React.useState<boolean>(false);

  const [isStarted, setIsStarted] = React.useState<boolean>(false);

  const [highlights, setHighlights] = React.useState<
    Array<HighlightedSegmentProps>
  >([]);

  const targetId = React.useRef<string>('');

  React.useEffect(() => {
    // Speech.initialize({silentMode: 'obey', ducking: true});

    const onSpeechEnd = () => {
      setIsStarted(false);
      setIsPaused(false);
      setHighlights([]);
      targetId.current = '';
    };

    const startSubscription = Speech.onStart(({id}) => {
      if (id === targetId.current) {
        setIsStarted(true);
        console.log(`Speech ${id} started`);
      }
    });
    const finishSubscription = Speech.onFinish(({id}) => {
      if (id === targetId.current) {
        onSpeechEnd();
        console.log(`Speech ${id} finished`);
      }
    });
    const pauseSubscription = Speech.onPause(({id}) => {
      if (id === targetId.current) {
        setIsPaused(true);
        console.log(`Speech ${id} paused`);
      }
    });
    const resumeSubscription = Speech.onResume(({id}) => {
      if (id === targetId.current) {
        setIsPaused(false);
        console.log(`Speech ${id} resumed`);
      }
    });
    const stoppedSubscription = Speech.onStopped(({id}) => {
      if (id === targetId.current) {
        onSpeechEnd();
        console.log(`Speech ${id} stopped`);
      }
    });
    const progressSubscription = Speech.onProgress(({id, location, length}) => {
      if (id === targetId.current) {
        setHighlights([
          {
            start: location,
            end: location + length,
          },
        ]);
        console.log(
          `Speech ${id} progress, current word length: ${length}, current char position: ${location}`,
        );
      }
    });

    // (async () => {
    //   const enVoices = await Speech.getAvailableVoices('en-us');
    //   Speech.initialize({
    //     rate: 0.5,
    //     volume: 1,
    //     voice: enVoices[3]?.identifier,
    //   });
    // })();

    // (async () => {
    //   const engines = await Speech.getEngines();
    //   if (engines?.[0]) {
    //     await Speech.setEngine(engines[0].name);
    //   }
    // })();

    return () => {
      startSubscription.remove();
      finishSubscription.remove();
      pauseSubscription.remove();
      resumeSubscription.remove();
      stoppedSubscription.remove();
      progressSubscription.remove();
    };
  }, []);

  const onStartPress = React.useCallback(async () => {
    const id = await Speech.speak(Introduction);
    targetId.current = id;
  }, []);

  const onHighlightedPress = React.useCallback(
    ({text, start, end}: HighlightedSegmentArgs) =>
      Alert.alert(
        'Highlighted',
        `The current segment is "${text}", starting at ${start} and ending at ${end}`,
      ),
    [],
  );

  return (
    <SafeAreaView style={[gs.flex, gs.p10]}>
      <View style={gs.flex}>
        <Text style={[gs.title, {color: textColor}]}>Introduction</Text>
        <HighlightedText
          text={Introduction}
          highlights={highlights}
          highlightedStyle={styles.highlighted}
          onHighlightedPress={onHighlightedPress}
          style={[gs.paragraph, {color: textColor}]}
        />
      </View>
      <View style={[gs.row, gs.p10]}>
        <Button label="Start" disabled={isStarted} onPress={onStartPress} />
        <Button label="Stop" disabled={!isStarted} onPress={Speech.stop} />
        {isAndroidLowerThan26 ? null : (
          <React.Fragment>
            <Button
              label="Pause"
              onPress={Speech.pause}
              disabled={isPaused || !isStarted}
            />
            <Button
              label="Resume"
              disabled={!isPaused}
              onPress={Speech.resume}
            />
          </React.Fragment>
        )}
      </View>
    </SafeAreaView>
  );
};

export default RootView;

const styles = StyleSheet.create({
  highlighted: {
    color: 'black',
    fontWeight: '600',
    backgroundColor: '#ffff00',
  },
});
