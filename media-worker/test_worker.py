import asyncio
from pathlib import Path
from unittest.mock import AsyncMock
from uuid import uuid4

import av
import numpy as np
from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription, VideoStreamTrack

import worker


def test_sdk_terminal_state_marks_ingress_for_recovery():
    async def run():
        for state in ("FAILED", "STOPPED"):
            identifier = uuid4()
            configuration = worker.StreamConfiguration()
            try:
                await worker.configure(identifier, configuration)
                await worker.sdk_signal(identifier, worker.Signal(type="STREAM_STATE", streamId=str(identifier), state=state))
                assert (await worker.configure(identifier, configuration))["state"] == "failed"
            finally:
                await worker.close_stream(identifier)
                await asyncio.gather(*worker.closing)
    asyncio.run(run())


def test_lease_renewal_does_not_extend_missing_offer_deadline(monkeypatch):
    async def run():
        identifier = uuid4()
        configuration = worker.StreamConfiguration()
        try:
            monkeypatch.setattr(worker.time, "monotonic", lambda: 100.0)
            assert (await worker.configure(identifier, configuration))["state"] == "new"
            monkeypatch.setattr(worker.time, "monotonic", lambda: 159.0)
            assert (await worker.configure(identifier, configuration))["state"] == "new"
            monkeypatch.setattr(worker.time, "monotonic", lambda: 160.0)
            assert (await worker.configure(identifier, configuration))["state"] == "failed"
        finally:
            monkeypatch.undo()
            await worker.close_stream(identifier)
            await asyncio.gather(*worker.closing)
    asyncio.run(run())


def test_mp4_preserves_source_frame_timestamps(tmp_path, monkeypatch):
    monkeypatch.setattr(worker, "ROOT", tmp_path)
    frame = av.VideoFrame.from_ndarray(np.zeros((64, 96, 3), dtype=np.uint8), format="rgb24")
    frame.pts = 42
    writer = worker.Mp4Writer(uuid4(), frame)
    for index in range(12):
        writer.write(frame, writer.monotonic_start + index / 30)
    segment = writer.finish()
    assert frame.pts == 42
    with av.open(str(segment.path)) as container:
        frames = list(container.decode(video=0))
        assert len(frames) == 12
        assert frames[-1].time > frames[0].time
        assert container.streams.video[0].codec_context.name == "h264"


def test_upload_backlog_is_bounded_and_records_gap(tmp_path):
    async def run():
        stream = worker.Ingress(worker.StreamConfiguration())
        stream.gap = AsyncMock()
        stream.pending_bytes = worker.MAX_PENDING_BYTES
        path = tmp_path / "overflow.mp4"
        path.write_bytes(b"video")
        segment = worker.Segment(uuid4(), uuid4(), path, worker.timestamp(), worker.timestamp())
        await stream.enqueue(segment)
        assert not path.exists()
        assert stream.queue.empty()
        assert stream.gap.await_args.args[-1] == "UPLOAD_BACKLOG"
        await stream.close()
    asyncio.run(run())


def test_sdk_ingress_relays_to_browser_and_records_without_browser(tmp_path, monkeypatch):
    monkeypatch.setattr(worker, "ROOT", tmp_path)
    async def run():
        identifier, viewer_id = uuid4(), uuid4()
        stream = worker.Ingress(worker.StreamConfiguration(recordingId=uuid4()))
        stream.enqueue = AsyncMock()
        stream.gap = AsyncMock()
        worker.streams[identifier] = stream
        sdk = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        browser = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        received = asyncio.Future()
        @browser.on("track")
        def on_track(track):
            if not received.done():
                received.set_result(track)
        try:
            sdk.addTrack(VideoStreamTrack())
            await sdk.setLocalDescription(await sdk.createOffer())
            answer = await worker.sdk_signal(identifier, worker.Signal(type="WEBRTC_OFFER", streamId=str(identifier), sdp=sdk.localDescription.sdp))
            await sdk.setRemoteDescription(RTCSessionDescription(sdp=answer["sdp"], type="answer"))
            offer = await worker.open_viewer(identifier, viewer_id)
            await browser.setRemoteDescription(RTCSessionDescription(sdp=offer["sdp"], type="offer"))
            await browser.setLocalDescription(await browser.createAnswer())
            await worker.viewer_signal(identifier, viewer_id, worker.Signal(type="WEBRTC_ANSWER", streamId=str(viewer_id), sdp=browser.localDescription.sdp))
            track = await asyncio.wait_for(received, 10)
            frame = await asyncio.wait_for(track.recv(), 10)
            assert frame.width > 0
            await worker.close_viewer(identifier, viewer_id)
            assert not stream.consumer.done()
            stream.recording = None
            for _ in range(100):
                if stream.enqueue.await_count:
                    break
                await asyncio.sleep(0.02)
            stream.enqueue.assert_awaited_once()
            segment = stream.enqueue.await_args.args[0]
            assert Path(segment.path).stat().st_size > 0
        finally:
            await browser.close()
            await sdk.close()
            await stream.close()
            worker.streams.pop(identifier, None)
    asyncio.run(run())


def test_recreated_ingress_accepts_new_sdk_and_existing_viewer_identifier(tmp_path, monkeypatch):
    monkeypatch.setattr(worker, "ROOT", tmp_path)

    async def run():
        identifier, viewer_id = uuid4(), uuid4()
        configuration = worker.StreamConfiguration()
        peers = []

        async def connect_and_receive():
            sdk = RTCPeerConnection(RTCConfiguration(iceServers=[]))
            browser = RTCPeerConnection(RTCConfiguration(iceServers=[]))
            peers.extend([sdk, browser])
            received = asyncio.Future()

            @browser.on("track")
            def on_track(track):
                if not received.done():
                    received.set_result(track)

            sdk.addTrack(VideoStreamTrack())
            await sdk.setLocalDescription(await sdk.createOffer())
            answer = await worker.sdk_signal(identifier, worker.Signal(type="WEBRTC_OFFER", streamId=str(identifier), sdp=sdk.localDescription.sdp))
            await sdk.setRemoteDescription(RTCSessionDescription(sdp=answer["sdp"], type="answer"))
            offer = await worker.open_viewer(identifier, viewer_id)
            await browser.setRemoteDescription(RTCSessionDescription(sdp=offer["sdp"], type="offer"))
            await browser.setLocalDescription(await browser.createAnswer())
            await worker.viewer_signal(identifier, viewer_id, worker.Signal(type="WEBRTC_ANSWER", streamId=str(viewer_id), sdp=browser.localDescription.sdp))
            track = await asyncio.wait_for(received, 10)
            assert (await asyncio.wait_for(track.recv(), 10)).width > 0

        try:
            assert (await worker.configure(identifier, configuration))["created"]
            await connect_and_receive()
            original = worker.streams[identifier]
            original_tracks = [sender.track for peer in original.viewers.values() for sender in peer.getSenders()]
            await worker.close_stream(identifier)
            assert identifier not in worker.streams
            assert (await worker.configure(identifier, configuration))["created"]
            await connect_and_receive()
            assert worker.streams[identifier] is not original
            assert str(viewer_id) in worker.streams[identifier].viewers
            await asyncio.gather(*worker.closing)
            assert all(track.readyState == "ended" for track in original_tracks)
        finally:
            await asyncio.gather(*(peer.close() for peer in peers))
            await worker.close_stream(identifier)
            await asyncio.gather(*worker.closing)

    asyncio.run(run())
