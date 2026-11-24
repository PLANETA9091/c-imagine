//! Per-packet rewriters: the biome registry packet and the chunk packet.
//!
//! Both operate on the payload AFTER the packet-id VarInt (the caller decodes
//! the id). Formats are measured from the 26.3 server jar:
//! `ClientboundRegistryDataPacket` = key string, count VarInt, entries
//! `(name string, presence u8, optional unnamed NBT)`; the chunk packet is
//! `x i32, z i32, heightmaps, buffer`, and the buffer is one record per
//! section `(nonEmpty u16, fluid u16, block container, biome container)`.

use crate::nbt::{self, NbtWriter, Reader};
use crate::{BiomeDef, BiomeError};
use std::collections::HashMap;

/// One biome registry entry as it arrived on the wire.
#[derive(Debug, Clone)]
pub struct Entry {
    pub name: String,
    pub temperature: Option<f32>,
    pub downfall: Option<f32>,
    /// Byte range of `[name..end]` inside the payload (verbatim splice).
    pub start: usize,
    pub end: usize,
}

/// A parsed `minecraft:worldgen/biome` registry packet.
#[derive(Debug, Clone)]
pub struct Registry {
    pub key: String,
    /// Offset of the entry-count VarInt.
    pub count_pos: usize,
    /// Offset of the first entry (just past the count VarInt).
    pub entries_start: usize,
    /// Offset just past the last entry.
    pub end: usize,
    pub entries: Vec<Entry>,
}

/// Parse a registry-data payload (after the packet id). Returns `None` when
/// the key is not the biome registry.
pub fn parse_registry(payload: &[u8]) -> Result<Option<Registry>, BiomeError> {
    let mut r = Reader::new(payload);
    let key = r.string(256)?;
    if key != "minecraft:worldgen/biome" {
        return Ok(None);
    }
    let count_pos = r.pos();
    let count = r.varint()?;
    if count < 0 {
        return Err(BiomeError::BadRegistry);
    }
    let entries_start = r.pos();
    let mut entries = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let start = r.pos();
        let name = r.string(1024)?;
        let present = r.u8()?;
        let (mut temp, mut downfall) = (None, None);
        if present != 0 {
            let mut p = r.pos();
            (temp, downfall) = nbt::climate(payload, &mut p)?;
            r = Reader::at(payload, p);
        }
        entries.push(Entry {
            name,
            temperature: temp,
            downfall,
            start,
            end: r.pos(),
        });
    }
    let end = r.pos();
    if end != payload.len() {
        return Err(BiomeError::BadRegistry);
    }
    Ok(Some(Registry {
        key,
        count_pos,
        entries_start,
        end,
        entries,
    }))
}

/// Append the modded biomes to a parsed registry packet, returning the new
/// payload and the registry id each appended biome landed on.
pub fn append_biomes(
    payload: &[u8],
    reg: &Registry,
    biomes: &[BiomeDef],
) -> Result<(Vec<u8>, HashMap<String, u32>), BiomeError> {
    let base = reg.entries.len() as u32;
    let mut ids = HashMap::new();
    let mut tail = Vec::new();
    for (i, b) in biomes.iter().enumerate() {
        if reg.entries.iter().any(|e| e.name == b.name) {
            // Already present (a previous connection's append, or the server
            // grew the registry): reuse its id and do not duplicate.
            if let Some(pos) = reg.entries.iter().position(|e| e.name == b.name) {
                ids.insert(b.name.clone(), pos as u32);
            }
            continue;
        }
        ids.insert(b.name.clone(), base + i as u32);
        nbt::write_varint(&mut tail, b.name.len() as i32);
        tail.extend_from_slice(b.name.as_bytes());
        tail.push(1); // presence
        tail.extend_from_slice(&b.nbt);
    }
    if tail.is_empty() {
        return Ok((payload.to_vec(), ids));
    }
    let mut out = Vec::with_capacity(payload.len() + tail.len() + 5);
    out.extend_from_slice(&payload[..reg.count_pos]);
    nbt::write_varint(&mut out, base as i32 + count_new(biomes, reg) as i32);
    out.extend_from_slice(&payload[reg.entries_start..reg.end]);
    out.extend_from_slice(&tail);
    Ok((out, ids))
}

