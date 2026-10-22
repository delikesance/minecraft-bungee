#!/usr/bin/env python3
"""Turns the werewolf sounds generated with ElevenLabs into pack sounds.

    python3 resource-pack/art/sounds.py

Sources are the chosen takes in art/werewolf/sounds/*.mp3 (ElevenLabs flow
"Vampyuyu · Loup-Garou pixel-art", Sound Effects v2 and Music v2.5). Each one
is levelled, trimmed, encoded as mono Ogg Vorbis and declared in sounds.json
as vampyuyu:werewolf.<name>, which is the key the arena plugin plays.
"""
import json, pathlib, subprocess

ART = pathlib.Path(__file__).resolve().parent
PACK = ART.parent / "pack" / "assets" / "vampyuyu"

# name: (pack volume, English subtitle). The music loops in the plugin every 75 s.
SOUNDS = {
    "music": (0.35, "Night music plays"),
    "night": (0.8, "Church bell tolls"),
    "dawn": (0.7, "Rooster crows"),
    "howl": (0.7, "Wolf howls"),
    "vote": (0.8, "Drums beat"),
    "death": (0.9, "Death knell"),
    "shot": (0.9, "Hunter fires"),
    "potion_life": (0.8, "Life potion drunk"),
    "potion_death": (0.8, "Poison bubbles"),
    "vision": (0.8, "Crystal ball shimmers"),
    "bind": (0.8, "Lovers bound"),
    "protect": (0.7, "Shield hums"),
    "game_over": (0.8, "Fanfare plays"),
}


def encode(name):
    music = name == "music"
    source = ART / "werewolf" / "sounds" / f"{name}.mp3"
    target = PACK / "sounds" / "werewolf" / f"{name}.ogg"
    target.parent.mkdir(parents=True, exist_ok=True)
    # Same loudness for every effect; the music sits lower and fades at both ends so the loop
    # seam is soft. Effects lose their trailing silence and end on a short fade.
    chain = (
        "loudnorm=I=-23:TP=-2,afade=t=in:d=2,areverse,afade=t=in:d=2,areverse"
        if music
        else "loudnorm=I=-16:TP=-1,areverse,silenceremove=start_periods=1:start_threshold=-50dB,"
        "afade=t=in:d=0.15,areverse"
    )
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-af", chain,
         "-ac", "1", "-ar", "44100", "-c:a", "libvorbis", "-q:a", "2" if music else "4",
         "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", str(target)],
        check=True,
    )


def main():
    definitions, subtitles = {}, {}
    for name, (volume, subtitle) in SOUNDS.items():
        encode(name)
        key = f"subtitles.vampyuyu.werewolf.{name}"
        sound = {"name": f"vampyuyu:werewolf/{name}", "volume": volume}
        if name == "music":
            sound["stream"] = True
        definitions[f"werewolf.{name}"] = {"sounds": [sound], "subtitle": key}
        subtitles[key] = subtitle
    (PACK / "sounds.json").write_text(json.dumps(definitions, indent=2) + "\n")
    (PACK / "lang").mkdir(exist_ok=True)
    (PACK / "lang" / "en_us.json").write_text(json.dumps(subtitles, indent=2) + "\n")


if __name__ == "__main__":
    main()
