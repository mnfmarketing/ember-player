# Ember-30: Books › All shows every book; Series as stacks; tap the player's middle label for percent.
say() { echo "== $*" | tee -a "$OUT/focus.txt"; }
say "Books: All, then Series"
open_route "library"; sleep 3
shot f01-books-all 1
tap_text "Series"; sleep 3
shot f02-series-stacks 1
say "Player: tap time left for percent, then back"
open_route "player"; sleep 4
shot f03-player-time-left 1
tap_text "*left"; sleep 2
shot f04-player-percent 1
tap_text "*complete"; sleep 2
shot f05-player-time-left-again 1
