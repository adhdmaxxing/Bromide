#!/bin/bash
# film_profile print_profile short_name
while read film print name; do
  [ -z "$film" ] && continue
  spektrafilm-lut build --film $film --print $print --input slog3cine --output srgb \
     --resolution 65 --name $name --out luts/bake > luts/bake_$name.log 2>&1 && echo "ok $name" || echo "FAIL $name"
done <<LIST
kodak_portra_400 kodak_portra_endura portra400
kodak_portra_160 kodak_portra_endura portra160
kodak_portra_800 kodak_portra_endura portra800
kodak_gold_200 kodak_ektacolor_edge gold200
kodak_ultramax_400 kodak_ektacolor_edge ultramax400
kodak_ektar_100 kodak_portra_endura ektar100
fujifilm_c200 fujifilm_crystal_archive_typeii c200
fujifilm_xtra_400 fujifilm_crystal_archive_typeii superia400
fujifilm_pro_400h fujifilm_crystal_archive_typeii pro400h
kodak_vision3_500t kodak_2383 vision500t
kodak_vision3_250d kodak_2383 vision250d
fujifilm_provia_100f kodak_portra_endura provia100f
fujifilm_velvia_100 kodak_portra_endura velvia100
kodak_ektachrome_100 kodak_portra_endura ektachrome100
kodak_kodachrome_64 kodak_portra_endura kodachrome64
LIST
echo ALLDONE
