use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use bytes::Bytes;
use cpal::traits::{DeviceTrait, HostTrait};
use futures_util::stream::StreamExt;
use pipewire as pw;
use pw::spa;
use spa::param::format::{MediaSubtype, MediaType};
use spa::param::format_utils;
use spa::pod::Pod;
use std::{
    collections::{HashMap, VecDeque},
    convert::TryInto,
    fs, mem,
    net::SocketAddr,
    sync::{
        atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};
use tokio::net::UdpSocket;
use tokio::sync::broadcast;
use tower_http::cors::CorsLayer;

pub const MAGIC_BYTES: &[u8; 4] = b"SWD\x01";

pub const PKT_AUDIO_DOWNLINK: u8 = 0x01;
pub const PKT_MIC_UPLINK: u8 = 0x02;
pub const PKT_HEARTBEAT_SUB: u8 = 0x03;
pub const PKT_UNSUB: u8 = 0x04;
pub const PKT_DISCOVERY: u8 = 0x05;
pub const PKT_PING: u8 = 0x06;

pub const PKT_HEARTBEAT_ACK: u8 = 0x83;
pub const PKT_DISCOVERY_ACK: u8 = 0x85;
pub const PKT_ECHO: u8 = 0x86;

// Audio ring buffer size configured: Reverse Mic Uplink = 8KiB
pub const MIC_BUFFER_SIZE_8KIB: usize = 8 * 1024; // 8KiB = 8,192 bytes
pub const MIC_BUFFER_SAMPLES_8KIB: usize = MIC_BUFFER_SIZE_8KIB / std::mem::size_of::<i16>(); // 4,096 i16 samples (~42.6ms stereo at 48kHz)

#[derive(Clone, serde::Serialize)]
pub struct AudioDeviceInfo {
    pub name: String,
    pub is_default: bool,
    pub sample_rates: Vec<u32>,
    pub max_channels: u16,
}

#[derive(Clone, serde::Serialize)]
pub struct VirtualMicInfo {
    pub name: String,
    pub description: String,
    pub is_streaming: bool,
    pub sample_rate: u32,
    pub channels: u32,
    pub peak: f32,
    pub total_received_frames: u64,
}

#[derive(Clone, serde::Serialize)]
pub struct AudioStackInfo {
    pub pipewire_version: String,
    pub cpal_host: String,
    pub default_output_device: String,
    pub default_input_device: String,
    pub capture_rate: u32,
    pub capture_channels: u32,
    pub capture_format: String,
    pub total_captured_frames: u64,
    pub peak_left: f32,
    pub peak_right: f32,
    pub devices: Vec<AudioDeviceInfo>,
    pub virtual_mic: VirtualMicInfo,
}

#[derive(Clone, serde::Serialize)]
pub struct OsInfo {
    pub os_name: String,
    pub kernel: String,
    pub hostname: String,
    pub cpu_model: String,
    pub cpu_cores: usize,
    pub mem_total_mb: u64,
    pub mem_used_mb: u64,
    pub uptime_secs: u64,
}

#[derive(Clone, serde::Serialize)]
pub struct SystemStatus {
    pub os: OsInfo,
    pub audio: AudioStackInfo,
    pub active_listeners: usize,
    pub server_time: u64,
}

pub struct VirtualMicState {
    pub buffer: Arc<Mutex<VecDeque<i16>>>,
    pub is_streaming: Arc<AtomicBool>,
    pub sample_rate: Arc<AtomicU32>,
    pub channels: Arc<AtomicU32>,
    pub peak: Arc<Mutex<f32>>,
    pub total_received_frames: Arc<AtomicU64>,
}

pub struct UdpClientsState {
    pub clients: Mutex<HashMap<SocketAddr, Instant>>,
}

struct SharedState {
    tx: broadcast::Sender<Bytes>,
    capture_rate: Arc<Mutex<u32>>,
    capture_channels: Arc<Mutex<u32>>,
    total_frames: Arc<AtomicU64>,
    peak_left: Arc<Mutex<f32>>,
    peak_right: Arc<Mutex<f32>>,
    virtual_mic: Arc<VirtualMicState>,
    udp_clients: Arc<UdpClientsState>,
}

fn get_os_info() -> OsInfo {
    let mut os_name = "Linux".to_string();
    if let Ok(content) = fs::read_to_string("/etc/os-release") {
        for line in content.lines() {
            if let Some(stripped) = line.strip_prefix("PRETTY_NAME=") {
                os_name = stripped.trim_matches('"').to_string();
                break;
            }
        }
    }

    let kernel = match std::process::Command::new("uname").arg("-r").output() {
        Ok(out) => String::from_utf8_lossy(&out.stdout).trim().to_string(),
        Err(_) => "Unknown".to_string(),
    };

    let hostname = match std::process::Command::new("uname").arg("-n").output() {
        Ok(out) => String::from_utf8_lossy(&out.stdout).trim().to_string(),
        Err(_) => "localhost".to_string(),
    };

    let mut cpu_model = "Unknown CPU".to_string();
    let mut cpu_cores = 0;
    if let Ok(content) = fs::read_to_string("/proc/cpuinfo") {
        for line in content.lines() {
            if line.starts_with("model name") {
                if let Some((_, model)) = line.split_once(':') {
                    if cpu_model == "Unknown CPU" {
                        cpu_model = model.trim().to_string();
                    }
                }
            }
            if line.starts_with("processor") {
                cpu_cores += 1;
            }
        }
    }
    if cpu_cores == 0 {
        cpu_cores = 1;
    }

    let mut mem_total_mb = 0;
    let mut mem_available_mb = 0;
    if let Ok(content) = fs::read_to_string("/proc/meminfo") {
        for line in content.lines() {
            if line.starts_with("MemTotal:") {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 {
                    mem_total_mb = parts[1].parse::<u64>().unwrap_or(0) / 1024;
                }
            } else if line.starts_with("MemAvailable:") {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 {
                    mem_available_mb = parts[1].parse::<u64>().unwrap_or(0) / 1024;
                }
            }
        }
    }
    let mem_used_mb = mem_total_mb.saturating_sub(mem_available_mb);

    let mut uptime_secs = 0;
    if let Ok(content) = fs::read_to_string("/proc/uptime") {
        if let Some(first) = content.split_whitespace().next() {
            if let Ok(secs) = first.parse::<f64>() {
                uptime_secs = secs as u64;
            }
        }
    }

    OsInfo {
        os_name,
        kernel,
        hostname,
        cpu_model,
        cpu_cores,
        mem_total_mb,
        mem_used_mb,
        uptime_secs,
    }
}

fn get_audio_stack_info(shared: &SharedState) -> AudioStackInfo {
    let host = cpal::default_host();
    let cpal_host = format!("{:?}", host.id());

    let default_output = host
        .default_output_device()
        .and_then(|d| d.name().ok())
        .unwrap_or_else(|| "Default Sink".to_string());

    let default_input = host
        .default_input_device()
        .and_then(|d| d.name().ok())
        .unwrap_or_else(|| "Default Source".to_string());

    let mut devices = Vec::new();
    if let Ok(devs) = host.devices() {
        for dev in devs {
            let name = dev.name().unwrap_or_else(|_| "Unknown Device".to_string());
            let is_default = name == default_output;
            let mut sample_rates = Vec::new();
            let mut max_channels = 2;
            if let Ok(configs) = dev.supported_output_configs() {
                for cfg in configs {
                    max_channels = max_channels.max(cfg.channels());
                    let min_rate = cfg.min_sample_rate();
                    let max_rate = cfg.max_sample_rate();
                    if !sample_rates.contains(&min_rate) {
                        sample_rates.push(min_rate);
                    }
                    if !sample_rates.contains(&max_rate) {
                        sample_rates.push(max_rate);
                    }
                }
            }
            sample_rates.sort_unstable();
            devices.push(AudioDeviceInfo {
                name,
                is_default,
                sample_rates,
                max_channels,
            });
        }
    }

    let rate = *shared.capture_rate.lock().unwrap();
    let channels = *shared.capture_channels.lock().unwrap();
    let total_captured_frames = shared.total_frames.load(Ordering::Relaxed);
    let peak_left = *shared.peak_left.lock().unwrap();
    let peak_right = *shared.peak_right.lock().unwrap();

    let pipewire_version = match std::process::Command::new("pkg-config")
        .args(["--modversion", "libpipewire-0.3"])
        .output()
    {
        Ok(out) => String::from_utf8_lossy(&out.stdout).trim().to_string(),
        Err(_) => "1.6.x".to_string(),
    };

    let virtual_mic = VirtualMicInfo {
        name: "SWD-Virtual-Mic".to_string(),
        description: "SWD 移动端麦克风 (Mobile Mic)".to_string(),
        is_streaming: shared.virtual_mic.is_streaming.load(Ordering::Relaxed),
        sample_rate: shared.virtual_mic.sample_rate.load(Ordering::Relaxed),
        channels: shared.virtual_mic.channels.load(Ordering::Relaxed),
        peak: *shared.virtual_mic.peak.lock().unwrap(),
        total_received_frames: shared.virtual_mic.total_received_frames.load(Ordering::Relaxed),
    };

    AudioStackInfo {
        pipewire_version,
        cpal_host,
        default_output_device: default_output,
        default_input_device: default_input,
        capture_rate: rate,
        capture_channels: channels,
        capture_format: "F32LE Stereo (Native 96kHz 16-bit PCM Low-Latency stream)".to_string(),
        total_captured_frames,
        peak_left,
        peak_right,
        devices,
        virtual_mic,
    }
}

async fn status_handler(State(state): State<Arc<SharedState>>) -> Json<SystemStatus> {
    let os = get_os_info();
    let audio = get_audio_stack_info(&state);
    let udp_active = state.udp_clients.clients.lock().unwrap().len();
    let active_listeners = state.tx.receiver_count() + udp_active;
    let server_time = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();

    Json(SystemStatus {
        os,
        audio,
        active_listeners,
        server_time,
    })
}

// WebSocket handler for real-time binary audio stream (Desktop -> Mobile)
async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<Arc<SharedState>>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, state))
}

