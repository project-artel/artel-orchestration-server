"""SDK 미디어를 받아 브라우저로 중계하고 QA 영상을 MP4 segment로 저장한다."""

import asyncio
import logging
import os
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from fractions import Fraction
from pathlib import Path
from typing import Literal
from uuid import UUID, uuid4

import av
import httpx
from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription
from aiortc.contrib.media import MediaRelay
from aiortc.mediastreams import MediaStreamError
from aiortc.sdp import candidate_from_sdp
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
ROOT = Path(os.environ.get("SEGMENT_DIRECTORY", "/tmp/artel-segments"))
ORCH = os.environ.get("ORCHESTRATION_INTERNAL_URL", "http://orchestration:8081").rstrip("/")
MAX_INGRESS = int(os.environ.get("MAX_INGRESS", "8"))
MAX_VIEWERS = int(os.environ.get("MAX_VIEWERS_PER_INGRESS", "8"))
MAX_PENDING_BYTES = 256 * 1024 * 1024
MAX_SEGMENT_BYTES = 32 * 1024 * 1024


def timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


class IceServer(BaseModel):
    urls: list[str]
    username: str | None = None
    credential: str | None = None


class StreamConfiguration(BaseModel):
    iceServers: list[IceServer] = Field(default_factory=list)
    recordingId: UUID | None = None


class IceCandidate(BaseModel):
    candidate: str
    sdpMid: str | None = None
    sdpMLineIndex: int | None = None


class Signal(BaseModel):
    type: Literal["WEBRTC_OFFER", "WEBRTC_ANSWER", "WEBRTC_ICE", "STREAM_STATE"]
    streamId: str
    sdp: str | None = None
    candidate: IceCandidate | None = None
    state: str | None = None


class UploadTarget(BaseModel):
    url: str
    requiredHeaders: dict[str, str]


@dataclass
class Segment:
    id: UUID
    recording: UUID
    path: Path
    started: str
    ended: str


class Mp4Writer:
    def __init__(self, recording: UUID, frame: av.VideoFrame) -> None:
        self.id = uuid4()
        self.recording = recording
        self.path = ROOT / f"{self.id}.mp4"
        self.started = timestamp()
        self.monotonic_start = time.monotonic()
        self.container = av.open(str(self.path), "w", format="mp4", options={"movflags": "+faststart"})
        self.stream = self.container.add_stream("libx264", rate=30)
        self.stream.width = min(frame.width, 1280) // 2 * 2
        self.stream.height = int(frame.height * self.stream.width / frame.width) // 2 * 2
        self.stream.pix_fmt = "yuv420p"
        self.stream.time_base = Fraction(1, 90000)
        self.stream.codec_context.time_base = Fraction(1, 90000)
        self.stream.codec_context.max_b_frames = 0
        self.stream.options = {"crf": "24", "preset": "veryfast"}
        self.last_pts = -1

    def write(self, frame: av.VideoFrame, received: float) -> None:
        # 브라우저와 공유하는 원본 frame의 PTS는 바꾸지 않는다.
        resized = frame.reformat(width=self.stream.width, height=self.stream.height, format="yuv420p")
        copied = av.VideoFrame.from_ndarray(resized.to_ndarray(format="yuv420p"), format="yuv420p")
        copied.pts = max(self.last_pts + 1, round((received - self.monotonic_start) * 90000))
        copied.time_base = Fraction(1, 90000)
        self.last_pts = copied.pts
        for packet in self.stream.encode(copied):
            self.container.mux(packet)

    def finish(self) -> Segment:
        for packet in self.stream.encode():
            self.container.mux(packet)
        self.container.close()
        return Segment(self.id, self.recording, self.path, self.started, timestamp())


