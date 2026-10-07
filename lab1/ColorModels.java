import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.metal.MetalSliderUI;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class ColorModels extends JFrame {

    private static final int VIS_W = 400;
    private static final int PREVIEW_H = 150;
    private static final int PALETTE_H = 360;
    private static final int GAP = 8;

    static class RangeModel extends SpinnerNumberModel {
        private final double max, step;

        RangeModel(double max, double step) {
            super(0.0, 0.0, max, step);
            this.max = max;
            this.step = step;
        }

        private double cur() {
            return getNumber().doubleValue();
        }

        @Override public Object getNextValue() {
            double c = cur();
            if (c >= max - 1e-9) return null;
            return Math.min(max, Math.round((c + step) * 10) / 10.0);
        }

        @Override public Object getPreviousValue() {
            double c = cur();
            if (c <= 1e-9) return null;
            return Math.max(0.0, Math.round((c - step) * 10) / 10.0);
        }
    }

    static class JumpSliderUI extends MetalSliderUI {
        @Override protected TrackListener createTrackListener(JSlider s) {
            return new TrackListener() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!slider.isEnabled() || !SwingUtilities.isLeftMouseButton(e)) return;
                    if (!thumbRect.contains(e.getX(), e.getY())) {
                        slider.setValue(valueForXPosition(e.getX()));
                    }
                    super.mousePressed(e);
                }

                @Override public boolean shouldScroll(int direction) {
                    return false;
                }
            };
        }

        @Override protected void scrollDueToClickInTrack(int direction) {}
    }

    static class Channel {
        final JSlider slider;
        final JSpinner spinner;
        final JTextField field = new JTextField();
        final int scale;
        final double max;
        final boolean integer;
        double lastValidValue = 0.0;
        boolean isProgrammaticUpdate = false;

        static final java.util.List<Channel> ALL = new java.util.ArrayList<>();
        boolean dirty = false;
        Runnable commit;

        static void commitPendingExcept(Channel except) {
            for (Channel c : new java.util.ArrayList<>(ALL)) {
                if (c != except && c.dirty) c.commit.run();
            }
        }

        Runnable onChange = () -> {};

        Channel(double max, boolean integer) {
            this.max = max;
            this.integer = integer;
            this.scale = integer ? 1 : 10;
            slider = new JSlider(0, (int) Math.round(max * scale), 0);
            slider.setUI(new JumpSliderUI());
            spinner = new JSpinner(new RangeModel(max, integer ? 1.0 : 0.1));

            JPanel editor = new JPanel(new BorderLayout());
            field.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            field.setHorizontalAlignment(JTextField.LEFT);
            field.setToolTipText("Допустимый диапазон: 0 – " + (int) max);
            editor.add(field, BorderLayout.CENTER);
            spinner.setEditor(editor);

            final String regex = integer ? "\\d{0,3}" : "\\d{0,3}(\\.\\d?)?";
            ((AbstractDocument) field.getDocument()).setDocumentFilter(new DocumentFilter() {
                @Override public void insertString(FilterBypass fb, int off, String str, AttributeSet a)
                        throws BadLocationException {
                    replace(fb, off, 0, str, a);
                }

                @Override public void replace(FilterBypass fb, int off, int len, String text, AttributeSet a)
                        throws BadLocationException {
                    if (isProgrammaticUpdate) {
                        super.replace(fb, off, len, text, a);
                        return;
                    }
                    Document d = fb.getDocument();
                    String cur = d.getText(0, d.getLength());
                    String t = text == null ? "" : text.replace(',', '.');
                    String next = cur.substring(0, off) + t + cur.substring(off + len);

                    if (next.matches(regex)) {
                        super.replace(fb, off, len, t, a);
                    } else {
                        Toolkit.getDefaultToolkit().beep();
                    }
                }
            });

            commit = () -> {
                if (isProgrammaticUpdate) return;
                dirty = false;
                String text = field.getText().trim();
                double val;

                if (text.isEmpty() || text.equals(".")) {
                    val = 0.0;
                } else {
                    try {
                        val = Double.parseDouble(text);
                        val = Math.max(0, Math.min(max, val));
                    } catch (NumberFormatException ex) {
                        val = lastValidValue;
                    }
                }
                userSet(val);
            };

            field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                private void mark() { if (!isProgrammaticUpdate) dirty = true; }
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { mark(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { mark(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { mark(); }
            });

            field.addActionListener(e -> commit.run());
            field.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) {
                    commit.run();
                }
            });

            ALL.add(this);

            set(0.0);
        }

        void updateArrows() {
            double v = get();
            for (Component c : spinner.getComponents()) {
                if (!(c instanceof JButton)) continue;
                if ("Spinner.nextButton".equals(c.getName())) c.setEnabled(v < max - 1e-9);
                else if ("Spinner.previousButton".equals(c.getName())) c.setEnabled(v > 1e-9);
            }
        }

        double get() {
            Object val = spinner.getValue();
            return val instanceof Number ? ((Number) val).doubleValue() : lastValidValue;
        }

        void set(double v) {
            v = Math.max(0, Math.min(max, v));
            double rounded = integer ? Math.round(v) : Math.round(v * 10) / 10.0;

            lastValidValue = rounded;
            isProgrammaticUpdate = true;
            try {
                slider.setValue((int) Math.round(rounded * scale));

                DecimalFormat df = new DecimalFormat(integer ? "0" : "0.0", DecimalFormatSymbols.getInstance(Locale.US));
                field.setText(df.format(rounded));
                dirty = false;
                spinner.setValue(rounded);
                updateArrows();
            } finally {
                isProgrammaticUpdate = false;
            }
        }

        void userSet(double v) {
            commitPendingExcept(this);
            double prev = lastValidValue;
            set(v);
            if (Math.abs(lastValidValue - prev) > 1e-9) {
                onChange.run();
            }
        }
    }

    private final Channel[] rgb = {new Channel(255, true), new Channel(255, true), new Channel(255, true)};
    private final Channel[] cmyk = {new Channel(100, false), new Channel(100, false),
            new Channel(100, false), new Channel(100, false)};
    private final Channel[] hls = {new Channel(360, false), new Channel(100, false), new Channel(100, false)};

    private final JPanel preview = new JPanel();
    private final PalettePanel palette = new PalettePanel();
    private boolean updating = false;

    private double lastS = 1.0;

    private double paletteS() {
        double s = hls[2].get() / 100.0;
        return s > 1e-6 ? s : lastS;
    }

    static double[] rgbToCmyk(double r, double g, double b) {
        double k = 1.0 - Math.max(r, Math.max(g, b));
        if (k >= 0.99999) return new double[]{0, 0, 0, 1.0};
        double c = (1.0 - r - k) / (1.0 - k);
        double m = (1.0 - g - k) / (1.0 - k);
        double y = (1.0 - b - k) / (1.0 - k);

        return new double[]{c, m, y, k};
    }

    static double[] cmykToRgb(double c, double m, double y, double k) {
        return new double[]{(1.0 - c) * (1.0 - k), (1.0 - m) * (1.0 - k), (1.0 - y) * (1.0 - k)};
    }

    static double[] rgbToHls(double r, double g, double b) {
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2.0;
        double d = max - min;
        double h = 0, s = 0;

        if (d > 1e-6) {
            s = l > 0.5 ? d / (2.0 - max - min) : d / (max + min);

            if (max == r)      h = ((g - b) / d) + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else               h = (r - g) / d + 4;
            h *= 60;
        }
        return new double[]{h, l, s};
    }

    static double[] hlsToRgb(double h, double l, double s) {
        if (s <= 1e-6) return new double[]{l, l, l};
        double q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
        double p = 2.0 * l - q;

        double hk = (h % 360) / 360.0;
        return new double[]{hue(p, q, hk + 1.0 / 3), hue(p, q, hk), hue(p, q, hk - 1.0 / 3)};
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6.0 * t;
        if (t < 1.0 / 2) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
        return p;
    }

    private void changed(int source) {
        if (updating) return;
        updating = true;
        try {
            double[] c;
            switch (source) {
                case 0:  c = new double[]{rgb[0].get() / 255.0, rgb[1].get() / 255.0, rgb[2].get() / 255.0}; break;
                case 1:  c = cmykToRgb(cmyk[0].get() / 100.0, cmyk[1].get() / 100.0, cmyk[2].get() / 100.0, cmyk[3].get() / 100.0); break;
                default: c = hlsToRgb(hls[0].get(), hls[1].get() / 100.0, hls[2].get() / 100.0); break;
            }
            applyRgb(clamp(c), source);
        } finally {
            updating = false;
        }
    }

    private void applyRgb(double[] c, int source) {
        if (source != 0) {
            for (int i = 0; i < 3; i++) rgb[i].set(c[i] * 255);
        }
        if (source != 1) {
            double[] k = rgbToCmyk(c[0], c[1], c[2]);
            for (int i = 0; i < 4; i++) cmyk[i].set(k[i] * 100);
        }
        if (source != 2) {
            double[] h = rgbToHls(c[0], c[1], c[2]);

            boolean extremeL = h[1] <= 1e-6 || h[1] >= 0.99999;
            boolean gray = h[2] <= 1e-6;

            if (extremeL || gray) {
                h[0] = hls[0].get();
            }
            if (extremeL) {
                h[2] = hls[2].get() / 100.0;
            }

            hls[0].set(h[0]);
            hls[1].set(h[1] * 100);
            hls[2].set(h[2] * 100);
        }

        double s = hls[2].get() / 100.0;
        if (s > 1e-6) lastS = s;

        Color col = new Color((int) Math.round(c[0] * 255), (int) Math.round(c[1] * 255), (int) Math.round(c[2] * 255));
        preview.setBackground(col);
        palette.repaint();
    }

    private static double[] clamp(double[] c) {
        double[] r = new double[c.length];
        for (int i = 0; i < c.length; i++) r[i] = Math.max(0, Math.min(1, c[i]));
        return r;
    }

    class PalettePanel extends JPanel {
        private BufferedImage img;
        private double imgS = -1;

        PalettePanel() {
            Dimension d = new Dimension(VIS_W, PALETTE_H);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            MouseAdapter ma = new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    pick(e);
                }
                public void mouseDragged(MouseEvent e) {
                    pick(e);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private void pick(MouseEvent e) {
            if (getWidth() <= 1 || getHeight() <= 1) return;
            Channel.commitPendingExcept(null);
            double h = 360.0 * Math.max(0, Math.min(getWidth() - 1, e.getX())) / (getWidth() - 1);
            double l = (1.0 - (double) Math.max(0, Math.min(getHeight() - 1, e.getY())) / (getHeight() - 1));

            if (hls[2].get() <= 1e-6) {
                hls[2].set(paletteS() * 100);
            }
            hls[0].set(h);
            hls[1].set(l * 100);
            changed(2);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth(), h = getHeight();
            if (w <= 1 || h <= 1) return;
            double s = paletteS();
            if (img == null || img.getWidth() != w || img.getHeight() != h || Math.abs(imgS - s) > 1e-4) {
                img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                for (int x = 0; x < w; x++) {
                    for (int y = 0; y < h; y++) {
                        double[] c = hlsToRgb(360.0 * x / (w - 1), 1.0 - (double) y / (h - 1), s);
                        img.setRGB(x, y, new Color((float) c[0], (float) c[1], (float) c[2]).getRGB());
                    }
                }
                imgS = s;
            }
            g.drawImage(img, 0, 0, null);
            int mx = (int) Math.round(hls[0].get() / 360.0 * (w - 1));
            int my = (int) Math.round((1.0 - hls[1].get() / 100.0) * (h - 1));
            g.setColor(Color.BLACK);
            g.drawOval(mx - 5, my - 5, 10, 10);
            g.setColor(Color.WHITE);
            g.drawOval(mx - 6, my - 6, 12, 12);
        }
    }

    private JPanel buildModelPanel(String title, String[] names, Channel[] ch, int modelIdx) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 13)));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 5, 3, 5);

        final Dimension labelSize = new Dimension(18, 26);
        final Dimension spinnerSize = new Dimension(90, 28);

        for (int i = 0; i < ch.length; i++) {
            final Channel c = ch[i];
            c.onChange = () -> changed(modelIdx);

            JLabel lbl = new JLabel(names[i]);
            lbl.setPreferredSize(labelSize);
            lbl.setMinimumSize(labelSize);

            c.slider.setMinimumSize(new Dimension(60, 26));
            c.spinner.setPreferredSize(spinnerSize);
            c.spinner.setMinimumSize(spinnerSize);
            c.spinner.setMaximumSize(spinnerSize);

            gc.gridy = i;
            gc.gridx = 0; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
            p.add(lbl, gc);
            gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
            p.add(c.slider, gc);
            gc.gridx = 2; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
            p.add(c.spinner, gc);

            c.slider.addChangeListener(e -> {
                if (updating || c.isProgrammaticUpdate) return;
                c.userSet(c.slider.getValue() / (double) c.scale);
            });

            c.spinner.addChangeListener(e -> {
                if (updating || c.isProgrammaticUpdate) return;
                c.userSet(c.get());
            });
        }
        return p;
    }

    public ColorModels() {
        super("Цветовые модели RGB / CMYK / HLS");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        setResizable(false);

        JPanel sliders = new JPanel();
        sliders.setLayout(new BoxLayout(sliders, BoxLayout.Y_AXIS));
        sliders.add(buildModelPanel("RGB (0–255)", new String[]{"R", "G", "B"}, rgb, 0));
        sliders.add(buildModelPanel("CMYK (%)", new String[]{"C", "M", "Y", "K"}, cmyk, 1));
        sliders.add(buildModelPanel("HLS (H: 0–360°, L и S: %)", new String[]{"H", "L", "S"}, hls, 2));

        Dimension previewSize = new Dimension(VIS_W, PREVIEW_H);
        preview.setPreferredSize(previewSize);
        preview.setMinimumSize(previewSize);
        preview.setMaximumSize(previewSize);
        preview.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        JPanel visual = new JPanel(new BorderLayout(0, GAP));
        visual.add(preview, BorderLayout.NORTH);
        visual.add(palette, BorderLayout.CENTER);
        Dimension visualSize = new Dimension(VIS_W, PREVIEW_H + GAP + PALETTE_H);
        visual.setPreferredSize(visualSize);
        visual.setMinimumSize(visualSize);

        JPanel visualWrap = new JPanel(new GridBagLayout());
        visualWrap.setPreferredSize(new Dimension(VIS_W + 20, PREVIEW_H + GAP + PALETTE_H + 20));
        visualWrap.setMinimumSize(new Dimension(0, 0));
        visualWrap.add(visual, new GridBagConstraints());

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(visualWrap, BorderLayout.WEST);
        root.add(sliders, BorderLayout.CENTER);
        setContentPane(root);

        applyRgb(new double[]{0.2, 0.5, 0.8}, -1);

        setSize(800, 600);
        setLocationRelativeTo(null);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ColorModels().setVisible(true));
    }
}
