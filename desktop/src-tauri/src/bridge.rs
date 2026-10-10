use futures_util::{SinkExt, StreamExt};
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs::{self, OpenOptions},
    io::Write,
    net::{IpAddr, Ipv4Addr},
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use subtle::ConstantTimeEq;
use tokio::{
    net::{TcpListener, TcpStream},
    sync::{mpsc, oneshot, Mutex, Semaphore},
    time::{interval, timeout, Instant},
};
use tokio_rustls::{
    rustls::{
        pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
        ServerConfig,
    },
    TlsAcceptor,
};
use tokio_tungstenite::{
    accept_async_with_config,
    tungstenite::{
        protocol::{frame::coding::CloseCode, CloseFrame, WebSocketConfig},
        Message,
    },
};
use uuid::Uuid;

pub const PORT: u16 = 38687;
const PAIRING_LIFETIME_MS: u64 = 5 * 60 * 1000;
const MAX_MESSAGE: usize = 256 * 1024;
const CONNECTION_LOST: &str = "手机连接已断开，任务执行状态未知；请重连后查看状态，不要重复下发";

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Device {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceState {
    pub task_id: Option<String>,
    pub status: String,
    pub goal: String,
    pub message: String,
    pub result: String,
    pub steps: u64,
    pub log: Vec<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct Snapshot {
    pub listening: bool,
    pub addresses: Vec<String>,
    pub port: u16,
    pub device: Option<Device>,
    pub connected: bool,
    pub state: Option<DeviceState>,
    pub error: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingCode {
    pub code: String,
    pub expires_at: u64,
}

#[derive(Clone, Serialize, Deserialize)]
struct Binding {
    device: Device,
    token: String,
}

#[derive(Clone, Serialize, Deserialize)]
struct Credentials {
    certificate: Vec<u8>,
    private_key: Vec<u8>,
    binding: Option<Binding>,
}

struct PendingPairing {
    token: String,
    expires_at: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Hello {
    #[serde(rename = "type")]
    kind: String,
    version: u32,
    token: String,
    device_id: String,
    name: String,
}

struct Connection {
    id: Uuid,
    sender: mpsc::Sender<Message>,
    close: oneshot::Sender<()>,
}

struct Inner {
    credentials: Option<Credentials>,
    pairing: Option<PendingPairing>,
    connection: Option<Connection>,
    pending: HashMap<String, oneshot::Sender<Result<Value, String>>>,
    listening: bool,
    state: Option<DeviceState>,
    error: Option<String>,
}

pub struct Bridge {
    path: PathBuf,
    inner: Mutex<Inner>,
    notify: Arc<dyn Fn(Snapshot) + Send + Sync>,
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn token() -> String {
    let mut bytes = [0; 32];
    OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

fn valid_token(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn same_token(left: &str, right: &str) -> bool {
    valid_token(left) && bool::from(left.as_bytes().ct_eq(right.as_bytes()))
}

fn private_address(address: IpAddr) -> bool {
    matches!(address, IpAddr::V4(address) if address.is_private() || address.is_link_local())
}

fn addresses() -> Vec<String> {
    let mut result: Vec<_> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .map(|interface| interface.ip())
        .filter(|address| private_address(*address))
        .map(|address| address.to_string())
        .collect();
    result.sort();
    result.dedup();
    result
}

fn tls_config(credentials: &Credentials) -> Result<Arc<ServerConfig>, String> {
    let provider = tokio_rustls::rustls::crypto::ring::default_provider();
    let config = ServerConfig::builder_with_provider(Arc::new(provider))
        .with_safe_default_protocol_versions()
        .map_err(|error| error.to_string())?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(credentials.certificate.clone())],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(credentials.private_key.clone())),
        )
        .map_err(|error| format!("桌面证书损坏：{error}"))?;
    Ok(Arc::new(config))
}

fn save_credentials(path: &Path, credentials: &Credentials) -> Result<(), String> {
    if fs::symlink_metadata(path).is_ok_and(|metadata| metadata.file_type().is_symlink()) {
        return Err("拒绝写入凭据符号链接".into());
    }
    let temporary = path.with_extension(format!("{}.tmp", Uuid::new_v4()));
    let result = (|| {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temporary)?;
        file.write_all(&serde_json::to_vec(credentials)?)?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        #[cfg(unix)]
        fs::File::open(path.parent().unwrap())?.sync_all()?;
        Ok::<_, std::io::Error>(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result.map_err(|error| format!("无法保存桌面凭据：{error}"))
}

fn load_credentials(directory: &Path) -> Result<Credentials, String> {
    fs::create_dir_all(directory).map_err(|error| error.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(directory, fs::Permissions::from_mode(0o700))
            .map_err(|error| error.to_string())?;
    }
    let path = directory.join("credentials.json");
    match fs::symlink_metadata(&path) {
        Ok(metadata) => {
            if !metadata.is_file() || metadata.len() > 64 * 1024 {
                return Err("桌面凭据文件异常，未覆盖原文件".into());
            }
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(&path, fs::Permissions::from_mode(0o600))
                    .map_err(|error| error.to_string())?;
            }
            let credentials: Credentials =
                serde_json::from_slice(&fs::read(&path).map_err(|error| error.to_string())?)
                    .map_err(|_| "桌面凭据文件损坏，未覆盖原文件".to_string())?;
            tls_config(&credentials)?;
            if credentials.binding.as_ref().is_some_and(|binding| {
                !valid_token(&binding.token)
                    || Uuid::parse_str(&binding.device.id).is_err()
                    || binding.device.name.trim().is_empty()
                    || binding.device.name.len() > 256
            }) {
                return Err("已保存的绑定信息损坏，未覆盖原文件".into());
            }
            Ok(credentials)
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let mut names = addresses();
            names.push("localhost".to_string());
            let certified =
                rcgen::generate_simple_self_signed(names).map_err(|error| error.to_string())?;
            let credentials = Credentials {
                certificate: certified.cert.der().to_vec(),
                private_key: certified.signing_key.serialize_der(),
                binding: None,
            };
            save_credentials(&path, &credentials)?;
            Ok(credentials)
        }
        Err(error) => Err(format!("无法读取桌面凭据：{error}")),
    }
}

fn authorize(inner: &Inner, hello: &Hello, now: u64) -> Result<bool, String> {
    if hello.kind != "hello"
        || hello.version != 1
        || Uuid::parse_str(&hello.device_id).is_err()
        || hello.name.trim().is_empty()
        || hello.name.len() > 256
        || !valid_token(&hello.token)
    {
        return Err("无效的设备身份或协议版本".into());
    }
    let credentials = inner.credentials.as_ref().ok_or("桌面凭据不可用")?;
    if let Some(binding) = &credentials.binding {
        return if binding.device.id == hello.device_id && same_token(&hello.token, &binding.token) {
            Ok(false)
        } else {
            Err("设备未获授权，请在桌面解绑后重新配对".into())
        };
    }
    match &inner.pairing {
        Some(pairing) if pairing.expires_at > now && same_token(&hello.token, &pairing.token) => {
            Ok(true)
        }
        _ => Err("设备码无效或已过期，请重新生成".into()),
    }
}

fn validate_request(method: &str, params: &Value) -> Result<(), String> {
    let object = params.as_object().ok_or("参数必须是对象")?;
    match method {
        "task.start" if object.len() == 1 => {
            let goal = object
                .get("goal")
                .and_then(Value::as_str)
                .ok_or("缺少任务目标")?;
            if goal.trim().is_empty() || goal.chars().count() > 4000 {
                return Err("任务目标长度必须为 1–4000 个字符".into());
            }
        }
        "task.stop" | "records.get" if object.len() == 1 => {
            let task_id = object
                .get("taskId")
                .and_then(Value::as_str)
                .ok_or("缺少任务 ID")?;
            if task_id.is_empty() || task_id.len() > 128 {
                return Err("任务 ID 无效".into());
            }
        }
        "records.list" | "status.get" | "config.get" if object.is_empty() => {}
        "config.set" if object.len() == 7 => {
            for (field, limit) in [
                ("jevEndpoint", 2048),
                ("chatEndpoint", 2048),
                ("jevModel", 200),
                ("chatModel", 200),
                ("language", 64),
                ("jevKey", 8192),
                ("chatKey", 8192),
            ] {
                let value = object.get(field).ok_or("模型配置字段不完整")?;
                if matches!(field, "jevKey" | "chatKey") && value.is_null() {
                    continue;
                }
                let text = value.as_str().ok_or("模型配置字段类型错误")?;
                if text.chars().count() > limit {
                    return Err("模型配置字段过长".into());
                }
            }
        }
        _ => return Err("不支持的请求或参数".into()),
    }
    Ok(())
}

fn disconnect(inner: &mut Inner, reason: &str) {
    if let Some(connection) = inner.connection.take() {
        let _ = connection.close.send(());
    }
    for (_, response) in inner.pending.drain() {
        let _ = response.send(Err(reason.to_string()));
    }
}

impl Bridge {
    pub fn new(directory: PathBuf, notify: Arc<dyn Fn(Snapshot) + Send + Sync>) -> Arc<Self> {
        let loaded = load_credentials(&directory);
        let error = loaded.as_ref().err().cloned();
        Arc::new(Self {
            path: directory.join("credentials.json"),
            inner: Mutex::new(Inner {
                credentials: loaded.ok(),
                pairing: None,
                connection: None,
                pending: HashMap::new(),
                listening: false,
                state: None,
                error,
            }),
            notify,
        })
    }

    pub async fn snapshot(&self) -> Snapshot {
        let inner = self.inner.lock().await;
        Snapshot {
            listening: inner.listening,
            addresses: addresses(),
            port: PORT,
            device: inner
                .credentials
                .as_ref()
                .and_then(|credentials| credentials.binding.as_ref())
                .map(|binding| binding.device.clone()),
            connected: inner.connection.is_some(),
            state: inner.state.clone(),
            error: inner.error.clone(),
        }
    }

    async fn publish(&self) {
        (self.notify)(self.snapshot().await);
    }

    pub async fn create_pairing(&self, host: &str) -> Result<PairingCode, String> {
        if !addresses().iter().any(|address| address == host) {
            return Err("请选择当前电脑的局域网 IPv4 地址".into());
        }
        let mut inner = self.inner.lock().await;
        if !inner.listening {
            return Err(inner.error.clone().unwrap_or("局域网服务尚未启动".into()));
        }
        let credentials = inner.credentials.as_ref().ok_or("桌面凭据不可用")?;
        if credentials.binding.is_some() {
            return Err("已绑定手机，请先解绑再连接其他设备".into());
        }
        let fingerprint = hex::encode(Sha256::digest(&credentials.certificate));
        let token = token();
        let expires_at = now_ms() + PAIRING_LIFETIME_MS;
        let code = format!(
            "odoubao://pair?v=1&host={host}&port={PORT}&fingerprint={fingerprint}&token={token}"
        );
        inner.pairing = Some(PendingPairing { token, expires_at });
        Ok(PairingCode { code, expires_at })
    }

    pub async fn forget_device(&self) -> Result<(), String> {
        let mut inner = self.inner.lock().await;
        let mut credentials = inner.credentials.clone().ok_or("桌面凭据不可用")?;
        credentials.binding = None;
        save_credentials(&self.path, &credentials)?;
        inner.credentials = Some(credentials);
        inner.pairing = None;
        disconnect(&mut inner, "设备已解绑，任务执行状态未知");
        inner.state = None;
        inner.error = None;
        drop(inner);
        self.publish().await;
        Ok(())
    }

    pub async fn request(&self, method: &str, params: Value) -> Result<Value, String> {
        validate_request(method, &params)?;
        let id = Uuid::new_v4().to_string();
        let receiver = {
            let mut inner = self.inner.lock().await;
            if inner.pending.len() >= 16 {
                return Err("请求过多，请稍后再试".into());
            }
            let sender = inner
                .connection
                .as_ref()
                .ok_or("手机未连接")?
                .sender
                .clone();
            let (response, receiver) = oneshot::channel();
            sender
                .try_send(Message::Text(
                    json!({"type":"request", "id":id, "method":method, "params":params})
                        .to_string()
                        .into(),
                ))
                .map_err(|_| "发送队列不可用，请检查手机连接".to_string())?;
            inner.pending.insert(id.clone(), response);
            receiver
        };
        match timeout(Duration::from_secs(10), receiver).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(CONNECTION_LOST.into()),
            Err(_) => {
                self.inner.lock().await.pending.remove(&id);
                Err("手机 10 秒内未回复，任务执行状态未知；请查看当前状态，不要重复下发".into())
            }
        }
    }

    pub async fn serve(self: Arc<Self>) {
        if let Err(error) = self.serve_inner().await {
            let mut inner = self.inner.lock().await;
            inner.listening = false;
            inner.error = Some(error);
            disconnect(&mut inner, CONNECTION_LOST);
            drop(inner);
            self.publish().await;
        }
    }

    async fn serve_inner(self: &Arc<Self>) -> Result<(), String> {
        let config = {
            let inner = self.inner.lock().await;
            tls_config(
                inner
                    .credentials
                    .as_ref()
                    .ok_or_else(|| inner.error.clone().unwrap_or("桌面凭据不可用".into()))?,
            )?
        };
        let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, PORT))
            .await
            .map_err(|error| format!("无法监听局域网端口 {PORT}：{error}"))?;
        self.inner.lock().await.listening = true;
        self.publish().await;
        let acceptor = TlsAcceptor::from(config);
        let handshakes = Arc::new(Semaphore::new(4));
        loop {
            let (stream, address) = listener.accept().await.map_err(|error| error.to_string())?;
            if !private_address(address.ip()) {
                continue;
            }
            let Ok(permit) = handshakes.clone().try_acquire_owned() else {
                continue;
            };
            let bridge = self.clone();
            let acceptor = acceptor.clone();
            tokio::spawn(async move {
                bridge.handle_socket(stream, acceptor, permit).await;
            });
        }
    }

    async fn handle_socket(
        self: Arc<Self>,
        stream: TcpStream,
        acceptor: TlsAcceptor,
        permit: tokio::sync::OwnedSemaphorePermit,
    ) {
        let handshake = async {
            let tls = acceptor.accept(stream).await.map_err(|_| ())?;
            let config = WebSocketConfig::default()
                .max_message_size(Some(MAX_MESSAGE))
                .max_frame_size(Some(MAX_MESSAGE));
            let mut socket = accept_async_with_config(tls, Some(config))
                .await
                .map_err(|_| ())?;
            let message = socket.next().await.ok_or(())?.map_err(|_| ())?;
            let hello = match message {
                Message::Text(text) => {
                    serde_json::from_str::<Hello>(&text).map_err(|_| "无效的设备身份".to_string())
                }
                _ => Err("请先发送设备身份".into()),
            };
            let (sender, receiver) = mpsc::channel(16);
            let (close, closed) = oneshot::channel();
            let id = Uuid::new_v4();
            let authenticated = match hello {
                Ok(hello) => self.authenticate(&hello, id, sender, close).await,
                Err(error) => Err(error),
            };
            if let Err(error) = authenticated {
                let _ = socket
                    .close(Some(CloseFrame {
                        code: CloseCode::Policy,
                        reason: error.into(),
                    }))
                    .await;
                return Err(());
            }
            Ok((socket, id, receiver, closed))
        };
        let Ok(Ok((mut socket, id, mut outgoing, mut closed))) =
            timeout(Duration::from_secs(10), handshake).await
        else {
            return;
        };
        drop(permit);
        self.publish().await;
        // Authentication is persisted before welcome, so a lost welcome can reconnect safely.
        let welcome = Message::Text(json!({"type":"welcome", "version":1}).to_string().into());
        if !matches!(
            timeout(Duration::from_secs(5), socket.send(welcome)).await,
            Ok(Ok(()))
        ) {
            self.connection_ended(id).await;
            return;
        }
        let mut heartbeat = interval(Duration::from_secs(15));
        let mut last_seen = Instant::now();
        loop {
            let outgoing_message = tokio::select! {
                _ = &mut closed => break,
                _ = heartbeat.tick() => {
                    if last_seen.elapsed() > Duration::from_secs(45) { break; }
                    Some(Message::Ping(Vec::new().into()))
                }
                message = outgoing.recv() => message,
                incoming = socket.next() => {
                    match incoming {
                        Some(Ok(Message::Text(text))) => {
                            last_seen = Instant::now();
                            if self.receive(id, &text).await.is_err() { break; }
                        }
                        Some(Ok(Message::Ping(data))) => { last_seen = Instant::now();
                            if !matches!(timeout(Duration::from_secs(5), socket.send(Message::Pong(data))).await, Ok(Ok(()))) { break; }
                        }
                        Some(Ok(Message::Pong(_))) => last_seen = Instant::now(),
                        _ => break,
                    }
                    continue;
                }
            };
            let Some(message) = outgoing_message else {
                break;
            };
            if !matches!(
                timeout(Duration::from_secs(5), socket.send(message)).await,
                Ok(Ok(()))
            ) {
                break;
            }
        }
        let _ = timeout(
            Duration::from_secs(1),
            socket.close(Some(CloseFrame {
                code: CloseCode::Normal,
                reason: "".into(),
            })),
        )
        .await;
        self.connection_ended(id).await;
    }

    async fn authenticate(
        &self,
        hello: &Hello,
        id: Uuid,
        sender: mpsc::Sender<Message>,
        close: oneshot::Sender<()>,
    ) -> Result<(), String> {
        let mut inner = self.inner.lock().await;
        if authorize(&inner, hello, now_ms())? {
            let mut credentials = inner.credentials.clone().ok_or("桌面凭据不可用")?;
            credentials.binding = Some(Binding {
                device: Device {
                    id: hello.device_id.clone(),
                    name: hello.name.clone(),
                },
                token: hello.token.clone(),
            });
            save_credentials(&self.path, &credentials)?;
            inner.credentials = Some(credentials);
            inner.pairing = None;
        }
        disconnect(&mut inner, CONNECTION_LOST);
        inner.connection = Some(Connection { id, sender, close });
        inner.state = None;
        inner.error = None;
        Ok(())
    }

    async fn receive(&self, connection_id: Uuid, text: &str) -> Result<(), ()> {
        let value: Value = serde_json::from_str(text).map_err(|_| ())?;
        let mut inner = self.inner.lock().await;
        if inner.connection.as_ref().map(|connection| connection.id) != Some(connection_id) {
            return Err(());
        }
        match value.get("type").and_then(Value::as_str) {
            Some("state") => {
                let state: DeviceState =
                    serde_json::from_value(value.get("data").ok_or(())?.clone()).map_err(|_| ())?;
                inner.state = Some(state);
                drop(inner);
                self.publish().await;
            }
            Some("response") => {
                let id = value.get("id").and_then(Value::as_str).ok_or(())?;
                let result = match value.get("ok").and_then(Value::as_bool) {
                    Some(true) => Ok(value.get("data").ok_or(())?.clone()),
                    Some(false) => Err(value
                        .get("error")
                        .and_then(Value::as_str)
                        .ok_or(())?
                        .to_string()),
                    None => return Err(()),
                };
                if let Some(sender) = inner.pending.remove(id) {
                    let _ = sender.send(result);
                }
            }
            _ => return Err(()),
        }
        Ok(())
    }

    async fn connection_ended(&self, id: Uuid) {
        let mut inner = self.inner.lock().await;
        if inner.connection.as_ref().map(|connection| connection.id) == Some(id) {
            disconnect(&mut inner, CONNECTION_LOST);
            inner.error = Some("手机连接已断开，等待手机重新连接".into());
            drop(inner);
            self.publish().await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio_rustls::{
        rustls::{pki_types::ServerName, ClientConfig, RootCertStore},
        TlsConnector,
    };
    use tokio_tungstenite::{client_async, WebSocketStream};

    fn hello(token: &str) -> Hello {
        Hello {
            kind: "hello".into(),
            version: 1,
            token: token.into(),
            device_id: Uuid::new_v4().to_string(),
            name: "测试手机".into(),
        }
    }

    fn inner() -> Inner {
        Inner {
            credentials: Some(Credentials {
                certificate: vec![],
                private_key: vec![],
                binding: None,
            }),
            pairing: Some(PendingPairing {
                token: "a".repeat(64),
                expires_at: 2000,
            }),
            connection: None,
            pending: HashMap::new(),
            listening: true,
            state: None,
            error: None,
        }
    }

    #[test]
    fn pairing_requires_valid_unexpired_token_and_version() {
        let inner = inner();
        let mut hello = hello(&"a".repeat(64));
        assert_eq!(authorize(&inner, &hello, 1999), Ok(true));
        assert!(authorize(&inner, &hello, 2000).is_err());
        hello.token = "b".repeat(64);
        assert!(authorize(&inner, &hello, 1000).is_err());
        hello.token = "a".repeat(64);
        hello.version = 2;
        assert!(authorize(&inner, &hello, 1000).is_err());
    }

    #[test]
    fn consumed_code_only_reconnects_the_same_device() {
        let mut inner = inner();
        let mut hello = hello(&"a".repeat(64));
        inner.credentials.as_mut().unwrap().binding = Some(Binding {
            device: Device {
                id: hello.device_id.clone(),
                name: hello.name.clone(),
            },
            token: hello.token.clone(),
        });
        inner.pairing = None;
        assert_eq!(authorize(&inner, &hello, u64::MAX), Ok(false));
        hello.device_id = Uuid::new_v4().to_string();
        assert!(authorize(&inner, &hello, 1000).is_err());
        inner.pairing = Some(PendingPairing {
            token: hello.token.clone(),
            expires_at: 2000,
        });
        assert!(authorize(&inner, &hello, 1000).is_err());
    }

    #[test]
    fn regenerating_pairing_revokes_previous_code() {
        let mut inner = inner();
        let old = hello(&"a".repeat(64));
        inner.pairing = Some(PendingPairing {
            token: "b".repeat(64),
            expires_at: 2000,
        });
        assert!(authorize(&inner, &old, 1000).is_err());
        assert_eq!(authorize(&inner, &hello(&"b".repeat(64)), 1000), Ok(true));
    }

    #[test]
    fn only_private_ipv4_peers_are_allowed() {
        for ip in ["192.168.1.2", "10.0.1.4", "172.16.1.2", "169.254.1.1"] {
            assert!(private_address(ip.parse().unwrap()));
        }
        for ip in [
            "127.0.0.1",
            "8.8.8.8",
            "172.32.0.1",
            "0.0.0.0",
            "::1",
            "fd00::1",
        ] {
            assert!(!private_address(ip.parse().unwrap()));
        }
    }

    #[test]
    fn request_allowlist_checks_parameters() {
        assert!(validate_request("shell.exec", &json!({})).is_err());
        assert!(validate_request("task.start", &json!({"goal":" "})).is_err());
        assert!(validate_request("task.start", &json!({"goal":"中".repeat(4001)})).is_err());
        assert!(validate_request("task.start", &json!({"goal":"打开设置"})).is_ok());
        assert!(validate_request("task.start", &json!({"goal":"x", "extra":1})).is_err());
        assert!(validate_request("task.stop", &json!({})).is_err());
        assert!(validate_request("records.get", &json!({"taskId":"record-1"})).is_ok());
        assert!(validate_request("records.list", &json!({})).is_ok());
        assert!(validate_request("config.get", &json!({})).is_ok());
        assert!(validate_request("config.get", &json!({"keys":true})).is_err());
        let mut config = json!({"jevEndpoint":"https://jev.example", "jevModel":"jev", "jevKey":null,
            "chatEndpoint":"https://chat.example", "chatModel":"chat", "chatKey":"new-key", "language":""});
        assert!(validate_request("config.set", &config).is_ok());
        config["jevKey"] = json!(5);
        assert!(validate_request("config.set", &config).is_err());
        config["jevKey"] = json!("a".repeat(8193));
        assert!(validate_request("config.set", &config).is_err());
        config.as_object_mut().unwrap().remove("jevKey");
        config["extra"] = Value::Null;
        assert!(validate_request("config.set", &config).is_err());
    }

    #[test]
    fn credentials_survive_restart_and_corruption_is_not_overwritten() {
        let directory = tempfile::tempdir().unwrap();
        let first = load_credentials(directory.path()).unwrap();
        let second = load_credentials(directory.path()).unwrap();
        assert_eq!(first.certificate, second.certificate);
        let path = directory.path().join("credentials.json");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
            assert_eq!(
                fs::metadata(directory.path()).unwrap().permissions().mode() & 0o777,
                0o700
            );
        }
        fs::write(&path, b"corrupted").unwrap();
        assert!(load_credentials(directory.path()).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"corrupted");
    }

    #[tokio::test]
    async fn disconnected_requests_fail_without_queueing() {
        let directory = tempfile::tempdir().unwrap();
        let bridge = Bridge::new(directory.path().into(), Arc::new(|_| {}));
        assert!(bridge
            .request("task.start", json!({"goal":"test"}))
            .await
            .unwrap_err()
            .contains("未连接"));
        assert!(bridge.inner.lock().await.pending.is_empty());
        assert!(bridge.create_pairing("8.8.8.8").await.is_err());
    }

    type TestSocket = WebSocketStream<tokio_rustls::client::TlsStream<TcpStream>>;

    async fn connect(bridge: &Arc<Bridge>) -> TestSocket {
        let credentials = bridge.inner.lock().await.credentials.clone().unwrap();
        let acceptor = TlsAcceptor::from(tls_config(&credentials).unwrap());
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = bridge.clone();
        tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            server
                .handle_socket(
                    stream,
                    acceptor,
                    Arc::new(Semaphore::new(1)).acquire_owned().await.unwrap(),
                )
                .await;
        });
        let mut roots = RootCertStore::empty();
        roots
            .add(CertificateDer::from(credentials.certificate))
            .unwrap();
        let config = ClientConfig::builder_with_provider(Arc::new(
            tokio_rustls::rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_root_certificates(roots)
        .with_no_client_auth();
        let tls = TlsConnector::from(Arc::new(config))
            .connect(
                ServerName::try_from("localhost").unwrap(),
                TcpStream::connect(address).await.unwrap(),
            )
            .await
            .unwrap();
        client_async("wss://localhost/", tls).await.unwrap().0
    }

    async fn send_hello(socket: &mut TestSocket, hello: &Hello) {
        socket
            .send(Message::Text(
                json!({"type":"hello", "version":hello.version, "token":hello.token,
            "deviceId":hello.device_id, "name":hello.name})
                .to_string()
                .into(),
            ))
            .await
            .unwrap();
    }

    async fn next_text(socket: &mut TestSocket) -> Value {
        timeout(Duration::from_secs(2), async {
            loop {
                match socket.next().await.unwrap().unwrap() {
                    Message::Text(text) => return serde_json::from_str(&text).unwrap(),
                    Message::Ping(data) => socket.send(Message::Pong(data)).await.unwrap(),
                    message => panic!("unexpected message: {message:?}"),
                }
            }
        })
        .await
        .unwrap()
    }

    #[tokio::test]
    async fn real_tls_pairing_requests_state_disconnect_reconnect_and_revoke() {
        let directory = tempfile::tempdir().unwrap();
        let bridge = Bridge::new(directory.path().into(), Arc::new(|_| {}));
        let hello = hello(&token());
        bridge.inner.lock().await.pairing = Some(PendingPairing {
            token: hello.token.clone(),
            expires_at: now_ms() + 10_000,
        });
        let mut socket = connect(&bridge).await;
        send_hello(&mut socket, &hello).await;
        assert_eq!(
            next_text(&mut socket).await,
            json!({"type":"welcome", "version":1})
        );
        assert!(bridge.snapshot().await.connected);
        assert!(bridge.inner.lock().await.pairing.is_none());
        let request_bridge = bridge.clone();
        let requested = tokio::spawn(async move {
            request_bridge
                .request("task.start", json!({"goal":"打开设置"}))
                .await
        });
        let request = next_text(&mut socket).await;
        assert_eq!(request["method"], "task.start");
        socket.send(Message::Text(json!({"type":"response", "id":request["id"], "ok":true, "data":{"taskId":"task-1"}}).to_string().into())).await.unwrap();
        assert_eq!(
            requested.await.unwrap().unwrap(),
            json!({"taskId":"task-1"})
        );
        socket.send(Message::Text(json!({"type":"state", "data":{"taskId":"task-1", "status":"completed", "goal":"打开设置", "message":"完成", "result":"已打开", "steps":1, "log":["打开设置"]}}).to_string().into())).await.unwrap();
        timeout(Duration::from_secs(2), async {
            while bridge.snapshot().await.state.is_none() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert_eq!(bridge.snapshot().await.state.unwrap().result, "已打开");
        let request_bridge = bridge.clone();
        let interrupted =
            tokio::spawn(async move { request_bridge.request("status.get", json!({})).await });
        next_text(&mut socket).await;
        socket.close(None).await.unwrap();
        assert!(timeout(Duration::from_secs(2), interrupted)
            .await
            .unwrap()
            .unwrap()
            .unwrap_err()
            .contains("未知"));
        let mut reconnected = connect(&bridge).await;
        send_hello(&mut reconnected, &hello).await;
        assert_eq!(next_text(&mut reconnected).await["type"], "welcome");
        let saved = load_credentials(directory.path()).unwrap();
        assert_eq!(saved.binding.unwrap().device.id, hello.device_id);
        bridge.forget_device().await.unwrap();
        let close = timeout(Duration::from_secs(2), async {
            loop {
                match reconnected.next().await.unwrap().unwrap() {
                    Message::Ping(_) => continue,
                    message => return message,
                }
            }
        })
        .await
        .unwrap();
        assert!(matches!(close, Message::Close(Some(frame)) if frame.code == CloseCode::Normal));
        assert!(!bridge.snapshot().await.connected);
        assert!(bridge.snapshot().await.state.is_none());
        assert!(load_credentials(directory.path())
            .unwrap()
            .binding
            .is_none());
        let mut revoked = connect(&bridge).await;
        send_hello(&mut revoked, &hello).await;
        let message = timeout(Duration::from_secs(2), revoked.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        assert!(matches!(message, Message::Close(Some(frame)) if frame.code == CloseCode::Policy));
    }

    #[tokio::test]
    async fn tls_rejects_invalid_token_with_policy_close() {
        let directory = tempfile::tempdir().unwrap();
        let bridge = Bridge::new(directory.path().into(), Arc::new(|_| {}));
        let mut socket = connect(&bridge).await;
        send_hello(&mut socket, &hello(&token())).await;
        let message = timeout(Duration::from_secs(2), socket.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        assert!(matches!(message, Message::Close(Some(frame)) if frame.code == CloseCode::Policy));
        assert!(!bridge.snapshot().await.connected);
    }
}
