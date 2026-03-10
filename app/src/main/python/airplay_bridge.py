"""Bridge between Android AudioCapture (Kotlin) and pyatv AirPlay streaming.

This module runs pyatv in a background thread with its own asyncio event loop.
Kotlin calls into this module via Chaquopy to:
  1. connect(host, port) - connect to an AirPlay 2 device
  2. send_audio(pcm_bytes) - send raw PCM audio frames
  3. set_volume(level) - set volume (0.0 to 1.0)
  4. disconnect() - stop streaming and disconnect

Audio is passed as a live WAV stream (header + continuous PCM from queue).
pyatv/miniaudio decodes and streams it to the AirPlay speaker.
"""

import ctypes
import os

# Pre-load libffi.so with RTLD_GLOBAL before _cffi_backend.so is imported.
# CenturyPlayApp.kt copies libffi.so to the Chaquopy requirements directory,
# but the Android linker won't auto-find it there. Loading it explicitly with
# RTLD_GLOBAL makes its symbols (like ffi_type_float) available to _cffi_backend.so.
_libffi_path = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "requirements", "libffi.so"
)
if not os.path.exists(_libffi_path):
    # Try absolute Chaquopy path
    _libffi_path = "/data/data/com.airplay.streamer/files/chaquopy/AssetFinder/requirements/libffi.so"
if os.path.exists(_libffi_path):
    ctypes.CDLL(_libffi_path, mode=ctypes.RTLD_GLOBAL)

import asyncio
import io
import logging
import queue
import struct
import threading
import traceback
import time
from typing import Optional

logging.basicConfig(level=logging.DEBUG)
_LOGGER = logging.getLogger("airplay_bridge")

# Global state
_loop: Optional[asyncio.AbstractEventLoop] = None
_thread: Optional[threading.Thread] = None
_atv = None  # pyatv Apple TV connection
_audio_queue: queue.Queue = queue.Queue(maxsize=200)
_connected = False
_streaming = False
_stop_event = threading.Event()
_error: Optional[str] = None

SAMPLE_RATE = 44100
CHANNELS = 2
BITS_PER_SAMPLE = 16
BYTES_PER_SAMPLE = BITS_PER_SAMPLE // 8


class LiveWavStream(io.RawIOBase):
    """A never-ending WAV stream that reads PCM data from a queue.

    Produces a WAV header followed by continuous PCM data.
    pyatv/miniaudio will decode this as a WAV file and stream it.
    """

    def __init__(self, audio_queue: queue.Queue, stop_event: threading.Event):
        super().__init__()
        self._queue = audio_queue
        self._stop = stop_event
        self._header = self._make_wav_header()
        self._header_pos = 0
        self._leftover = b''  # unused bytes from previous chunk

    def _make_wav_header(self) -> bytes:
        """Create a WAV header for a very long PCM stream."""
        data_size = 0x7FFFFFFF
        byte_rate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
        block_align = CHANNELS * BYTES_PER_SAMPLE

        header = struct.pack(
            '<4sI4s4sIHHIIHH4sI',
            b'RIFF',
            data_size + 36,
            b'WAVE',
            b'fmt ',
            16,
            1,                   # PCM format
            CHANNELS,
            SAMPLE_RATE,
            byte_rate,
            block_align,
            BITS_PER_SAMPLE,
            b'data',
            data_size,
        )
        return header

    def readable(self) -> bool:
        return True

    def seekable(self) -> bool:
        return False

    def read(self, size=-1) -> bytes:
        """Read audio data, returning pure Python bytes.

        All logic lives here to avoid memoryview issues with Chaquopy's
        Java buffer wrappers (which don't support slice assignment).
        """
        if size == -1:
            size = 4096

        result = bytearray()

        # First, serve the WAV header
        if self._header_pos < len(self._header):
            remaining = len(self._header) - self._header_pos
            to_copy = min(remaining, size)
            result.extend(self._header[self._header_pos:self._header_pos + to_copy])
            self._header_pos += to_copy
            if len(result) >= size:
                return bytes(result)

        # Drain leftover from previous read first
        if self._leftover:
            to_copy = min(len(self._leftover), size - len(result))
            result.extend(self._leftover[:to_copy])
            self._leftover = self._leftover[to_copy:]
            if len(result) >= size:
                return bytes(result)

        # Then serve PCM data from queue
        empty_count = 0
        while len(result) < size and not self._stop.is_set():
            try:
                chunk = self._queue.get(timeout=0.05)
                empty_count = 0
                needed = size - len(result)
                if len(chunk) <= needed:
                    result.extend(chunk)
                else:
                    result.extend(chunk[:needed])
                    self._leftover = chunk[needed:]
            except queue.Empty:
                if self._stop.is_set():
                    break
                empty_count += 1
                if len(result) > 0 and empty_count >= 2:
                    return bytes(result)
                if empty_count >= 6:
                    # ~300ms with no data - return silence to keep stream alive
                    silence_size = min(size - len(result), 1408)
                    result.extend(bytes(silence_size))
                    return bytes(result)

        return bytes(result) if result else b''

    def readinto(self, b) -> int:
        """Thin wrapper over read() for BufferedReader compatibility."""
        data = self.read(len(b))
        n = len(data)
        if n > 0:
            try:
                b[:n] = data
            except (ValueError, SystemError):
                # Chaquopy Java buffer fallback: write byte by byte
                for i in range(n):
                    b[i] = data[i]
        return n


