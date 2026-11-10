use std::collections::VecDeque;
use std::io::Read;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use image::RgbaImage;
use sha2::{Digest, Sha256};

type Job = Box<dyn FnOnce() + Send>;

pub(super) fn enqueue(job: impl FnOnce() + Send + 'static) {
    static QUEUE: OnceLock<std::sync::mpsc::Sender<Job>> = OnceLock::new();
    let queue = QUEUE.get_or_init(|| {
        let (sender, receiver) = std::sync::mpsc::channel::<Job>();
        std::thread::Builder::new()
            .name("whisker-image-cache".into())
            .spawn(move || {
                for job in receiver {
                    job();
                }
            })
            .expect("Desktop Host can spawn an image cache worker");
        sender
    });
    queue
        .send(Box::new(job))
        .expect("image cache worker remains available");
}

#[derive(Default, serde::Deserialize)]
struct Options {
    _src: Option<String>,
    _headers: Option<String>,
    decode_width: Option<u32>,
    decode_height: Option<u32>,
}

pub(super) struct MemoryCache {
    entries: VecDeque<(String, Arc<RgbaImage>)>,
    used: usize,
    limit: usize,
}

impl MemoryCache {
    pub(super) fn new(limit: usize) -> Self {
        Self {
            entries: VecDeque::new(),
            used: 0,
            limit,
        }
    }

    pub(super) fn get(&mut self, key: &str) -> Option<Arc<RgbaImage>> {
        let index = self.entries.iter().position(|(k, _)| k == key)?;
        let entry = self.entries.remove(index)?;
        let result = Arc::clone(&entry.1);
        self.entries.push_back(entry);
        Some(result)
    }

    pub(super) fn insert(&mut self, key: String, image: Arc<RgbaImage>) {
        self.remove_where(|k| k == &key);
        let cost = image.as_raw().len();
        if cost > self.limit {
            return;
        }
        while self.used > self.limit - cost {
            self.evict();
        }
        self.used += cost;
        self.entries.push_back((key, image));
    }

    pub(super) fn set_limit(&mut self, limit: usize) {
        self.limit = limit;
        while self.used > limit {
            self.evict();
        }
    }

    fn evict(&mut self) {
        if let Some((_, image)) = self.entries.pop_front() {
            self.used -= image.as_raw().len();
        }
    }

    fn remove_where(&mut self, predicate: impl Fn(&String) -> bool) {
        self.entries.retain(|(key, image)| {
            if predicate(key) {
                self.used -= image.as_raw().len();
                false
            } else {
                true
            }
        });
    }

    #[cfg(test)]
    pub(super) fn bytes(&self) -> usize {
        self.used
    }
}

struct Cache {
    memory: MemoryCache,
    root: PathBuf,
}

fn shared() -> &'static Mutex<Cache> {
    static CACHE: OnceLock<Mutex<Cache>> = OnceLock::new();
    CACHE.get_or_init(|| {
        let identity = std::env::current_exe().unwrap_or_default();
        Mutex::new(Cache {
            memory: MemoryCache::new(64 * 1024 * 1024),
            root: std::env::temp_dir().join(format!(
                "whisker-images-{}",
                digest(&identity.to_string_lossy())
            )),
        })
    })
}

fn digest(value: &str) -> String {
    format!("{:x}", Sha256::digest(value.as_bytes()))
}

pub(super) fn configure(value: &str) -> Result<(), String> {
    let limit = value.parse::<usize>().map_err(|e| e.to_string())?;
    shared()
        .lock()
        .map_err(|e| e.to_string())?
        .memory
        .set_limit(limit);
    Ok(())
}

