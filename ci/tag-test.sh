#!/usr/bin/env bash
# Builds two real M4B audiobooks with ffmpeg (index at the end, and at the front),
# runs Ember's tag/chapter writer tests on them, then double-checks the edited
# files with ffprobe and decodes every frame with ffmpeg.
set -euo pipefail
IN="$PWD/tagtest/in"
OUT="$PWD/tagtest/out"
rm -rf tagtest && mkdir -p "$IN" "$OUT"

ffmpeg -loglevel error -f lavfi -i "sine=frequency=330:duration=180" -c:a aac -b:a 64k "$IN/audio.m4a"
ffmpeg -loglevel error -f lavfi -i "color=c=0x7A2E18:s=600x600" -frames:v 1 "$IN/cover.jpg"
cat > "$IN/meta.txt" <<'EOF'
;FFMETADATA1
title=Original Title
album=Original Title
artist=Original Author
[CHAPTER]
TIMEBASE=1/1000
START=0
END=60000
title=Original One
[CHAPTER]
TIMEBASE=1/1000
START=60000
END=120000
title=Original Two
[CHAPTER]
TIMEBASE=1/1000
START=120000
END=180000
title=Original Three
EOF
ffmpeg -loglevel error -i "$IN/audio.m4a" -i "$IN/meta.txt" -map 0 -map_metadata 1 -map_chapters 1 -c copy -f mp4 "$IN/end.m4b"
ffmpeg -loglevel error -i "$IN/audio.m4a" -i "$IN/meta.txt" -map 0 -map_metadata 1 -map_chapters 1 -c copy -f mp4 -movflags +faststart "$IN/front.m4b"

# A podcast-style MP3 with chapter marks (ID3 CHAP frames), in both ID3 versions.
cat > "$IN/epmeta.txt" <<'EOM'
;FFMETADATA1
title=Test Episode
[CHAPTER]
TIMEBASE=1/1000
START=0
END=40000
title=Cold open
[CHAPTER]
TIMEBASE=1/1000
START=40000
END=75000
title=Héllo — main story
[CHAPTER]
TIMEBASE=1/1000
START=75000
END=100000
title=Outro
EOM
ffmpeg -loglevel error -f lavfi -i "sine=frequency=440:duration=100" -i "$IN/epmeta.txt" -map 0 -map_metadata 1 -map_chapters 1 -c:a libmp3lame -b:a 64k -id3v2_version 3 "$IN/episode-v3.mp3"
ffmpeg -loglevel error -f lavfi -i "sine=frequency=440:duration=100" -i "$IN/epmeta.txt" -map 0 -map_metadata 1 -map_chapters 1 -c:a libmp3lame -b:a 64k -id3v2_version 4 "$IN/episode-v4.mp3"
ffprobe -v error -show_chapters -of compact "$IN/episode-v3.mp3"

echo "Original chapters:"
ffprobe -v error -show_chapters -of compact "$IN/front.m4b"

TEST_M4B_DIR="$IN" TEST_M4B_OUT="$OUT" gradle :app:testDebugUnitTest --tests 'app.ember.data.Mp4TagWriterTest' --tests 'app.ember.podcasts.EpisodeChaptersTest' --no-daemon --console=plain

status=0
for f in "$OUT"/edited-*.m4b; do
  echo "== $f"
  ffprobe -v error -show_chapters -show_entries format_tags:stream=codec_type,codec_name:stream_disposition=attached_pic -of json "$f" | tee "$f.json"
  pics=$(python3 -c "import json; d=json.load(open('$f.json')); print(sum(1 for s in d.get('streams',[]) if s.get('disposition',{}).get('attached_pic')==1))")
  [ "$pics" = "1" ] || { echo "::error::$f has $pics embedded cover images, expected 1"; status=1; }
  chapters=$(python3 -c "import json,sys; d=json.load(open('$f.json')); print(len(d.get('chapters',[])))")
  titles=$(python3 -c "import json,sys; d=json.load(open('$f.json')); print('|'.join(c.get('tags',{}).get('title','') for c in d.get('chapters',[])))")
  album=$(python3 -c "import json,sys; d=json.load(open('$f.json')); t=d.get('format',{}).get('tags',{}); print(t.get('album',''))")
  echo "chapters=$chapters titles=$titles album=$album"
  [ "$chapters" = "2" ] || { echo "::error::$f has $chapters chapters, expected 2"; status=1; }
  [ "$titles" = "One|Two" ] || { echo "::error::$f chapter titles were '$titles'"; status=1; }
  [ "$album" = "Second Title" ] || { echo "::error::$f album tag was '$album'"; status=1; }
  if ffmpeg -v error -i "$f" -f null - 2> "$f.decode.txt"; then
    if [ -s "$f.decode.txt" ]; then echo "::error::decode warnings in $f"; cat "$f.decode.txt"; status=1; fi
  else
    echo "::error::$f failed to decode"; cat "$f.decode.txt"; status=1
  fi
done
exit $status
