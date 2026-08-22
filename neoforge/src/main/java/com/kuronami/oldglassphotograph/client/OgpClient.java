package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.client.render.PhotographHandRenderer;
import com.kuronami.oldglassphotograph.client.render.PhotographSpecialRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;

/** client 側の入口。 */
public final class OgpClient {

    private OgpClient() {
    }

    public static void init(IEventBus modBus) {
        PhotoCaptureClient.init(modBus);
        modBus.addListener(PlateStageProperty::register);
        modBus.addListener(OgpClient::registerSpecialModelRenderers);
        // RenderHandEvent は game bus（NeoForge.EVENT_BUS）側。
        PhotographHandRenderer.init();
    }

    private static void registerSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        event.register(PhotographSpecialRenderer.ID, PhotographSpecialRenderer.Unbaked.MAP_CODEC);
    }
}
