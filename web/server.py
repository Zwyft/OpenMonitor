#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import dataclasses
import json
import os
import re
import shutil
import socket
import ssl
import threading
import time
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Iterable, Optional


ROOT = Path(__file__).resolve().parent
STATIC_DIR = ROOT / "static"
RUNTIME_DIR = ROOT / "runtime"
HLS_DIR = RUNTIME_DIR / "hls"

ONVIF_MULTICAST = ("239.255.255.250", 3702)
COMMON_HTTP_PORTS = [80, 81, 443, 8000, 8080, 8443, 8888, 8899]
COMMON_RTSP_PORTS = [554, 8554]
TCP_TIMEOUT = 0.4
HTTP_TIMEOUT = 1.4
SCAN_WORKERS = 64
BRIDGE_SEGMENT_SECONDS = 2
BRIDGE_PLAYLIST_SIZE = 6


@dataclass
class CameraEndpoint:
    id: str
    name: str
    source: str
    transport: str
    playback_strategy: str
    host: str
    port: Optional[int]
    path: str
    stream_url: str
    username: Optional[str] = None
    password: Optional[str] = None
    confidence: float = 0.5
    details: str = ""
    last_seen: float = field(default_factory=time.time)
    snapshot_url: Optional[str] = None
    is_baseus_candidate: bool = False

    @property
    def dedupe_key(self) -> str:
        return f"{self.transport}|{self.host.lower()}|{self.port or 0}|{self.path.lower()}"

    def to_dict(self) -> dict:
        data = dataclasses.asdict(self)
        data["last_seen"] = int(self.last_seen)
        return data


class CameraStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._endpoints: list[CameraEndpoint] = []
        self._custom_endpoints: list[CameraEndpoint] = []
        self._status = "Ready"
        self._is_refreshing = False
        self._last_refresh: Optional[float] = None
        self._error: Optional[str] = None

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "endpoints": [endpoint.to_dict() for endpoint in self._endpoints],
                "status_text": self._status,
                "is_refreshing": self._is_refreshing,
                "last_refresh": self._last_refresh,
                "error_message": self._error,
            }

    def set_custom_endpoint(self, endpoint: CameraEndpoint) -> CameraEndpoint:
        with self._lock:
            self._custom_endpoints = [
                existing for existing in self._custom_endpoints if existing.dedupe_key != endpoint.dedupe_key
            ] + [endpoint]
            self._endpoints = deduplicate_and_sort(self._endpoints + [endpoint])
        return endpoint

    def upsert_custom_endpoint(self, endpoint: CameraEndpoint) -> None:
        with self._lock:
            self._custom_endpoints = [
                existing for existing in self._custom_endpoints if existing.dedupe_key != endpoint.dedupe_key
            ] + [endpoint]
            self._endpoints = deduplicate_and_sort(self._endpoints + [endpoint])

    def refresh(self, deep_scan: bool) -> dict:
        with self._lock:
            if self._is_refreshing:
                return {
                    "endpoints": [endpoint.to_dict() for endpoint in self._endpoints],
                    "status_text": self._status,
                    "is_refreshing": self._is_refreshing,
                    "last_refresh": self._last_refresh,
                    "error_message": self._error,
                }
            self._is_refreshing = True
            self._error = None
            self._status = "Deep scanning the local subnet…" if deep_scan else "Looking for cameras on the LAN…"

        try:
            discovered = CameraDiscoverer().discover(deep_scan=deep_scan)
            with self._lock:
                combined = discovered + self._custom_endpoints
                self._endpoints = deduplicate_and_sort(combined)
                self._last_refresh = time.time()
                self._status = f"{len(self._endpoints)} camera candidate(s) found." if self._endpoints else "No cameras found yet."
        except Exception as exc:  # pragma: no cover - surfaced to UI
            with self._lock:
                self._error = str(exc)
                self._status = "Discovery failed."
        finally:
            with self._lock:
                self._is_refreshing = False

        return self.snapshot()


