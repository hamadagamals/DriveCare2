"""
Generate high quality natural Arabic human voice audio clips and
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

VOICE_PROMPTS = [
    ("bump_100m_male.mp3", "ar-EG-ShakirNeural", "انتبه، مطب بعد مائة متر"),
    ("bump_100m_female.mp3", "ar-EG-SalmaNeural", "انتبه، يوجد مطب على بعد مائة متر"),
    ("bump_25m_male.mp3", "ar-EG-ShakirNeural", "مطب الآن، هدئ السرعة"),
    ("bump_25m_female.mp3", "ar-EG-SalmaNeural", "مطب الآن، خفف السرعة"),
    ("voice_engine_heat.mp3", "ar-EG-ShakirNeural", "تحذير طارئ، حرارة المحرك مرتفعة جداً، يرجى التوقف بأمان"),
    ("voice_dtc_error.mp3", "ar-EG-ShakirNeural", "تنبيه، تم رصد كود عطل في كمبيوتر السيارة"),
    ("voice_battery_low.mp3", "ar-EG-SalmaNeural", "تنبيه، انخفاض في جهد بطارية السيارة، يرجى فحص الشحن"),
    ("voice_oil_maint.mp3", "ar-EG-ShakirNeural", "تذكير، حان موعد الصيانة وتغيير زيت المحرك"),
    ("voice_speed_limit.mp3", "ar-EG-SalmaNeural", "تنبيه، لقد تجاوزت السرعة المحددة للطريق"),
]


async def generate_voice_file(filename, voice, text):
    out_path = os.path.join(RAW_DIR, filename)
    print(f"Generating voice [{voice}] -> {filename} ...")
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
            # Fade in and out envelope
            env = 1.0
            attack = 0.02
            decay = 0.05
            if t < attack:
                env = t / attack
            elif t > duration_sec - decay:
                env = max(0.0, (duration_sec - t) / decay)
            
            # Fundamental + soft 2nd harmonic
            val = 0.7 * math.sin(2.0 * math.pi * freq * t) + 0.3 * math.sin(4.0 * math.pi * freq * t)
            val = val * env * 0.7
            sample = int(val * 32767.0)
            sample = max(-32768, min(32767, sample))
            samples.append(sample)
        
        # Short 20ms silence between notes
        silence_samples = int(sample_rate * 0.02)
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
    print("Generating DriveCare Natural Voice & Tone Audio Assets")
    print("=" * 60)

    # 1. Natural Arabic Voice Prompts
    for fn, voice, txt in VOICE_PROMPTS:
        try:
            await generate_voice_file(fn, voice, txt)
        except Exception as e:
            print(f"  Error generating {fn}: {e}")

    # 2. Harmonic Tones
    # 100m Early bump chime: C6 -> E6 (1046 Hz -> 1318 Hz)
    generate_harmonic_wave("tone_bump_100m.wav", [1046.5, 1318.5], [0.12, 0.22])
    
    # 25m Final bump chime: G6 -> E6 -> C6 (1567 Hz -> 1318 Hz -> 1046 Hz)
    generate_harmonic_wave("tone_bump_25m.wav", [1567.9, 1318.5, 1046.5], [0.08, 0.08, 0.18])
    
    # Engine Heat urgent alarm: 880 Hz -> 660 Hz alternating
    generate_harmonic_wave("tone_engine_heat.wav", [880.0, 659.2, 880.0, 659.2], [0.15, 0.15, 0.15, 0.25])
    
    # DTC Diagnostic tone: 587 Hz -> 880 Hz
    generate_harmonic_wave("tone_dtc_error.wav", [587.3, 880.0], [0.12, 0.22])
    
    # Battery low: 783 Hz -> 523 Hz
    generate_harmonic_wave("tone_battery.wav", [783.9, 523.2], [0.15, 0.25])
    
    # Oil / Maintenance soft chime: 523 Hz -> 659 Hz -> 783 Hz
    generate_harmonic_wave("tone_oil_maint.wav", [523.2, 659.2, 783.9], [0.12, 0.12, 0.28])

    print("\nAll audio files generated in res/raw successfully!")

if __name__ == '__main__':
    asyncio.run(main())