async fn handle_socket(mut socket: WebSocket, state: Arc<SharedState>) {
    let mut rx = state.tx.subscribe();
    println!("[WebSocket-Audio] Client connected to live audio stream");

    let rate = *state.capture_rate.lock().unwrap();
    let meta = serde_json::json!({
        "type": "meta",
        "sampleRate": if rate == 0 { 96000 } else { rate },
        "channels": 2,
        "format": "s16le",
    });
    if socket
        .send(Message::Text(meta.to_string().into()))
        .await
        .is_err()
    {
        return;
    }

    loop {
        tokio::select! {
            result = rx.recv() => {
                match result {
                    Ok(chunk) => {
                        if socket.send(Message::Binary(chunk)).await.is_err() {
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(_lag)) => {
                        continue;
                    }
                    Err(broadcast::error::RecvError::Closed) => {
                        break;
                    }
                }
            }
            client_msg = socket.recv() => {
                match client_msg {
                    Some(Ok(Message::Close(_))) | None => break,
                    _ => {}
                }
            }
        }
    }
    println!("[WebSocket-Audio] Client disconnected");
}

// WebSocket handler for reverse microphone stream
async fn mic_ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<Arc<SharedState>>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_mic_socket(socket, state))
}

async fn handle_mic_socket(mut socket: WebSocket, state: Arc<SharedState>) {
    println!("[WebSocket-Mic] Mobile mic client connected");
    state.virtual_mic.is_streaming.store(true, Ordering::Relaxed);

    let ack = serde_json::json!({
        "type": "mic_ack",
        "status": "connected",
        "targetSampleRate": 48000,
        "targetChannels": 2,
    });
    let _ = socket.send(Message::Text(ack.to_string().into())).await;

    let mut client_channels: u32 = 1;
    let max_buffer_samples = MIC_BUFFER_SAMPLES_8KIB;

    while let Some(msg) = socket.recv().await {
        let msg = match msg {
            Ok(m) => m,
            Err(_) => break,
        };

        match msg {
            Message::Binary(bytes) => {
                if bytes.is_empty() {
                    continue;
                }
                let n_raw_samples = bytes.len() / 2;
                if n_raw_samples == 0 {
                    continue;
                }

                let mut max_abs: f32 = 0.0;
                let mut stereo_buf = Vec::with_capacity(if client_channels == 1 {
                    n_raw_samples * 2
                } else {
                    n_raw_samples
                });

                if client_channels == 1 {
                    for i in 0..n_raw_samples {
                        let sample = i16::from_le_bytes([bytes[i * 2], bytes[i * 2 + 1]]);
                        let abs_norm = (sample as f32 / 32768.0).abs();
                        if abs_norm > max_abs {
                            max_abs = abs_norm;
                        }
                        stereo_buf.push(sample);
                        stereo_buf.push(sample);
                    }
                    state
                        .virtual_mic
                        .total_received_frames
                        .fetch_add(n_raw_samples as u64, Ordering::Relaxed);
                } else {
                    for i in 0..n_raw_samples {
                        let sample = i16::from_le_bytes([bytes[i * 2], bytes[i * 2 + 1]]);
                        let abs_norm = (sample as f32 / 32768.0).abs();
                        if abs_norm > max_abs {
                            max_abs = abs_norm;
                        }
                        stereo_buf.push(sample);
                    }
                    state
                        .virtual_mic
                        .total_received_frames
                        .fetch_add((n_raw_samples / 2) as u64, Ordering::Relaxed);
                }

                *state.virtual_mic.peak.lock().unwrap() = max_abs;

                let mut q = state.virtual_mic.buffer.lock().unwrap();
                q.extend(stereo_buf);

                if q.len() > max_buffer_samples {
                    let excess = q.len() - max_buffer_samples;
                    q.drain(0..excess);
                }
            }
            Message::Text(text) => {
                if let Ok(val) = serde_json::from_str::<serde_json::Value>(&text) {
                    if let Some(ch) = val.get("channels").and_then(|v| v.as_u64()) {
                        client_channels = ch as u32;
                    }
                }
            }
            Message::Close(_) => break,
            Message::Ping(p) => {
                let _ = socket.send(Message::Pong(p)).await;
            }
            _ => {}
        }
    }

    println!("[WebSocket-Mic] Mobile mic client disconnected");
    state.virtual_mic.is_streaming.store(false, Ordering::Relaxed);
    *state.virtual_mic.peak.lock().unwrap() = 0.0;
    state.virtual_mic.buffer.lock().unwrap().clear();
}

