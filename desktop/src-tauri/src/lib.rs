pub mod bridge;

#[cfg(feature = "desktop")]
mod desktop {
    use crate::bridge::{Bridge, DeviceState, PairingCode, Snapshot};
    use serde_json::Value;
    use std::sync::{Arc, Mutex};
    use tauri::{AppHandle, Emitter, Manager, State};
    use tauri_plugin_notification::NotificationExt;

    /// Notifies once when a task seen running pauses or ends while the window is in the background.
    fn announce(handle: &AppHandle, running: &Mutex<Option<String>>, state: Option<&DeviceState>) {
        let mut running = running.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some(state) = state else {
            *running = None;
            return;
        };
        if state.status == "RUNNING" {
            *running = state.task_id.clone();
            return;
        }
        let title = match state.status.as_str() {
            "PAUSED" => "任务已暂停，请在手机上处理",
            "COMPLETED" => "任务已完成",
            "FAILED" => "任务未完成",
            "STOPPED" => "任务已停止",
            _ => return,
        };
        // A task first seen already finished (reconnect, app start) is old news.
        if state.task_id.is_none() || *running != state.task_id {
            return;
        }
        *running = None;
        let focused = handle
            .get_webview_window("main")
            .and_then(|window| window.is_focused().ok())
            .unwrap_or(false);
        if focused {
            return;
        }
        let detail = if state.status == "PAUSED" && !state.message.is_empty() {
            &state.message
        } else {
            &state.goal
        };
        let body: String = detail.chars().take(120).collect();
        let _ = handle.notification().builder().title(title).body(body).show();
    }

    #[tauri::command]
    async fn get_snapshot(bridge: State<'_, Arc<Bridge>>) -> Result<Snapshot, String> {
        Ok(bridge.snapshot().await)
    }

    #[tauri::command]
    async fn create_pairing(
        host: String,
        bridge: State<'_, Arc<Bridge>>,
    ) -> Result<PairingCode, String> {
        bridge.create_pairing(&host).await
    }

    #[tauri::command]
    async fn request(
        method: String,
        params: Value,
        bridge: State<'_, Arc<Bridge>>,
    ) -> Result<Value, String> {
        bridge.request(&method, params).await
    }

    #[tauri::command]
    async fn forget_device(bridge: State<'_, Arc<Bridge>>) -> Result<(), String> {
        bridge.forget_device().await
    }

    pub fn run() {
        tauri::Builder::default()
            .plugin(tauri_plugin_notification::init())
            .setup(|app| {
                let handle = app.handle().clone();
                let running = Mutex::new(None);
                let bridge = Bridge::new(
                    app.path().app_data_dir()?,
                    Arc::new(move |snapshot: Snapshot| {
                        announce(&handle, &running, snapshot.state.as_ref());
                        let _ = handle.emit("desktop-snapshot", snapshot);
                    }),
                );
                app.manage(bridge.clone());
                tauri::async_runtime::spawn(bridge.serve());
                Ok(())
            })
            .invoke_handler(tauri::generate_handler![
                get_snapshot,
                create_pairing,
                request,
                forget_device
            ])
            .run(tauri::generate_context!())
            .expect("failed to run Open Doubao Phone desktop");
    }
}

#[cfg(feature = "desktop")]
pub use desktop::run;
