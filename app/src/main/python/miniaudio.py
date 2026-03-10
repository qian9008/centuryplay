"""Minimal pure-Python miniaudio mock for pyatv on Android.

Provides just enough API for pyatv's RAOP streaming to work with WAV/PCM data.
The real miniaudio uses cffi which requires libffi.so (unavailable on Android).
Since we only stream raw PCM audio, we don't need actual codec support.
"""

import abc
import array
import io
import struct
import sys
from enum import Enum
from typing import Generator, Optional, Union


class FileFormat(Enum):
    UNKNOWN = 0
    WAV = 1
    FLAC = 2
    MP3 = 3
    VORBIS = 4


class SampleFormat(Enum):
    UNKNOWN = 0
    UNSIGNED8 = 1
    SIGNED16 = 2
    SIGNED24 = 3
    SIGNED32 = 4
    FLOAT32 = 5


class DitherMode(Enum):
    NONE = 0
    RECTANGLE = 1
    TRIANGLE = 2


class SeekOrigin(Enum):
    START = 0
    CURRENT = 1


class MiniaudioError(Exception):
    pass


class DecodeError(MiniaudioError):
    pass


def width_from_format(fmt: SampleFormat) -> int:
    return {
        SampleFormat.UNSIGNED8: 1,
        SampleFormat.SIGNED16: 2,
        SampleFormat.SIGNED24: 3,
        SampleFormat.SIGNED32: 4,
        SampleFormat.FLOAT32: 4,
    }.get(fmt, 2)


class SoundFileInfo:
    def __init__(self, name="", nchannels=2, sample_rate=44100,
                 sample_format=SampleFormat.SIGNED16,
                 duration=0.0, num_frames=0):
        self.name = name
        self.nchannels = nchannels
        self.sample_rate = sample_rate
        self.sample_format = sample_format
        self.sample_width = width_from_format(sample_format)
        self.duration = duration
        self.num_frames = num_frames
        self.file_format = FileFormat.WAV


class DecodedSoundFile(SoundFileInfo):
    def __init__(self, name, nchannels, sample_rate, sample_format, samples):
        super().__init__(name, nchannels, sample_rate, sample_format)
        self.samples = samples
        self.num_frames = len(samples) // nchannels


class StreamableSource(abc.ABC):
    """Base class for streams of audio data bytes."""

    @abc.abstractmethod
    def read(self, num_bytes: int) -> Union[bytes, memoryview]:
        pass

    def seek(self, offset: int, origin: SeekOrigin) -> bool:
        return False

    def close(self) -> None:
        pass

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()


def _int2sf(sample_size: int) -> SampleFormat:
    return {1: SampleFormat.UNSIGNED8, 2: SampleFormat.SIGNED16,
            3: SampleFormat.SIGNED24, 4: SampleFormat.SIGNED32}.get(
        sample_size, SampleFormat.SIGNED16)


def _parse_wav_header(data: bytes):
    """Parse a WAV header and return (nchannels, sample_rate, bits_per_sample, data_offset)."""
    if len(data) < 44:
        return 2, 44100, 16, 44  # defaults

    # Standard WAV header
    if data[:4] != b'RIFF' or data[8:12] != b'WAVE':
        return 2, 44100, 16, 0  # Not WAV, treat all as data

    # Parse fmt chunk
    fmt_offset = 12
    nchannels = 2
    sample_rate = 44100
    bits_per_sample = 16
    data_offset = 44

    pos = 12
    while pos < len(data) - 8:
        chunk_id = data[pos:pos+4]
        chunk_size = struct.unpack_from('<I', data, pos + 4)[0]
        if chunk_id == b'fmt ':
            if chunk_size >= 16:
                nchannels = struct.unpack_from('<H', data, pos + 10)[0]
                sample_rate = struct.unpack_from('<I', data, pos + 12)[0]
                bits_per_sample = struct.unpack_from('<H', data, pos + 22)[0]
        elif chunk_id == b'data':
            data_offset = pos + 8
            break
        pos += 8 + chunk_size
        if chunk_size % 2:  # WAV chunks are 2-byte aligned
            pos += 1

    return nchannels, sample_rate, bits_per_sample, data_offset


