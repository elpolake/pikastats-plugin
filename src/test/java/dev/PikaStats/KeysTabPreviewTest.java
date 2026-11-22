package dev.PikaStats;

import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builds the ViaProxy GUI tab with ViaProxy's dark look and saves build/keys-tab.png. */
class KeysTabPreviewTest {
    @TempDir
    File folder;

    @Test
    void rendersTabAndSavesKeys() throws Exception {
        System.setProperty("java.awt.headless", "true");
        UIManager.setLookAndFeel(new FlatDarkLaf());
        PluginConfig config = PluginConfig.load(this.folder);
        LocalWebSocketServer websocket = new LocalWebSocketServer(0, "{}", "{}");
        Services services = new Services(this.folder, Executors.newFixedThreadPool(2), config,
                new PartyStore(this.folder), websocket);
        services.updateApiKeys(List.of("aaaaaaaa-1111-2222-3333-444444444444", "bbbbbbbb-5555-6666-7777-888888888888"));
        // PIKA_STATS_API_KEY from the environment may add one more key.
        assertTrue(services.keyPool().keys().size() >= 2);
        assertTrue(PluginConfig.load(this.folder).apiKeys().containsAll(List.of(
                "aaaaaaaa-1111-2222-3333-444444444444", "bbbbbbbb-5555-6666-7777-888888888888")), "keys persisted");

        BufferedImage image = new BufferedImage(860, 520, BufferedImage.TYPE_INT_ARGB);
        SwingUtilities.invokeAndWait(() -> {
            KeysTab tab = new KeysTab(services);
            tab.setSize(860, 520);
            tab.doLayout();
            layoutTree(tab);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(UIManager.getColor("Panel.background"));
            graphics.fillRect(0, 0, 860, 520);
            tab.printAll(graphics);
            graphics.dispose();
        });
        File output = new File("build/keys-tab.png");
        ImageIO.write(image, "png", output);
        assertTrue(output.length() > 0);
        services.close();
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layoutTree(nested);
            }
        }
    }
}
