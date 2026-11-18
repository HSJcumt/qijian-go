package go.client;

import static go.client.AppTheme.*;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Server configuration stays within the main application window. */
@SuppressWarnings("serial")
final class ServerSettingsPanel extends JPanel {
    private final JTextField host = new JTextField();
    private final JTextField port = new JTextField();
    private final JLabel feedback = label("输入服务器 IP 或域名及端口", 13, MUTED);

    ServerSettingsPanel(ClientProfiles.Server server, Consumer<ClientProfiles.Server> save) {
        super(new GridBagLayout());
        setOpaque(false);
        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setPreferredSize(new Dimension(440, 340));
        form.setBorder(new EmptyBorder(20, 20, 20, 20));
        host.setName("serverHostInput");
        port.setName("serverPortInput");
        feedback.setName("serverSettingsFeedback");
        host.setText(server.host());
        port.setText(Integer.toString(server.port()));
        form.add(label("服务器地址", 15, INK));
        form.add(Box.createVerticalStrut(10));
        for (JTextField input : new JTextField[] {host, port}) {
            input.setFont(FONT);
            input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        }
        form.add(host);
        form.add(Box.createVerticalStrut(18));
        form.add(label("端口", 15, INK));
        form.add(Box.createVerticalStrut(10));
        form.add(port);
        form.add(Box.createVerticalStrut(22));
        JButton connect = new AppButton("保存并连接", true);
        connect.setName("saveServerAndConnect");
        Runnable submit = () -> {
            try {
                save.accept(new ClientProfiles.Server("游戏服务器", host.getText().strip(),
                        Integer.parseInt(port.getText().strip())));
            } catch (NumberFormatException e) {
                message("请输入 1～65535 的端口。");
            } catch (IllegalArgumentException e) { message(e.getMessage()); }
        };
        connect.addActionListener(e -> submit.run());
        host.addActionListener(e -> submit.run());
        port.addActionListener(e -> submit.run());
        form.add(connect);
        form.add(Box.createVerticalStrut(16));
        form.add(feedback);
        add(form);
    }

    void message(String message) { feedback.setText(message); }
}
