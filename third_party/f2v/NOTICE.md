# F2V

`paper-plugin/src/main/java/dev/f2v/` is copied from `jefthebuilder/F2V` at 4ae0324,
on the owner's instruction: the bytecode discovery package, `JarAccess` and
`ParallelPackWork`. That repository has no licence file.

Changed for this project: `F2VLog`, `RuntimePlatformVersions`, `DiscoveryManager`,
`ServerCapture` and `IsolatedModelCapture` are small stand-ins for classes that need
a running NeoForge. Registry validation was removed from `ModScanner`, and the fluid
tint lookup from `FluidRenderBytecodeScanner`.
