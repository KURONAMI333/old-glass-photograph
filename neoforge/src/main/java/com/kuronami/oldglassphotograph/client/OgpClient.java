package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import net.neoforged.bus.api.IEventBus;

/** client 側の入口。 */
public final class OgpClient {

    private OgpClient() {
    }

    public static void init(IEventBus modBus) {
        PhotoCaptureClient.init(modBus);
        modBus.addListener(PlateStageProperty::register);
    }
}