class Ingress:
    def __init__(self, configuration: StreamConfiguration) -> None:
        self.configuration = RTCConfiguration(iceServers=[RTCIceServer(**server.model_dump()) for server in configuration.iceServers])
        self.peer = RTCPeerConnection(self.configuration)
        self.recording = configuration.recordingId
        self.relay = MediaRelay()
        self.video = None
        self.video_ready = asyncio.Event()
        self.viewers: dict[str, RTCPeerConnection] = {}
        self.last_lease = time.monotonic()
        self.negotiation_started = self.last_lease
        self.queue: asyncio.Queue[Segment] = asyncio.Queue(maxsize=10)
        self.pending_bytes = 0
        self.consumer: asyncio.Task | None = None
        self.uploader = asyncio.create_task(self.upload_loop())
        self.closed = False
        self.failed = False

        @self.peer.on("track")
        def track_received(track):
            if track.kind == "video" and self.video is None:
                self.video = track
                self.video_ready.set()
                self.consumer = asyncio.create_task(self.consume(self.relay.subscribe(track, buffered=False)))

    async def gap(self, recording: UUID, start: str, end: str, reason: str) -> None:
        try:
            async with httpx.AsyncClient(timeout=10) as client:
                response = await client.post(f"{ORCH}/internal/qa/recordings/{recording}/gaps",
                    json={"id": str(uuid4()), "startedAt": start, "endedAt": end, "reason": reason})
                response.raise_for_status()
        except Exception:
            logger.exception("영상 누락 구간을 기록하지 못했습니다.")

    async def enqueue(self, segment: Segment) -> None:
        size = segment.path.stat().st_size
        if self.queue.full() or self.pending_bytes + size > MAX_PENDING_BYTES or size > MAX_SEGMENT_BYTES:
            await self.gap(segment.recording, segment.started, segment.ended, "UPLOAD_BACKLOG")
            segment.path.unlink(missing_ok=True)
            return
        self.pending_bytes += size
        self.queue.put_nowait(segment)

    async def consume(self, track) -> None:
        writer = None
        async def encode(function, *args):
            # close가 호출돼도 진행 중인 encode가 끝난 뒤에 container를 닫는다.
            pending = asyncio.create_task(asyncio.to_thread(function, *args))
            try:
                return await asyncio.shield(pending)
            except asyncio.CancelledError:
                self.closed = True
                return await pending
        try:
            while not self.closed:
                frame = await track.recv()
                received = time.monotonic()
                if writer and (writer.recording != self.recording or received - writer.monotonic_start >= 60
                               or (writer.path.exists() and writer.path.stat().st_size >= MAX_SEGMENT_BYTES)):
                    segment = await encode(writer.finish)
                    writer = None
                    await self.enqueue(segment)
                if self.recording is not None:
                    if writer is None:
                        writer = await encode(Mp4Writer, self.recording, frame)
                    await encode(writer.write, frame, received)
        except MediaStreamError:
            self.failed = not self.closed
        except asyncio.CancelledError:
            pass
        except Exception:
            self.failed = True
            logger.exception("영상 인코딩을 중단했습니다.")
            if writer:
                await self.gap(writer.recording, writer.started, timestamp(), "ENCODING_FAILED")
        finally:
            track.stop()
            if writer:
                try:
                    await self.enqueue(await asyncio.to_thread(writer.finish))
                except Exception:
                    writer.path.unlink(missing_ok=True)

    async def upload_loop(self) -> None:
        async with httpx.AsyncClient(timeout=30) as client:
            while True:
                segment = await self.queue.get()
                size = segment.path.stat().st_size
                try:
                    for attempt in range(3):
                        try:
                            endpoint = f"{ORCH}/internal/qa/recordings/{segment.recording}/segments/{segment.id}"
                            response = await client.post(endpoint, json={"startedAt": segment.started,
                                "endedAt": segment.ended, "sizeBytes": size})
                            response.raise_for_status()
                            target = UploadTarget.model_validate(response.json())
                            async def chunks():
                                with segment.path.open("rb") as source:
                                    while chunk := await asyncio.to_thread(source.read, 1024 * 1024):
                                        yield chunk
                            uploaded = await client.put(target.url, headers={**target.requiredHeaders, "Content-Length": str(size)}, content=chunks())
                            uploaded.raise_for_status()
                            confirmed = await client.post(endpoint + "/complete")
                            confirmed.raise_for_status()
                            break
                        except Exception:
                            if attempt == 2:
                                await self.gap(segment.recording, segment.started, segment.ended, "UPLOAD_FAILED")
                            else:
                                await asyncio.sleep(2 ** attempt)
                finally:
                    segment.path.unlink(missing_ok=True)
                    self.pending_bytes -= size
                    self.queue.task_done()

    async def close(self) -> None:
        self.closed = True
        if self.consumer:
            self.consumer.cancel()
            await asyncio.gather(self.consumer, return_exceptions=True)
        await self.peer.close()
        for peer in self.viewers.values():
            for sender in peer.getSenders():
                if sender.track:
                    sender.track.stop()
        await asyncio.gather(*(peer.close() for peer in self.viewers.values()))
        try:
            await asyncio.wait_for(self.queue.join(), timeout=100)
        except TimeoutError:
            pass
        self.uploader.cancel()
        await asyncio.gather(self.uploader, return_exceptions=True)
        while not self.queue.empty():
            segment = self.queue.get_nowait()
            segment.path.unlink(missing_ok=True)
            self.queue.task_done()


streams: dict[UUID, Ingress] = {}
closing: set[asyncio.Task] = set()


def close_in_background(stream: Ingress):
    task = asyncio.create_task(stream.close())
    closing.add(task)
    task.add_done_callback(closing.discard)


async def expire_leases():
    while True:
        await asyncio.sleep(10)
        expired = [identifier for identifier, stream in streams.items() if time.monotonic() - stream.last_lease > 90]
        for identifier in expired:
            stream = streams.pop(identifier, None)
            if stream:
                close_in_background(stream)


