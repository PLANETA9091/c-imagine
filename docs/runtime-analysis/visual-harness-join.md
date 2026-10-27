# Visual harness join path — 26.3 client bytecode autopsy

Date: 2026-10-28 (session 10-d continuation). Method: `javap -p -c` over the
piston client.jar 26.3 (`e877b6a07acd633fb3bb475002175cec036e7b87`, 41,483,720
bytes). Trigger: visual run 6 (`b884412`) — client reached startup atlas
creation (16:56:43) then 12 minutes of total silence, join-wait timed out.

## 1. Run 6 root cause (measured, not guessed)

`client.out` ends with the STARTUP resource reload (`Reloading ResourceManager:
vanilla, vanilla` → SoundSystem error (non-fatal) → 12 atlas `Created:` lines).
Nothing else — no connect logs, no `[CHAT]`, no second reload. The client was
alive (12-min wait), rendered nothing new, and never joined.

Autopsy chain (all javap):

1. `QuickPlay.joinMultiplayerWorld(Minecraft, String address)`:
   - `new ServerList(mc)` → `load()` → `ServerList.get(address)`;
   - **entry not found ⇒ `new ServerData(name, ip, Type.OTHER)`** — fresh entry
     whose `packStatus` defaults to `PROMPT` — then `ServerList.add+save`.
2. `ConnectScreen$1` (the connect thread):
   - `DownloadedPackSource.configureForServerControl(conn,
     convertPackStatus(serverData.getResourcePackStatus()))`;
   - `convertPackStatus` (tableswitch on ordinals):
     `ENABLED→PackPromptStatus.ALLOWED`, `DISABLED→DECLINED`, `PROMPT→PENDING`.
3. The server pushed the config-phase pack (server.properties `resource-pack=`);
   the client's `ServerPackManager` with PENDING prompt status builds
   `ClientCommonPacketListenerImpl$PackConfirmScreen` (a `ConfirmScreen`
   subclass: "Do you want to install the server resource pack?" Yes/No) and
   **waits for input**. Under Xvfb nobody clicks. ConfirmScreen logs nothing.
   ⇒ exact run 6 signature: silence after startup reload.

## 2. The fix: pre-seed servers.dat (bytecode-proven, no client patches)

`ServerData$ServerPackStatus.FIELD_CODEC` construction (static block):
`Codec.BOOL.optionalFieldOf("acceptTextures")` xmap'd:
`true→ENABLED`, `false→DISABLED`, absent→PROMPT. The NBT key is the
historical 1.7-era **`acceptTextures`** (TAG_Byte 0/1 in NBT terms).

Full accepted-join path with a seeded entry:

```
servers.dat { servers: [ { name: "c-imagine", ip: "127.0.0.1:25565",
                           acceptTextures: 1 } ] }
→ ServerList.load()     SYNCHRONOUS (NbtIo.read on calling thread;
                        IO_EXECUTOR is save-only) — javap: load() has no
                        executor hop, file read → lambda$load$0 forEach
→ lambda$load$0         "hidden" tag absent → visible list
→ ServerList.get        EXACT ip-field equals("127.0.0.1:25565")
→ packStatus ENABLED    → convertPackStatus → ALLOWED
→ ServerPackManager.pushPack(config-phase push) → acceptPack() immediately:
   download http://127.0.0.1:25580/... → apply → NO prompt screen
```

### servers.dat format drift (26.3-measured)

- **Uncompressed NBT**: `ServerList.load` calls `NbtIo.read(Path)` which is
  `Files.newInputStream` → `NbtIo.read(DataInput, NbtAccounter)` — RAW. GZIP
  lives only in `readCompressed`/`createDecompressorStream`. (Older versions
  shipped gzip servers.dat; 26.3 reads raw. Historical gzip files would fail
  to load with "Couldn't load server list" — caught, empty list.)
- Root compound, empty name; key `servers` = TAG_List of TAG_Compound;
  entry keys: `name` (string), `ip` (string), `icon` (optional base64 blob),
  `acceptTextures` (via FIELD_CODEC bool), `acceptedCodeOfConduct` (int,
  written only when non-zero), `hidden` (bool, splits visible/hidden lists).
- Layout produced by `scripts/visual/make_servers_dat.py` (selftest asserts
  the exact 78-byte encoding).

## 3. Join markers in client.out (why the wait-loop greps work)

- `ChatComponent` logs every incoming chat/system message at INFO:
  `LOGGER.info("[CHAT] {}")` (or `"[{}] [CHAT] {}"` with the tag name) —
  the stdout mirror carries `[CHAT] cimviewer joined the game`. Proven.
- `ReloadableResourceManager.createReload` logs
  `"Reloading ResourceManager: {}"` with the pack-id list — the SECOND such
  line in client.out is the server pack being applied (first = startup).
- Non-fatal noise measured on runners: OpenAL/ALSA "cannot find card '0'" +
  `libflite.so` UnsatisfiedLinkError (text2speech) — SoundSystem turns off
  sounds, the game continues. Do not treat as failure.

## 4. Harness contract after the fix

`visual.yml` vanilla-client-visual job:
1. pre-launch `make_servers_dat.py visual-client/game` (bytecode-proven seed);
2. wait-loop: `joined the game` (play phase) OR diagnostics; progress tails
   every ~90 s (run 6 lesson: no more blind 12-minute windows);
3. on stall: `Shot shots/stall-state.png` into the artifact before failing —
   the screenshot that would have diagnosed run 6 in one dispatch.