def _log(msg):
    _LOGGER.info(msg)
    print(f"[AirPlayBridge] {msg}")


def _run_loop():
    global _loop
    _loop = asyncio.new_event_loop()
    asyncio.set_event_loop(_loop)
    _loop.run_forever()


def _ensure_loop():
    global _thread, _loop
    if _thread is None or not _thread.is_alive():
        _thread = threading.Thread(target=_run_loop, daemon=True)
        _thread.start()
        while _loop is None:
            time.sleep(0.01)


def _run_async(coro, timeout=30):
    _ensure_loop()
    future = asyncio.run_coroutine_threadsafe(coro, _loop)
    return future.result(timeout=timeout)


async def _do_connect(host: str, port: int):
    global _atv, _connected, _error

    import pyatv

    _error = None

    try:
        _log(f"Scanning for AirPlay device at {host}...")

        loop = asyncio.get_event_loop()
        devices = await pyatv.scan(loop, hosts=[host], timeout=10)

        if not devices:
            _error = f"No AirPlay device found at {host}"
            _log(_error)
            return False

        config = devices[0]
        _log(f"Found device: {config.name}")

        _atv = await pyatv.connect(config, loop)
        _connected = True
        _log(f"Connected to {config.name}")
        return True

    except Exception as e:
        _error = f"Connection failed: {e}"
        _log(_error)
        traceback.print_exc()
        return False


async def _do_stream():
    global _streaming, _error

    try:
        if not _connected or _atv is None:
            _error = "Not connected"
            return

        _streaming = True
        _stop_event.clear()

        wav_stream = LiveWavStream(_audio_queue, _stop_event)
        buffered = io.BufferedReader(wav_stream, buffer_size=8192)

        _log("Starting AirPlay stream via pyatv...")
        stream = _atv.stream
        await stream.stream_file(buffered)
        _log("Stream ended normally")

    except asyncio.CancelledError:
        _log("Stream cancelled")
    except Exception as e:
        _error = f"Streaming error: {e}"
        _log(_error)
        traceback.print_exc()
    finally:
        _streaming = False


async def _do_disconnect():
    global _atv, _connected, _streaming

    _stop_event.set()
    _streaming = False

    if _atv is not None:
        try:
            _atv.close()
        except Exception as e:
            _log(f"Error closing connection: {e}")
        _atv = None

    _connected = False
    _log("Disconnected")


# ─── Public API (called from Kotlin via Chaquopy) ───────────────────

def connect(host: str, port: int) -> bool:
    """Connect to an AirPlay device. Returns True on success."""
    _log(f"connect({host}, {port})")
    try:
        return _run_async(_do_connect(host, port))
    except Exception as e:
        _log(f"Connect error: {e}")
        traceback.print_exc()
        return False


def start_stream():
    """Start streaming audio. Call send_audio() to feed PCM data."""
    _log("start_stream()")
    _ensure_loop()
    asyncio.run_coroutine_threadsafe(_do_stream(), _loop)


def send_audio(pcm_data: bytes):
    """Send raw PCM audio data (16-bit LE stereo, 44100Hz)."""
    try:
        _audio_queue.put_nowait(pcm_data)
    except queue.Full:
        try:
            _audio_queue.get_nowait()
        except queue.Empty:
            pass
        try:
            _audio_queue.put_nowait(pcm_data)
        except queue.Full:
            pass


def set_volume(volume: float):
    """Set volume (0.0 to 1.0)."""
    if _atv is not None and _connected:
        try:
            _run_async(_atv.audio.set_volume(volume * 100.0))
        except Exception as e:
            _log(f"Volume error: {e}")


def disconnect():
    """Disconnect from the AirPlay device."""
    _log("disconnect()")
    _stop_event.set()

    while not _audio_queue.empty():
        try:
            _audio_queue.get_nowait()
        except queue.Empty:
            break

    try:
        _run_async(_do_disconnect(), timeout=10)
    except Exception as e:
        _log(f"Disconnect error: {e}")


def is_connected() -> bool:
    return _connected


def is_streaming() -> bool:
    return _streaming


def get_error() -> str:
    return _error or ""