class RTSPHLSBridgeManager:
    def __init__(self) -> None:
        self._ffmpeg = shutil.which("ffmpeg")
        self._lock = threading.Lock()
        self._bridges: dict[str, dict] = {}
        self._processes: dict[str, subprocess.Popen] = {}
        RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
        HLS_DIR.mkdir(parents=True, exist_ok=True)

    def snapshot(self, bridge_id: str) -> Optional[dict]:
        with self._lock:
            bridge = self._bridges.get(bridge_id)
            if not bridge:
                return None

            process = self._processes.get(bridge_id)
            snapshot = dict(bridge)
            playlist_path = Path(snapshot["playlist_path"])

            if process and process.poll() is None:
                snapshot["status"] = "running" if playlist_path.exists() else "starting"
            elif "exit_code" in snapshot:
                snapshot["status"] = "running" if snapshot.get("exit_code") == 0 and playlist_path.exists() else "error"
            elif playlist_path.exists():
                snapshot["status"] = "running"

            return snapshot

    def start(self, endpoint: CameraEndpoint) -> dict:
        if not self._ffmpeg:
            raise RuntimeError(
                "ffmpeg is required for RTSP-to-HLS playback bridging. Install ffmpeg on the laptop and try again."
            )
        bridge_id = bridge_key_for(endpoint.stream_url)
        bridge_dir = HLS_DIR / bridge_id
        bridge_dir.mkdir(parents=True, exist_ok=True)

        playlist_path = bridge_dir / "index.m3u8"
        log_path = bridge_dir / "ffmpeg.log"

        self.stop(bridge_id)

        command = [
            self._ffmpeg,
            "-hide_banner",
            "-loglevel",
            "warning",
            "-rtsp_transport",
            "tcp",
            "-i",
            endpoint.stream_url,
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-ar",
            "44100",
            "-f",
            "hls",
            "-hls_time",
            str(BRIDGE_SEGMENT_SECONDS),
            "-hls_list_size",
            str(BRIDGE_PLAYLIST_SIZE),
            "-hls_flags",
            "delete_segments+append_list+omit_endlist+program_date_time",
            "-hls_segment_filename",
            str(bridge_dir / "segment-%05d.ts"),
            str(playlist_path),
        ]

        log_file = open(log_path, "ab", buffering=0)
        process = subprocess.Popen(command, stdout=log_file, stderr=log_file, cwd=str(bridge_dir))

        bridge = {
            "id": bridge_id,
            "status": "starting",
            "source_url": endpoint.stream_url,
            "playlist_url": f"/hls/{bridge_id}/index.m3u8",
            "playlist_path": str(playlist_path),
            "log_path": str(log_path),
            "pid": process.pid,
            "transport": endpoint.transport,
            "updated_at": int(time.time()),
        }

        with self._lock:
            self._bridges[bridge_id] = bridge
            self._processes[bridge_id] = process

        threading.Thread(target=self._watch_process, args=(bridge_id, process, log_file), daemon=True).start()
        return bridge

    def stop(self, bridge_id: str) -> None:
        with self._lock:
            process = self._processes.pop(bridge_id, None)
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()

    def _watch_process(self, bridge_id: str, process: subprocess.Popen, log_file) -> None:
        try:
            exit_code = process.wait()
        finally:
            try:
                log_file.close()
            except Exception:
                pass

        with self._lock:
            bridge = self._bridges.get(bridge_id, {})
            bridge.update(
                {
                    "status": "running" if exit_code == 0 else "error",
                    "exit_code": exit_code,
                    "updated_at": int(time.time()),
                }
            )
            self._bridges[bridge_id] = bridge


