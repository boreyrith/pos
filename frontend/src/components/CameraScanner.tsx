"use client";

import { Flashlight, SwitchCamera, ZoomIn } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { Button, ErrorNote, Modal, Select, cx } from "./ui";
import { translate, useT } from "@/lib/i18n";

/** The browser's built-in detector (Chrome, Edge, Android). Not in TypeScript's DOM types yet. */
interface NativeDetector {
  detect(source: HTMLVideoElement | HTMLCanvasElement): Promise<{ rawValue: string }[]>;
}
type NativeDetectorClass = {
  new (options?: { formats?: string[] }): NativeDetector;
  getSupportedFormats(): Promise<string[]>;
};

const WANTED_FORMATS = ["qr_code", "ean_13", "ean_8", "upc_a", "upc_e", "code_128", "code_39", "itf", "codabar", "data_matrix"];
/** The same code is ignored for this long, so holding it in front of the camera adds it once. */
const REPEAT_MS = 2000;
/** After this long without a read, show tips (distance, light, zoom). */
const HINT_AFTER_MS = 6000;
/** Side of the enlarged crop of the aiming square that is decoded every other frame. */
const CROP_SIZE = 900;

/** Camera features some devices expose (Chrome on Android, some webcams); not all in TS's DOM types. */
type CameraCaps = {
  torch?: boolean;
  focusMode?: string[];
  zoom?: { min: number; max: number; step?: number };
  pointsOfInterest?: unknown;
};

type Feedback = { tone: "ok" | "warn"; text: string } | null;

/**
 * Scans barcodes and QR codes with the device camera. Decoding uses the browser's BarcodeDetector
 * where it handles QR and EAN, and otherwise the `barcode-detector` library (zxing-cpp compiled to
 * WebAssembly, served from /zxing so it works offline), which copes well with small, blurry,
 * tilted or dim codes. Each frame is read whole and, alternately, as an enlarged crop of the
 * aiming square. The camera asks for high resolution and continuous autofocus; where the device
 * supports them there is tap-to-focus, zoom and a light. Cameras need a secure page: https, or localhost.
 *
 * In continuous mode the scanner stays open; `onDetected` returns a short message to show
 * ("Added Iced Latte"). Otherwise it closes after the first code.
 */
export function CameraScanner({
  open,
  onClose,
  onDetected,
  continuous = false,
  title,
}: {
  open: boolean;
  onClose: () => void;
  onDetected: (code: string) => Promise<Feedback | void> | Feedback | void;
  continuous?: boolean;
  title?: string;
}) {
  return (
    <Modal open={open} onClose={onClose} title={title ?? translate("Scan a barcode or QR code")}>
      {open && <ScannerView onClose={onClose} onDetected={onDetected} continuous={continuous} />}
    </Modal>
  );
}

