#import "RNSpeech.h"

using namespace JS::NativeSpeech;

@implementation RNSpeech
{
  BOOL isDucking;
  NSDictionary *defaultOptions;
  NSMutableDictionary *utteranceIdMap;
}

RCT_EXPORT_MODULE();

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

- (NSDictionary<NSString *, id> *)constantsToExport
{
  return @{};
}

- (NSDictionary<NSString *, id> *)getConstants
{
  return [self constantsToExport];
}

- (instancetype)init {
  self = [super init];

  if (self) {
    _synthesizer = [[AVSpeechSynthesizer alloc] init];
    _synthesizer.delegate = self;

    utteranceIdMap = [[NSMutableDictionary alloc] init];

    defaultOptions = @{
      @"pitch": @(1.0),
      @"volume": @(1.0),
      @"ducking": @(NO),
      @"silentMode": @"obey",
      @"rate": @(AVSpeechUtteranceDefaultSpeechRate),
      @"language": [AVSpeechSynthesisVoice currentLanguageCode] ?: @"en-US"
    };
    self.globalOptions = [defaultOptions copy];
  }
  return self;
}

- (void)activateDuckingSession {
  if (!isDucking) {
    return;
  }
  NSError *error = nil;
  AVAudioSession *session = [AVAudioSession sharedInstance];

  [session setCategory:AVAudioSessionCategoryPlayback
            mode:AVAudioSessionModeSpokenAudio
            options:AVAudioSessionCategoryOptionDuckOthers
                  error:&error];
  if (error) {
    NSLog(@"[Speech] Failed to set audio session configuration for ducking: %@", error.localizedDescription);
    return;
  }
  [session setActive:YES error:&error];
  if (error) {
    NSLog(@"[Speech] Failed to activate audio session for ducking: %@", error.localizedDescription);
  }
}

- (void)deactivateDuckingSession {
  if (!isDucking) {
    return;
  }
  NSError *error = nil;
  [[AVAudioSession sharedInstance] setActive:NO
                                 withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
                                       error:&error];

  if (error) {
    NSLog(@"[Speech] AVAudioSession setActive (deactivate) error: %@", error.localizedDescription);
  }
}

- (void)configureSilentModeSession:(NSString *)silentMode {
  if (isDucking || [silentMode isEqualToString:@"obey"]) {
    return;
  }
  NSError *error = nil;
  if ([silentMode isEqualToString:@"ignore"]) {
     [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryPlayback
             mode:AVAudioSessionModeSpokenAudio
             options:AVAudioSessionCategoryOptionInterruptSpokenAudioAndMixWithOthers
                   error:&error];
  } else if ([silentMode isEqualToString:@"respect"]) {
    [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryAmbient error:&error];
  }
  if (error) {
    NSLog(@"[Speech] AVAudioSession setCategory error: %@", error.localizedDescription);
  }
}

- (NSDictionary *)getEventData:(AVSpeechUtterance *)utterance {
  NSString *utteranceId = utteranceIdMap[@(utterance.hash).stringValue];
  return @{
    @"id": utteranceId ?: @(utterance.hash).stringValue
  };
}

- (NSString *)generateAndMapUtteranceId:(AVSpeechUtterance *)utterance {
  NSUUID *uuid = [[NSUUID alloc] init];
  NSString *utteranceId = [uuid UUIDString];
  utteranceIdMap[@(utterance.hash).stringValue] = utteranceId;
  return utteranceId;
}

- (void)cleanupUtteranceId:(AVSpeechUtterance *)utterance {
  [utteranceIdMap removeObjectForKey:@(utterance.hash).stringValue];
}

- (NSDictionary *)getVoiceItem:(AVSpeechSynthesisVoice *)voice {
  return @{
    @"name": voice.name,
    @"language": voice.language,
    @"identifier": voice.identifier,
    @"quality": voice.quality == AVSpeechSynthesisVoiceQualityEnhanced ? @"Enhanced" : @"Default"
  };
}

