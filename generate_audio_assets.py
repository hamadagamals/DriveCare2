"""
Generate high quality concise natural Arabic human voice audio clips and
harmonic acoustic tones for DriveCare & RoadGuardian.
Places all generated audio files into android-native/app/src/main/res/raw/
"""

import os
import sys
import asyncio
import math
import wave
import struct

ROOT = os.path.dirname(os.path.abspath(__file__))
RAW_DIR = os.path.join(ROOT, 'android-native', 'app', 'src', 'main', 'res', 'raw')
os.makedirs(RAW_DIR, exist_ok=True)

import edge_tts

try:
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass

# Concise, non-annoying natural phrases
VOICE_PROMPTS = [
    ("bump_100m_male.mp3", "ar-EG-ShakirNeural", "مطب بعد مائة متر"),
    ("bump_100m_female.mp3", "ar-EG-SalmaNeural", "مطب صناعي بعد 100 متر"),
    ("bump_25m_male.mp3", "ar-EG-ShakirNeural", "مطب الآن"),
    ("bump_25m_female.mp3", "ar-EG-SalmaNeural", "مطب"),
    ("voice_engine_heat.mp3", "ar-EG-ShakirNeural", "حرارة المحرك مرتفعة"),
    ("voice_dtc_error.mp3", "ar-EG-ShakirNeural", "عطل في المحرك"),
    ("voice_battery_low.mp3", "ar-EG-SalmaNeural", "شحن البطارية منخفض"),
    ("voice_oil_maint.mp3", "ar-EG-ShakirNeural", "موعد غيار الزيت"),
]


async def generate_voice_file(filename, voice, text):
    out_path = os.path.join(RAW_DIR, filename)
    print(f"Generating concise voice [{voice}] -> {filename} ('{text}') ...")
    communicate = edge_tts.Communicate(text, voice)
    await communicate.save(out_path)
    print(f"  Saved {filename} ({os.path.getsize(out_path)} bytes)")


def generate_harmonic_wave(filename, frequencies, durations, sample_rate=44100):
    """Generates a soft, clean harmonic acoustic wave file."""
    out_path = os.path.join(RAW_DIR, filename)
    samples = []
    
    for freq, duration_sec in zip(frequencies, durations):
        num_samples = int(sample_rate * duration_sec)
        for i in range(num_samples):
            t = float(i) / sample_rate
            env = 1.0
            attack = 0.015
            decay = 0.04
            if t < attack:
                env = t / attack
            elif t > duration_sec - decay:
                env = max(0.0, (duration_sec - t) / decay)
            
            val = 0.75 * math.sin(2.0 * math.pi * freq * t) + 0.25 * math.sin(4.0 * math.pi * freq * t)
            val = val * env * 0.75
            sample = int(val * 32767.0)
            sample = max(-32768, min(32767, sample))
            samples.append(sample)
        
        silence_samples = int(sample_rate * 0.015)
        samples.extend([0] * silence_samples)

    with wave.open(out_path, 'w') as wf:
        wf.setnchannels(1)  # Mono
        wf.setsampwidth(2)  # 16-bit
        wf.setframerate(sample_rate)
        raw_bytes = struct.pack(f'<{len(samples)}h', *samples)
        wf.writeframes(raw_bytes)
    
    print(f"Generated tone -> {filename} ({os.path.getsize(out_path)} bytes)")


async def main():
    print("=" * 60)
    print("Generating DriveCare Ultra-Concise Natural Voices & Tones")
    print("=" * 60)

    # 1. Natural Arabic Voice Prompts
    for fn, voice, txt in VOICE_PROMPTS:
        try:
            await generate_voice_file(fn, voice, txt)
        except Exception as e:
            print(f"  Error generating {fn}: {e}")

    # 2. Harmonic Tones
    # 100m Early bump chimes
    generate_harmonic_wave("tone_bump_100m.wav", [1046.5, 1318.5], [0.10, 0.18])
    generate_harmonic_wave("tone_bump_100m_alt.wav", [880.0, 1174.6], [0.10, 0.20])
    
    # 25m Final bump chimes
    generate_harmonic_wave("tone_bump_25m.wav", [1567.9, 1318.5, 1046.5], [0.07, 0.07, 0.15])
    generate_harmonic_wave("tone_bump_25m_alt.wav", [1760.0, 1318.5], [0.09, 0.16])
    
    # Engine Heat urgent alarm
    generate_harmonic_wave("tone_engine_heat.wav", [880.0, 659.2, 880.0, 659.2], [0.12, 0.12, 0.12, 0.20])
    
    # DTC Diagnostic tone
    generate_harmonic_wave("tone_dtc_error.wav", [587.3, 880.0], [0.10, 0.20])
    
    # Battery tone
    generate_harmonic_wave("tone_battery.wav", [783.9, 523.2], [0.12, 0.22])
    
    # Oil / Maintenance soft chime
    generate_harmonic_wave("tone_oil_maint.wav", [523.2, 659.2, 783.9], [0.10, 0.10, 0.24])

    print("\nAll audio files generated in res/raw successfully!")

if __name__ == '__main__':
    asyncio.run(main())
