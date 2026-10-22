"""合成开脉占位提示音；无需第三方录音，ffmpeg 仅负责编码 Ogg Vorbis。"""
from pathlib import Path
import math
import struct
import subprocess
import tempfile
import wave


def generate(target, success):
    # 柔和泛音上行表示完成，低频下行表示回退；首尾包络避免点击噪声。
    rate = 44100
    duration = 1.15 if success else .8
    samples = []
    phase = 0
    for index in range(int(rate * duration)):
        t = index / rate
        envelope = min(1, t / .035) * math.exp(-t * 3.5) * min(1, (duration - t) / .10)
        frequency = 440 + 220 * min(1, t / .25) if success else 230 - 100 * t / duration
        phase += 2 * math.pi * frequency / rate
        tone = math.sin(phase) + .3 * math.sin(phase * 1.5) + .12 * math.sin(phase * 2)
        samples.append(struct.pack('<h', int(10000 * envelope * tone)))
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        source = Path(temporary) / 'sound.wav'
        with wave.open(str(source), 'wb') as output:
            output.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
            output.writeframes(b''.join(samples))
        subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', str(source), '-c:a', 'libvorbis',
                        '-q:a', '4', str(target)], check=True)


if __name__ == '__main__':
    # 输出只包含本项目生成的两个短音效，生产资源可重复生成。
    root = Path(__file__).resolve().parents[1] / 'client/src/main/resources/assets/meridian_ui/sounds/opening'
    generate(root / 'complete.ogg', True)
    generate(root / 'interrupted.ogg', False)
