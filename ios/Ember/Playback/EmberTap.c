// The audio tap: a five-band equalizer, a voice boost (more level with a limiter so it never
// clips) and a quiet detector for trim silence. Runs on the audio thread, so no allocations here.
#include "EmberTap.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

#define BANDS 5
#define MAXCH 8

static const double kFreq[BANDS] = {60, 230, 910, 3600, 14000};

typedef struct {
    EmberFX *fx;
    double rate;
    int channels;
    int usable;
    int interleaved;
    int version;
    int eqWasOn;
    double b0[BANDS], b1[BANDS], b2[BANDS], a1[BANDS], a2[BANDS];
    double z1[MAXCH][BANDS], z2[MAXCH][BANDS];
    double gain;
    double quiet;
} TapState;

EmberFX *EmberFXCreate(void) {
    return (EmberFX *)calloc(1, sizeof(EmberFX));
}

static void tapInit(MTAudioProcessingTapRef tap, void *clientInfo, void **tapStorageOut) {
    TapState *s = (TapState *)calloc(1, sizeof(TapState));
    s->fx = (EmberFX *)clientInfo;
    s->gain = 1;
    s->version = -1;
    *tapStorageOut = s;
}

static void tapFinalize(MTAudioProcessingTapRef tap) {
    free(MTAudioProcessingTapGetStorage(tap));
}

static void tapPrepare(MTAudioProcessingTapRef tap, CMItemCount maxFrames, const AudioStreamBasicDescription *f) {
    TapState *s = (TapState *)MTAudioProcessingTapGetStorage(tap);
    s->rate = f->mSampleRate > 0 ? f->mSampleRate : 44100;
    s->channels = (int)f->mChannelsPerFrame;
    s->interleaved = (f->mFormatFlags & kAudioFormatFlagIsNonInterleaved) == 0;
    s->usable = f->mFormatID == kAudioFormatLinearPCM && (f->mFormatFlags & kAudioFormatFlagIsFloat) != 0 &&
                f->mBitsPerChannel == 32 && s->channels >= 1 && s->channels <= MAXCH;
    s->version = -1;
    s->gain = 1;
    s->quiet = 0;
    memset(s->z1, 0, sizeof(s->z1));
    memset(s->z2, 0, sizeof(s->z2));
}

static void tapUnprepare(MTAudioProcessingTapRef tap) {}

/// Peaking filters (the usual "audio EQ cookbook" ones), one per band.
static void design(TapState *s) {
    for (int b = 0; b < BANDS; b++) {
        double g = s->fx->gains[b];
        double f = kFreq[b];
        if (f > s->rate * 0.45 || fabs(g) < 0.05) {
            s->b0[b] = 1;
            s->b1[b] = s->b2[b] = s->a1[b] = s->a2[b] = 0;
            continue;
        }
        double A = pow(10.0, g / 40.0);
        double w = 2.0 * M_PI * f / s->rate;
        double alpha = sin(w) / 2.0;  // Q = 1
        double cw = cos(w);
        double a0 = 1 + alpha / A;
        s->b0[b] = (1 + alpha * A) / a0;
        s->b1[b] = (-2 * cw) / a0;
        s->b2[b] = (1 - alpha * A) / a0;
        s->a1[b] = (-2 * cw) / a0;
        s->a2[b] = (1 - alpha / A) / a0;
    }
}

