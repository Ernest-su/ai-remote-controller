package ernest.airc.bridge;

import android.content.Context;
import android.media.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** One bounded, non-persistent PCM injection session. EOF always releases its optional key. */
public final class VoiceBridge {
    private AudioManager audio;
    private Object policy, input;
    private Class<?> policyClass;
    private Method inject;
    private AudioTrack track;
    private int key, receiverUid;
    private String registration;
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger queuedBytes = new java.util.concurrent.atomic.AtomicInteger();
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4000);
    private static final byte[] END = new byte[0];
    private volatile boolean ending, ready;
    private long injectedBytes, writtenFrames, writeDeadline;
    private volatile Throwable playerError;
    private long down;
    private boolean registered, pressed, closed;
    private volatile long touched = SystemClock.elapsedRealtime();
    private final long started = touched;

    private void open(String packageName, int code) throws Exception {
        if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("Virtual microphone requires Android 14 or newer");
        Context context = FrameworkContext.forUser(0);
        int uid = context.getPackageManager().getApplicationInfo(packageName, 0).uid;
        receiverUid = uid;
        audio = context.getSystemService(AudioManager.class);
        key = code;
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> rbClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object rb = rbClass.getConstructor().newInstance();
        rbClass.getMethod("setTargetMixRole", int.class).invoke(rb, ruleClass.getField("MIX_ROLE_INJECTOR").getInt(null));
        rbClass.getMethod("addMixRule", int.class, Object.class).invoke(rb,
            ruleClass.getField("RULE_MATCH_UID").getInt(null), Integer.valueOf(uid));
        AudioAttributes.Builder attributes = new AudioAttributes.Builder();
        AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class).invoke(attributes, MediaRecorder.AudioSource.MIC);
        rbClass.getMethod("addMixRule", int.class, Object.class).invoke(rb,
            ruleClass.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null), attributes.build());
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mbClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object mb = mbClass.getConstructor(ruleClass).newInstance(rbClass.getMethod("build").invoke(rb));
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        mbClass.getMethod("setFormat", AudioFormat.class).invoke(mb, format);
        mbClass.getMethod("setRouteFlags", int.class).invoke(mb, 2);
        Object mix = mbClass.getMethod("build").invoke(mb);
        policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> pbClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object pb = pbClass.getConstructor(Context.class).newInstance(context);
        pbClass.getMethod("setLooper", Looper.class).invoke(pb, Looper.myLooper());
        pbClass.getMethod("addMix", mixClass).invoke(pb, mix);
        policy = pbClass.getMethod("build").invoke(pb);
        int status = (Integer) AudioManager.class.getMethod("registerAudioPolicy", policyClass).invoke(audio, policy);
        if (status != 0) throw new IllegalStateException("Audio routing permission or remote submix unavailable: " + status);
        registered = true;
        track = (AudioTrack) policyClass.getMethod("createAudioTrackSource", mixClass).invoke(policy, mix);
        if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("Virtual microphone unavailable");
        registration = (String) mixClass.getMethod("getRegistration").invoke(mix);
        track.play(); // Connect submix input before the receiving application creates its recorder.
        if (key != 0) {
            Class<?> im = Class.forName("android.hardware.input.InputManagerGlobal");
            input = im.getMethod("getInstance").invoke(null);
            inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
    }
    private void sendKey(int action) throws Exception {
        KeyEvent event = new KeyEvent(down, SystemClock.uptimeMillis(), action, key, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
        if (!Boolean.TRUE.equals(inject.invoke(input, event, 0))) throw new IOException("Voice key submission rejected");
    }
    private int receiverSession() throws Exception {
        Method uid = AudioRecordingConfiguration.class.getMethod("getClientUid");
        for (AudioRecordingConfiguration config : audio.getActiveRecordingConfigurations()) {
            AudioDeviceInfo device = config.getAudioDevice();
            if ((Integer) uid.invoke(config) == receiverUid && config.getClientAudioSource() == MediaRecorder.AudioSource.MIC
                && !config.isClientSilenced() && device != null && device.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                && registration.equals(device.getAddress())) return config.getClientAudioSessionId();
        }
        return -1;
    }
    private void checkCancelled() throws InterruptedException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Voice cancelled");
    }
    private void silence() {
        int written = track.write(new byte[640], 0, 640, AudioTrack.WRITE_NON_BLOCKING);
        if (written > 0) writtenFrames += written / 2;
    }
    private void writePaced(byte[] pcm) throws Exception {
        for (int offset = 0; offset < pcm.length;) {
            checkCancelled();
            int count = Math.min(640, pcm.length - offset);
            int written = track.write(pcm, offset, count, AudioTrack.WRITE_NON_BLOCKING);
            if (written < 0) throw new IOException("PCM injection failed: " + written);
            if (written > 0) { offset += written; writtenFrames += written / 2; }
            if (written > 0) {
                long now = System.nanoTime();
                if (writeDeadline < now - 20000000L) writeDeadline = now;
                writeDeadline += written * 31250L; // 32,000 bytes/second, without accumulating sleep overhead.
                long wait = writeDeadline - System.nanoTime();
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            } else Thread.sleep(5);
        }
    }
    private void play() {
        try {
            checkCancelled();
            if (key != 0) {
                down = SystemClock.uptimeMillis(); pressed = true;
                sendKey(KeyEvent.ACTION_DOWN);
            }
            long until = SystemClock.elapsedRealtime() + 5000;
            int session = -1;
            while (SystemClock.elapsedRealtime() < until) {
                checkCancelled();
                session = receiverSession();
                if (session >= 0) break;
                silence(); Thread.sleep(20);
            }
            if (session < 0) throw new IOException("Target recorder did not become active on the virtual microphone");
            ready = true; touched = SystemClock.elapsedRealtime();
            System.out.println("{\"ready\":true,\"sampleRate\":16000,\"channels\":1,\"receiverSession\":" + session + "}"); System.out.flush();
            while (true) {
                checkCancelled();
                byte[] pcm = queue.poll(20, TimeUnit.MILLISECONDS);
                if (pcm == null) { silence(); continue; }
                if (pcm == END) break;
                writePaced(pcm);
                injectedBytes += pcm.length;
                queuedBytes.addAndGet(-pcm.length);
                System.out.println("{\"consumedBytes\":" + injectedBytes + "}"); System.out.flush();
            }
            // Give the receiving AudioRecord time to read its final payload before key UP.
            writePaced(new byte[6400]);
            long drainUntil = SystemClock.elapsedRealtime() + 2000;
            while (Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < writtenFrames) {
                checkCancelled();
                if (SystemClock.elapsedRealtime() >= drainUntil) throw new IOException("Virtual microphone playback drain timed out");
                Thread.sleep(5);
            }
            System.out.println("{\"drained\":true,\"injectedBytes\":" + injectedBytes + "}"); System.out.flush();
        } catch (InterruptedException error) {
            if (!cancelled.get()) { playerError = error; report(error); }
        } catch (Throwable error) { playerError = error; report(error); }
    }
    private synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        Exception failure = null;
        if (pressed) {
            try { sendKey(KeyEvent.ACTION_UP); } catch (Exception error) { failure = error; }
            pressed = false;
        }
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception error) { if (failure == null) failure = error; }
        }
        if (registered) {
            try { AudioManager.class.getMethod("unregisterAudioPolicy", policyClass).invoke(audio, policy); }
            catch (Exception error) { if (failure == null) failure = error; }
            registered = false;
        }
        if (failure != null) throw failure;
    }
    private static void report(Throwable error) {
        try { System.out.println(new JSONObject().put("error", error.toString())); }
        catch (Exception ignored) { System.out.println("{\"error\":\"Virtual microphone failed\"}"); }
    }
    private static void finish(int status) {
        System.out.flush(); System.err.flush();
        try { android.system.Os.execv("/system/bin/sh", new String[]{"sh", "-c", status == 0 ? "exit 0" : "exit 1"}); }
        catch (Exception error) { System.exit(1); }
    }
    public static void main(String[] args) {
        VoiceBridge bridge = new VoiceBridge();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        Thread player = null;
        int status = 0;
        try {
            if (args.length != 2 || !args[0].matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw new IllegalArgumentException("Invalid receiving package");
            int code = Integer.parseInt(args[1]);
            if (code < 0 || code > 65535) throw new IllegalArgumentException("Invalid voice key");
            bridge.open(args[0], code);
            watchdog.scheduleAtFixedRate(() -> {
                if (SystemClock.elapsedRealtime() - bridge.started > 75000
                    || (bridge.ready && !bridge.ending && SystemClock.elapsedRealtime() - bridge.touched > 2000)) {
                    bridge.cancelled.set(true);
                    try { bridge.close(); } catch (Exception error) { report(error); }
                    finish(1);
                }
            }, 100, 100, TimeUnit.MILLISECONDS);
            System.out.println("{\"prepared\":true}"); System.out.flush();
            DataInputStream stream = new DataInputStream(System.in);
            while (true) {
                int length;
                try { length = stream.readUnsignedShort(); }
                catch (EOFException end) { bridge.cancelled.set(true); break; }
                bridge.touched = SystemClock.elapsedRealtime();
                if (length == 0 && player == null) {
                    player = new Thread(bridge::play, "airc-voice-player"); player.start(); continue;
                }
                if (length == 65535 && bridge.ready && !bridge.ending) {
                    bridge.ending = true;
                    if (!bridge.queue.offer(END)) throw new IOException("Voice queue full");
                    player.join(6500);
                    if (player.isAlive()) throw new IOException("Voice drain timed out");
                    break;
                }
                if (!bridge.ready || bridge.ending || length < 2 || length > 3200 || length % 2 != 0) throw new IOException("Invalid PCM frame or phase");
                byte[] pcm = new byte[length]; stream.readFully(pcm);
                if (bridge.queuedBytes.addAndGet(length) > 128000 || !bridge.queue.offer(pcm)) throw new IOException("Voice receive buffer full");
            }
            if (bridge.playerError != null) throw new IOException("Voice player failed", bridge.playerError);
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            report(error); status = 1;
        } finally {
            bridge.cancelled.set(true);
            if (player != null && player.isAlive()) {
                player.interrupt();
                try { player.join(1000); } catch (InterruptedException ignored) {}
            }
            watchdog.shutdownNow();
            try { bridge.close(); } catch (Exception error) { report(error); status = 1; }
        }
        finish(status);
    }
}
