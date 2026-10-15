package io.github.gabrielmmoraes1999.nexora.studio;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;

final class StartPage extends JPanel {

    StartPage(Runnable create, Runnable register, Runnable sql) {
        super(new GridBagLayout());
        setBackground(Look.PAPER);
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.putClientProperty("FlatLaf.style", "arc: 18");
        card.setBorder(new CompoundBorder(new LineBorder(Look.LINE, 1, true), new EmptyBorder(28, 32, 28, 32)));

        JLabel mark = new JLabel(Look.imageIcon(72));
        mark.setAlignmentX(LEFT_ALIGNMENT);

        JLabel title = new JLabel("Nexora Studio");
        title.setFont(Look.UI.deriveFont(Font.BOLD, 26f));
        title.setForeground(Look.NAVY);
        title.setAlignmentX(LEFT_ALIGNMENT);

        JLabel text = new JLabel("""
                <html><body style='width:460px'>
                Administrative interface for the <b>.db</b> file, in the spirit of IBExpert.
                The explorer sits on the left. SQL and objects open in the center.
                The grid writes to tables that have a primary key.
                <br><br>
                F9 runs the script. The embedded connection opens the file in this process.
                If the server already holds the file, register the database over the network.
                </body></html>
                """);
        text.setFont(Look.UI);
        text.setForeground(Look.INK);
        text.setAlignmentX(LEFT_ALIGNMENT);

        JButton createButton = Look.primary("Create database", Icons.plus(Color.WHITE));
        JButton registerButton = Look.button("Register database");
        JButton sqlButton = Look.button("New query");
        createButton.addActionListener(event -> create.run());
        registerButton.addActionListener(event -> register.run());
        sqlButton.addActionListener(event -> sql.run());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        actions.add(createButton);
        actions.add(registerButton);
        actions.add(sqlButton);

        card.add(mark);
        card.add(Box.createVerticalStrut(16));
        card.add(title);
        card.add(Box.createVerticalStrut(10));
        card.add(text);
        card.add(Box.createVerticalStrut(18));
        card.add(actions);
        add(card);
    }
}