fn count_new(biomes: &[BiomeDef], reg: &Registry) -> usize {
    biomes
        .iter()
        .filter(|b| !reg.entries.iter().any(|e| e.name == b.name))
        .count()
}

// ---------------------------------------------------------------------------
// Chunk packet
// ---------------------------------------------------------------------------

/// The overworld's section count (`-64..320`). Nether chunks (16) fail the
/// parse and travel unchanged; a taller dimension fails too (safe).
pub const OVERWORLD_SECTIONS: usize = 24;

/// Rewrite the biome palettes of a chunk packet (payload after the id).
/// `None` = leave the packet untouched (malformed / not a chunk / no ids).
pub fn rewrite_chunk(payload: &[u8], remap: &HashMap<u32, u32>) -> Option<Vec<u8>> {
    let mut r = Reader::new(payload);
    r.i32().ok()?; // x
    r.i32().ok()?; // z
    let maps = r.varint().ok()?;
    if !(0..=64).contains(&maps) {
        return None;
    }
    for _ in 0..maps {
        let _kind = r.varint().ok()?;
        let len = r.varint().ok()?;
        if len < 0 {
            return None;
        }
        r.bytes(len as usize * 8).ok()?;
    }
    let len_pos = r.pos();
    let buf_len = r.varint().ok()?;
    if buf_len < 0 {
        return None;
    }
    let buf_start = r.pos();
    let buf = r.bytes(buf_len as usize).ok()?;
    let new_buf = rewrite_buffer(buf, remap)?;
    if new_buf == *buf {
        return None;
    }
    let mut out = Vec::with_capacity(payload.len());
    out.extend_from_slice(&payload[..len_pos]);
    nbt::write_varint(&mut out, new_buf.len() as i32);
    out.extend_from_slice(&new_buf);
    out.extend_from_slice(&payload[buf_start + buf_len as usize..]);
    // The buffer is the only length-prefixed variable part we touched; the
    // surrounding packet is copied verbatim.
    Some(out)
}

fn rewrite_buffer(buf: &[u8], remap: &HashMap<u32, u32>) -> Option<Vec<u8>> {
    let mut r = Reader::new(buf);
    let mut out = Vec::with_capacity(buf.len() + 16);
    for _ in 0..OVERWORLD_SECTIONS {
        let head = r.pos();
        r.u16().ok()?; // nonEmptyBlockCount
        r.u16().ok()?; // fluidCount
                       // block states: skip (no remap)
        skip_container(&mut r, 4096)?;
        let bio_start = r.pos();
        let rewritten = rewrite_container(&mut r, remap, 64)?;
        out.extend_from_slice(&buf[head..bio_start]);
        out.extend_from_slice(&rewritten);
    }
    if !r.is_empty() {
        return None; // not the layout we understand
    }
    Some(out)
}

/// Number of longs a container stores for `entries` values at `bits`.
///
/// 26.3 writes the long array WITHOUT a length prefix
/// (`FriendlyByteBuf.writeFixedSizeLongArray(ByteBuf, long[])`); the size is
/// implied by the container's entry count and bit width.
fn longs_for(bits: u8, entries: usize) -> Option<usize> {
    if bits == 0 {
        return Some(0);
    }
    if bits as usize > 32 {
        return None;
    }
    let per = 64 / bits as usize;
    Some(entries.div_ceil(per))
}

/// Advance past one paletted container without materialising it.
fn skip_container(r: &mut Reader<'_>, entries: usize) -> Option<()> {
    let bits = r.u8().ok()?;
    if bits == 0 {
        r.varint().ok()?; // single value
    } else {
        let len = r.varint().ok()?;
        if len < 0 {
            return None;
        }
        for _ in 0..len {
            r.varint().ok()?;
        }
    }
    let n = longs_for(bits, entries)?;
    r.bytes(n * 8).ok()?;
    Some(())
}

