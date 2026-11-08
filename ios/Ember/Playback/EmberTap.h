#ifndef EmberTap_h
#define EmberTap_h

#include <MediaToolbox/MediaToolbox.h>

#pragma clang assume_nonnull begin

/// Settings shared between the app and the audio tap. The app writes them; the audio thread reads
/// them for every block of sound (and writes `silent` back).
typedef struct EmberFX {
    /// Equalizer gains in dB at 60 Hz, 230 Hz, 910 Hz, 3.6 kHz and 14 kHz.
    float gains[5];
    int eq;
    int boost;
    /// Watch for quiet stretches (trim silence).
    int detect;
    /// Bumped whenever the gains change, so the tap redesigns its filters.
    int version;
    /// 1 while the audio has been quiet for a moment.
    int silent;
} EmberFX;

EmberFX *EmberFXCreate(void);

/// A tap for one audio track (an AVPlayerItem's audio mix). Nil if the system refuses.
MTAudioProcessingTapRef _Nullable EmberTapCreate(EmberFX *fx) CF_RETURNS_RETAINED;

#pragma clang assume_nonnull end

#endif
