pub mod bridge;

#[cfg(feature = "desktop")]
mod desktop {
    use crate::bridge::{Bridge, DeviceState, PairingCode, Snapshot};
    use serde_json::Value;
    use std::sync::{Arc, Mutex};
    use tauri::{AppHandle, Emitter, Manager, State};
    use tauri_plugin_notification::NotificationExt;
    use tauri_plugin_opener::OpenerExt;

    #[tauri::command]
    fn get_update_context(app: AppHandle) -> Value {
        serde_json::json!({ "version": app.package_info().version.to_string(),
            "os": std::env::consts::OS, "arch": std::env::consts::ARCH })
    }

    fn release_url_allowed(url: &str) -> bool {
        let Ok(parsed) = tauri::Url::parse(url) else { return false };
        parsed.scheme() == "https" && parsed.host_str() == Some("github.com")
            && parsed.username().is_empty() && parsed.password().is_none() && parsed.port().is_none()
            && (parsed.path().starts_with("/moonrailgun/open-doubao-phone/releases/download/")
                || parsed.path().starts_with("/moonrailgun/open-doubao-phone/releases/tag/"))
    }

    #[tauri::command]
    fn open_release(app: AppHandle, url: String) -> Result<(), String> {
        if !release_url_allowed(&url) {
            return Err("只允许打开本项目的 GitHub Release".into());
        }
        app.opener().open_url(url, None::<&str>).map_err(|error| error.to_string())
    }

    #[test]
    fn release_links_stay_on_the_project() {
        let url = "https://github.com/moonrailgun/open-doubao-phone/releases/download/v1.0.2/Open.Doubao_1.0.2_aarch64.dmg";
        assert!(release_url_allowed(url));
        assert!(release_url_allowed("https://github.com/moonrailgun/open-doubao-phone/releases/tag/v1.0.2"));
        for invalid in [url.replace("https:", "http:"), url.replace("github.com", "github.com.evil.example"),
            url.replace("github.com", "user:secret@github.com"), url.replace("/download/v1.0.2/", "/../../../../"),
            "file:///tmp/installer".into(), "javascript:alert(1)".into()] {
            assert!(!release_url_allowed(&invalid));
        }
    }

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

    #[tauri::command]
    async fn download_calls(
        task_id: String,
        app: tauri::AppHandle,
        bridge: State<'_, Arc<Bridge>>,
    ) -> Result<String, String> {
        let directory = app
            .path()
            .download_dir()
            .map_err(|error| format!("无法定位下载目录：{error}"))?;
        let path = bridge.download_calls(&task_id, &directory).await?;
        Ok(path.to_string_lossy().into_owned())
    }

    pub fn run() {
        tauri::Builder::default()
            .plugin(tauri_plugin_notification::init())
            .plugin(tauri_plugin_opener::init())
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
                forget_device,
                download_calls,
                get_update_context,
                open_release
            ])
            .run(tauri::generate_context!())
            .expect("failed to run Open Doubao desktop");
    }
}

#[cfg(feature = "desktop")]
pub use desktop::run;