/// Rewrite one paletted container (biomes) and return its new bytes.
fn rewrite_container(
    r: &mut Reader<'_>,
    remap: &HashMap<u32, u32>,
    entries: usize,
) -> Option<Vec<u8>> {
    let bits = r.u8().ok()?;
    let mut out = Vec::with_capacity(16);
    out.push(bits);
    if bits == 0 {
        let v = r.varint().ok()?;
        if v < 0 {
            return None;
        }
        nbt::write_varint(
            &mut out,
            remap.get(&(v as u32)).copied().unwrap_or(v as u32) as i32,
        );
        return Some(out);
    }
    let len = r.varint().ok()?;
    if len < 0 {
        return None;
    }
    if len > 0 {
        nbt::write_varint(&mut out, len);
        for _ in 0..len {
            let v = r.varint().ok()?;
            if v < 0 {
                return None;
            }
            let nv = remap.get(&(v as u32)).copied().unwrap_or(v as u32);
            nbt::write_varint(&mut out, nv as i32);
        }
        let n = longs_for(bits, entries)?;
        out.extend_from_slice(r.bytes(n * 8).ok()?);
        return Some(out);
    }
    // Direct palette: the values are raw registry ids packed into longs.
    nbt::write_varint(&mut out, 0);
    let n = longs_for(bits, entries)?;
    let raw = r.bytes(n * 8).ok()?;
    let mut longs: Vec<u64> = raw
        .as_chunks::<8>()
        .0
        .iter()
        .map(|c| u64::from_be_bytes(*c))
        .collect();
    if bits > 32 {
        return None;
    }
    let per = 64 / bits as usize;
    let mask = (1u64 << bits) - 1;
    for i in 0..entries.min(per * longs.len()) {
        let word = i / per;
        let shift = (i % per) * bits as usize;
        let v = ((longs[word] >> shift) & mask) as u32;
        if let Some(&nv) = remap.get(&v) {
            if u64::from(nv) > mask {
                return None; // does not fit: leave the packet alone
            }
            longs[word] = (longs[word] & !(mask << shift)) | ((u64::from(nv)) << shift);
        }
    }
    for l in longs {
        out.extend_from_slice(&l.to_be_bytes());
    }
    Some(out)
}

/// Build the 26.3 biome definition blob for one modded biome JSON.
pub fn biome_blob(json: &serde_json::Value) -> Option<Vec<u8>> {
    let temp = json.get("temperature").and_then(|v| v.as_f64())? as f32;
    let downfall = json.get("downfall").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
    let precip = json
        .get("has_precipitation")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let effects = json.get("effects");
    let color = |key: &str| -> Option<String> {
        let v = effects?.get(key)?;
        if let Some(s) = v.as_str() {
            return Some(s.to_string());
        }
        let n = v.as_u64()?;
        Some(format!("#{:06x}", n & 0xFF_FFFF))
    };
    let modifier = effects
        .and_then(|e| e.get("grass_color_modifier"))
        .and_then(|v| v.as_str())
        .map(str::to_string);

    let mut w = NbtWriter::new()
        .root_compound()
        .float("temperature", temp)
        .float("downfall", downfall)
        .byte("has_precipitation", if precip { 1 } else { 0 });
    w = w.compound("effects", |mut w| {
        for (key, target) in [
            ("water_color", "water_color"),
            ("foliage_color", "foliage_color"),
            ("grass_color", "grass_color"),
            ("dry_foliage_color", "dry_foliage_color"),
        ] {
            if let Some(v) = color(key) {
                w = w.string(target, &v);
            }
        }
        if let Some(m) = &modifier {
            w = w.string("grass_color_modifier", m);
        }
        w
    });
    w = w.compound("attributes", |mut w| {
        for (key, target) in [
            ("sky_color", "minecraft:visual/sky_color"),
            ("fog_color", "minecraft:visual/fog_color"),
            ("water_fog_color", "minecraft:visual/water_fog_color"),
        ] {
            if let Some(v) = color(key) {
                w = w.string(target, &v);
            }
        }
        w
    });
    w = w
        .empty_list("carvers", nbt::TAG_STRING)
        .empty_list("features", nbt::TAG_LIST)
        .end();
    Some(w.into_bytes())
}