async fn http_stream_handler(State(state): State<Arc<SharedState>>) -> Response {
    let rx = state.tx.subscribe();
    let stream = futures_util::stream::unfold(rx, |mut rx| async move {
        loop {
            match rx.recv().await {
                Ok(chunk) => {
                    return Some((Ok::<_, std::io::Error>(chunk), rx));
                }
                Err(broadcast::error::RecvError::Lagged(_)) => {
                    continue;
                }
                Err(broadcast::error::RecvError::Closed) => {
                    return None;
                }
            }
        }
    });

    let body = axum::body::Body::from_stream(stream);
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "audio/pcm; rate=96000; channels=2")
        .header(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate")
        .header(header::CONNECTION, "keep-alive")
        .body(body)
        .unwrap()
}

async fn http_wav_stream_handler(State(state): State<Arc<SharedState>>) -> Response {
    let rx = state.tx.subscribe();

    let sample_rate: u32 = 96000;
    let channels: u16 = 2;
    let bits_per_sample: u16 = 16;
    let byte_rate = sample_rate * channels as u32 * (bits_per_sample as u32 / 8);
    let block_align = channels * (bits_per_sample / 8);

    let mut wav_header = Vec::with_capacity(44);
    wav_header.extend_from_slice(b"RIFF");
    wav_header.extend_from_slice(&0x7ffff000u32.to_le_bytes());
    wav_header.extend_from_slice(b"WAVE");
    wav_header.extend_from_slice(b"fmt ");
    wav_header.extend_from_slice(&16u32.to_le_bytes());
    wav_header.extend_from_slice(&1u16.to_le_bytes());
    wav_header.extend_from_slice(&channels.to_le_bytes());
    wav_header.extend_from_slice(&sample_rate.to_le_bytes());
    wav_header.extend_from_slice(&byte_rate.to_le_bytes());
    wav_header.extend_from_slice(&block_align.to_le_bytes());
    wav_header.extend_from_slice(&bits_per_sample.to_le_bytes());
    wav_header.extend_from_slice(b"data");
    wav_header.extend_from_slice(&0x7fffefdcu32.to_le_bytes());

    let initial_stream = futures_util::stream::once(async move {
        Ok::<_, std::io::Error>(Bytes::from(wav_header))
    });

    let audio_stream = futures_util::stream::unfold(rx, |mut rx| async move {
        loop {
            match rx.recv().await {
                Ok(chunk) => {
                    return Some((Ok::<_, std::io::Error>(chunk), rx));
                }
                Err(broadcast::error::RecvError::Lagged(_)) => {
                    continue;
                }
                Err(broadcast::error::RecvError::Closed) => {
                    return None;
                }
            }
        }
    });

    let combined = initial_stream.chain(audio_stream);
    let body = axum::body::Body::from_stream(combined);
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "audio/wav")
        .header(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate")
        .header(header::CONNECTION, "keep-alive")
        .body(body)
        .unwrap()
}

