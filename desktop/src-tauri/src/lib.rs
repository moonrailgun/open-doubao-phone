pub mod bridge;

#[cfg(feature = "desktop")]
mod desktop {
    use crate::bridge::{Bridge, PairingCode, Snapshot};
    use serde_json::Value;
    use std::sync::Arc;
    use tauri::{Emitter, Manager, State};

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
            .setup(|app| {
                let handle = app.handle().clone();
                let bridge = Bridge::new(
                    app.path().app_data_dir()?,
                    Arc::new(move |snapshot| {
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
            .expect("failed to run Open Odoubao desktop");
    }
}

#[cfg(feature = "desktop")]
pub use desktop::run;
