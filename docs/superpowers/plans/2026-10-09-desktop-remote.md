# Desktop remote implementation

Spec: `../specs/2026-10-09-desktop-remote-design.md`. User selected LAN-only connectivity. Implement in the current checkout, preserve existing untracked files, no commit requested.

1. **Native desktop transport** — `desktop/src-tauri/`: Tauri v2 setup, private durable TLS identity, bounded WSS listener, expiring device codes and persistent authenticated binding, request correlation/timeouts, heartbeat, revocation and safe native commands. Validate with Rust unit/transport tests and build checks.
2. **Android receiver** — `android/app/.../remote/`, `Vault.kt`, `AssistantService.kt`, `MainActivity.kt`: exact certificate pin and code parser, encrypted binding, service-owned reconnect lifecycle, Main-thread command router, stable task IDs, state/log responses and settings controls. Unit-test malformed inputs, duplicate/stale requests and lifecycle. Run unit/lint/assemble from `android/`.
3. **Desktop interface** — `desktop/index.html`, `desktop/src/`, npm/Vite configuration: device binding, task dispatch/stop, live state/summary, history and details. No frontend framework is needed for this small workspace. Test meaningful error/success interactions and build assets.
4. **Integration and handoff** — cross-check the shared protocol, review authorization and disconnect races, document launch/build/pairing/troubleshooting in README, run the relevant verification and report exactly what was exercised. Produce a native desktop application bundle if the local platform permits.

The native and Android work can proceed independently against the protocol; UI and documentation proceed alongside them. Final review focuses on untrusted payload rendering, secret exposure, durable binding, stale callbacks, duplicate execution, privacy wording and preservation of local task behavior.