/// Step-by-step parse report for one chunk payload (debugging aid).
pub fn dissect(payload: &[u8]) -> String {
    let mut out = String::new();
    let mut r = Reader::new(payload);
    let x = r.i32();
    let z = r.i32();
    out.push_str(&format!("x={x:?} z={z:?}\n"));
    let maps = r.varint();
    out.push_str(&format!("heightmaps={maps:?}\n"));
    if let Ok(m) = maps {
        if (0..64).contains(&m) {
            for _ in 0..m {
                let _ = r.varint();
                if let Ok(len) = r.varint() {
                    if len >= 0 {
                        let _ = r.bytes(len as usize * 8);
                    }
                }
            }
        }
    }
    let len = r.varint();
    out.push_str(&format!("buffer_len={len:?} at {}\n", r.pos()));
    let Ok(len) = len else { return out };
    let Ok(buf) = r.bytes(len as usize) else {
        return out;
    };
    out.push_str(&format!(
        "buffer bytes={} tail={}\n",
        buf.len(),
        r.remaining()
    ));
    let mut b = Reader::new(buf);
    for i in 0..OVERWORLD_SECTIONS {
        let p0 = b.pos();
        let n = b.u16();
        let f = b.u16();
        let block = skip_container(&mut b, 4096);
        if block.is_none() {
            out.push_str(&format!("section {i}: block container failed at {p0}\n"));
            return out;
        }
        let bio_at = b.pos();
        let bits = b.u8();
        let Ok(bits) = bits else {
            out.push_str(&format!("section {i}: biome bits missing at {bio_at}\n"));
            return out;
        };
        let a = b.varint();
        out.push_str(&format!(
            "section {i}: n={n:?} f={f:?} block_ok bio_bits={bits} a={a:?} at={bio_at}\n"
        ));
        match a {
            Ok(a) if a > 0 => {
                for _ in 0..a {
                    if b.varint().is_err() {
                        out.push_str("  palette fail\n");
                        return out;
                    }
                }
            }
            Ok(_) => {}
            Err(_) => {
                out.push_str("  a fail\n");
                return out;
            }
        }
        let Some(cnt) = longs_for(bits, 64) else {
            out.push_str("  bad bits\n");
            return out;
        };
        out.push_str(&format!("  data_longs={cnt}\n"));
        if b.bytes(cnt * 8).is_err() {
            out.push_str("  data fail\n");
            return out;
        }
    }
    out.push_str(&format!("end={} remaining={}\n", buf.len(), b.remaining()));
    out
}

