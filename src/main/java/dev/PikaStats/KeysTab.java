package dev.PikaStats;

import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.ui.ViaProxyWindow;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** "PikaStats" tab in ViaProxy's window: manage API keys and watch quotas live. */
final class KeysTab extends JPanel {
    private static final String[] COLUMNS = {"#", "API key", "Status", "Left", "Resets in", "Sent", "429s", "Last result"};

    private final Services services;
    private final KeyModel model = new KeyModel();
    private final JTable table = new JTable(this.model);
    private final JLabel summary = new JLabel(" ");
    private final JLabel feedback = new JLabel(" ");
    private final JPasswordField keyField = new JPasswordField(36);
    private final JTextField testPlayer = new JTextField("Notch", 12);
    private final Timer timer;

    /** Waits for ViaProxy's window (GUI mode only) and adds the tab to it. */
    static void installWhenReady(Services services) {
        Thread waiter = new Thread(() -> {
            for (int attempt = 0; attempt < 240; attempt++) {
                ViaProxyWindow window = ViaProxy.getViaProxyWindow();
                if (window != null && window.contentPane != null) {
                    SwingUtilities.invokeLater(() -> {
                        try {
                            window.contentPane.addTab("PikaStats", new KeysTab(services));
                        } catch (RuntimeException error) {
                            System.err.println("[PikaStats] Could not add the GUI tab: " + error.getMessage());
                        }
                    });
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "PikaStats-GUI-Installer");
        waiter.setDaemon(true);
        waiter.start();
    }

    KeysTab(Services services) {
        super(new BorderLayout(0, 10));
        this.services = services;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("PikaStats  -  API keys");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
        JLabel help = new JLabel("<html><body style='width: 640px'>Each PikaNetwork account can make one key with <b>/apikey</b> in game. Every key adds "
                + "about 120 requests per minute, and PikaStats spreads requests over all of them, always using the key "
                + "with the most quota left. Your own chat commands always go first; the overlay keeps a reserve free for them. "
                + "Keys are saved in plugins/PikaStats/config.properties - never share them.</html>");
        help.setForeground(help.getForeground().darker());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        help.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(6));
        header.add(help);
        add(header, BorderLayout.NORTH);

        this.table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        this.table.setRowHeight(this.table.getRowHeight() + 6);
        this.table.setFillsViewportHeight(true);
        this.table.getTableHeader().setReorderingAllowed(false);
        int[] widths = {30, 170, 80, 70, 75, 60, 50, 170};
        for (int index = 0; index < widths.length; index++) {
            this.table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
        }
        this.table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        JScrollPane scroll = new JScrollPane(this.table);
        scroll.setPreferredSize(new Dimension(600, 170));
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(scroll, BorderLayout.CENTER);
        center.add(this.summary, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        addRow.add(new JLabel("New key:"));
        addRow.add(this.keyField);
        JCheckBox show = new JCheckBox("Show");
        char echo = this.keyField.getEchoChar();
        show.addActionListener(event -> this.keyField.setEchoChar(show.isSelected() ? (char) 0 : echo));
        addRow.add(show);
        JButton add = new JButton("Add key");
        add.addActionListener(event -> addKey());
        this.keyField.addActionListener(event -> addKey());
        addRow.add(add);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton remove = new JButton("Remove selected");
        remove.addActionListener(event -> removeSelected());
        JButton test = new JButton("Test selected");
        test.addActionListener(event -> testKeys(selectedKeys()));
        JButton testAll = new JButton("Test all");
        testAll.addActionListener(event -> testKeys(this.services.keyPool().keys()));
        JButton reload = new JButton("Reload config");
        reload.addActionListener(event -> {
            this.services.reload();
            this.feedback.setText("Reloaded config.properties.");
            refresh();
        });
        actionRow.add(remove);
        actionRow.add(test);
        actionRow.add(testAll);
        actionRow.add(new JLabel("  test with player:"));
        actionRow.add(this.testPlayer);
        actionRow.add(reload);

        JPanel feedbackRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        feedbackRow.add(this.feedback);
        for (JPanel row : new JPanel[]{addRow, actionRow, feedbackRow}) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.add(row);
            controls.add(Box.createVerticalStrut(6));
        }
        add(controls, BorderLayout.SOUTH);

        this.timer = new Timer(1000, event -> refresh());
        this.timer.start();
        refresh();
    }

    private void refresh() {
        this.model.update(this.services.keyPool().keys());
        ApiKeyPool pool = this.services.keyPool();
        PikaStatsApi api = this.services.api();
        long hits = api.cacheHits();
        long total = hits + api.cacheMisses();
        this.summary.setText(String.format(Locale.ROOT,
                "%d key(s)  |  %d requests/min  |  %d left right now  |  queue %d  |  %,d sent  |  cache hits %d%%  |  %d stale fallbacks",
                pool.keys().size(), pool.totalLimit(), pool.totalRemaining(), pool.queued(), pool.sent(),
                total == 0 ? 0 : Math.round(100.0 * hits / total), api.staleServed()));
    }

    private void addKey() {
        String value = new String(this.keyField.getPassword()).trim();
        if (value.isEmpty()) {
            return;
        }
        if (value.length() < 8 || value.chars().anyMatch(Character::isWhitespace)) {
            this.feedback.setText("That does not look like an API key.");
            return;
        }
        List<String> keys = currentValues();
        if (keys.contains(value)) {
            this.feedback.setText("That key is already in the list.");
            return;
        }
        keys.add(value);
        this.services.updateApiKeys(keys);
        this.keyField.setText("");
        this.feedback.setText("Key added and saved. Testing it...");
        refresh();
        this.services.keyPool().keys().stream().filter(key -> key.value.equals(value)).findFirst()
                .ifPresent(key -> testKeys(List.of(key)));
    }

    private void removeSelected() {
        List<ApiKeyPool.Key> selected = selectedKeys();
        if (selected.isEmpty()) {
            this.feedback.setText("Select a key first.");
            return;
        }
        List<String> keys = currentValues();
        selected.forEach(key -> keys.remove(key.value));
        this.services.updateApiKeys(keys);
        this.feedback.setText("Removed " + selected.size() + " key(s).");
        refresh();
    }

    private void testKeys(List<ApiKeyPool.Key> keys) {
        if (keys.isEmpty()) {
            this.feedback.setText("No keys to test.");
            return;
        }
        String player = this.testPlayer.getText().trim();
        if (!PartyStore.isValidUsername(player)) {
            this.feedback.setText("Enter a valid player name to test with.");
            return;
        }
        this.feedback.setText("Testing " + keys.size() + " key(s)...");
        for (ApiKeyPool.Key key : keys) {
            this.services.api().testKey(key, player).whenComplete((profile, error) -> SwingUtilities.invokeLater(() -> {
                String result;
                if (error == null || PikaStatsApi.errorKind(error) == PikaStatsApi.ErrorKind.PLAYER_NOT_FOUND) {
                    result = key.masked() + " works" + (error == null ? " (" + profile.username() + " Lv " + profile.level() + ")" : "");
                } else {
                    result = key.masked() + ": " + PikaStatsApi.errorKind(error).message();
                }
                this.feedback.setText(result);
                refresh();
            }));
        }
    }

    private List<ApiKeyPool.Key> selectedKeys() {
        List<ApiKeyPool.Key> keys = this.services.keyPool().keys();
        List<ApiKeyPool.Key> selected = new ArrayList<>();
        for (int row : this.table.getSelectedRows()) {
            if (row >= 0 && row < keys.size()) {
                selected.add(keys.get(row));
            }
        }
        return selected;
    }

    private List<String> currentValues() {
        List<String> values = new ArrayList<>();
        this.services.keyPool().keys().forEach(key -> values.add(key.value));
        return values;
    }

    private static final class KeyModel extends AbstractTableModel {
        private List<ApiKeyPool.Key> keys = List.of();

        void update(List<ApiKeyPool.Key> updated) {
            boolean sameRows = updated.size() == this.keys.size();
            this.keys = updated;
            if (sameRows) {
                fireTableRowsUpdated(0, Math.max(0, this.keys.size() - 1));
            } else {
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return this.keys.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            ApiKeyPool.Key key = this.keys.get(row);
            long now = System.currentTimeMillis();
            return switch (column) {
                case 0 -> row + 1;
                case 1 -> key.masked();
                case 2 -> key.state();
                case 3 -> key.invalid ? "-" : key.remaining(now) + " / " + key.limit;
                case 4 -> key.resetsInMillis() == 0 ? "-" : (key.resetsInMillis() / 1000) + "s";
                case 5 -> key.used.get();
                case 6 -> key.rateLimited.get();
                default -> key.lastStatus;
            };
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                component.setForeground(switch (String.valueOf(value)) {
                    case "OK" -> new Color(0x4ade80);
                    case "READY" -> new Color(0x93c5fd);
                    case "COOLING", "EMPTY" -> new Color(0xfbbf24);
                    case "INVALID" -> new Color(0xf87171);
                    default -> table.getForeground();
                });
            }
            setFont(getFont().deriveFont(Font.BOLD));
            return component;
        }
    }
}
