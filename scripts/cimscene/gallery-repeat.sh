#!/usr/bin/env bash
# The selected pages come from the last completed Create join reports.
# Keep capture in the caller's shell so rcon can reach its coprocess.
declare -A GALLERY_REPEAT_PAGES=()
while IFS=$'\t' read -r kind namespace page missing; do
  [[ "$kind" == \#* ]] && continue
  GALLERY_REPEAT_PAGES["$kind:$namespace:$page"]=$missing
done < scripts/cimscene/gallery-repeat.tsv

gallery_repeat_selected() { # kind namespace page
  [[ -n "${GALLERY_REPEAT_PAGES[$1:$2:$3]+selected}" ]]
}

gallery_repeat_capture() { # kind namespace page primary.png
  if gallery_repeat_selected "$1" "$2" "$3"; then
    local repeat="${4%.png}-self.png"
    sleep 2
    rcon "tp cimviewer 0.5 -60 0.5 0 0" >/dev/null || return 1
    bash scripts/visual/shot.sh "$repeat" || return 1
    test -s "$repeat"
  fi
}

gallery_repeat_options() { # kind namespace page truth.png ours.png
  local truth="${4%.png}" ours="${5%.png}"
  GALLERY_SELF_ARGS=()
  if [[ "$1" == "block" ]]; then
    [[ ! -f "$truth-t1.png" ]] || GALLERY_SELF_ARGS+=(--truth-self "$truth-t1.png")
    [[ ! -f "$ours-t1.png" ]] || GALLERY_SELF_ARGS+=(--ours-self "$ours-t1.png")
  fi
  if gallery_repeat_selected "$1" "$2" "$3" && [[ ${#GALLERY_SELF_ARGS[@]} -ne 4 ]]; then
    GALLERY_SELF_ARGS=()
    if [[ ! -s "$truth-self.png" || ! -s "$ours-self.png" ]]; then
      echo "::error::Missing repeat capture for $2 $1 page $3 ($truth-self.png, $ours-self.png)" >&2
      return 1
    fi
    GALLERY_SELF_ARGS=(--measure-self-only --truth-self "$truth-self.png" --ours-self "$ours-self.png")
  fi
}