struct CaptureContext {
    format: spa::param::audio::AudioInfoRaw,
    tx: broadcast::Sender<Bytes>,
    capture_rate: Arc<Mutex<u32>>,
    capture_channels: Arc<Mutex<u32>>,
    total_frames: Arc<AtomicU64>,
    peak_left: Arc<Mutex<f32>>,
    peak_right: Arc<Mutex<f32>>,
}

fn start_pipewire_capture(
    tx: broadcast::Sender<Bytes>,
    capture_rate: Arc<Mutex<u32>>,
    capture_channels: Arc<Mutex<u32>>,
    total_frames: Arc<AtomicU64>,
    peak_left: Arc<Mutex<f32>>,
    peak_right: Arc<Mutex<f32>>,
) {
    thread::spawn(move || {
        pw::init();
        println!("[PipeWire] Initializing stream capture with stream.capture.sink=true");

        let mainloop = pw::main_loop::MainLoopRc::new(None)
            .expect("Failed to create PipeWire mainloop");
        let context = pw::context::ContextRc::new(&mainloop, None)
            .expect("Failed to create PipeWire context");
        let core = context.connect_rc(None)
            .expect("Failed to connect to PipeWire core");

        let mut props = pw::properties::properties! {
            *pw::keys::MEDIA_TYPE => "Audio",
            *pw::keys::MEDIA_CATEGORY => "Capture",
            *pw::keys::MEDIA_ROLE => "Music",
            *pw::keys::APP_NAME => "SWD-AudioStreamer",
            *pw::keys::NODE_LATENCY => "256/96000",
        };

        props.insert(*pw::keys::STREAM_CAPTURE_SINK, "true");
        props.insert(*pw::keys::NODE_LATENCY, "256/96000");

        unsafe {
            let raw_props = props.as_raw_ptr();
            let key = std::ffi::CString::new("stream.capture.sink").unwrap();
            let val = std::ffi::CString::new("true").unwrap();
            pw::sys::pw_properties_set(raw_props, key.as_ptr(), val.as_ptr());

            let lat_key = std::ffi::CString::new("node.latency").unwrap();
            let lat_val = std::ffi::CString::new("256/96000").unwrap();
            pw::sys::pw_properties_set(raw_props, lat_key.as_ptr(), lat_val.as_ptr());
            println!("[PipeWire] Configured pw_properties_set: STREAM_CAPTURE_SINK=true, node.latency=256/96000");
        }

        let stream = pw::stream::StreamBox::new(&core, "swd-sink-capture", props)
            .expect("Failed to create PipeWire StreamBox");

        let ctx = CaptureContext {
            format: spa::param::audio::AudioInfoRaw::new(),
            tx,
            capture_rate,
            capture_channels,
            total_frames,
            peak_left,
            peak_right,
        };

        let _listener = stream
            .add_local_listener_with_user_data(ctx)
            .state_changed(|_, _, old, new| {
                println!("[PipeWire] Stream state: {:?} -> {:?}", old, new);
            })
            .param_changed(|_, ctx, id, param| {
                let Some(param) = param else { return; };
                if id != pw::spa::param::ParamType::Format.as_raw() { return; }
                let Ok((media_type, media_subtype)) = format_utils::parse_format(param) else { return; };
                if media_type != MediaType::Audio || media_subtype != MediaSubtype::Raw { return; }

                if ctx.format.parse(param).is_ok() {
                    let rate = ctx.format.rate();
                    let channels = ctx.format.channels();
                    println!("[PipeWire] Negotiated format: rate={}Hz, channels={}", rate, channels);
                    *ctx.capture_rate.lock().unwrap() = rate;
                    *ctx.capture_channels.lock().unwrap() = channels;
                }
            })
            .process(|stream, ctx| {
                if let Some(mut buffer) = stream.dequeue_buffer() {
                    let datas = buffer.datas_mut();
                    if datas.is_empty() { return; }

                    let data = &mut datas[0];
                    let chunk_size = data.chunk().size() as usize;
                    let n_channels = ctx.format.channels() as usize;
                    if n_channels == 0 || chunk_size == 0 { return; }

                    let n_samples = chunk_size / mem::size_of::<f32>();
                    let n_frames = n_samples / n_channels;
                    ctx.total_frames.fetch_add(n_frames as u64, Ordering::Relaxed);

                    if let Some(samples) = data.data() {
                        let mut p_left: f32 = 0.0;
                        let mut p_right: f32 = 0.0;

                        let mut pcm16 = Vec::with_capacity(n_samples * 2);

                        for frame_idx in 0..n_frames {
                            for ch in 0..n_channels {
                                let offset = (frame_idx * n_channels + ch) * 4;
                                if offset + 4 <= samples.len() {
                                    let sample_f32 = f32::from_le_bytes(
                                        samples[offset..offset + 4].try_into().unwrap_or([0; 4])
                                    );
                                    let abs_val = sample_f32.abs();
                                    if ch == 0 {
                                        p_left = p_left.max(abs_val);
                                    } else if ch == 1 {
                                        p_right = p_right.max(abs_val);
                                    }

                                    let clamped = sample_f32.clamp(-1.0, 1.0);
                                    let s16 = (clamped * 32767.0) as i16;
                                    pcm16.extend_from_slice(&s16.to_le_bytes());
                                }
                            }
                        }

                        *ctx.peak_left.lock().unwrap() = p_left;
                        *ctx.peak_right.lock().unwrap() = if n_channels > 1 { p_right } else { p_left };

                        let bytes = Bytes::from(pcm16);
                        let _ = ctx.tx.send(bytes);
                    }
                }
            })
            .register()
            .expect("Failed to register stream listener");

        let mut audio_info = spa::param::audio::AudioInfoRaw::new();
        audio_info.set_format(spa::param::audio::AudioFormat::F32LE);
        audio_info.set_rate(96000);
        audio_info.set_channels(2);

        let obj = pw::spa::pod::Object {
            type_: pw::spa::utils::SpaTypes::ObjectParamFormat.as_raw(),
            id: pw::spa::param::ParamType::EnumFormat.as_raw(),
            properties: audio_info.into(),
        };
        let values: Vec<u8> = pw::spa::pod::serialize::PodSerializer::serialize(
            std::io::Cursor::new(Vec::new()),
            &pw::spa::pod::Value::Object(obj),
        )
        .expect("Failed to serialize pod")
        .0
        .into_inner();

        let mut params = [Pod::from_bytes(&values).unwrap()];

        stream.connect(
            spa::utils::Direction::Input,
            None,
            pw::stream::StreamFlags::AUTOCONNECT
                | pw::stream::StreamFlags::MAP_BUFFERS
                | pw::stream::StreamFlags::RT_PROCESS,
            &mut params,
        )
        .expect("Failed to connect capture stream");

        println!("[PipeWire] Capture stream connected to default sink monitor. Running event loop...");
        mainloop.run();
    });
}