def stream_any(source: StreamableSource, source_format: FileFormat = FileFormat.UNKNOWN,
               output_format: SampleFormat = SampleFormat.SIGNED16, nchannels: int = 2,
               sample_rate: int = 44100, frames_to_read: int = 1024,
               dither: DitherMode = DitherMode.NONE, seek_frame: int = 0) -> Generator[array.array, int, None]:
    """Decode and stream audio data. For WAV/PCM this is essentially a pass-through."""

    # Read header to find data start
    header_data = source.read(256)
    if not header_data:
        return

    src_channels, src_rate, src_bits, data_offset = _parse_wav_header(header_data)

    # Any data after the header in our initial read
    leftover = header_data[data_offset:]

    sample_width = width_from_format(output_format)
    frame_size = nchannels * sample_width
    chunk_size = frames_to_read * frame_size

    # Initialize generator
    requested = yield array.array('h', [])

    buffer = bytearray(leftover)

    while True:
        actual_frames = requested if requested else frames_to_read
        needed = actual_frames * frame_size

        # Read more data if needed
        while len(buffer) < needed:
            data = source.read(max(needed - len(buffer), chunk_size))
            if not data:
                break
            buffer.extend(data)

        if not buffer:
            return

        # Take the frames we need
        to_yield = bytes(buffer[:needed])
        del buffer[:needed]

        # Convert to array
        if output_format == SampleFormat.SIGNED16:
            fmt_char = 'h'
        elif output_format == SampleFormat.SIGNED32:
            fmt_char = 'i'
        elif output_format == SampleFormat.FLOAT32:
            fmt_char = 'f'
        else:
            fmt_char = 'h'

        # Pad if needed
        elem_size = array.array(fmt_char, [0]).itemsize
        if len(to_yield) % elem_size:
            to_yield += bytes(elem_size - (len(to_yield) % elem_size))

        samples = array.array(fmt_char, to_yield)
        requested = yield samples


def _make_wav_header(sample_rate, nchannels, bits_per_sample, data_size=0x7FFFFFFF):
    """Create a minimal WAV header."""
    byte_rate = sample_rate * nchannels * (bits_per_sample // 8)
    block_align = nchannels * (bits_per_sample // 8)
    return struct.pack(
        '<4sI4s4sIHHIIHH4sI',
        b'RIFF', data_size + 36, b'WAVE',
        b'fmt ', 16, 1, nchannels, sample_rate, byte_rate, block_align, bits_per_sample,
        b'data', data_size,
    )


class WavFileReadStream(io.RawIOBase):
    """An IO stream that reads as a .wav file from a PCM sample generator."""

    def __init__(self, pcm_sample_gen, sample_rate: int, nchannels: int,
                 output_format: SampleFormat, max_frames: int = 0):
        self.sample_gen = pcm_sample_gen
        self.sample_rate = sample_rate
        self.nchannels = nchannels
        self.format = output_format
        self.max_frames = max_frames
        self.sample_width = width_from_format(output_format)
        self.max_bytes = (max_frames * nchannels * self.sample_width) or sys.maxsize
        self.bytes_done = 0
        # Create WAV header in pure Python
        self.buffered = _make_wav_header(
            sample_rate, nchannels, self.sample_width * 8,
            max_frames * nchannels * self.sample_width if max_frames > 0 else 0x7FFFFFFF
        )

    def read(self, amount: int = sys.maxsize) -> Optional[bytes]:
        if self.bytes_done >= self.max_bytes or not self.sample_gen:
            return b""
        while len(self.buffered) < amount:
            try:
                samples = next(self.sample_gen)
            except StopIteration:
                self.bytes_done = sys.maxsize
                break
            else:
                if isinstance(samples, array.array):
                    self.buffered += samples.tobytes()
                elif isinstance(samples, bytes):
                    self.buffered += samples
                else:
                    self.buffered += bytes(samples)
        result = self.buffered[:amount]
        self.buffered = self.buffered[amount:]
        self.bytes_done += len(result)
        return result

    def close(self) -> None:
        pass


def get_file_info(filename: str) -> SoundFileInfo:
    """Get basic info about an audio file."""
    try:
        with open(filename, 'rb') as f:
            header = f.read(256)
            nchannels, sample_rate, bits, data_offset = _parse_wav_header(header)
            file_size = f.seek(0, 2)
            data_size = file_size - data_offset
            frame_size = nchannels * (bits // 8)
            num_frames = data_size // frame_size if frame_size > 0 else 0
            duration = num_frames / sample_rate if sample_rate > 0 else 0.0
            return SoundFileInfo(filename, nchannels, sample_rate,
                                 _int2sf(bits // 8), duration, num_frames)
    except Exception:
        return SoundFileInfo(filename)


def decode_file(filename: str, output_format: SampleFormat = SampleFormat.SIGNED16,
                nchannels: int = 0, sample_rate: int = 0,
                dither: DitherMode = DitherMode.NONE) -> DecodedSoundFile:
    """Decode a complete audio file (only supports WAV for this mock)."""
    with open(filename, 'rb') as f:
        data = f.read()

    ch, sr, bits, offset = _parse_wav_header(data)
    if nchannels == 0:
        nchannels = ch
    if sample_rate == 0:
        sample_rate = sr

    pcm_data = data[offset:]
    samples = array.array('h', pcm_data)
    return DecodedSoundFile(filename, nchannels, sample_rate, output_format, samples)