class CameraDiscoverer:
    def discover(self, deep_scan: bool) -> list[CameraEndpoint]:
        results = self._discover_onvif()
        if deep_scan:
            results.extend(self._scan_subnet())
        return deduplicate_and_sort(results)

    def _discover_onvif(self) -> list[CameraEndpoint]:
        results: list[CameraEndpoint] = []
        probe_body = f"""<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
            xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
            xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
            xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
    <e:Header>
        <w:MessageID>uuid:{uuid.uuid4()}</w:MessageID>
        <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
        <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
    </e:Header>
    <e:Body>
        <d:Probe>
            <d:Types>tds:Device</d:Types>
        </d:Probe>
    </e:Body>
</e:Envelope>
"""

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        try:
            sock.settimeout(1.5)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
            sock.sendto(probe_body.encode("utf-8"), ONVIF_MULTICAST)

            deadline = time.time() + 1.5
            while time.time() < deadline:
                try:
                    payload, _ = sock.recvfrom(65535)
                except socket.timeout:
                    break
                except OSError:
                    break

                xml_text = payload.decode("utf-8", errors="ignore")
                for xaddr in extract_xaddrs(xml_text):
                    resolved = self._resolve_onvif(xaddr)
                    if resolved:
                        results.append(resolved)
        finally:
            sock.close()

        return results

    def _resolve_onvif(self, device_service_url: str) -> Optional[CameraEndpoint]:
        try:
            device_xml = soap_request(
                device_service_url,
                "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
                """
                <tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
                    <tds:Category>All</tds:Category>
                </tds:GetCapabilities>
                """,
            )
            media_service = find_capability_xaddr(device_xml, "Media")
            if not media_service:
                return CameraEndpoint(
                    id=uuid.uuid4().hex,
                    name=url_host(device_service_url) or "ONVIF device",
                    source="ONVIF",
                    transport="HTTP",
                    playback_strategy="inspection",
                    host=url_host(device_service_url) or "",
                    port=url_port(device_service_url),
                    path=url_path(device_service_url),
                    stream_url=device_service_url,
                    confidence=0.85,
                    details="ONVIF device service discovered",
                )

            profiles_xml = soap_request(
                media_service,
                "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
                """
                <trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl" />
                """,
            )
            profile_token = find_profile_token(profiles_xml)
            if not profile_token:
                return None

            stream_xml = soap_request(
                media_service,
                "http://www.onvif.org/ver10/media/wsdl/GetStreamUri",
                f"""
                <trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
                    <trt:StreamSetup>
                        <tt:Stream>RTP-Unicast</tt:Stream>
                        <tt:Transport>
                            <tt:Protocol>RTSP</tt:Protocol>
                        </tt:Transport>
                    </trt:StreamSetup>
                    <trt:ProfileToken>{profile_token}</trt:ProfileToken>
                </trt:GetStreamUri>
                """,
            )
            stream_url = find_soap_uri(stream_xml) or device_service_url

            snapshot_url = None
            try:
                snapshot_xml = soap_request(
                    media_service,
                    "http://www.onvif.org/ver10/media/wsdl/GetSnapshotUri",
                    f"""
                    <trt:GetSnapshotUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
                        <trt:ProfileToken>{profile_token}</trt:ProfileToken>
                    </trt:GetSnapshotUri>
                    """,
                )
                snapshot_url = find_soap_uri(snapshot_xml)
            except Exception:
                snapshot_url = None

            host = url_host(stream_url) or url_host(device_service_url) or ""
            return CameraEndpoint(
                id=uuid.uuid4().hex,
                name=host or "ONVIF camera",
                source="ONVIF",
                transport="RTSP" if stream_url.lower().startswith("rtsp://") else "HTTP",
                playback_strategy="hls" if stream_url.lower().endswith(".m3u8") else "stream",
                host=host,
                port=url_port(stream_url),
                path=url_path(stream_url),
                stream_url=stream_url,
                confidence=0.98,
                details="ONVIF device and media services resolved",
                snapshot_url=snapshot_url,
            )
        except Exception:
            return None

    def _scan_subnet(self) -> list[CameraEndpoint]:
        prefix = local_ipv4_prefix()
        if not prefix:
            return []

        local_ip = local_ipv4_address()
        hosts = [f"{prefix}.{octet}" for octet in range(1, 255) if f"{prefix}.{octet}" != local_ip]
        results: list[CameraEndpoint] = []

        def probe_host(host: str) -> list[CameraEndpoint]:
            discovered: list[CameraEndpoint] = []
            for port in COMMON_RTSP_PORTS + COMMON_HTTP_PORTS:
                if not tcp_port_open(host, port, TCP_TIMEOUT):
                    continue

                if port in COMMON_RTSP_PORTS:
                    discovered.append(
                        CameraEndpoint(
                            id=uuid.uuid4().hex,
                            name=f"RTSP @ {host}",
                            source="LAN probe",
                            transport="RTSP",
                            playback_strategy="stream",
                            host=host,
                            port=port,
                            path="/",
                            stream_url=f"rtsp://{host}:{port}/",
                            confidence=0.64,
                            details="Open RTSP port discovered during subnet scan",
                        )
                    )
                    continue

                try:
                    response = fetch_http(f"http://{host}:{port}/", timeout=HTTP_TIMEOUT)
                except Exception:
                    continue

                body = response["body"]
                title = response["title"] or host
                lower = body.lower()
                baseus_candidate = any(term in lower for term in ("baseus", "homestation", "x1 pro", "security"))
                stream_urls = extract_stream_urls(body, f"http://{host}:{port}/")
                snapshot_url = extract_snapshot_url(body, f"http://{host}:{port}/")

                if stream_urls:
                    stream_url = stream_urls[0]
                    discovered.append(
                        CameraEndpoint(
                            id=uuid.uuid4().hex,
                            name=title,
                            source="Baseus probe" if baseus_candidate else "LAN probe",
                            transport=stream_transport(stream_url),
                            playback_strategy="hls" if stream_url.lower().endswith(".m3u8") else "stream",
                            host=host,
                            port=port,
                            path=url_path(stream_url),
                            stream_url=stream_url,
                            confidence=0.74 if baseus_candidate else 0.7,
                            details="Stream URL extracted from an HTTP page",
                            snapshot_url=snapshot_url,
                            is_baseus_candidate=baseus_candidate,
                        )
                    )

                if baseus_candidate or not discovered:
                    discovered.append(
                        CameraEndpoint(
                            id=uuid.uuid4().hex,
                            name=title if title else f"HTTP service @ {host}",
                            source="Baseus probe" if baseus_candidate else "LAN probe",
                            transport="HTTP",
                            playback_strategy="inspection",
                            host=host,
                            port=port,
                            path="/",
                            stream_url=f"http://{host}:{port}/",
                            confidence=0.52 if baseus_candidate else 0.22,
                            details="HTTP service responded during subnet scan",
                            snapshot_url=snapshot_url,
                            is_baseus_candidate=baseus_candidate,
                        )
                    )

            return discovered

        with concurrent.futures.ThreadPoolExecutor(max_workers=SCAN_WORKERS) as executor:
            futures = [executor.submit(probe_host, host) for host in hosts]
            for future in concurrent.futures.as_completed(futures):
                try:
                    results.extend(future.result())
                except Exception:
                    continue

        return results


