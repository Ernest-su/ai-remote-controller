package ernest.airc.probe;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.util.ArrayList;
import org.json.JSONObject;

/** Reads only an explicitly routed remote submix; validates synthetic markers, never a physical mic. */
public final class VoiceReceiverProbe {
    public static void main(String[] args) throws Exception {
        int status = 0;
        AudioRecord record = null;
        try {
            Looper.prepare();
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("systemMain").invoke(null);
            Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
            Thread.sleep(Integer.parseInt(args[0]));
            record = new AudioRecord.Builder().setContext(context).setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(6400).build();
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Probe recording rejected");
            ArrayList<Short> samples = new ArrayList<>();
            short[] block = new short[320];
            long until = SystemClock.elapsedRealtime() + 3500;
            boolean signal = false;
            int silence = 0;
            while (SystemClock.elapsedRealtime() < until) {
                AudioDeviceInfo device = record.getRoutedDevice();
                if (device == null || device.getType() != AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                    if (signal) break;
                    throw new IllegalStateException("Refusing to read a physical microphone");
                }
                int count = record.read(block, 0, block.length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IllegalStateException("Probe read failed " + count);
                for (int i = 0; i < count; i++) {
                    samples.add(block[i]);
                    if (Math.abs(block[i]) > 100) { signal = true; silence = 0; }
                    else silence++;
                }
                if (signal && silence > 4000) break;
                if (count == 0) Thread.sleep(3);
            }
            int[] bins = new int[3];
            int[] frequencies = {2333, 1103, 1879};
            int first = -1, last = -1;
            for (int offset = 0; offset + 160 <= samples.size(); offset += 160) {
                double best = 1000;
                int matched = -1;
                for (int f = 0; f < frequencies.length; f++) {
                    double sin = 0, cos = 0;
                    for (int i = 0; i < 160; i++) {
                        double phase = 2 * Math.PI * frequencies[f] * i / 16000;
                        sin += samples.get(offset + i) * Math.sin(phase);
                        cos += samples.get(offset + i) * Math.cos(phase);
                    }
                    double amplitude = 2 * Math.hypot(sin, cos) / 160;
                    if (amplitude > best) { best = amplitude; matched = f; }
                }
                if (matched >= 0) { bins[matched]++; if (first < 0) first = matched; last = matched; }
            }
            JSONObject result = new JSONObject().put("prefixBlocks", bins[0]).put("bodyBlocks", bins[1])
                .put("suffixBlocks", bins[2]).put("firstMarker", first).put("lastMarker", last).put("samples", samples.size());
            System.out.println(result);
            if (bins[0] < 18 || bins[1] < 58 || bins[2] < 18 || first != 0 || last != 2) throw new IllegalStateException("Head or tail marker missing");
        } catch (Throwable error) { System.out.println(new JSONObject().put("error", error.toString())); status = 1; }
        finally { if (record != null) { try { record.stop(); } catch (Exception ignored) {} record.release(); } }
        System.out.flush(); System.err.flush();
        android.system.Os.execv("/system/bin/sh", new String[] {"sh", "-c", status == 0 ? "exit 0" : "exit 1"});
    }
}