function ScannerView({
  onClose,
  onDetected,
  continuous,
}: {
  onClose: () => void;
  onDetected: (code: string) => Promise<Feedback | void> | Feedback | void;
  continuous: boolean;
}) {
  const t = useT();
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const stopDecoderRef = useRef<() => void>(() => {});
  const lastRef = useRef<{ code: string; at: number }>({ code: "", at: 0 });
  const busyRef = useRef(false);
  const handlerRef = useRef(onDetected);
  useEffect(() => {
    handlerRef.current = onDetected;
  }, [onDetected]);

  const [error, setError] = useState<string | null>(null);
  const [cameras, setCameras] = useState<MediaDeviceInfo[]>([]);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [torchAvailable, setTorchAvailable] = useState(false);
  const [torchOn, setTorchOn] = useState(false);
  const [feedback, setFeedback] = useState<Feedback>(null);
  const [caps, setCaps] = useState<CameraCaps>({});
  const [zoom, setZoom] = useState<number | null>(null);
  const [focusAt, setFocusAt] = useState<{ x: number; y: number; key: number } | null>(null);
  const [hint, setHint] = useState(false);

  const handle = useCallback(
    async (raw: string) => {
      const code = raw.trim();
      const now = Date.now();
      if (!code || busyRef.current) return;
      if (code === lastRef.current.code && now - lastRef.current.at < REPEAT_MS) return;
      lastRef.current = { code, at: now };
      busyRef.current = true;
      setHint(false);
      beep();
      navigator.vibrate?.(60);
      try {
        const result = await handlerRef.current(code);
        if (!continuous) {
          onClose();
          return;
        }
        setFeedback(result ?? { tone: "ok", text: code });
      } finally {
        busyRef.current = false;
      }
    },
    [continuous, onClose],
  );

  useEffect(() => {
    let cancelled = false;
    async function start() {
      setError(null);
      if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
        setError(translate("The camera only works on a secure page (https://, or localhost on this device)."));
        return;
      }
      try {
        // High resolution gives the decoder more pixels per module; most cameras then pick their best mode.
        const stream = await navigator.mediaDevices.getUserMedia({
          video: {
            ...(deviceId ? { deviceId: { exact: deviceId } } : { facingMode: { ideal: "environment" } }),
            width: { ideal: 1920 },
            height: { ideal: 1080 },
          },
          audio: false,
        });
        if (cancelled) {
          stream.getTracks().forEach((x) => x.stop());
          return;
        }
        streamRef.current = stream;
        const video = videoRef.current!;
        video.srcObject = stream;
        await video.play().catch(() => {});

        const track = stream.getVideoTracks()[0];
        const found = (track.getCapabilities?.() ?? {}) as CameraCaps;
        setCaps(found);
        setTorchAvailable(Boolean(found.torch));
        // Keep refocusing as the code moves closer or farther.
        if (found.focusMode?.includes("continuous")) {
          await track.applyConstraints({ advanced: [{ focusMode: "continuous" } as MediaTrackConstraintSet] }).catch(() => {});
        }
        if (found.zoom) {
          const current = (track.getSettings() as { zoom?: number }).zoom;
          setZoom(current ?? found.zoom.min);
        }
        // Labels are only available after permission is granted.
        const devices = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === "videoinput");
        if (!cancelled) setCameras(devices);

        stopDecoderRef.current = await startDecoder(video, handle);
      } catch (e) {
        const name = e instanceof DOMException ? e.name : "";
        setError(
          name === "NotAllowedError"
            ? translate("Camera access was blocked. Allow the camera for this site in the browser's settings, then try again.")
            : name === "NotFoundError"
              ? translate("No camera was found on this device.")
              : translate("The camera could not be started. It may be in use by another app."),
        );
      }
    }
    start();
    return () => {
      cancelled = true;
      stopDecoderRef.current();
      stopDecoderRef.current = () => {};
      streamRef.current?.getTracks().forEach((x) => x.stop());
      streamRef.current = null;
    };
  }, [deviceId, handle]);

  // Tips if nothing has been read for a while (reset after every read).
  useEffect(() => {
    if (hint || error) return;
    const x = setTimeout(() => setHint(true), HINT_AFTER_MS);
    return () => clearTimeout(x);
  }, [hint, error, feedback]);

  async function applyZoom(value: number) {
    setZoom(value);
    await streamRef.current
      ?.getVideoTracks()[0]
      ?.applyConstraints({ advanced: [{ zoom: value } as MediaTrackConstraintSet] })
      .catch(() => {});
  }

  /** Focuses where the screen was tapped, where the camera supports it, then back to continuous focus. */
  async function tapToFocus(e: React.PointerEvent<HTMLDivElement>) {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = (e.clientX - rect.left) / rect.width;
    const y = (e.clientY - rect.top) / rect.height;
    setFocusAt({ x, y, key: Date.now() });
    const track = streamRef.current?.getVideoTracks()[0];
    if (!track) return;
    const modes = caps.focusMode ?? [];
    const single = modes.includes("single-shot") ? "single-shot" : null;
    try {
      await track.applyConstraints({
        advanced: [
          {
            ...(caps.pointsOfInterest !== undefined ? { pointsOfInterest: [{ x, y }] } : {}),
            ...(single ? { focusMode: single } : {}),
          } as MediaTrackConstraintSet,
        ],
      });
      if (single && modes.includes("continuous")) {
        setTimeout(() => {
          track.applyConstraints({ advanced: [{ focusMode: "continuous" } as MediaTrackConstraintSet] }).catch(() => {});
        }, 1500);
      }
    } catch {
      // focus control not available on this camera
    }
  }

  async function toggleTorch() {
    const track = streamRef.current?.getVideoTracks()[0];
    if (!track) return;
    try {
      await track.applyConstraints({ advanced: [{ torch: !torchOn } as MediaTrackConstraintSet] });
      setTorchOn(!torchOn);
    } catch {
      setTorchAvailable(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {error ? (
        <ErrorNote error={new Error(error)} />
      ) : (
        <div className="relative overflow-hidden rounded-xl bg-black" onPointerDown={tapToFocus}>
          <video ref={videoRef} className="aspect-[4/3] w-full object-cover" muted playsInline aria-label={t("Camera preview")} />
          {focusAt && (
            <span
              key={focusAt.key}
              aria-hidden
              className="pointer-events-none absolute size-14 -translate-x-1/2 -translate-y-1/2 animate-ping rounded-full border-2 border-white/90 [animation-iteration-count:1]"
              style={{ left: `${focusAt.x * 100}%`, top: `${focusAt.y * 100}%` }}
            />
          )}
          {/* Aiming frame */}
          <div className="pointer-events-none absolute inset-0 grid place-items-center" aria-hidden>
            <div className="aspect-square h-2/3 rounded-xl border-2 border-white/80 shadow-[0_0_0_9999px_rgba(0,0,0,0.35)]" />
          </div>
          {feedback && (
            <div
              role="status"
              className={cx(
                "absolute inset-x-3 bottom-3 rounded-lg px-3 py-2 text-center text-sm font-medium",
                feedback.tone === "ok" ? "bg-success text-white" : "bg-warning text-white",
              )}
            >
              {t(feedback.text)}
            </div>
          )}
        </div>
      )}
      <p className="text-xs text-muted">
        {t("Hold the code inside the square, about 15–25 cm away; tap the picture to focus.")}{" "}{continuous ? t("Keep scanning items; close when you're done.") : ""}
      </p>
      {hint && !error && (
        <p role="status" className="rounded-lg bg-surface-2 px-3 py-2 text-xs">
          {t("Can't read it? Move the code a little farther away (laptop cameras can't focus up close), add light, and hold it steady and flat.")}
          {caps.zoom ? ` ${t("Zoom in if it helps.")}` : ""}
          {torchAvailable ? ` ${t("Turn on the light if it's dark.")}` : ""}
        </p>
      )}
      {caps.zoom && zoom !== null && caps.zoom.max > caps.zoom.min && (
        <label className="flex items-center gap-2 text-sm">
          <ZoomIn className="size-4 shrink-0 text-muted" aria-hidden />
          <span className="sr-only">{t("Zoom")}</span>
          <input
            type="range"
            min={caps.zoom.min}
            max={caps.zoom.max}
            step={caps.zoom.step || 0.1}
            value={zoom}
            onChange={(e) => applyZoom(Number(e.target.value))}
            className="flex-1"
          />
          <span className="w-10 text-right text-xs tabular-nums text-muted">{zoom.toFixed(1)}×</span>
        </label>
      )}
      <div className="flex flex-wrap items-center gap-2">
        {cameras.length > 1 && (
          <label className="flex min-w-0 flex-1 items-center gap-2 text-sm">
            <SwitchCamera className="size-4 shrink-0 text-muted" aria-hidden />
            <Select aria-label={t("Camera")} value={deviceId ?? ""} onChange={(e) => setDeviceId(e.target.value || null)} className="min-w-0 flex-1">
              <option value="">{t("Back camera (default)")}</option>
              {cameras.map((c, i) => (
                <option key={c.deviceId} value={c.deviceId}>
                  {c.label || t("Camera {i}", { i: i + 1 })}
                </option>
              ))}
            </Select>
          </label>
        )}
        {torchAvailable && (
          <Button type="button" variant="secondary" size="sm" onClick={toggleTorch} aria-pressed={torchOn}>
            <Flashlight className="size-4" aria-hidden /> {torchOn ? t("Light off") : t("Light on")}
          </Button>
        )}
        <Button type="button" className="ml-auto" onClick={onClose}>
          {continuous ? t("Done") : t("Cancel")}
        </Button>
      </div>
    </div>
  );
}

/**
 * Starts decoding frames from the video; returns a function that stops it. Frames alternate
 * between the whole picture and an enlarged crop of the aiming square, which helps with small or
 * slightly blurry codes.
 */
async function startDecoder(video: HTMLVideoElement, onCode: (code: string) => void): Promise<() => void> {
  const detector = await createDetector();
  const canvas = document.createElement("canvas");
  canvas.width = CROP_SIZE;
  canvas.height = CROP_SIZE;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  let stopped = false;
  let frame = 0;
  const tick = async () => {
    if (stopped) return;
    try {
      if (video.readyState >= 2 && video.videoWidth > 0) {
        const source = frame++ % 2 === 0 || !ctx ? video : cropSquare(video, ctx);
        const found = await detector.detect(source);
        if (found[0]?.rawValue) onCode(found[0].rawValue);
      }
    } catch {
      // a frame that can't be read; try the next one
    }
    if (!stopped) setTimeout(tick, 80);
  };
  tick();
  return () => {
    stopped = true;
  };
}

/**
 * Draws the part of the video under the aiming square (2/3 of the visible height, centred; the
 * preview is 4:3 with object-cover) onto the canvas, enlarged.
 */
function cropSquare(video: HTMLVideoElement, ctx: CanvasRenderingContext2D): HTMLCanvasElement {
  const vw = video.videoWidth;
  const vh = video.videoHeight;
  const visibleH = Math.min(vh, (vw * 3) / 4);
  const side = (visibleH * 2) / 3;
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(video, (vw - side) / 2, (vh - side) / 2, side, side, 0, 0, CROP_SIZE, CROP_SIZE);
  return ctx.canvas;
}

type Detector = { detect(source: HTMLVideoElement | HTMLCanvasElement): Promise<{ rawValue: string }[]> };

/** The browser's own detector if it reads QR and EAN, otherwise the zxing-cpp (WebAssembly) one. */
async function createDetector(): Promise<Detector> {
  const Native = (window as unknown as { BarcodeDetector?: NativeDetectorClass }).BarcodeDetector;
  if (Native) {
    try {
      const supported = await Native.getSupportedFormats();
      const formats = WANTED_FORMATS.filter((f) => supported.includes(f));
      if (formats.includes("qr_code") && formats.includes("ean_13")) {
        return new Native({ formats }) as unknown as Detector;
      }
    } catch {
      // fall through to the library
    }
  }
  // Loaded only when needed; the engine (zxing_reader.wasm) is served by this app from /zxing.
  const { BarcodeDetector, prepareZXingModule } = await import("barcode-detector/ponyfill");
  prepareZXingModule({
    overrides: {
      locateFile: (file: string, prefix: string) => (file.endsWith(".wasm") ? `/zxing/${file}` : prefix + file),
    },
  });
  return new BarcodeDetector({ formats: WANTED_FORMATS as never }) as unknown as Detector;
}

/** A short confirmation beep. */
function beep() {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.frequency.value = 1200;
    gain.gain.value = 0.08;
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.09);
    osc.onended = () => ctx.close();
  } catch {
    // no sound available
  }
}
