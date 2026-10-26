# Cross-version drift: 1.21.1 -> 26.3 (M3.2 generated report)

- sources: mcmeta registries 0eaeabd259d09369c6fc45b810cc5a675c3c1af4 -> 2240df2376509bfaf12becbb36e156e29f8ecb4d;
  summary 7cffbcdad5fa36f22acef6bd4b869ecad4ca3fd9 -> d96c75fec200c4580dd75e033a76341521165461

- generated: 2026-10-26 18:19:27Z


## registry census (old -> new, added/removed keys)

| registry | old | new | added | removed |
|---|---|---|---|---|
| block | 1060 | 1286 | 227 | 1 |
| item | 1333 | 1658 | 326 | 1 |
| entity_type | 130 | 161 | 34 | 3 |
| block_entity_type | 44 | 49 | 6 | 1 |
| fluid | 5 | 5 | 0 | 0 |
| sound_event | 1611 | 1991 | 386 | 6 |
| particle_type | 109 | 128 | 19 | 0 |
| potion | 46 | 46 | 0 | 0 |
| enchantment | 42 | 43 | 1 | 0 |
| menu | 25 | 25 | 0 | 0 |
| mob_effect | 39 | 40 | 1 | 0 |
| game_event | 60 | 61 | 1 | 0 |
| attribute | 31 | 40 | 40 | 31 |
| position_source_type | 2 | 2 | 0 | 0 |
| villager_profession | 15 | 15 | 0 | 0 |
| villager_type | 7 | 7 | 0 | 0 |
| point_of_interest_type | 20 | 21 | 1 | 0 |
| memory_module_type | 106 | 115 | 11 | 2 |
| sensor_type | 26 | 24 | 3 | 5 |
| activity | 26 | 26 | 0 | 0 |
| data_component_type | 57 | 122 | 69 | 4 |

## blockstate drift

- blocks present in both: 1059 (added 227, removed 1)
- blocks with property-set drift: 1
- individual value-set drifts: 1

### per-block drift detail

#### note_block
- value drift `instrument`: ['harp', 'basedrum', 'snare', 'hat', 'bass', 'flute', 'bell', 'guitar', 'chime', 'xylophone', 'iron_xylophone', 'cow_bell', 'didgeridoo', 'bit', 'banjo', 'pling', 'zombie', 'skeleton', 'creeper', 'dragon', 'wither_skeleton', 'piglin', 'custom_head'] -> ['harp', 'basedrum', 'snare', 'hat', 'bass', 'flute', 'bell', 'guitar', 'chime', 'xylophone', 'iron_xylophone', 'cow_bell', 'didgeridoo', 'bit', 'banjo', 'pling', 'trumpet', 'trumpet_exposed', 'trumpet_oxidized', 'trumpet_weathered', 'zombie', 'skeleton', 'creeper', 'dragon', 'wither_skeleton', 'piglin', 'custom_head'] (map {'harp': 'harp', 'basedrum': 'basedrum', 'snare': 'snare', 'hat': 'hat', 'bass': 'bass', 'flute': 'flute', 'bell': 'bell', 'guitar': 'guitar', 'chime': 'chime', 'xylophone': 'xylophone', 'iron_xylophone': 'iron_xylophone', 'cow_bell': 'cow_bell', 'didgeridoo': 'didgeridoo', 'bit': 'bit', 'banjo': 'banjo', 'pling': 'pling', 'zombie': 'zombie', 'skeleton': 'skeleton', 'creeper': 'creeper', 'dragon': 'dragon', 'wither_skeleton': 'wither_skeleton', 'piglin': 'piglin', 'custom_head': 'custom_head'})


## confirmed renames

- `chain` -> `iron_chain`