- (NSDictionary *)getValidatedOptions:(VoiceOptions &)options {
  NSMutableDictionary *validatedOptions = [self.globalOptions mutableCopy];

  if (options.ducking()) {
    validatedOptions[@"ducking"] = @(options.ducking().value());
  }
  if (options.voice()) {
    validatedOptions[@"voice"] = options.voice();
  }
  if (options.language()) {
    validatedOptions[@"language"] = options.language();
  }
  if (options.silentMode()) {
    validatedOptions[@"silentMode"] = options.silentMode();
  }
  if (options.pitch()) {
    float pitch = MAX(0.5, MIN(2.0, options.pitch().value()));
    validatedOptions[@"pitch"] = @(pitch);
  }
  if (options.volume()) {
    float volume = MAX(0, MIN(1.0, options.volume().value()));
    validatedOptions[@"volume"] = @(volume);
  }
  if (options.rate()) {
    float rate = MAX(AVSpeechUtteranceMinimumSpeechRate,
                    MIN(AVSpeechUtteranceMaximumSpeechRate, options.rate().value()));
    validatedOptions[@"rate"] = @(rate);
  }
  return validatedOptions;
}

- (AVSpeechUtterance *)getUtterance:(NSString *)text withOptions:(NSDictionary *)options {
  AVSpeechUtterance *utterance = [[AVSpeechUtterance alloc] initWithString:text];

  if (options[@"voice"]) {
    AVSpeechSynthesisVoice *voice = [AVSpeechSynthesisVoice voiceWithIdentifier:options[@"voice"]];
    if (voice) {
      utterance.voice = voice;
    }
  } else if (options[@"language"]) {
    utterance.voice = [AVSpeechSynthesisVoice voiceWithLanguage:options[@"language"]];
  }
  utterance.rate = [options[@"rate"] floatValue];
  utterance.volume = [options[@"volume"] floatValue];
  utterance.pitchMultiplier = [options[@"pitch"] floatValue];

  return utterance;
}

- (void)initialize:(VoiceOptions &)options {
  NSMutableDictionary *newOptions = [NSMutableDictionary dictionaryWithDictionary:self.globalOptions];
  NSDictionary *validatedOptions = [self getValidatedOptions:options];
  [newOptions addEntriesFromDictionary:validatedOptions];
  self.globalOptions = newOptions;
}

- (void)reset {
  self.globalOptions = [defaultOptions copy];
}

- (void)getAvailableVoices:(NSString *)language
                  resolve:(RCTPromiseResolveBlock)resolve
                   reject:(RCTPromiseRejectBlock)reject
{
  NSMutableArray *voicesArray = [NSMutableArray new];
  NSArray *speechVoices = [AVSpeechSynthesisVoice speechVoices];
  
  if (language) {
    NSString *lowercaseLanguage = [language lowercaseString];
    
    for (AVSpeechSynthesisVoice *voice in speechVoices) {
      if ([[voice.language lowercaseString] hasPrefix:lowercaseLanguage]) {
        [voicesArray addObject:[self getVoiceItem:voice]];
      }
    }
  } else {
    for (AVSpeechSynthesisVoice *voice in speechVoices) {
      [voicesArray addObject:[self getVoiceItem:voice]];
    }
  }
  resolve(voicesArray);
}

- (void)openVoiceDataInstaller:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  resolve(nil);
}

- (void)getEngines:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  resolve(@[]);
}

- (void)setEngine:(NSString *)engineName
          resolve:(RCTPromiseResolveBlock)resolve
           reject:(RCTPromiseRejectBlock)reject {
  resolve(nil);
}

- (void)isSpeaking:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  resolve(@(self.synthesizer.isSpeaking));
}

- (void)stop:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  if (self.synthesizer.isSpeaking) {
    [self.synthesizer stopSpeakingAtBoundary:AVSpeechBoundaryImmediate];
  }
  resolve(nil);
}