class DashboardHandler(SimpleHTTPRequestHandler):
    server_version = "OpenMonitorWeb/1.0"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(STATIC_DIR), **kwargs)

    @property
    def store(self) -> CameraStore:
        return self.server.store  # type: ignore[attr-defined]

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        super().end_headers()

    def do_GET(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path in {"/", "/index.html"}:
            self.path = "/index.html"
            return super().do_GET()

        if parsed.path == "/api/state":
            self._write_json(self.store.snapshot())
            return

        if parsed.path == "/api/scan":
            query = urllib.parse.parse_qs(parsed.query)
            deep_scan = query.get("deep", ["0"])[0] == "1"
            self._write_json(self.store.refresh(deep_scan=deep_scan))
            return

        if parsed.path == "/api/bridge":
            query = urllib.parse.parse_qs(parsed.query)
            bridge_id = query.get("id", [None])[0]
            if not bridge_id:
                self.send_error(HTTPStatus.BAD_REQUEST, "Missing bridge id")
                return
            bridge = BRIDGE_MANAGER.snapshot(bridge_id)
            if not bridge:
                self.send_error(HTTPStatus.NOT_FOUND, "Bridge not found")
                return
            self._write_json(bridge)
            return

        if parsed.path == "/api/proxy":
            self._proxy(parsed)
            return

        if parsed.path.startswith("/hls/"):
            self._serve_hls_file(parsed.path)
            return

        self.send_error(HTTPStatus.NOT_FOUND, "Not found")

    def do_POST(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path == "/api/manual":
            payload = self._read_json()
            endpoint = manual_endpoint_from_payload(payload)
            stored = self.store.set_custom_endpoint(endpoint)
            self._write_json({"endpoint": stored.to_dict(), "state": self.store.snapshot()}, status=HTTPStatus.CREATED)
            return

        if parsed.path == "/api/bridge":
            payload = self._read_json()
            endpoint = endpoint_from_payload(payload)
            try:
                bridge = BRIDGE_MANAGER.start(endpoint)
            except Exception as exc:
                self.send_error(HTTPStatus.BAD_REQUEST, str(exc))
                return
            self.store.upsert_custom_endpoint(endpoint)
            self._write_json({"bridge": bridge, "state": self.store.snapshot()}, status=HTTPStatus.CREATED)
            return

        self.send_error(HTTPStatus.NOT_FOUND, "Not found")

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        payload = self.rfile.read(length).decode("utf-8") if length else "{}"
        return json.loads(payload)

    def _write_json(self, payload: dict, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _proxy(self, parsed: urllib.parse.SplitResult) -> None:
        query = urllib.parse.parse_qs(parsed.query)
        target = query.get("url", [None])[0]
        if not target:
            self.send_error(HTTPStatus.BAD_REQUEST, "Missing url")
            return

        try:
            response = open_http(target, timeout=HTTP_TIMEOUT, request_headers={
                "Range": self.headers.get("Range", ""),
            })
        except Exception as exc:
            self.send_error(HTTPStatus.BAD_GATEWAY, str(exc))
            return

        try:
            content_type = response.headers.get("Content-Type", "application/octet-stream")
            if is_playlist(target, content_type):
                playlist = response.read().decode("utf-8", errors="ignore")
                rewritten = rewrite_playlist(playlist, target).encode("utf-8")
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(rewritten)))
                self.send_header("Cache-Control", "no-store, max-age=0")
                self.end_headers()
                self.wfile.write(rewritten)
                return

            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-store, max-age=0")
            self.end_headers()

            while True:
                chunk = response.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
        finally:
            try:
                response.close()
            except Exception:
                pass

    def _serve_hls_file(self, path: str) -> None:
        relative_path = path[len("/hls/"):]
        file_path = (HLS_DIR / relative_path).resolve()
        if not str(file_path).startswith(str(HLS_DIR.resolve())):
            self.send_error(HTTPStatus.BAD_REQUEST, "Invalid path")
            return

        if not file_path.exists() or not file_path.is_file():
            self.send_error(HTTPStatus.NOT_FOUND, "Not found")
            return

        content_type = "application/vnd.apple.mpegurl" if file_path.suffix == ".m3u8" else "video/MP2T"
        data = file_path.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.end_headers()
        self.wfile.write(data)


class RoutedHTTPServer(ThreadingHTTPServer):
    def __init__(self, server_address, RequestHandlerClass, store: CameraStore):
        super().__init__(server_address, RequestHandlerClass)
        self.store = store


BRIDGE_MANAGER: RTSPHLSBridgeManager


def endpoint_from_payload(payload: dict, source: str = "Manual") -> CameraEndpoint:
    mode = (payload.get("mode") or "rtsp").lower()
    host = (payload.get("host") or "").strip()
    if not host:
        raise ValueError("Host is required")

    port = int(payload.get("port") or (554 if mode == "rtsp" else 80))
    path = (payload.get("path") or ("/onvif/device_service" if mode == "onvif" else "/stream")).strip()
    username = (payload.get("username") or "").strip() or None
    password = (payload.get("password") or "").strip() or None

    if mode == "rtsp":
        stream_url = build_url("rtsp", host, port, path, username, password)
        playback_strategy = "stream"
        transport = "RTSP"
        details = "Manual RTSP stream"
    elif mode == "hls":
        stream_url = build_url("http", host, port, path, username, password)
        playback_strategy = "hls"
        transport = "HLS"
        details = "Manual HLS stream"
    else:
        stream_url = build_url("http", host, port, path, username, password)
        playback_strategy = "onvif"
        transport = "HTTP"
        details = "Manual ONVIF device service"

    return CameraEndpoint(
        id=uuid.uuid4().hex,
        name=payload.get("name") or host,
        source=source,
        transport=transport,
        playback_strategy=playback_strategy,
        host=host,
        port=port,
        path=path,
        stream_url=stream_url,
        username=username,
        password=password,
        confidence=1.0,
        details=details,
    )


def manual_endpoint_from_payload(payload: dict) -> CameraEndpoint:
    return endpoint_from_payload(payload, source="Manual")


def build_url(scheme: str, host: str, port: int, path: str, username: Optional[str], password: Optional[str]) -> str:
    path = path if path.startswith("/") else f"/{path}"
    netloc = host
    if username:
        userinfo = urllib.parse.quote(username, safe="")
        if password is not None:
            userinfo += ":" + urllib.parse.quote(password, safe="")
        netloc = f"{userinfo}@{host}"
    return urllib.parse.urlunsplit((scheme, f"{netloc}:{port}", path, "", ""))


def bridge_key_for(stream_url: str) -> str:
    return re.sub(r"[^a-zA-Z0-9]+", "-", stream_url).strip("-").lower()[:64] or uuid.uuid4().hex


def url_host(value: str) -> Optional[str]:
    return urllib.parse.urlsplit(value).hostname


def url_port(value: str) -> Optional[int]:
    return urllib.parse.urlsplit(value).port


def url_path(value: str) -> str:
    return urllib.parse.urlsplit(value).path or "/"


def stream_transport(stream_url: str) -> str:
    scheme = urllib.parse.urlsplit(stream_url).scheme.lower()
    if scheme == "rtsp":
        return "RTSP"
    if scheme in {"http", "https"} and stream_url.lower().endswith(".m3u8"):
        return "HLS"
    if scheme in {"http", "https"}:
        return "HTTP"
    return scheme.upper() or "HTTP"


def local_ipv4_address() -> Optional[str]:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("1.1.1.1", 80))
        return sock.getsockname()[0]
    except OSError:
        return None
    finally:
        sock.close()