pub(super) fn remove(url: &str) -> Result<(), String> {
    let mut cache = shared().lock().map_err(|e| e.to_string())?;
    let hash = digest(url);
    cache
        .memory
        .remove_where(|key| key.starts_with(&format!("{hash}:")));
    match std::fs::remove_file(cache.root.join(hash)) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

pub(super) fn load(src: &str, headers: &str, options: &str) -> Result<Arc<RgbaImage>, String> {
    let options: Options = serde_json::from_str(options).map_err(|e| e.to_string())?;
    let src = options._src.as_deref().unwrap_or(src);
    let headers = options._headers.as_deref().unwrap_or(headers);
    if src.is_empty() {
        return Err("image URL must not be empty".into());
    }
    decode_size(1, 1, options.decode_width, options.decode_height)?;
    if !src.starts_with("http://") && !src.starts_with("https://") {
        return Err("managed image cache requires an HTTP(S) URL".into());
    }
    let mut cache = shared().lock().map_err(|e| e.to_string())?;
    cache.load(src, headers, &options)
}

impl Cache {
    fn load(
        &mut self,
        src: &str,
        headers: &str,
        options: &Options,
    ) -> Result<Arc<RgbaImage>, String> {
        let hash = digest(src);
        let key = format!(
            "{hash}:{:?}:{:?}",
            options.decode_width, options.decode_height
        );
        if let Some(image) = self.memory.get(&key) {
            return Ok(image);
        }
        let path = self.root.join(&hash);
        if let Ok(bytes) = std::fs::read(&path) {
            if let Ok(image) = decode(&bytes, options) {
                self.memory.insert(key, Arc::clone(&image));
                return Ok(image);
            }
            std::fs::remove_file(&path).map_err(|e| e.to_string())?;
        }
        let parsed: std::collections::BTreeMap<String, String> = if headers.trim().is_empty() {
            Default::default()
        } else {
            serde_json::from_str(headers).map_err(|e| e.to_string())?
        };
        let agent = ureq::AgentBuilder::new()
            .timeout(std::time::Duration::from_secs(60))
            .build();
        let mut request = agent.get(src);
        for (name, value) in parsed {
            request = request.set(&name, &value);
        }
        let response = request.call().map_err(|e| e.to_string())?;
        let mut bytes = Vec::new();
        response
            .into_reader()
            .take(64 * 1024 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| e.to_string())?;
        if bytes.len() > 64 * 1024 * 1024 {
            return Err("image exceeds 64 MiB download limit".into());
        }
        let image = decode(&bytes, options)?;
        std::fs::create_dir_all(&self.root).map_err(|e| e.to_string())?;
        let partial = self
            .root
            .join(format!("{hash}.{}.part", std::process::id()));
        let saved =
            std::fs::write(&partial, &bytes).and_then(|()| std::fs::rename(&partial, &path));
        if let Err(e) = saved {
            let _ = std::fs::remove_file(&partial);
            return Err(e.to_string());
        }
        self.memory.insert(key, Arc::clone(&image));
        Ok(image)
    }
}

fn decode(bytes: &[u8], options: &Options) -> Result<Arc<RgbaImage>, String> {
    use image::ImageDecoder;
    let reader = image::ImageReader::new(std::io::Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|e| e.to_string())?;
    let mut decoder = reader.into_decoder().map_err(|e| e.to_string())?;
    let orientation = decoder.orientation().map_err(|e| e.to_string())?;
    let mut image = image::DynamicImage::from_decoder(decoder).map_err(|e| e.to_string())?;
    image.apply_orientation(orientation);
    let (width, height) = decode_size(
        image.width(),
        image.height(),
        options.decode_width,
        options.decode_height,
    )?;
    let image = if (width, height) == (image.width(), image.height()) {
        image
    } else {
        image.resize_exact(width, height, image::imageops::FilterType::Triangle)
    };
    Ok(Arc::new(image.into_rgba8()))
}

pub(super) fn decode_size(
    width: u32,
    height: u32,
    bound_width: Option<u32>,
    bound_height: Option<u32>,
) -> Result<(u32, u32), String> {
    if width == 0 || height == 0 || bound_width == Some(0) || bound_height == Some(0) {
        return Err("image and decode dimensions must be positive".into());
    }
    let scale = 1.0_f64
        .min(bound_width.map_or(1.0, |w| w as f64 / width as f64))
        .min(bound_height.map_or(1.0, |h| h as f64 / height as f64));
    Ok((
        (width as f64 * scale).floor().max(1.0) as u32,
        (height as f64 * scale).floor().max(1.0) as u32,
    ))
}

#[cfg(test)]
pub(super) fn seed(url: &str, bytes: &[u8]) {
    let cache = shared().lock().unwrap();
    std::fs::create_dir_all(&cache.root).unwrap();
    std::fs::write(cache.root.join(digest(url)), bytes).unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::net::TcpListener;

    #[test]
    fn disk_survives_memory_eviction_and_corruption_refetches_once() {
        let mut png = std::io::Cursor::new(Vec::new());
        image::DynamicImage::ImageRgba8(RgbaImage::new(8, 6))
            .write_to(&mut png, image::ImageFormat::Png)
            .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/image?variant=1", listener.local_addr().unwrap());
        let bytes = png.into_inner();
        let server = std::thread::spawn(move || {
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().unwrap();
                let mut request = [0; 2048];
                assert!(stream.read(&mut request).unwrap() > 0);
                write!(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    bytes.len()
                )
                .unwrap();
                stream.write_all(&bytes).unwrap();
            }
        });
        let root =
            std::env::temp_dir().join(format!("whisker-managed-test-{}", std::process::id()));
        let mut cache = Cache {
            memory: MemoryCache::new(1024),
            root: root.clone(),
        };
        let options = Options {
            decode_width: Some(4),
            ..Options::default()
        };
        let first = cache.load(&url, "", &options).unwrap();
        assert_eq!(first.dimensions(), (4, 3));
        assert!(Arc::ptr_eq(
            &first,
            &cache.load(&url, "", &options).unwrap()
        ));
        cache.memory.set_limit(0);
        let mut reopened = Cache {
            memory: MemoryCache::new(0),
            root: root.clone(),
        };
        assert_eq!(
            reopened
                .load(&url, "", &Options::default())
                .unwrap()
                .dimensions(),
            (8, 6)
        );
        std::fs::write(root.join(digest(&url)), b"broken").unwrap();
        assert_eq!(
            reopened.load(&url, "", &options).unwrap().dimensions(),
            (4, 3)
        );
        server.join().unwrap();
        assert_ne!(digest(&url), digest(&url.replace("variant=1", "variant=2")));
        std::fs::remove_dir_all(root).unwrap();
    }
}
