#!/usr/bin/env python3
"""Composes narrated MP4 videos (+ SRT) from record.js output using Piper TTS and ffmpeg.
Usage: python3 compose.py <recordingDir> <voice.onnx> <outDir>"""
import json, os, subprocess, sys, wave
rec, voice, out = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
m = json.load(open(os.path.join(rec, "manifest.json")))
names = {"platform": "IntiMed-Platform-Overview", "demo": "IntiMed-Demo-Execution-Steps"}
def ts(t):
    h, r = divmod(t, 3600); mm, s = divmod(r, 60); return "%02d:%02d:%02d,%03d" % (h, mm, int(s), int((s - int(s)) * 1000))
for video, shots in m.items():
    work = os.path.join(rec, "work-" + video); os.makedirs(work, exist_ok=True)
    segs, srt, t = [], [], 0.0
    for i, s in enumerate(shots):
        wav = os.path.join(work, "%03d.wav" % i)
        subprocess.run([sys.executable, "-m", "piper", "--model", voice, "--output_file", wav, "--length-scale", "1.02"], input=s["narration"].encode(), check=True, capture_output=True)
        with wave.open(wav) as w: dur = w.getnframes() / w.getframerate()
        total = dur + 0.9
        seg = os.path.join(work, "%03d.mp4" % i)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-loop", "1", "-i", os.path.join(rec, "shots", s["file"]), "-i", wav, "-af", "apad=pad_dur=0.9",
                        "-t", "%.2f" % total, "-vf", "scale=1440:900,format=yuv420p", "-r", "25", "-c:v", "libx264", "-preset", "medium", "-crf", "22", "-tune", "stillimage",
                        "-c:a", "aac", "-b:a", "128k", "-ar", "44100", seg], check=True)
        segs.append(seg)
        srt.append("%d\n%s --> %s\n%s\n" % (i + 1, ts(t + 0.1), ts(t + dur), s["narration"]))
        t += total
    lst = os.path.join(work, "list.txt")
    open(lst, "w").write("".join("file '%s'\n" % os.path.abspath(x) for x in segs))
    mp4 = os.path.join(out, names[video] + ".mp4")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", mp4], check=True)
    open(os.path.join(out, names[video] + ".srt"), "w").write("\n".join(srt))
    print("%s: %d scenes, %.1f min, %.1f MB" % (mp4, len(segs), t / 60, os.path.getsize(mp4) / 1e6))
