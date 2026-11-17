package kr.chaeum.fieldremote;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PC로 파일 보내기: 고른 파일을 블루투스로 동글에 보냄 → 동글을 꽂은 PC에 'HW 리모컨' 드라이브로 나타남.
 * 파일 내용은 화면(JS)을 거치지 않고 여기서 바로 보냄 (빠르고 메모리를 덜 씀).
 * 진행 상황: onFileState(state, name, sent, total, index, count)
 *   state = start / progress / done / all / error / cancel
 */
final class FileSender {
    private static final int OP_BEGIN = 0x60, OP_DATA = 0x61, OP_END = 0x62;
    private static final int MAX_WRITE = 512;   // 동글이 한 번에 받는 최대 크기
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile String addr = "";
    private static volatile long free = 0;
    private static volatile boolean cancel = false;

    private FileSender() {}

    static void prepare(String a, long f) { addr = a; free = f; cancel = false; }
    static void cancel() { cancel = true; }

    static void onPicked(Context ctx, Intent data) {
        List<Uri> list = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) list.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) list.add(data.getData());
        if (list.isEmpty()) { Hub.emit("onFileState", "cancel", "", 0, 0, 0, 0); return; }
        final String target = addr;
        worker.execute(() -> sendAll(ctx.getContentResolver(), target, list));
    }

    private static void sendAll(ContentResolver cr, String target, List<Uri> list) {
        // 먼저 크기를 모두 확인 (남은 공간보다 크면 시작하지 않음)
        String[] names = new String[list.size()];
        long[] sizes = new long[list.size()];
        long total = 0;
        for (int i = 0; i < list.size(); i++) {
            names[i] = "파일";
            sizes[i] = -1;
            try (Cursor c = cr.query(list.get(i), null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0 && c.getString(ni) != null) names[i] = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) sizes[i] = c.getLong(si);
                }
            } catch (Exception ignored) {}
            if (sizes[i] < 0) { Hub.emit("onFileState", "error", names[i], 0, 0, i, list.size(), "크기를 알 수 없는 파일이에요"); return; }
            // 파일마다 4KB 단위로 자리를 차지함
            total += (sizes[i] + 4095) / 4096 * 4096;
        }
        if (total > free) { Hub.emit("onFileState", "error", "", total, free, 0, list.size(), "full"); return; }

        int mtu = Hub.ble.mtu(target);
        int chunk = Math.min(MAX_WRITE, Math.max(23, mtu) - 3) - 5;   // [OP][위치 4바이트][내용]
        if (chunk < 16) chunk = 16;
        long tzOff = TimeZone.getDefault().getOffset(System.currentTimeMillis());
        long now = (System.currentTimeMillis() + tzOff) / 1000;   // 현지 시각 (PC 탐색기에 보일 시각)

        for (int i = 0; i < list.size(); i++) {
            String name = names[i];
            long size = sizes[i];
            byte[] nm = utf8Cut(name, 90);
            byte[] begin = new byte[9 + nm.length];
            begin[0] = (byte) OP_BEGIN;
            putU32(begin, 1, size);
            putU32(begin, 5, now);
            System.arraycopy(nm, 0, begin, 9, nm.length);
            Hub.ble.write(target, begin, true);
            Hub.emit("onFileState", "start", name, 0, size, i, list.size());
            long sent = 0, lastEmit = 0;
            try (InputStream in = cr.openInputStream(list.get(i))) {
                if (in == null) throw new Exception("파일을 열 수 없어요");
                byte[] buf = new byte[chunk];
                while (sent < size) {
                    if (cancel) { Hub.emit("onFileState", "cancel", name, sent, size, i, list.size()); return; }
                    int n = readFully(in, buf, (int) Math.min(chunk, size - sent));
                    if (n <= 0) throw new Exception("파일을 끝까지 읽지 못했어요");
                    byte[] pkt = new byte[5 + n];
                    pkt[0] = (byte) OP_DATA;
                    putU32(pkt, 1, sent);
                    System.arraycopy(buf, 0, pkt, 5, n);
                    Hub.ble.write(target, pkt, true);
                    sent += n;
                    while (Hub.ble.pending(target) > 6) {
                        if (cancel) break;
                        Thread.sleep(4);
                    }
                    long t = System.currentTimeMillis();
                    if (t - lastEmit > 250) { lastEmit = t; Hub.emit("onFileState", "progress", name, sent, size, i, list.size()); }
                }
            } catch (Exception e) {
                Hub.emit("onFileState", "error", name, sent, size, i, list.size(), String.valueOf(e.getMessage()));
                return;
            }
            while (Hub.ble.pending(target) > 0) { try { Thread.sleep(10); } catch (InterruptedException ignored) {} }
            Hub.ble.write(target, new byte[]{(byte) OP_END}, true);
            Hub.emit("onFileState", "done", name, size, size, i, list.size());
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        Hub.emit("onFileState", "all", "", 0, 0, list.size(), list.size());
    }

    private static int readFully(InputStream in, byte[] b, int want) throws Exception {
        int got = 0;
        while (got < want) {
            int n = in.read(b, got, want - got);
            if (n < 0) break;
            got += n;
        }
        return got;
    }

    private static void putU32(byte[] b, int o, long v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    /** UTF-8로 max바이트 이하, 글자 중간에서 자르지 않음 */
    private static byte[] utf8Cut(String s, int max) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) return b;
        int n = max;
        while (n > 0 && (b[n] & 0xC0) == 0x80) n--;
        byte[] out = new byte[n];
        System.arraycopy(b, 0, out, 0, n);
        return out;
    }
}