fn start_pipewire_virtual_mic(mic_state: Arc<VirtualMicState>) {
    thread::spawn(move || {
        pw::init();
        println!("[PipeWire-Mic] Initializing virtual microphone audio source node...");

        let mainloop = pw::main_loop::MainLoopRc::new(None)
            .expect("Failed to create PipeWire virtual mic mainloop");
        let context = pw::context::ContextRc::new(&mainloop, None)
            .expect("Failed to create PipeWire context for virtual mic");
        let core = context.connect_rc(None)
            .expect("Failed to connect to PipeWire core for virtual mic");

        let props = pw::properties::properties! {
            *pw::keys::MEDIA_CLASS => "Audio/Source",
            *pw::keys::NODE_NAME => "SWD-Virtual-Mic",
            *pw::keys::NODE_DESCRIPTION => "SWD 移动端麦克风 (Mobile Mic)",
            *pw::keys::MEDIA_TYPE => "Audio",
            *pw::keys::MEDIA_CATEGORY => "Playback",
            *pw::keys::MEDIA_ROLE => "Communication",
            *pw::keys::NODE_LATENCY => "256/48000",
        };

        let stream = pw::stream::StreamBox::new(&core, "SWD-Virtual-Mic", props)
            .expect("Failed to create PipeWire StreamBox for virtual mic");

        let mic_clone = mic_state.clone();

        let _listener = stream
            .add_local_listener_with_user_data(())
            .state_changed(|_, _, old, new| {
                println!("[PipeWire-Mic] Virtual mic stream state: {:?} -> {:?}", old, new);
            })
            .process(move |stream, _| {
                if let Some(mut buffer) = stream.dequeue_buffer() {
                    let datas = buffer.datas_mut();
                    if datas.is_empty() { return; }

                    let data = &mut datas[0];
                    if let Some(slice) = data.data() {
                        let max_bytes = slice.len();
                        let req_bytes = max_bytes.min(1920); // 480 frames stereo s16le = 1920 bytes
                        let req_samples = req_bytes / 2;
                        let actual_bytes = req_samples * 2;

                        let mut q = mic_clone.buffer.lock().unwrap();
                        for i in 0..req_samples {
                            let sample = q.pop_front().unwrap_or(0);
                            let le = sample.to_le_bytes();
                            slice[i * 2] = le[0];
                            slice[i * 2 + 1] = le[1];
                        }

                        let chunk = data.chunk_mut();
                        *chunk.offset_mut() = 0;
                        *chunk.size_mut() = actual_bytes as u32;
                        *chunk.stride_mut() = 4;
                    }
                }
            })
            .register()
            .expect("Failed to register virtual mic stream listener");

        let mut audio_info = spa::param::audio::AudioInfoRaw::new();
        audio_info.set_format(spa::param::audio::AudioFormat::S16LE);
        audio_info.set_rate(48000);
        audio_info.set_channels(2);

        let obj = pw::spa::pod::Object {
            type_: pw::spa::utils::SpaTypes::ObjectParamFormat.as_raw(),
            id: pw::spa::param::ParamType::EnumFormat.as_raw(),
            properties: audio_info.into(),
        };
        let values: Vec<u8> = pw::spa::pod::serialize::PodSerializer::serialize(
            std::io::Cursor::new(Vec::new()),
            &pw::spa::pod::Value::Object(obj),
        )
        .expect("Failed to serialize virtual mic pod")
        .0
        .into_inner();

        let mut params = [Pod::from_bytes(&values).unwrap()];

        stream.connect(
            spa::utils::Direction::Output,
            None,
            pw::stream::StreamFlags::MAP_BUFFERS | pw::stream::StreamFlags::RT_PROCESS,
            &mut params,
        ).expect("Failed to connect virtual mic stream");

        println!("[PipeWire-Mic] Virtual microphone device active: 'SWD-Virtual-Mic'");
        mainloop.run();
    });
}

