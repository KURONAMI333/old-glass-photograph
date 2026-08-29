package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;

/**
 * server -&gt; client の撮影指示を {@link PhotoCaptureClient} へ渡すだけの橋。
 *
 * <p>OgpNet（両ディストリビューションでロードされる）の S2C handler が client 専用クラスを
 * 名指しする経路を、この 1 枚に集約する。lambda 本体は実行時までクラス解決されないので、
 * dedicated server がこの参照へ到達することはない。
 */
public final class OgpClientNet {

    private OgpClientNet() {
    }

    public static void openViewfinder(ViewfinderOpenPayload message) {
        PhotoCaptureClient.openViewfinder(message);
    }

    public static void openShutter(ShutterOpenPayload message) {
        PhotoCaptureClient.openShutter(message);
    }

    public static void closeViewfinder() {
        PhotoCaptureClient.closeViewfinder();
    }
}
