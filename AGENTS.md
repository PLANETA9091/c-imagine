# c-imagine repo conventions (for humans and agents)

## Language / commits
- Repo language: **EN**. Code, docs, logs, commit messages.
- Commits: conventional (`feat:`, `fix:`, `docs:`, `ci:`, `test:`, `perf:`, `refactor:`).
- Author: `PLANETA9091 <247359227+PLANETA9091@users.noreply.github.com>` (noreply).
- Private repo; do not publish tokens, do not commit server logs or world data.

## The working loop (mandatory)
1. **Analyze first** — before writing: read the relevant docs + code, re-check
   upstream NeoForge behavior, write down the expected observable behavior.
2. **Implement** the smallest correct slice.
3. **Test** — unit + the CI matrix; visual changes need the visual pipeline, not eyeballs.
4. **Re-analyze** — what regressed, what the profiling report shows; fix before moving on.
5. Nothing advances while the matrix is red. A bug found in any subsystem blocks all
   forward progress until fixed (project rule from the owner).

## Performance law
- The module must not charge Paper extra: measure (profiling.yml) before and after any
  change that touches hot paths (codec, registry, bridge ring, net hooks).
- Budgets live in `docs/ARCHITECTURE.md` §4.6; tightening a budget in CI counts as a
  feature; exceeding one is a `fix:` blocker.
- Prefer zero-copy native paths; allocations in per-packet code are review blockers.

## Code style
- Rust: clippy clean at `-D warnings`; no `unsafe` without a `# Safety` contract;
  every crate has tests; tests must not require a JDK (fixtures are hand-assembled).
- Java (bootstrap + plugin): minimal surface, `-release 21` bytecode, no dependencies
  beyond JDK unless reviewed.
- Mods (test-mods): server-side first, deterministic `[cimtest]` log markers, no
  client classes until M5.

## CI artifacts ownership
- `boot.log`, criterion reports, RSS samples, visual diffs: artifacts only, never
  committed.
- Baselines (criterion) live in the Actions cache; `perf:` PRs must state baseline deltas.

## Relationship to other repos
- `PLANETA9091/CRUSSTY` — the engine; vendored `cplug-abi`/`cplug-sdk` under
  `third_party/` (MIT, notices kept). Engine bugs → upstream repo, not here.
- `jefthebuilder/F2V` — read-only reference. **Never** push code, tests, or issues
  there; its docs are mirrored as needed under `docs/`.
