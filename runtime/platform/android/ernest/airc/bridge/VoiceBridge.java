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
    private int key;
    private long down;
    private boolean registered, pressed, closed;
    private volatile long touched = SystemClock.elapsedRealtime();
    private final long started = touched;

    private void open(String packageName, int code) throws Exception {
        if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("Virtual microphone requires Android 14 or newer");
        Context context = FrameworkContext.forUser(0);
        int uid = context.getPackageManager().getApplicationInfo(packageName, 0).uid;
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
        int status = 0;
        try {
            if (args.length != 2 || !args[0].matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw new IllegalArgumentException("Invalid receiving package");
            int code = Integer.parseInt(args[1]);
            if (code < 0 || code > 65535) throw new IllegalArgumentException("Invalid voice key");
            bridge.open(args[0], code);
            watchdog.scheduleAtFixedRate(() -> {
                long now = SystemClock.elapsedRealtime();
                if (now - bridge.touched > 2000 || now - bridge.started > 60000) {
                    try { bridge.close(); } catch (Exception error) { System.err.println(error); }
                    finish(1);
                }
            }, 100, 100, TimeUnit.MILLISECONDS);
            System.out.println("{\"ready\":true,\"sampleRate\":16000,\"channels\":1}"); System.out.flush();
            DataInputStream stream = new DataInputStream(System.in);
            while (true) {
                int length;
                try { length = stream.readUnsignedShort(); } catch (EOFException end) { break; }
                if (length < 2 || length > 3200 || length % 2 != 0) throw new IOException("Invalid PCM frame");
                byte[] pcm = new byte[length]; stream.readFully(pcm);
                bridge.touched = SystemClock.elapsedRealtime();
                // A cancelled setup/quick tap with no PCM must never produce a late key DOWN.
                if (bridge.key != 0 && !bridge.pressed) {
                    bridge.down = SystemClock.uptimeMillis();
                    bridge.pressed = true; // Also attempt UP for an uncertain DOWN submission.
                    bridge.sendKey(KeyEvent.ACTION_DOWN);
                }
                int offset = 0;
                while (offset < length) {
                    int written = bridge.track.write(pcm, offset, length - offset, AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) throw new IOException("PCM injection failed: " + written);
                    offset += written;
                }
            }
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            report(error);
            status = 1;
        } finally {
            watchdog.shutdownNow();
            try { bridge.close(); } catch (Exception error) { report(error); status = 1; }
        }
        finish(status);
    }
}
