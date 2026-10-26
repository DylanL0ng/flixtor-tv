`qa-big-buck-bunny.jpg` is the Big Buck Bunny poster by Blender Foundation, from Wikimedia Commons, licensed under CC BY 3.0.

Source: https://commons.wikimedia.org/wiki/File:Big_buck_bunny_poster_big.jpg
License: https://creativecommons.org/licenses/by/3.0/

`qa-player.m3u8` and `qa-player-0.ts` are a generated 60-second solid navy HLS fixture (160×90, 2 fps). Player action QA runs offline with this fixture, including seeks and end-of-episode prompts. Generated using FFmpeg color and libx264; no third-party media.

`qa-audio.aac` is generated 60-second mono silence (16 kHz AAC/ADTS). Remote-control QA combines it with the existing video and generated captions to exercise audio and subtitle selection offline. Generated with `ffmpeg -f lavfi -i anullsrc=r=16000:cl=mono -t 60 -c:a aac -b:a 16k -f adts qa-audio.aac`; no third-party media.