def local_ipv4_prefix() -> Optional[str]:
    address = local_ipv4_address()
    if not address:
        return None
    parts = address.split(".")
    if len(parts) != 4:
        return None
    return ".".join(parts[:3])


def tcp_port_open(host: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def open_http(url: str, timeout: float = HTTP_TIMEOUT, request_headers: Optional[dict] = None):
    request = urllib.request.Request(url, method="GET")
    for key, value in (request_headers or {}).items():
        if value:
            request.add_header(key, value)

    parsed = urllib.parse.urlsplit(url)
    if parsed.username:
        username = urllib.parse.unquote(parsed.username)
        password = urllib.parse.unquote(parsed.password or "")
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        request.add_header("Authorization", f"Basic {token}")

    context = ssl._create_unverified_context() if parsed.scheme == "https" else None
    return urllib.request.urlopen(request, timeout=timeout, context=context)


def fetch_http(url: str, timeout: float = HTTP_TIMEOUT, request_headers: Optional[dict] = None) -> dict:
    response = open_http(url, timeout=timeout, request_headers=request_headers)
    try:
        headers = dict(response.headers.items())
        body_bytes = response.read()
        body_text = body_bytes.decode("utf-8", errors="ignore")
        title = extract_title(body_text)
        return {
            "headers": headers,
            "body_bytes": body_bytes,
            "body": body_text,
            "title": title or headers.get("Server"),
        }
    finally:
        response.close()


def soap_request(url: str, action: str, body: str) -> str:
    envelope = f"""<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope">
    <e:Header />
    <e:Body>
        {body}
    </e:Body>
</e:Envelope>
"""
    request = urllib.request.Request(url, data=envelope.encode("utf-8"), method="POST")
    request.add_header("Content-Type", "text/xml; charset=utf-8")
    request.add_header("SOAPAction", f'"{action}"')

    parsed = urllib.parse.urlsplit(url)
    if parsed.username:
        username = urllib.parse.unquote(parsed.username)
        password = urllib.parse.unquote(parsed.password or "")
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        request.add_header("Authorization", f"Basic {token}")

    context = ssl._create_unverified_context() if parsed.scheme == "https" else None
    with urllib.request.urlopen(request, timeout=3.0, context=context) as response:
        return response.read().decode("utf-8", errors="ignore")


def extract_xaddrs(xml_text: str) -> list[str]:
    values = []
    for chunk in regex_capture(r"<(?:\w+:)?XAddrs[^>]*>(.*?)</(?:\w+:)?XAddrs>", xml_text, flags=re.I | re.S):
        for item in re.split(r"\s+", chunk.strip()):
            if item.startswith("http://") or item.startswith("https://"):
                values.append(item)
    return values


def find_capability_xaddr(xml_text: str, capability_name: str) -> Optional[str]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return None

    for element in root.iter():
        if element.tag.endswith(capability_name):
            child = next((child for child in element if child.tag.endswith("XAddr")), None)
            if child is not None and child.text:
                return child.text.strip()
    return None


def find_profile_token(xml_text: str) -> Optional[str]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return None

    for element in root.iter():
        if element.tag.endswith("Profiles"):
            token = element.attrib.get("token")
            if token:
                return token
    return None


def find_soap_uri(xml_text: str) -> Optional[str]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return None

    for element in root.iter():
        if element.tag.endswith("Uri") and element.text:
            return element.text.strip()
    return None


def extract_stream_urls(body: str, base_url: str) -> list[str]:
    candidates = regex_capture(r"""rtsp://[^\s"'<>]+|https?://[^\s"'<>]+\.m3u8[^\s"'<>]*|https?://[^\s"'<>]+\.mp4[^\s"'<>]*""", body, flags=re.I)
    resolved: list[str] = []
    for candidate in candidates:
        cleaned = candidate.rstrip("\"'<>),.;")
        resolved.append(urllib.parse.urljoin(base_url, cleaned))
    return deduplicate_strings(resolved)


def extract_snapshot_url(body: str, base_url: str) -> Optional[str]:
    for pattern in (
        r"""https?://[^\s"'<>]+(?:snapshot|snap|image|jpg|jpeg)[^\s"'<>]*""",
        r"""/(?:snapshot|snap|image|jpg|jpeg)[^\s"'<>]*""",
    ):
        matches = regex_capture(pattern, body, flags=re.I)
        if matches:
            return urllib.parse.urljoin(base_url, matches[0].rstrip("\"'<>),.;"))
    return None


def regex_capture(pattern: str, text: str, flags: int = 0) -> list[str]:
    try:
        compiled = re.compile(pattern, flags)
    except re.error:
        return []
    results: list[str] = []
    for match in compiled.finditer(text):
        if match.groups():
            results.append(match.group(1))
        else:
            results.append(match.group(0))
    return results


def extract_title(body: str) -> Optional[str]:
    match = re.search(r"<title[^>]*>(.*?)</title>", body, flags=re.I | re.S)
    if not match:
        return None
    title = re.sub(r"\s+", " ", match.group(1)).strip()
    return title or None


def is_playlist(url: str, content_type: str) -> bool:
    lower = f"{url} {content_type}".lower()
    return ".m3u8" in lower or "mpegurl" in lower


def rewrite_playlist(playlist: str, playlist_url: str) -> str:
    rewritten: list[str] = []
    for line in playlist.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            rewritten.append(line)
            continue
        target = urllib.parse.urljoin(playlist_url, stripped)
        rewritten.append(f"/api/proxy?url={urllib.parse.quote(target, safe='')}")
    return "\n".join(rewritten) + "\n"


def deduplicate_strings(values: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    output: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            output.append(value)
    return output


def deduplicate_and_sort(endpoints: list[CameraEndpoint]) -> list[CameraEndpoint]:
    by_key: dict[str, CameraEndpoint] = {}
    for endpoint in endpoints:
        key = endpoint.dedupe_key
        existing = by_key.get(key)
        if existing is None:
            by_key[key] = endpoint
            continue
        if endpoint.confidence > existing.confidence or endpoint.last_seen > existing.last_seen:
            by_key[key] = merge_endpoints(existing, endpoint)

    return sorted(
        by_key.values(),
        key=lambda item: (-item.confidence, item.source.lower(), item.name.lower()),
    )


def merge_endpoints(existing: CameraEndpoint, replacement: CameraEndpoint) -> CameraEndpoint:
    merged = dataclasses.replace(existing)
    merged.source = replacement.source if replacement.confidence >= existing.confidence else existing.source
    merged.name = replacement.name or existing.name
    merged.transport = replacement.transport
    merged.playback_strategy = replacement.playback_strategy
    merged.stream_url = replacement.stream_url
    merged.username = replacement.username or existing.username
    merged.password = replacement.password or existing.password
    merged.confidence = max(existing.confidence, replacement.confidence)
    merged.details = " • ".join(filter(None, [existing.details, replacement.details]))
    merged.last_seen = max(existing.last_seen, replacement.last_seen)
    merged.snapshot_url = replacement.snapshot_url or existing.snapshot_url
    merged.is_baseus_candidate = existing.is_baseus_candidate or replacement.is_baseus_candidate
    return merged


def main() -> None:
    global BRIDGE_MANAGER

    parser = argparse.ArgumentParser(description="OpenMonitor browser dashboard")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()

    store = CameraStore()
    BRIDGE_MANAGER = RTSPHLSBridgeManager()
    threading.Thread(target=store.refresh, kwargs={"deep_scan": False}, daemon=True).start()

    server = RoutedHTTPServer((args.host, args.port), DashboardHandler, store)
    print(f"OpenMonitor browser dashboard: http://{args.host}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
