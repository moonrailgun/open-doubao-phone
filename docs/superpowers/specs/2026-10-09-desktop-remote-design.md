# Tauri desktop and Android LAN receiver

The user requests a Tauri desktop that binds a phone through a device code, sends tasks and reads replies, summaries, logs and execution records. The user explicitly selected same-LAN operation without a deployed server.

## Architecture

The desktop embeds a TLS WebSocket listener on port 38687. Android connects out using the existing OkHttp dependency. Android's AccessibilityService continues to own TaskController, model credentials, allowed apps, task cancellation, summaries and encrypted history. The desktop never executes phone actions directly or receives model keys.

Use one bound phone per desktop for this first implementation. Pairing requires user action on both devices. The desktop generates a five-minute code containing a selected private IPv4 address, port, SHA-256 certificate fingerprint and random 256-bit token. Android pins the exact certificate before sending credentials. On first authentication the desktop persists the phone ID and token; subsequent connections must match both. Regenerating an unused code invalidates it. Unbinding closes the session and revokes its token. No plaintext fallback or trust-all certificate verification.

Desktop credentials remain in the native application data directory with private permissions and atomic writes. Android stores its connection in the existing Keystore-encrypted Vault. No cloud relay, accounts or model-key synchronization. The phone reconnects while reception and its accessibility service are enabled. Disconnection does not replay tasks; the phone can finish its current task locally.

## Protocol v1

Device code: `open-doubao://pair?v=1&host=<private IPv4>&port=38687&fingerprint=<64 hex>&token=<64 hex>`.

All WebSocket frames are bounded JSON text, at most 256 KiB.

- Phone hello: `{type:"hello",version:1,token,deviceId,name}`. Desktop acknowledgement: `{type:"welcome",version:1}`.
- Desktop request: `{type:"request",id:<UUID>,method,params}`.
- Phone response: `{type:"response",id,ok:true,data}` or `{type:"response",id,ok:false,error:<string>}`.
- Phone state: `{type:"state",data:{taskId,status,goal,message,result,steps,log}}`.
- `task.start {goal}` returns `{taskId}`. Goal is 1–4000 characters. The request UUID is also the task ID; a duplicate retained task ID returns its existing ID and does not execute again.
- `task.stop {taskId}` checks the active ID before stopping, then returns `{taskId}`.
- `task.resume {taskId}` continues the active task only when it is paused, then returns `{taskId}`. The phone must be unlocked.
- `status.get {}` returns the current state.
- `records.list {}` returns up to 30 record previews (goal up to 240 characters, result up to 500), without detailed logs.
- `records.get {taskId}` returns `{id,time,goal,status,result,log}`.
- `records.delete {taskId}` removes one retained record and returns `{taskId}`; refused while a task is running or paused, like the phone's own delete.
- `config.get {}` returns endpoints, model names, language and `hasJevKey`/`hasChatKey`, never keys. `config.set {jevEndpoint,jevModel,jevKey,chatEndpoint,chatModel,chatKey,language}` saves it; a null key keeps the saved key only when its endpoint is unchanged.
- `config.test` takes the same fields, applies the same key rule and asks the phone to call both services once without saving. The desktop waits up to 130 seconds for it.
- `apps.get {}` returns `{apps:[{packageName,label,launchable}],selected}` from the phone's installed-app catalog. `apps.set {apps}` saves a non-empty set of installed packages.
- `tts.get {}` returns `{endpoint,model,voice,hasToken}`, never the token. `tts.set {endpoint,token,model,voice}` saves online playback with the phone's own validation; a null token keeps the saved token only when the endpoint is unchanged, and an empty endpoint clears it for local playback. Allowed while a task runs, like on the phone.
- Configuration, app scope and record deletion are refused while a task is running or paused.

Only these methods are accepted. Phone network callbacks return to Main for Vault and task operations. A single task can run at a time, whether started locally or remotely. Late callbacks after receiver shutdown are ignored. Desktop response timeout is ten seconds (130 for `config.test`) and reports an unknown outcome rather than silently resending. TLS/hello handshake concurrency, timeout and message size are bounded.

## User interface

Desktop: device sidebar with online status, pairing/forget, model configuration with a connection test, voice playback settings, and app scope; task composer with stop and resume controls; live progress, reply and summary rendered from the phone's Markdown subset; history list, a detailed log reader and record deletion. A system notification reports a task that pauses or ends while the window is in the background. Use a restrained blue-gray workspace, native system Chinese type, clear labels, visible keyboard focus and selectable output. Render all device text as text, never HTML.

Phone: settings section for device-code entry, explicit bind, reception toggle, status, and unbind. Existing local tasks and settings remain available. Update privacy wording to explain that a bound desktop can read retained task history and logs.

The existing execution log is the source for access details: application package, reads, actions, timing and exceptions. Do not introduce screenshot archives, page-text collection or browser-history scraping. Existing limits (30 records, bounded logs, 30 task steps/five minutes) continue to apply.

## Validation and constraints

Cover pairing parsing, pin checks, expiry/revocation, authentication, command validation, duplicate IDs, stale stop requests and receiver cancellation with targeted tests. Exercise the native TLS transport with a simulated phone; test desktop interactions with a mocked native bridge. Run Android unit tests, lint and assemble, desktop tests/build and Rust tests/check. Real phone, firewall/Wi-Fi isolation and OS background behavior remain hardware acceptance checks unless explicitly exercised.

No commit or PR was requested. Preserve the initially untracked Android source and existing artifacts; do not stage or commit them.