static void tapProcess(MTAudioProcessingTapRef tap, CMItemCount frames, MTAudioProcessingTapFlags flags,
                       AudioBufferList *bl, CMItemCount *framesOut, MTAudioProcessingTapFlags *flagsOut) {
    if (MTAudioProcessingTapGetSourceAudio(tap, frames, bl, flagsOut, NULL, framesOut) != noErr) return;
    TapState *s = (TapState *)MTAudioProcessingTapGetStorage(tap);
    EmberFX *fx = s->fx;
    if (!s->usable) return;
    long n = (long)*framesOut;
    int chs = s->channels;
    float *p[MAXCH];
    long stride;
    if (s->interleaved) {
        if (bl->mNumberBuffers < 1 || bl->mBuffers[0].mData == NULL) return;
        for (int c = 0; c < chs; c++) p[c] = (float *)bl->mBuffers[0].mData + c;
        stride = chs;
    } else {
        if ((int)bl->mNumberBuffers < chs) return;
        for (int c = 0; c < chs; c++) {
            if (bl->mBuffers[c].mData == NULL) return;
            p[c] = (float *)bl->mBuffers[c].mData;
        }
        stride = 1;
    }

    // Trim silence: is this stretch quiet (about -44 dBFS) for long enough?
    if (fx->detect) {
        double sum = 0;
        for (int c = 0; c < chs; c++)
            for (long i = 0; i < n; i++) {
                double v = p[c][i * stride];
                sum += v * v;
            }
        double rms = n > 0 ? sqrt(sum / (double)(n * chs)) : 0;
        if (rms < 0.006) s->quiet += (double)n; else s->quiet = 0;
        fx->silent = s->quiet > s->rate * 0.3 ? 1 : 0;
    } else {
        s->quiet = 0;
        fx->silent = 0;
    }

    int eq = fx->eq;
    if (eq) {
        if (s->version != fx->version || !s->eqWasOn) {
            if (!s->eqWasOn) {
                memset(s->z1, 0, sizeof(s->z1));
                memset(s->z2, 0, sizeof(s->z2));
            }
            design(s);
            s->version = fx->version;
        }
        for (int c = 0; c < chs; c++) {
            float *x = p[c];
            for (int b = 0; b < BANDS; b++) {
                if (s->b0[b] == 1 && s->a1[b] == 0) continue;
                double b0 = s->b0[b], b1 = s->b1[b], b2 = s->b2[b], a1 = s->a1[b], a2 = s->a2[b];
                double z1 = s->z1[c][b], z2 = s->z2[c][b];
                for (long i = 0; i < n; i++) {
                    double in = x[i * stride];
                    double y = b0 * in + z1;
                    z1 = b1 * in - a1 * y + z2;
                    z2 = b2 * in - a2 * y;
                    x[i * stride] = (float)y;
                }
                s->z1[c][b] = z1;
                s->z2[c][b] = z2;
            }
        }
    }
    s->eqWasOn = eq;

    // Voice boost (+7 dB) and a limiter, which also catches anything the equalizer pushed too loud.
    if (fx->boost || eq) {
        double pre = fx->boost ? 2.24 : 1.0;
        const double ceiling = 0.92;
        double release = 1.0 - exp(-1.0 / (0.15 * s->rate));
        double g = s->gain;
        for (long i = 0; i < n; i++) {
            double peak = 0;
            for (int c = 0; c < chs; c++) {
                double v = fabs(p[c][i * stride] * pre);
                if (v > peak) peak = v;
            }
            double target = peak > ceiling ? ceiling / peak : 1.0;
            if (target < g) g = target; else g += (target - g) * release;
            double k = pre * g;
            for (int c = 0; c < chs; c++) p[c][i * stride] = (float)(p[c][i * stride] * k);
        }
        s->gain = g;
    } else {
        s->gain = 1;
    }
}

MTAudioProcessingTapRef EmberTapCreate(EmberFX *fx) {
    MTAudioProcessingTapCallbacks cb;
    memset(&cb, 0, sizeof(cb));
    cb.version = kMTAudioProcessingTapCallbacksVersion_0;
    cb.clientInfo = fx;
    cb.init = tapInit;
    cb.finalize = tapFinalize;
    cb.prepare = tapPrepare;
    cb.unprepare = tapUnprepare;
    cb.process = tapProcess;
    MTAudioProcessingTapRef tap = NULL;
    OSStatus err = MTAudioProcessingTapCreate(kCFAllocatorDefault, &cb, kMTAudioProcessingTapCreationFlag_PreEffects, &tap);
    return err == noErr ? tap : NULL;
}
