#!/usr/bin/env bash
# Builds the two files of the browser demo from song.wav (see make_song.py):
#
#   sample-song.m4a   the song as a streaming service would serve it: AAC, 128 kbit/s
#   sample-video.mp4  its "music video": squares that swell with the music, with 5 s of silence in
#                     front, one cut (song 30 s .. 40 s is missing), 3 s of silence after, the audio
#                     re-encoded at 96 kbit/s, grain on the picture and a burned-in video clock.
#
# So the video's map onto the song is two stretches: song 0..30 s = video 5..35 s (offset +5.0 s) and
# song 40..72 s = video 35..67 s (offset -5.0 s), with nothing to show for song 30..40 s.
#
#   ./make_sample.sh song.wav out_dir
set -euo pipefail
wav="$(realpath "${1:?song.wav}")"
mkdir -p "${2:?output dir}"
out="$(realpath "$2")"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

python -I "$here/make_video_cmds.py" "$wav" "$work/cmds.txt"

ffmpeg -y -loglevel error -i "$wav" -c:a aac -b:a 128k -movflags +faststart "$out/sample-song.m4a"

# run in $work so the filter can name the command file without a drive letter
cd "$work"
ffmpeg -y -loglevel error \
  -i "$wav" \
  -f lavfi -t 5 -i "anullsrc=r=32000:cl=mono" \
  -f lavfi -t 3 -i "anullsrc=r=32000:cl=mono" \
  -f lavfi -i "color=c=0x14072e:s=960x540:r=25:d=70" \
  -filter_complex "
    [0:a]asplit=2[s1][s2];
    [s1]atrim=0:30,asetpts=PTS-STARTPTS[a1];
    [s2]atrim=40:72,asetpts=PTS-STARTPTS[a2];
    [1:a][a1][a2][2:a]concat=n=4:v=0:a=1,aformat=sample_rates=32000:channel_layouts=mono[vo];
    [3:v]sendcmd=f=cmds.txt,
      drawbox@a=x=0:y=0:w=100:h=100:color=0xec4899:t=fill,
      drawbox@b=x=0:y=0:w=60:h=60:color=0xa78bfa:t=fill,
      drawbox@c=x=0:y=0:w=30:h=30:color=0xfff1c2:t=fill,
      noise=alls=7:allf=t,
      drawtext=fontfile='C\\:/Windows/Fonts/arial.ttf':text='video %{pts\\:hms}':x=(w-text_w)/2:y=h-190:fontsize=34:fontcolor=white:box=1:boxcolor=0x00000088:boxborderw=8,
      format=yuv420p[v]
  " \
  -map "[v]" -map "[vo]" -c:v libx264 -preset slow -crf 33 -r 25 -c:a aac -b:a 96k -movflags +faststart "$out/sample-video.mp4"

ls -l "$out"
