use serde::Serialize;
use serde_json::json;
use std::{
    fs::{self, OpenOptions},
    io::{self, Write},
    path::{Path, PathBuf},
    sync::Mutex,
    time::{SystemTime, UNIX_EPOCH},
};

const MAX_BYTES: u64 = 2 * 1024 * 1024;

pub struct DiagnosticLog {
    directory: PathBuf,
    error: Mutex<Option<String>>,
}

impl DiagnosticLog {
    pub fn new(directory: PathBuf) -> Self {
        Self { directory, error: Mutex::new(None) }
    }

    pub fn error(&self) -> Option<String> {
        self.error.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn directory(&self) -> Result<PathBuf, String> {
        self.record("open_diagnostics", &json!({}));
        if fs::symlink_metadata(&self.directory).is_ok_and(|m| m.is_dir()) {
            Ok(self.directory.clone())
        } else {
            Err(self.error().unwrap_or_else(|| "诊断目录不可用".into()))
        }
    }

    pub fn record(&self, event: &str, data: &impl Serialize) {
        let mut error = self.error.lock().unwrap_or_else(|e| e.into_inner());
        let result = (|| {
            let time = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
            let mut line = serde_json::to_vec(&json!({"time": time, "event": event, "data": data}))?;
            line.push(b'\n');
            self.append(&line)
        })();
        let next = result.err().map(|e| format!("诊断日志写入失败：{e}"));
        if next != *error {
            if let Some(message) = &next { eprintln!("{message}"); }
        }
        *error = next;
    }

    fn append(&self, line: &[u8]) -> io::Result<()> {
        if line.len() as u64 > MAX_BYTES {
            return Err(io::Error::other("单条诊断记录过大"));
        }
        check_path(&self.directory, true)?;
        let mut builder = fs::DirBuilder::new();
        builder.recursive(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        builder.create(&self.directory)?;
        let current = self.directory.join("desktop.jsonl");
        let previous = self.directory.join("desktop.previous.jsonl");
        check_path(&current, false)?;
        check_path(&previous, false)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&self.directory, fs::Permissions::from_mode(0o700))?;
            for path in [&current, &previous] {
                if path.exists() { fs::set_permissions(path, fs::Permissions::from_mode(0o600))?; }
            }
        }
        if fs::metadata(&current).map(|m| m.len()).unwrap_or(0) + line.len() as u64 > MAX_BYTES {
            match fs::remove_file(&previous) {
                Ok(()) => (),
                Err(e) if e.kind() == io::ErrorKind::NotFound => (),
                Err(e) => return Err(e),
            }
            fs::rename(&current, &previous)?;
        }
        let mut options = OpenOptions::new();
        options.append(true).create(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(current)?;
        let length = file.metadata()?.len();
        if let Err(error) = file.write_all(line) {
            // A failed write must not leave half a JSON record before the next append.
            let _ = file.set_len(length);
            return Err(error);
        }
        Ok(())
    }
}

fn check_path(path: &Path, directory: bool) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(m) if if directory { m.is_dir() } else { m.is_file() } => Ok(()),
        Ok(_) => Err(io::Error::other("诊断路径不是普通文件或目录")),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn rotates_bounded_json_lines_and_keeps_latest_across_restart() {
        let directory = tempfile::tempdir().unwrap();
        let log = DiagnosticLog::new(directory.path().join("diagnostics"));
        let payload = "界".repeat(100_000);
        for sequence in 0..20 { log.record("test", &json!({"sequence": sequence, "text": payload})); }
        let restarted = DiagnosticLog::new(log.directory.clone());
        restarted.record("restart", &json!({}));
        assert_eq!(restarted.error(), None);
        let mut records = Vec::new();
        for name in ["desktop.previous.jsonl", "desktop.jsonl"] {
            let path = log.directory.join(name);
            assert!(fs::metadata(&path).unwrap().len() <= MAX_BYTES);
            records.extend(fs::read_to_string(path).unwrap().lines().map(|line| serde_json::from_str::<Value>(line).unwrap()));
        }
        assert_eq!(records.last().unwrap()["event"], "restart");
        assert_eq!(records[records.len() - 2]["data"]["sequence"], 19);
        assert_eq!(fs::read_dir(&log.directory).unwrap().count(), 2);
    }

    #[test]
    fn write_failure_is_visible_and_recovers_without_overwriting_other_files() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("diagnostics");
        fs::write(&path, "preserve").unwrap();
        let log = DiagnosticLog::new(path.clone());
        assert!(log.directory().unwrap_err().contains("诊断日志写入失败"));
        assert_eq!(fs::read_to_string(&path).unwrap(), "preserve");
        fs::remove_file(&path).unwrap();
        assert_eq!(log.directory().unwrap(), path);
        assert_eq!(log.error(), None);
        log.record("oversized", &"x".repeat(MAX_BYTES as usize));
        assert!(log.error().unwrap().contains("过大"));
        assert_eq!(fs::read_to_string(path.join("desktop.jsonl")).unwrap().lines().count(), 1);
        fs::remove_file(path.join("desktop.jsonl")).unwrap();
        fs::create_dir(path.join("desktop.jsonl")).unwrap();
        assert_eq!(log.directory().unwrap(), path);
        assert!(log.error().is_some());
    }

    #[cfg(unix)]
    #[test]
    fn paths_are_private_and_symlinks_are_not_followed() {
        use std::os::unix::fs::{symlink, PermissionsExt};
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("diagnostics");
        let log = DiagnosticLog::new(path.clone());
        log.record("test", &json!({}));
        for (target, mode) in [(&path, 0o700), (&path.join("desktop.jsonl"), 0o600)] {
            assert_eq!(fs::metadata(target).unwrap().permissions().mode() & 0o777, mode);
        }
        let outside = directory.path().join("outside");
        fs::write(&outside, "preserve").unwrap();
        for name in ["desktop.jsonl", "desktop.previous.jsonl"] {
            let file = path.join(name);
            let _ = fs::remove_file(&file);
            symlink(&outside, &file).unwrap();
            log.record("test", &json!({}));
            assert!(log.error().is_some());
            assert_eq!(fs::read_to_string(&outside).unwrap(), "preserve");
            fs::remove_file(file).unwrap();
        }
        fs::remove_dir(&path).unwrap();
        symlink(directory.path(), &path).unwrap();
        assert!(log.directory().is_err());
    }
}