/// The 26.3 biome definition as JSON (same shape as the codec's NBT form).
///
/// The plugin registers these into Paper's server biome registry, so the
/// biome is a real registry entry: `/locate biome` suggestions, chunk biome
/// ids and the client's registry sync all come from the server, not from a
/// wire overlay.
pub fn biome_json(json: &serde_json::Value) -> Option<serde_json::Value> {
    use serde_json::{json as j, Map, Value};
    let temp = json.get("temperature").and_then(|v| v.as_f64())?;
    let downfall = json.get("downfall").and_then(|v| v.as_f64()).unwrap_or(0.0);
    let precip = json
        .get("has_precipitation")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let effects = json.get("effects");
    let color = |key: &str| -> Option<String> {
        let v = effects?.get(key)?;
        if let Some(s) = v.as_str() {
            return Some(s.to_string());
        }
        let n = v.as_u64()?;
        Some(format!("#{:06x}", n & 0xFF_FFFF))
    };
    let mut out = Map::new();
    out.insert("temperature".into(), Value::from(temp));
    out.insert("downfall".into(), Value::from(downfall));
    out.insert("has_precipitation".into(), Value::from(precip));
    let mut eff = Map::new();
    for (key, target) in [
        ("water_color", "water_color"),
        ("foliage_color", "foliage_color"),
        ("grass_color", "grass_color"),
        ("dry_foliage_color", "dry_foliage_color"),
    ] {
        if let Some(v) = color(key) {
            eff.insert(target.into(), Value::String(v));
        }
    }
    if let Some(m) = effects
        .and_then(|e| e.get("grass_color_modifier"))
        .and_then(|v| v.as_str())
    {
        eff.insert("grass_color_modifier".into(), Value::String(m.to_string()));
    }
    out.insert("effects".into(), Value::Object(eff));
    let mut attrs = Map::new();
    for (key, target) in [
        ("sky_color", "minecraft:visual/sky_color"),
        ("fog_color", "minecraft:visual/fog_color"),
        ("water_fog_color", "minecraft:visual/water_fog_color"),
    ] {
        if let Some(v) = color(key) {
            attrs.insert(target.into(), Value::String(v));
        }
    }
    out.insert("attributes".into(), Value::Object(attrs));
    // 26.3 encodes carvers as a LIST of carver ids (1.21.1 used a map).
    out.insert("carvers".into(), j!([]));
    out.insert("features".into(), j!([]));
    Some(Value::Object(out))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn biome(name: &str) -> BiomeDef {
        BiomeDef {
            name: name.to_string(),
            temperature: 0.5,
            downfall: 0.5,
            nbt: NbtWriter::new()
                .root_compound()
                .float("temperature", 0.5)
                .end()
                .into_bytes(),
            json: serde_json::json!({"temperature": 0.5, "downfall": 0.5}),
        }
    }

    fn registry_payload(entries: &[(&str, f32, f32)]) -> Vec<u8> {
        let mut out = Vec::new();
        nbt::write_varint(&mut out, "minecraft:worldgen/biome".len() as i32);
        out.extend_from_slice(b"minecraft:worldgen/biome");
        nbt::write_varint(&mut out, entries.len() as i32);
        for (name, t, d) in entries {
            nbt::write_varint(&mut out, name.len() as i32);
            out.extend_from_slice(name.as_bytes());
            out.push(1);
            out.extend_from_slice(
                &NbtWriter::new()
                    .root_compound()
                    .float("temperature", *t)
                    .float("downfall", *d)
                    .end()
                    .into_bytes(),
            );
        }
        out
    }

    #[test]
    fn registry_parses_climate_and_appends() {
        let payload = registry_payload(&[
            ("minecraft:plains", 0.8, 0.4),
            ("minecraft:taiga", 0.25, 0.8),
        ]);
        let reg = parse_registry(&payload).unwrap().unwrap();
        assert_eq!(reg.entries.len(), 2);
        assert_eq!(reg.entries[0].temperature, Some(0.8));
        let (out, ids) = append_biomes(&payload, &reg, &[biome("biomesoplenty:prairie")]).unwrap();
        assert_eq!(ids["biomesoplenty:prairie"], 2);
        let reg2 = parse_registry(&out).unwrap().unwrap();
        assert_eq!(reg2.entries.len(), 3);
        assert_eq!(reg2.entries[2].name, "biomesoplenty:prairie");
        assert_eq!(reg2.entries[2].temperature, Some(0.5));
        // appending twice must not duplicate
        let (out2, ids2) = append_biomes(&out, &reg2, &[biome("biomesoplenty:prairie")]).unwrap();
        assert_eq!(out2, out);
        assert_eq!(ids2["biomesoplenty:prairie"], 2);
    }

    #[test]
    fn non_biome_registry_is_ignored() {
        let mut payload = Vec::new();
        nbt::write_varint(&mut payload, 8);
        payload.extend_from_slice(b"minecraft:item");
        nbt::write_varint(&mut payload, 0);
        assert!(parse_registry(&payload).unwrap().is_none());
    }
}