- (void)pause:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  if (self.synthesizer.isSpeaking && !self.synthesizer.isPaused) {
    BOOL paused = [self.synthesizer pauseSpeakingAtBoundary:AVSpeechBoundaryImmediate];
    [self deactivateDuckingSession];
    resolve(@(paused));
  } else {
    resolve(@(false));
  }
}

- (void)resume:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  if (self.synthesizer.isPaused) {
    [self activateDuckingSession];
    BOOL resumed = [self.synthesizer continueSpeaking];
    resolve(@(resumed));
  } else {
    resolve(@(false));
  }
}

- (void)speak:(NSString *)text
    resolve:(RCTPromiseResolveBlock)resolve
    reject:(RCTPromiseRejectBlock)reject
{
  if (!text) {
    reject(@"speech_error", @"Text cannot be null", nil);
    return;
  }

  AVSpeechUtterance *utterance;
 
  @try {
    isDucking = [self.globalOptions[@"ducking"] boolValue];

    [self activateDuckingSession];
    [self configureSilentModeSession:self.globalOptions[@"silentMode"]];

    utterance = [self getUtterance:text withOptions:self.globalOptions];
    NSString *utteranceId = [self generateAndMapUtteranceId:utterance];
    [self.synthesizer speakUtterance:utterance];
    resolve(utteranceId);
  }
  @catch (NSException *exception) {
    [self deactivateDuckingSession];
    if (utterance) {
      [self emitOnError:[self getEventData:utterance]];
      [self cleanupUtteranceId:utterance];
    }
    reject(@"speech_error", exception.reason, nil);
  }
}

- (void)speakWithOptions:(NSString *)text
    options:(VoiceOptions &)options
    resolve:(RCTPromiseResolveBlock)resolve
    reject:(RCTPromiseRejectBlock)reject
{
  if (!text) {
    reject(@"speech_error", @"Text cannot be null", nil);
    return;
  }
  
  AVSpeechUtterance *utterance;

  @try {
    NSDictionary *validatedOptions = [self getValidatedOptions:options];
    isDucking = [validatedOptions[@"ducking"] boolValue];

    [self activateDuckingSession];
    [self configureSilentModeSession:validatedOptions[@"silentMode"]];
    
    utterance = [self getUtterance:text withOptions:validatedOptions];
    NSString *utteranceId = [self generateAndMapUtteranceId:utterance];
    [self.synthesizer speakUtterance:utterance];
    resolve(utteranceId);
  }
  @catch (NSException *exception) {
    [self deactivateDuckingSession];
    if (utterance) {
      [self emitOnError:[self getEventData:utterance]];
      [self cleanupUtteranceId:utterance];
    }
    reject(@"speech_error", exception.reason, nil);
  }
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didStartSpeechUtterance:(AVSpeechUtterance *)utterance {
  [self emitOnStart:[self getEventData:utterance]];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  willSpeakRangeOfSpeechString:(NSRange)characterRange utterance:(AVSpeechUtterance *)utterance {
  NSMutableDictionary *progressData = [[self getEventData:utterance] mutableCopy];
  progressData[@"length"] = @(characterRange.length);
  progressData[@"location"] = @(characterRange.location);
  [self emitOnProgress:progressData];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didFinishSpeechUtterance:(AVSpeechUtterance *)utterance {
  [self deactivateDuckingSession];
  [self emitOnFinish:[self getEventData:utterance]];
  [self cleanupUtteranceId:utterance];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didPauseSpeechUtterance:(nonnull AVSpeechUtterance *)utterance {
  [self emitOnPause:[self getEventData:utterance]];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didContinueSpeechUtterance:(nonnull AVSpeechUtterance *)utterance {
  [self emitOnResume:[self getEventData:utterance]];
}

- (void)speechSynthesizer:(AVSpeechSynthesizer *)synthesizer
  didCancelSpeechUtterance:(AVSpeechUtterance *)utterance {
  [self deactivateDuckingSession];
  [self emitOnStopped:[self getEventData:utterance]];
  [self cleanupUtteranceId:utterance];
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeSpeechSpecJSI>(params);
}

@end
