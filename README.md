# centuryplay

stream audio from your android device to airplay speakers.

![android](https://img.shields.io/badge/android-10%2B-green)
![airplay](https://img.shields.io/badge/airplay-v1%20%2B%20v2-blue)
![license](https://img.shields.io/badge/license-mit-yellow)

<div align="center">
  <img src="images/main.png" width="220" />
  <img src="images/streaming.png" width="220" />
  <img src="images/settings.png" width="220" />
</div>

<div align="center">
  <p><i>main interface • active streaming • settings</i></p>
</div>

## the story

started as a way to breathe new life into a bang & olufsen beosound century - a beautiful 1990s hi-fi system with stunning sound quality but no wireless capabilities. by adding a raspberry pi running [shairport-sync](https://github.com/mikebrady/shairport-sync), the century can now receive airplay streams.

centuryplay completes the chain by letting android devices stream system audio to shairport-sync (or any airplay receiver), effectively turning a vintage b&o system into a modern wireless speaker.

## features

- system audio capture: stream any audio playing on your device.
- auto discovery: automatically find airplay devices via mdns/bonjour.
- encrypted streaming: aes-128-cbc encryption with rsa key exchange.
- synchronized playback: proper rtp timing and sync packets.
- volume control: adjust volume on the receiver.
- music player integration: now playing metadata and controls.

## lossless & hi-res audio

### does it work with apple music lossless?

yes. when playing apple music (or any other source) on android, this app captures the audio and streams it over airplay. however, there are caveats regarding android's audio pipeline.

### android audio resampling

| source | android behavior | stream output |
|--------|------------------|---------------|
| 44.1 khz (cd quality) | no resampling | bit-perfect |
| 48 khz | native | bit-perfect |
| 96 khz hi-res | resampled to 48 khz | downsampled |
| 192 khz hi-res | resampled to 48 khz | downsampled |

key points:
- android's mixer typically runs at 48 khz.
- hi-res content is downsampled by android before reaching this app.
- airplay 1 only supports 44.1 khz, so additional resampling may occur.
- for true bit-perfect playback, exclusive usb audio mode would be required.

bottom line: excellent quality, but not bit-perfect hi-res. cd quality (16-bit/44.1khz) is handled cleanly.

## supported protocols

| protocol | status | notes |
|----------|--------|-------|
| airplay 1 (raop) | working | l16 pcm audio, encrypted |
| airplay 2 | working | via pyatv, homekit transient pairing |

## requirements

- android 10 (api 29) or higher.
- airplay-compatible receiver (e.g. shairport-sync, apple tv, homepod, airport express).

## installation

### from source

1. clone the repository:
   ```bash
   git clone https://github.com/g8row/centuryplay.git
   cd centuryplay
   ```

2. build with gradle:
   ```bash
   ./gradlew assembleDebug
   ```

3. install the apk:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### from release

download the latest apk from the [releases](https://github.com/g8row/centuryplay/releases) page.

### building libffi for android (optional)

the pre-compiled `libffi.so` files for arm64-v8a and x86_64 are included in the repo under `app/src/main/jniLibs/`. you only need to rebuild them if you want to target a different android abi or update the library.

**why libffi?** chaquopy's cffi package (`_cffi_backend.so`) dynamically links `libffi.so`, which android doesn't ship. the library must be cross-compiled from source.

**why version 3.3 specifically?** chaquopy's cffi expects symbol version `LIBFFI_BASE_7.0`. libffi 3.4+ exports `LIBFFI_BASE_8.0` instead, causing a runtime symbol lookup failure.

prerequisites:
- android ndk (r25+, install via android studio sdk manager)
- autoconf, automake, libtool, make (`brew install autoconf automake libtool` on macos)

```bash
./scripts/build-libffi.sh
# or specify ndk path explicitly:
./scripts/build-libffi.sh /path/to/android-ndk
```

this produces:
```
app/src/main/jniLibs/arm64-v8a/libffi.so
app/src/main/jniLibs/x86_64/libffi.so
```

## usage

1. grant permissions: requires audio recording permission for capture.
2. start media: play audio/video on your device.
3. select device: tap an airplay device from the list.
4. allow capture: approve the screen/audio capture prompt.
5. stream: audio will play through your airplay speaker.

## how it works

uses android's `audioplaybackcapture` api to capture system audio, then streams it to airplay receivers using raop (remote audio output protocol).

```
┌─────────────┐     ┌──────────────┐     ┌────────────────┐
│   android   │────▶│  airplay     │────▶│   airplay      │
│   device    │     │  streamer    │     │   receiver     │
│  (audio)    │ pcm │  (this app)  │ rtp │  (speaker)     │
└─────────────┘     └──────────────┘     └────────────────┘
```

### technical details

- audio format: l16/44100/2 (16-bit pcm, 44.1khz, stereo).
- transport: rtp over udp.
- control: rtsp over tcp (port 5000).
- encryption: aes-128-cbc with rsa-oaep key exchange.
- timing: ntp-style timestamps with sync packets.

see [docs/airplay_protocol.md](docs/AIRPLAY_PROTOCOL.md) for detailed protocol documentation.

## tested receivers

| receiver | protocol | status | notes |
|----------|----------|--------|-------|
| shairport-sync v4.x | airplay 1 | working | recommended |
| shairport-sync v3.x | airplay 1 | working | |
| airport express | airplay 1 | working | |
| apple tv (gen 2-3) | airplay 1 | working | |
| apple tv 4k | airplay 2 | working | via pyatv |
| homepod / mini | airplay 2 | working | via pyatv |
| airscreen (android)| airplay 1 | issues | compatibility variations |

## limitations

- drm content: some apps block capture (netflix etc).
- latency: inherent ~2s buffer latency.

## changelog

### v1.0 (january 2026)
- music player integration: real-time metadata (title, artist, art) and controls.
- ui polish: minimal aesthetics, lowercase typography, neutral status indicators.
- settings: keep screen on, auto-connect, and layout fixes.
- technical: project configuration updated for stable release.

### v0.2 (january 2026)
- wavy volume slider.
- material 3 theming.
- connection monitoring.
- crash fixes.

### v0.1 (december 2025)
- initial release.
- airplay 1 (raop) support.
- mdns device discovery.
- encrypted audio streaming.

## development

see [development.md](DEVELOPMENT.md) for notes.

### tech stack

- language: kotlin + python (via chaquopy)
- ui: android views (viewbinding)
- architecture: mvvm + stateflow
- concurrency: coroutines + flow
- airplay 1: raw sockets, jmdns, bouncycastle
- airplay 2: pyatv 0.17.0 (embedded cpython 3.13 via chaquopy)

## contributing

contributions welcome. submit a pull request.

## license

mit license. see [license](LICENSE) file.

## third-party licenses

this project uses the following open-source libraries:

| library | license | usage |
|---------|---------|-------|
| [pyatv](https://github.com/postlund/pyatv) | MIT | airplay 2 protocol |
| [chaquopy](https://chaquo.com/chaquopy/) | MIT | python runtime for android |
| [libffi](https://github.com/libffi/libffi) | MIT | foreign function interface (cross-compiled for android) |
| [cffi](https://cffi.readthedocs.io/) | MIT | c foreign function interface for python |
| [cryptography](https://github.com/pyca/cryptography) | Apache 2.0 / BSD-3 | tls and encryption |
| [zeroconf](https://github.com/jstasiak/python-zeroconf) | LGPL v2.1 | mdns service discovery |
| [protobuf](https://github.com/protocolbuffers/protobuf) | BSD-3 | protocol buffer serialization |
| [aiohttp](https://github.com/aio-libs/aiohttp) | Apache 2.0 | async http client |
| [jmDNS](https://github.com/jmdns/jmdns) | Apache 2.0 | java mdns discovery |
| [bouncycastle](https://www.bouncycastle.org/) | MIT | cryptography (airplay 1) |
| [AndroidX](https://developer.android.com/jetpack/androidx) | Apache 2.0 | android support libraries |

full license texts are included with their respective packages.

## acknowledgments

- [shairport-sync](https://github.com/mikebrady/shairport-sync)
- [pyatv](https://github.com/postlund/pyatv) - the airplay 2 implementation that made this possible
- [unofficial airplay protocol spec](https://nto.github.io/AirPlay.html)

---

disclaimer: airplay is a trademark of apple inc. this project is not affiliated with apple inc.