@asynccontextmanager
async def lifespan(app):
    ROOT.mkdir(parents=True, exist_ok=True)
    # 전용 디렉터리에 남은 미완성 파일은 재사용하지 않는다. DB의 미완료 segment가 누락을 표시한다.
    for path in ROOT.glob("*.mp4"):
        path.unlink()
    expiry = asyncio.create_task(expire_leases())
    yield
    expiry.cancel()
    await asyncio.gather(expiry, return_exceptions=True)
    await asyncio.gather(*(stream.close() for stream in streams.values()))
    await asyncio.gather(*closing, return_exceptions=True)


app = FastAPI(lifespan=lifespan)


def stream_of(identifier: UUID) -> Ingress:
    stream = streams.get(identifier)
    if stream is None:
        raise HTTPException(404, "stream을 찾을 수 없습니다.")
    return stream


async def apply_signal(peer: RTCPeerConnection, signal: Signal):
    if signal.type in ("WEBRTC_OFFER", "WEBRTC_ANSWER"):
        if not signal.sdp:
            raise HTTPException(400, "SDP가 없습니다.")
        await peer.setRemoteDescription(RTCSessionDescription(sdp=signal.sdp, type="offer" if signal.type == "WEBRTC_OFFER" else "answer"))
    elif signal.type == "WEBRTC_ICE" and signal.candidate:
        value = signal.candidate
        if value.candidate:
            candidate = candidate_from_sdp(value.candidate.removeprefix("candidate:"))
            candidate.sdpMid, candidate.sdpMLineIndex = value.sdpMid, value.sdpMLineIndex
            await peer.addIceCandidate(candidate)
        else:
            await peer.addIceCandidate(None)


@app.put("/internal/streams/{identifier}")
async def configure(identifier: UUID, configuration: StreamConfiguration):
    stream = streams.get(identifier)
    created = stream is None
    if created:
        if len(streams) + len(closing) >= MAX_INGRESS:
            raise HTTPException(503, "동시 녹화 상한에 도달했습니다.")
        stream = streams[identifier] = Ingress(configuration)
    stream.last_lease = time.monotonic()
    stream.recording = configuration.recordingId
    # 임대 갱신만으로 SDP가 오지 않는 연결을 무기한 유지하지 않는다.
    if stream.peer.connectionState in ("new", "connecting") and stream.last_lease - stream.negotiation_started >= 60:
        stream.failed = True
    return {"created": created, "state": "failed" if stream.failed else stream.peer.connectionState}


@app.post("/internal/streams/{identifier}/signal")
async def sdk_signal(identifier: UUID, signal: Signal):
    stream = stream_of(identifier)
    if signal.type == "STREAM_STATE" and signal.state in ("FAILED", "STOPPED"):
        stream.failed = True
        return None
    await apply_signal(stream.peer, signal)
    if signal.type == "WEBRTC_OFFER":
        await stream.peer.setLocalDescription(await stream.peer.createAnswer())
        return {"type": "WEBRTC_ANSWER", "streamId": str(identifier), "sdp": stream.peer.localDescription.sdp}
    return None


@app.post("/internal/streams/{identifier}/viewers/{viewer}")
async def open_viewer(identifier: UUID, viewer: UUID):
    stream = stream_of(identifier)
    if len(stream.viewers) >= MAX_VIEWERS and str(viewer) not in stream.viewers:
        raise HTTPException(503, "동시 영상 열람 상한에 도달했습니다.")
    await asyncio.wait_for(stream.video_ready.wait(), timeout=20)
    previous = stream.viewers.pop(str(viewer), None)
    if previous:
        for sender in previous.getSenders():
            if sender.track:
                sender.track.stop()
        await previous.close()
    peer = stream.viewers[str(viewer)] = RTCPeerConnection(stream.configuration)
    peer.addTrack(stream.relay.subscribe(stream.video, buffered=False))
    await peer.setLocalDescription(await peer.createOffer())
    return {"type": "WEBRTC_OFFER", "streamId": str(viewer), "sdp": peer.localDescription.sdp}


@app.post("/internal/streams/{identifier}/viewers/{viewer}/signal")
async def viewer_signal(identifier: UUID, viewer: UUID, signal: Signal):
    peer = stream_of(identifier).viewers.get(str(viewer))
    if peer is None:
        raise HTTPException(404, "viewer를 찾을 수 없습니다.")
    await apply_signal(peer, signal)


@app.delete("/internal/streams/{identifier}/viewers/{viewer}")
async def close_viewer(identifier: UUID, viewer: UUID):
    stream = streams.get(identifier)
    peer = stream.viewers.pop(str(viewer), None) if stream else None
    if peer:
        for sender in peer.getSenders():
            if sender.track:
                sender.track.stop()
        await peer.close()


@app.delete("/internal/streams/{identifier}")
async def close_stream(identifier: UUID):
    stream = streams.pop(identifier, None)
    if stream:
        close_in_background(stream)


@app.get("/health")
async def health():
    return {"status": "ok", "streams": len(streams)}