// Direct UDP Audio Downlink & Mic Uplink Service
async fn start_udp_audio_service(state: Arc<SharedState>, udp_socket: Arc<UdpSocket>) {
    let socket_recv = udp_socket.clone();
    let state_recv = state.clone();

    // 1. Task: Receive UDP packets (Heartbeat, Mic Uplink, Disconnect, Discovery)
    tokio::spawn(async move {
        let mut buf = [0u8; 65535];
        println!("[UDP-Engine] Inbound UDP listener active on {}", socket_recv.local_addr().unwrap());

        loop {
            let (len, src) = match socket_recv.recv_from(&mut buf).await {
                Ok(r) => r,
                Err(e) => {
                    eprintln!("[UDP-Engine] recv_from error: {}", e);
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    continue;
                }
            };

            if len == 4 && &buf[0..4] == b"PING" {
                {
                    let mut map = state_recv.udp_clients.clients.lock().unwrap();
                    if let Some(last_seen) = map.get_mut(&src) {
                        *last_seen = Instant::now();
                    }
                }
                let _ = socket_recv.send_to(b"ECHO", src).await;
                continue;
            }

            if len < 5 {
                continue;
            }

            // Verify Magic Bytes
            if &buf[0..4] != MAGIC_BYTES {
                continue;
            }

            let pkt_type = buf[4];
            match pkt_type {
                PKT_PING => {
                    // Update client last_seen only if already subscribed to audio stream
                    {
                        let mut map = state_recv.udp_clients.clients.lock().unwrap();
                        if let Some(last_seen) = map.get_mut(&src) {
                            *last_seen = Instant::now();
                        }
                    }

                    // Send Echo response
                    let rate = *state_recv.capture_rate.lock().unwrap();
                    let rate = if rate == 0 { 96000 } else { rate };

                    let mut echo = Vec::with_capacity(17);
                    echo.extend_from_slice(MAGIC_BYTES);
                    echo.push(PKT_ECHO);
                    if len >= 9 {
                        echo.extend_from_slice(&buf[5..9]); // Echo client id
                    } else {
                        echo.extend_from_slice(&0u32.to_be_bytes());
                    }
                    echo.extend_from_slice(b"ECHO");
                    echo.extend_from_slice(&rate.to_be_bytes());

                    let _ = socket_recv.send_to(&echo, src).await;
                }
                PKT_HEARTBEAT_SUB => {
                    // Update client last_seen
                    {
                        let mut map = state_recv.udp_clients.clients.lock().unwrap();
                        let is_new = !map.contains_key(&src);
                        map.insert(src, Instant::now());
                        if is_new {
                            println!("[UDP-Engine] New client registered: {}", src);
                        }
                    }

                    // Send Heartbeat Ack
                    let rate = *state_recv.capture_rate.lock().unwrap();
                    let rate = if rate == 0 { 96000 } else { rate };
                    let now_secs = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_secs();

                    let mut ack = Vec::with_capacity(22);
                    ack.extend_from_slice(MAGIC_BYTES);
                    ack.push(PKT_HEARTBEAT_ACK);
                    if len >= 9 {
                        ack.extend_from_slice(&buf[5..9]); // Echo client id
                    } else {
                        ack.extend_from_slice(&0u32.to_be_bytes());
                    }
                    ack.extend_from_slice(&rate.to_be_bytes());
                    ack.push(2); // channels
                    ack.extend_from_slice(&now_secs.to_be_bytes());

                    let _ = socket_recv.send_to(&ack, src).await;
                }
                PKT_UNSUB => {
                    let mut map = state_recv.udp_clients.clients.lock().unwrap();
                    if map.remove(&src).is_some() {
                        println!("[UDP-Engine] Client unregistered: {}", src);
                    }
                }
                PKT_MIC_UPLINK => {
                    // Header: Magic(4) + Type(1) + Seq(4) + Rate(4) + Channels(1) + Flags(1) + Len(2) = 17 bytes
                    if len < 17 {
                        continue;
                    }

                    let channels = buf[13] as usize;
                    let payload_len = u16::from_be_bytes([buf[15], buf[16]]) as usize;
                    let pcm_end = 17 + payload_len;
                    if pcm_end > len {
                        continue;
                    }

                    let pcm_bytes = &buf[17..pcm_end];
                    let n_raw_samples = pcm_bytes.len() / 2;
                    if n_raw_samples == 0 {
                        continue;
                    }

                    state_recv.virtual_mic.is_streaming.store(true, Ordering::Relaxed);

                    let mut max_abs: f32 = 0.0;
                    let mut stereo_buf = Vec::with_capacity(if channels == 1 {
                        n_raw_samples * 2
                    } else {
                        n_raw_samples
                    });

                    if channels == 1 {
                        for i in 0..n_raw_samples {
                            let sample = i16::from_le_bytes([pcm_bytes[i * 2], pcm_bytes[i * 2 + 1]]);
                            let abs_norm = (sample as f32 / 32768.0).abs();
                            if abs_norm > max_abs {
                                max_abs = abs_norm;
                            }
                            stereo_buf.push(sample);
                            stereo_buf.push(sample);
                        }
                        state_recv
                            .virtual_mic
                            .total_received_frames
                            .fetch_add(n_raw_samples as u64, Ordering::Relaxed);
                    } else {
                        for i in 0..n_raw_samples {
                            let sample = i16::from_le_bytes([pcm_bytes[i * 2], pcm_bytes[i * 2 + 1]]);
                            let abs_norm = (sample as f32 / 32768.0).abs();
                            if abs_norm > max_abs {
                                max_abs = abs_norm;
                            }
                            stereo_buf.push(sample);
                        }
                        state_recv
                            .virtual_mic
                            .total_received_frames
                            .fetch_add((n_raw_samples / 2) as u64, Ordering::Relaxed);
                    }

                    *state_recv.virtual_mic.peak.lock().unwrap() = max_abs;

                    let mut q = state_recv.virtual_mic.buffer.lock().unwrap();
                    q.extend(stereo_buf);

                    let max_buffer_samples = MIC_BUFFER_SAMPLES_8KIB;
                    if q.len() > max_buffer_samples {
                        let excess = q.len() - max_buffer_samples;
                        q.drain(0..excess);
                    }
                }
                PKT_DISCOVERY => {
                    let os = get_os_info();
                    let rate = *state_recv.capture_rate.lock().unwrap();
                    let discovery_resp = serde_json::json!({
                        "hostname": os.hostname,
                        "os": os.os_name,
                        "sampleRate": if rate == 0 { 96000 } else { rate },
                        "channels": 2,
                        "udpPort": 9890,
                        "httpPort": 9890,
                    });
                    let resp_str = discovery_resp.to_string();
                    let mut resp_bytes = Vec::with_capacity(5 + resp_str.len());
                    resp_bytes.extend_from_slice(MAGIC_BYTES);
                    resp_bytes.push(PKT_DISCOVERY_ACK);
                    resp_bytes.extend_from_slice(resp_str.as_bytes());

                    let _ = socket_recv.send_to(&resp_bytes, src).await;
                }
                _ => {}
            }
        }
    });

    // 2. Task: Outbound Audio Downlink (Broadcast to active UDP clients)
    let socket_send = udp_socket.clone();
    let state_send = state.clone();
    tokio::spawn(async move {
        let mut rx = state_send.tx.subscribe();
        let mut seq: u32 = 0;
        let mut prune_timer = Instant::now();

        loop {
            let chunk = match rx.recv().await {
                Ok(c) => c,
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(broadcast::error::RecvError::Closed) => break,
            };

            // Prune inactive clients every 3 seconds (allow up to 24s inactivity for 8s heartbeats)
            if prune_timer.elapsed() > Duration::from_secs(3) {
                prune_timer = Instant::now();
                let now = Instant::now();
                let mut map = state_send.udp_clients.clients.lock().unwrap();
                map.retain(|addr, last_seen| {
                    if now.duration_since(*last_seen) > Duration::from_secs(24) {
                        println!("[UDP-Engine] Pruned inactive client: {}", addr);
                        false
                    } else {
                        true
                    }
                });
            }

            // Get target client list
            let targets: Vec<SocketAddr> = {
                let map = state_send.udp_clients.clients.lock().unwrap();
                map.keys().copied().collect()
            };

            if targets.is_empty() {
                continue;
            }

            let sample_rate = *state_send.capture_rate.lock().unwrap();
            let sample_rate = if sample_rate == 0 { 96000 } else { sample_rate };

            // Packetize into MTU-safe sub-packets (<= 1024 bytes payload)
            // Header: Magic(4) + Type(1) + Seq(4) + Rate(4) + Channels(1) + Bits(1) + Len(2) = 17 bytes
            const MAX_PAYLOAD_CHUNK: usize = 1024;
            let mut offset = 0;

            while offset < chunk.len() {
                let end = (offset + MAX_PAYLOAD_CHUNK).min(chunk.len());
                let sub_payload = &chunk[offset..end];
                let payload_len = sub_payload.len() as u16;

                let mut packet = Vec::with_capacity(17 + sub_payload.len());
                packet.extend_from_slice(MAGIC_BYTES);
                packet.push(PKT_AUDIO_DOWNLINK);
                packet.extend_from_slice(&seq.to_be_bytes());
                packet.extend_from_slice(&sample_rate.to_be_bytes());
                packet.push(2);  // Channels: Stereo
                packet.push(16); // Bits: 16-bit
                packet.extend_from_slice(&payload_len.to_be_bytes());
                packet.extend_from_slice(sub_payload);

                seq = seq.wrapping_add(1);

                for target in &targets {
                    let _ = socket_send.send_to(&packet, target).await;
                }

                offset = end;
            }
        }
    });
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("==================================================");
    println!(" SWD - PipeWire Audio Streaming & Virtual Mic Hub ");
    println!(" Technology Stack: Rust + pipewire-rs + UDP Direct");
    println!("==================================================");

    let (tx, _) = broadcast::channel::<Bytes>(128);

    let capture_rate = Arc::new(Mutex::new(96000));
    let capture_channels = Arc::new(Mutex::new(2));
    let total_frames = Arc::new(AtomicU64::new(0));
    let peak_left = Arc::new(Mutex::new(0.0));
    let peak_right = Arc::new(Mutex::new(0.0));

    // Spawn PipeWire desktop sink monitor capture thread
    start_pipewire_capture(
        tx.clone(),
        capture_rate.clone(),
        capture_channels.clone(),
        total_frames.clone(),
        peak_left.clone(),
        peak_right.clone(),
    );

    // Initialize Virtual Microphone State with 8KiB buffer
    let virtual_mic = Arc::new(VirtualMicState {
        buffer: Arc::new(Mutex::new(VecDeque::with_capacity(MIC_BUFFER_SAMPLES_8KIB))),
        is_streaming: Arc::new(AtomicBool::new(false)),
        sample_rate: Arc::new(AtomicU32::new(48000)),
        channels: Arc::new(AtomicU32::new(2)),
        peak: Arc::new(Mutex::new(0.0)),
        total_received_frames: Arc::new(AtomicU64::new(0)),
    });

    // Spawn PipeWire Virtual Microphone Source thread
    start_pipewire_virtual_mic(virtual_mic.clone());

    let udp_clients = Arc::new(UdpClientsState {
        clients: Mutex::new(HashMap::new()),
    });

    let state = Arc::new(SharedState {
        tx,
        capture_rate,
        capture_channels,
        total_frames,
        peak_left,
        peak_right,
        virtual_mic,
        udp_clients,
    });

    // Setup UDP Direct Streaming Socket on 0.0.0.0:9890
    let udp_addr = "0.0.0.0:9890";
    let udp_socket = match UdpSocket::bind(udp_addr).await {
        Ok(s) => {
            println!("[UDP] Successfully bound UDP direct engine on {}", udp_addr);
            Arc::new(s)
        }
        Err(e) => {
            println!("[UDP] Warning: Unable to bind UDP port 9890 ({}). Trying fallback 9892...", e);
            let fallback_addr = "0.0.0.0:9892";
            let s = UdpSocket::bind(fallback_addr).await
                .expect("Failed to bind UDP socket on fallback port 9892");
            println!("[UDP] Successfully bound UDP direct engine on fallback {}", fallback_addr);
            Arc::new(s)
        }
    };

    // Start UDP background streaming tasks
    start_udp_audio_service(state.clone(), udp_socket).await;

    // Setup HTTP & WebSocket Axum Server
    let app = Router::new()
        .route("/api/status", get(status_handler))
        .route("/api/audio/ws", get(ws_handler))
        .route("/api/audio/stream", get(http_stream_handler))
        .route("/api/audio/wav", get(http_wav_stream_handler))
        .route("/api/mic/ws", get(mic_ws_handler))
        .layer(CorsLayer::permissive())
        .with_state(state);

    let listen_addr = "0.0.0.0:9890";
    println!("[Axum] Starting backend status & audio server on {}", listen_addr);
    let listener = match tokio::net::TcpListener::bind(listen_addr).await {
        Ok(l) => l,
        Err(e) => {
            println!("[Axum] Port 9890 TCP already bound ({}). Falling back to 9892 TCP...", e);
            tokio::net::TcpListener::bind("0.0.0.0:9892").await?
        }
    };
    axum::serve(listener, app).await?;

    Ok(())
}
