package client;

import javax.swing.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class SmartClassroomClient extends JFrame {
    private static final int DEFAULT_SERVER_PORT = 5000;
    private static final int BUFFER_SIZE = 65507;
    private static final int FILE_CHUNK_SIZE = 8192;
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private final Color NAVY = new Color(25, 42, 70);
    private final Color BLUE = new Color(52, 116, 235);
    private final Color GREEN = new Color(33, 150, 83);
    private final Color BG = new Color(245, 247, 251);

    private DatagramSocket socket;
    private InetAddress serverAddress;
    private int serverPort = DEFAULT_SERVER_PORT;
    private volatile boolean running = false;
    private String username;
    private String role;
    private String currentRoom;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

    // UDP file transfer: chia file thành nhiều chunk có số thứ tự.
    private final ConcurrentHashMap<String, IncomingFile> incomingFiles = new ConcurrentHashMap<>();
    private final Set<String> fileStartAcks = ConcurrentHashMap.newKeySet();
    private final Set<String> fileChunkAcks = ConcurrentHashMap.newKeySet();

    private final CopyOnWriteArrayList<String> roomUsernames = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> webRtcSignals = new LinkedBlockingQueue<>();
    private volatile String webRtcTarget;
    private volatile String webRtcMode = "caller";

    private final BlockingQueue<String> classWebRtcSignals = new LinkedBlockingQueue<>();
    private final CopyOnWriteArraySet<String> classParticipants = new CopyOnWriteArraySet<>();
    private volatile boolean classCallActive = false;
    private volatile String classTeacher = null;
    private volatile String classWebRtcMode = "student";

    private HttpServer webRtcHttpServer;
    private volatile int webRtcHttpPort = -1;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private final JTextField txtServerIp = new JTextField("127.0.0.1", 18);
    private final JTextField txtServerPort = new JTextField("5000", 6);
    private final JTextField txtUsername = new JTextField(18);
    private final JComboBox<String> cbRole = new JComboBox<>(new String[]{"HỌC SINH", "GIÁO VIÊN"});
    private final JButton btnConnect = new JButton("Kết nối vào lớp học");
    private final JLabel lblLoginStatus = new JLabel("Nhập địa chỉ IP LAN được hiển thị trên máy Server.");

    private final JLabel lblIdentity = new JLabel("-");
    private final JLabel lblConnection = new JLabel("ĐÃ KẾT NỐI");
    private final JLabel lblRoom = new JLabel("Chưa vào phòng");
    private final JTextField txtRoom = new JTextField(10);
    private final JButton btnCreate = new JButton("Tạo phòng");
    private final JButton btnJoin = new JButton("Vào phòng");
    private final JButton btnLeave = new JButton("Rời phòng");
    private final JButton btnAttendance = new JButton("Điểm danh");
    private final JButton btnRaise = new JButton("Giơ tay");
    private final JButton btnQuiz = new JButton("Tạo câu hỏi");
    private final JButton btnWebRTC = new JButton("Gọi Video cá nhân");
    private final JButton btnClassWebRTC = new JButton("Mở lớp học trực tuyến");
    private final JTextArea areaChat = new JTextArea();
    private final JTextArea areaUsers = new JTextArea();
    private final JTextField txtMessage = new JTextField();
    private final JButton btnSend = new JButton("Gửi");
    private final JButton btnSendFile = new JButton("Gửi file");

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel"); }
            catch (Exception ignored) {}
            new SmartClassroomClient().setVisible(true);
        });
    }

    public SmartClassroomClient() {
        setTitle("Smart Classroom - Ứng dụng lớp học");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 700));
        setSize(1100, 740);
        setLocationRelativeTo(null);
        cards.add(buildLoginPanel(), "LOGIN");
        cards.add(buildClassroomPanel(), "CLASSROOM");
        setContentPane(cards);
        cardLayout.show(cards, "LOGIN");

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                shutdown();
                dispose();
            }
        });
    }

    private JPanel buildLoginPanel() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BG);
        JPanel card = new JPanel(new BorderLayout());
        card.setPreferredSize(new Dimension(560, 470));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(224, 230, 240)),
                new EmptyBorder(30, 40, 30, 40)));

        JPanel title = new JPanel(new GridLayout(0, 1));
        title.setOpaque(false);
        JLabel h = new JLabel("LỚP HỌC TRỰC TUYẾN", SwingConstants.CENTER);
        h.setFont(new Font("Segoe UI", Font.BOLD, 28)); h.setForeground(NAVY);
        JLabel s = new JLabel("Kết nối đến máy chủ lớp học bằng giao thức UDP", SwingConstants.CENTER);
        s.setFont(new Font("Segoe UI", Font.PLAIN, 14)); s.setForeground(new Color(105, 115, 130));
        title.add(h); title.add(s);
        card.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(8, 4, 8, 4); g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        addField(form, g, 0, "IP LAN của Server", txtServerIp);
        addField(form, g, 1, "Cổng UDP", txtServerPort);
        addField(form, g, 2, "Tên người dùng", txtUsername);
        addField(form, g, 3, "Vai trò", cbRole);
        g.gridx = 0; g.gridy = 4; g.gridwidth = 2;
        btnConnect.setBackground(BLUE); btnConnect.setForeground(Color.WHITE);
        btnConnect.setFont(new Font("Segoe UI", Font.BOLD, 14)); btnConnect.setFocusPainted(false);
        form.add(btnConnect, g);
        g.gridy = 5;
        lblLoginStatus.setForeground(new Color(95, 105, 120));
        lblLoginStatus.setHorizontalAlignment(SwingConstants.CENTER);
        form.add(lblLoginStatus, g);
        card.add(form, BorderLayout.CENTER);

        JLabel hint = new JLabel("Khi dùng nhiều máy: nhập IP LAN của Server, không dùng 127.0.0.1.", SwingConstants.CENTER);
        hint.setForeground(new Color(120, 125, 135));
        card.add(hint, BorderLayout.SOUTH);
        btnConnect.addActionListener(e -> connect());
        root.add(card);
        return root;
    }

    private void addField(JPanel p, GridBagConstraints g, int row, String label, JComponent field) {
        g.gridy = row; g.gridwidth = 1; g.gridx = 0; g.weightx = 0.3;
        JLabel l = new JLabel(label); l.setFont(new Font("Segoe UI", Font.BOLD, 13)); l.setForeground(NAVY); p.add(l, g);
        g.gridx = 1; g.weightx = 0.7; field.setFont(new Font("Segoe UI", Font.PLAIN, 14)); p.add(field, g);
    }

    private JPanel buildClassroomPanel() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(NAVY); header.setBorder(new EmptyBorder(16, 20, 16, 20));
        JPanel left = new JPanel(new GridLayout(0, 1)); left.setOpaque(false);
        JLabel title = new JLabel("LỚP HỌC TRỰC TUYẾN"); title.setForeground(Color.WHITE); title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        lblIdentity.setForeground(new Color(214, 223, 238)); lblIdentity.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        left.add(title); left.add(lblIdentity); header.add(left, BorderLayout.WEST);
        JPanel right = new JPanel(new GridLayout(0, 1)); right.setOpaque(false);
        lblConnection.setForeground(new Color(120, 235, 165)); lblConnection.setHorizontalAlignment(SwingConstants.RIGHT);
        lblRoom.setForeground(Color.WHITE); lblRoom.setHorizontalAlignment(SwingConstants.RIGHT);
        right.add(lblConnection); right.add(lblRoom); header.add(right, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(12, 12)); body.setBackground(BG); body.setBorder(new EmptyBorder(14, 14, 14, 14));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6)); toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createLineBorder(new Color(225, 230, 238)));
        toolbar.add(new JLabel("Mã phòng:")); toolbar.add(txtRoom);
        styleBlue(btnCreate); styleBlue(btnJoin); styleLight(btnLeave); styleLight(btnAttendance); styleLight(btnRaise); styleBlue(btnQuiz); styleLight(btnWebRTC); styleBlue(btnClassWebRTC);
        toolbar.add(btnCreate); toolbar.add(btnJoin); toolbar.add(btnLeave); toolbar.add(btnAttendance); toolbar.add(btnRaise); toolbar.add(btnQuiz); toolbar.add(btnWebRTC); toolbar.add(btnClassWebRTC);
        body.add(toolbar, BorderLayout.NORTH);

        areaChat.setEditable(false); areaChat.setLineWrap(true); areaChat.setWrapStyleWord(true); areaChat.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        areaUsers.setEditable(false); areaUsers.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        areaUsers.setText("NGƯỜI DÙNG TRỰC TUYẾN\n----------------\n");
        JScrollPane chatScroll = new JScrollPane(areaChat); chatScroll.setBorder(BorderFactory.createTitledBorder("Hoạt động lớp học"));
        JScrollPane userScroll = new JScrollPane(areaUsers); userScroll.setBorder(BorderFactory.createTitledBorder("Thành viên"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatScroll, userScroll);
        split.setResizeWeight(0.76); split.setDividerLocation(780);
        body.add(split, BorderLayout.CENTER);

        JPanel composer = new JPanel(new BorderLayout(8, 0)); composer.setBackground(BG);
        txtMessage.setFont(new Font("Segoe UI", Font.PLAIN, 14)); styleBlue(btnSend); styleLight(btnSendFile);
        JPanel sendActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        sendActions.setOpaque(false);
        sendActions.add(btnSendFile);
        sendActions.add(btnSend);
        composer.add(txtMessage, BorderLayout.CENTER);
        composer.add(sendActions, BorderLayout.EAST);
        body.add(composer, BorderLayout.SOUTH);
        root.add(body, BorderLayout.CENTER);

        btnCreate.addActionListener(e -> send("CREATE_ROOM|" + clean(txtRoom.getText())));
        btnJoin.addActionListener(e -> send("JOIN_ROOM|" + clean(txtRoom.getText())));
        btnLeave.addActionListener(e -> send("LEAVE_ROOM"));
        btnAttendance.addActionListener(e -> send("ATTENDANCE"));
        btnRaise.addActionListener(e -> send("RAISE_HAND"));
        btnQuiz.addActionListener(e -> showQuizCreator());
        btnWebRTC.addActionListener(e -> startWebRTCCall());
        btnClassWebRTC.addActionListener(e -> startClassWebRTCCall());
        btnSend.addActionListener(e -> sendChat());
        btnSendFile.addActionListener(e -> chooseAndSendFile());
        txtMessage.addActionListener(e -> sendChat());
        return root;
    }

    private void styleBlue(JButton b) { b.setBackground(BLUE); b.setForeground(Color.WHITE); b.setFocusPainted(false); b.setFont(new Font("Segoe UI", Font.BOLD, 12)); }
    private void styleLight(JButton b) { b.setBackground(new Color(236, 240, 247)); b.setForeground(NAVY); b.setFocusPainted(false); b.setFont(new Font("Segoe UI", Font.BOLD, 12)); }

    private void connect() {
        if (running) return;
        try {
            username = clean(txtUsername.getText());
            role = "GIÁO VIÊN".equals(String.valueOf(cbRole.getSelectedItem())) ? "TEACHER" : "STUDENT";
            if (username.isBlank()) { JOptionPane.showMessageDialog(this, "Vui lòng nhập tên người dùng."); return; }
            serverAddress = InetAddress.getByName(txtServerIp.getText().trim());
            serverPort = Integer.parseInt(txtServerPort.getText().trim());
            socket = new DatagramSocket();
            running = true;
            btnConnect.setEnabled(false);
            lblLoginStatus.setText("Đang kết nối...");
            Thread receiver = new Thread(this::receiveLoop, "udp-client-receiver"); receiver.setDaemon(true); receiver.start();
            send("LOGIN|" + username + "|" + role);
            heartbeat.scheduleAtFixedRate(() -> { if (running) send("HEARTBEAT"); }, 2, 3, TimeUnit.SECONDS);
        } catch (Exception e) {
            running = false; btnConnect.setEnabled(true);
            JOptionPane.showMessageDialog(this, e.getMessage(), "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void receiveLoop() {
        while (running) {
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                SwingUtilities.invokeLater(() -> processMessage(msg));
            } catch (SocketException e) {
                if (running) SwingUtilities.invokeLater(() -> disconnected("Socket đã đóng"));
                break;
            } catch (Exception e) {
                if (running) append("[MẠNG] " + e.getMessage());
            }
        }
    }

    private void processMessage(String msg) {
        String[] p = msg.split("\\|", -1);
        switch (p[0]) {
            case "LOGIN_OK" -> {
                lblIdentity.setText(p[1] + "  |  " + displayRole(p[2]) + "  |  Máy chủ " + txtServerIp.getText().trim() + ":" + serverPort);
                lblConnection.setText("ĐÃ KẾT NỐI"); lblConnection.setForeground(new Color(120, 235, 165));
                btnCreate.setVisible("TEACHER".equals(role)); btnQuiz.setVisible("TEACHER".equals(role));
                btnClassWebRTC.setVisible("TEACHER".equals(role));
                btnAttendance.setVisible("STUDENT".equals(role)); btnRaise.setVisible("STUDENT".equals(role));
                cardLayout.show(cards, "CLASSROOM");
                append("[HỆ THỐNG] Kết nối thành công: " + p[1] + " (" + displayRole(p[2]) + ")");
            }
            case "ROOM_CREATED", "ROOM_JOINED" -> {
                currentRoom = p.length > 1 ? p[1] : null; lblRoom.setText("Phòng: " + currentRoom); append("[HỆ THỐNG] Đã vào phòng " + currentRoom);
            }
            case "ROOM_LEFT" -> { endClassCallLocal(); currentRoom = null; lblRoom.setText("Chưa vào phòng"); areaUsers.setText("NGƯỜI DÙNG TRỰC TUYẾN\n----------------\n"); append("[HỆ THỐNG] Đã rời phòng"); }
            case "ROOM_CLOSED" -> { endClassCallLocal(); currentRoom = null; lblRoom.setText("Chưa vào phòng"); areaUsers.setText("NGƯỜI DÙNG TRỰC TUYẾN\n----------------\n"); JOptionPane.showMessageDialog(this, "Phòng " + p[1] + " đã được quản trị viên Server đóng."); }
            case "CHAT" -> append("[" + p[1] + "] " + (p.length > 2 ? p[2] : ""));
            case "SYSTEM" -> append("[HỆ THỐNG] " + translateSystemMessage(p.length > 1 ? p[1] : ""));
            case "ATTENDANCE_OK" -> append("[ĐIỂM DANH] " + p[1] + " đã điểm danh. Tổng: " + p[2]);
            case "RAISE_HAND" -> append("[GIƠ TAY] " + p[1] + " đang xin phát biểu.");
            case "USER_LIST" -> updateUserList(p.length > 1 ? p[1] : "");
            case "QUIZ" -> showQuiz(p);
            case "QUIZ_RESULT" -> JOptionPane.showMessageDialog(this, "Kết quả: " + translateQuizResult(p[1]) + "\nĐáp án đúng: " + (p.length > 2 ? p[2] : ""), "Kết quả câu hỏi", JOptionPane.INFORMATION_MESSAGE);
            case "QUIZ_PROGRESS" -> append("[CÂU HỎI] " + p[1] + " -> " + translateQuizResult(p[2]) + " | Đã trả lời: " + p[3]);
            case "WEBRTC_CALLING" -> append("[WEBRTC] Đang gọi " + (p.length > 1 ? p[1] : "") + "...");
            case "WEBRTC_INCOMING" -> handleIncomingWebRTCCall(p.length > 1 ? p[1] : "");
            case "WEBRTC_ACCEPT" -> append("[WEBRTC] " + (p.length > 1 ? p[1] : "Người dùng") + " đã chấp nhận cuộc gọi. Hãy thao tác trên cửa sổ trình duyệt.");
            case "WEBRTC_REJECT" -> {
                String who = p.length > 1 ? p[1] : "Người dùng";
                JOptionPane.showMessageDialog(this, who + " đã từ chối cuộc gọi WebRTC.", "WebRTC", JOptionPane.INFORMATION_MESSAGE);
                webRtcTarget = null;
            }
            case "WEBRTC_CLASS_STARTED" -> {
                int count = p.length > 1 ? safeInt(p[1]) : 0;
                append("[LỚP TRỰC TUYẾN] Đã gửi lời mời đến " + count + " học sinh. Đang chờ các bạn tham gia...");
            }
            case "WEBRTC_CLASS_INCOMING" -> handleIncomingClassCall(p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : currentRoom);
            case "WEBRTC_CLASS_JOINED" -> append("[LỚP TRỰC TUYẾN] Đã tham gia buổi học của giáo viên " + (p.length > 1 ? p[1] : "") + ".");
            case "WEBRTC_CLASS_ACCEPTED" -> {
                String student = p.length > 1 ? p[1] : "";
                if (!student.isBlank()) classParticipants.add(student);
                append("[LỚP TRỰC TUYẾN] " + student + " đã tham gia. Tổng đang tham gia: " + classParticipants.size());
            }
            case "WEBRTC_CLASS_REJECTED" -> append("[LỚP TRỰC TUYẾN] " + (p.length > 1 ? p[1] : "Học sinh") + " đã từ chối lời mời.");
            case "WEBRTC_CLASS_ENDED" -> {
                String teacher = p.length > 1 ? p[1] : "Giáo viên";
                classWebRtcSignals.offer("{\"from\":\"" + jsonEscape(teacher) + "\",\"signal\":{\"type\":\"hangup-all\"}}");
                append("[LỚP TRỰC TUYẾN] Buổi học đã kết thúc.");
                if (classCallActive && !"TEACHER".equals(role)) JOptionPane.showMessageDialog(this, "Giáo viên đã kết thúc buổi học trực tuyến.", "Lớp học trực tuyến", JOptionPane.INFORMATION_MESSAGE);
                classCallActive = false;
                classParticipants.clear();
            }
            case "WEBRTC_SIGNAL" -> handleWebRTCSignal(p);
            case "WEBRTC_HANGUP" -> handleWebRTCHangup(p.length > 1 ? p[1] : "Người dùng");
            case "FILE_START_OK" -> { if (p.length > 1) fileStartAcks.add(p[1]); }
            case "FILE_ACK" -> { if (p.length > 2) fileChunkAcks.add(p[1] + ":" + p[2]); }
            case "FILE_SENT" -> append("[FILE] Server đã nhận đủ file " + (p.length > 1 ? p[1] : "") + ".");
            case "FILE_START" -> handleIncomingFileStart(p);
            case "FILE_CHUNK" -> handleIncomingFileChunk(p);
            case "FILE_END" -> handleIncomingFileEnd(p);
            case "ANNOUNCEMENT" -> { append("[THÔNG BÁO SERVER] " + (p.length > 1 ? p[1] : "")); JOptionPane.showMessageDialog(this, p.length > 1 ? p[1] : "", "Thông báo từ Server", JOptionPane.INFORMATION_MESSAGE); }
            case "KICKED" -> { JOptionPane.showMessageDialog(this, "Bạn đã bị quản trị viên Server mời ra khỏi hệ thống.\nLý do: " + (p.length > 1 ? p[1] : ""), "Đã ngắt kết nối", JOptionPane.WARNING_MESSAGE); disconnected("Bị quản trị viên mời ra khỏi hệ thống"); }
            case "SERVER_SHUTDOWN" -> { JOptionPane.showMessageDialog(this, "Server đã dừng hoạt động.\n" + (p.length > 1 ? p[1] : "")); disconnected("Server đã dừng"); }
            case "ERROR" -> { String err = p.length > 1 ? p[1] : "Lỗi không xác định"; String viErr = translateServerError(err); append("[LỖI] " + viErr); if ("No students in room".equals(err)) endClassCallLocal(); if (!cards.isShowing()) {} JOptionPane.showMessageDialog(this, viErr, "Thông báo từ Server", JOptionPane.ERROR_MESSAGE); if (err.toLowerCase().contains("username already")) resetLogin(); }
            default -> append("[RAW] " + msg);
        }
    }

    private void updateUserList(String data) {
        StringBuilder b = new StringBuilder("NGƯỜI DÙNG TRỰC TUYẾN\n----------------\n");
        roomUsernames.clear();
        if (!data.isBlank()) for (String item : data.split(",")) {
            String[] u = item.split(":", 2);
            String name = u[0].trim();
            if (!name.isBlank()) roomUsernames.add(name);
            b.append(name);
            if (u.length > 1) b.append("  [").append(displayRole(u[1])).append("]");
            b.append("\n");
        }
        areaUsers.setText(b.toString());
    }

    private String displayRole(String value) {
        return "TEACHER".equals(value) ? "GIÁO VIÊN" : "HỌC SINH";
    }

    private String translateQuizResult(String value) {
        return "CORRECT".equals(value) ? "ĐÚNG" : ("WRONG".equals(value) ? "SAI" : value);
    }

    private String translateServerError(String value) {
        if (value == null) return "Lỗi không xác định";
        return switch (value) {
            case "LOGIN requires username and role" -> "Thiếu tên người dùng hoặc vai trò.";
            case "Username cannot be empty" -> "Tên người dùng không được để trống.";
            case "Username already in use" -> "Tên người dùng này đang được sử dụng.";
            case "Only TEACHER can create a room" -> "Chỉ GIÁO VIÊN mới được tạo phòng.";
            case "Room code cannot be empty" -> "Mã phòng không được để trống.";
            case "Room already exists" -> "Mã phòng đã tồn tại.";
            case "Room code is required" -> "Vui lòng nhập mã phòng.";
            case "Room does not exist" -> "Phòng không tồn tại.";
            case "Only TEACHER can start quiz" -> "Chỉ GIÁO VIÊN mới được tạo câu hỏi.";
            case "Quiz data is incomplete" -> "Thông tin câu hỏi chưa đầy đủ.";
            case "Correct answer must be A-D" -> "Đáp án đúng phải nằm trong A-D.";
            case "No active quiz" -> "Hiện không có câu hỏi đang hoạt động.";
            case "Invalid answer" -> "Đáp án không hợp lệ.";
            case "Please login first" -> "Vui lòng đăng nhập trước.";
            case "WebRTC target is not online" -> "Người bạn muốn gọi hiện không trực tuyến.";
            case "WebRTC target must be in the same room" -> "Chỉ có thể gọi người đang ở cùng phòng học.";
            case "Cannot call yourself" -> "Không thể tự gọi chính mình.";
            case "WebRTC target is required" -> "Vui lòng chọn người muốn gọi.";
            case "WebRTC signal is incomplete" -> "Dữ liệu kết nối WebRTC chưa đầy đủ.";
            case "Only TEACHER can start online class" -> "Chỉ GIÁO VIÊN mới được mở lớp học trực tuyến.";
            case "Please join a room first" -> "Vui lòng vào hoặc tạo một phòng học trước.";
            case "No students in room" -> "Trong phòng chưa có học sinh để mời vào lớp trực tuyến.";
            case "Only STUDENT can accept online class" -> "Chỉ HỌC SINH mới nhận lời mời vào lớp trực tuyến.";
            case "Online class host must be TEACHER" -> "Người chủ trì lớp trực tuyến phải là GIÁO VIÊN.";
            case "Only TEACHER can end online class" -> "Chỉ GIÁO VIÊN mới được kết thúc lớp trực tuyến.";
            default -> value;
        };
    }

    private String translateSystemMessage(String value) {
        if (value == null) return "";
        return value
                .replace(" created room ", " đã tạo phòng ")
                .replace(" joined the room", " đã vào phòng")
                .replace(" left the room", " đã rời phòng");
    }

    private void sendChat() {
        String msg = clean(txtMessage.getText()); if (msg.isBlank()) return;
        if (currentRoom == null) { JOptionPane.showMessageDialog(this, "Vui lòng vào hoặc tạo phòng trước."); return; }
        send("CHAT|" + msg); txtMessage.setText("");
    }

    private void chooseAndSendFile() {
        if (currentRoom == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng vào hoặc tạo phòng trước khi gửi file.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Chọn file gửi cho cả phòng");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        new Thread(() -> sendFileUdp(file), "udp-file-sender").start();
    }

    private void sendFileUdp(Path file) {
        try {
            long size = Files.size(file);
            if (size <= 0) throw new IOException("File rỗng.");
            if (size > MAX_FILE_SIZE) throw new IOException("File vượt quá giới hạn 20 MB của bản demo UDP.");

            byte[] all = Files.readAllBytes(file);
            int totalChunks = (all.length + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE;
            String transferId = Long.toHexString(System.currentTimeMillis()) + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
            String encodedName = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));

            SwingUtilities.invokeLater(() -> append("[FILE] Đang gửi " + file.getFileName() + " (" + size + " byte, " + totalChunks + " gói)..."));

            boolean started = false;
            for (int attempt = 1; attempt <= 3 && !started; attempt++) {
                fileStartAcks.remove(transferId);
                send("FILE_START|" + transferId + "|" + encodedName + "|" + size + "|" + totalChunks);
                started = waitForSet(fileStartAcks, transferId, 900);
            }
            if (!started) throw new IOException("Server không xác nhận bắt đầu truyền file.");

            int lastPercent = -1;
            for (int seq = 0; seq < totalChunks; seq++) {
                int from = seq * FILE_CHUNK_SIZE;
                int to = Math.min(all.length, from + FILE_CHUNK_SIZE);
                byte[] chunk = java.util.Arrays.copyOfRange(all, from, to);
                String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(chunk);
                String ackKey = transferId + ":" + seq;
                boolean acked = false;
                for (int attempt = 1; attempt <= 4 && !acked; attempt++) {
                    fileChunkAcks.remove(ackKey);
                    send("FILE_CHUNK|" + transferId + "|" + seq + "|" + encoded);
                    acked = waitForSet(fileChunkAcks, ackKey, 700);
                }
                if (!acked) throw new IOException("Mất gói số " + seq + " sau nhiều lần gửi lại.");

                int percent = (int) (((long) (seq + 1) * 100) / totalChunks);
                if (percent / 10 != lastPercent / 10 || percent == 100) {
                    lastPercent = percent;
                    int shown = percent;
                    SwingUtilities.invokeLater(() -> append("[FILE] Tiến độ gửi: " + shown + "%"));
                }
            }

            send("FILE_END|" + transferId);
            SwingUtilities.invokeLater(() -> append("[FILE] Đã gửi xong: " + file.getFileName()));
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Không gửi được file: " + e.getMessage(), "Lỗi gửi file", JOptionPane.ERROR_MESSAGE));
        }
    }

    private boolean waitForSet(Set<String> set, String key, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (running && System.currentTimeMillis() < deadline) {
            if (set.remove(key)) return true;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return set.remove(key);
    }

    private void handleIncomingFileStart(String[] p) {
        if (p.length < 6) return;
        try {
            String sender = p[1];
            String id = p[2];
            String fileName = decodeFileName(p[3]);
            long size = Long.parseLong(p[4]);
            int totalChunks = Integer.parseInt(p[5]);
            if (size < 0 || size > MAX_FILE_SIZE || totalChunks <= 0) return;
            IncomingFile incoming = new IncomingFile(sender, id, safeFileName(fileName), size, totalChunks);
            incomingFiles.put(sender + "|" + id, incoming);
            append("[FILE] " + sender + " đang gửi: " + incoming.fileName + " (" + size + " byte)");
        } catch (Exception e) {
            append("[FILE] Không đọc được thông tin file: " + e.getMessage());
        }
    }

    private void handleIncomingFileChunk(String[] p) {
        if (p.length < 5) return;
        try {
            String sender = p[1];
            String id = p[2];
            int seq = Integer.parseInt(p[3]);
            IncomingFile incoming = incomingFiles.get(sender + "|" + id);
            if (incoming == null || seq < 0 || seq >= incoming.totalChunks) return;
            incoming.chunks.put(seq, Base64.getUrlDecoder().decode(p[4]));
        } catch (Exception e) {
            append("[FILE] Lỗi nhận chunk: " + e.getMessage());
        }
    }

    private void handleIncomingFileEnd(String[] p) {
        if (p.length < 3) return;
        String sender = p[1];
        String id = p[2];
        IncomingFile incoming = incomingFiles.get(sender + "|" + id);
        if (incoming == null) return;

        List<Integer> missing = new java.util.ArrayList<>();
        for (int i = 0; i < incoming.totalChunks; i++) if (!incoming.chunks.containsKey(i)) missing.add(i);
        if (!missing.isEmpty()) {
            String csv = missing.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            append("[FILE] Thiếu " + missing.size() + " gói, đang yêu cầu Server gửi lại...");
            send("FILE_MISSING|" + clean(sender) + "|" + id + "|" + csv);
            return;
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(incoming.size, Integer.MAX_VALUE));
            for (int i = 0; i < incoming.totalChunks; i++) out.write(incoming.chunks.get(i));
            byte[] data = out.toByteArray();
            if (data.length != incoming.size) throw new IOException("Kích thước file nhận không khớp.");

            Path base = Paths.get(System.getProperty("user.home"), "Downloads");
            if (!Files.isDirectory(base)) base = Paths.get(System.getProperty("user.home"));
            Path dir = base.resolve("SmartClassroomReceived");
            Files.createDirectories(dir);
            Path output = uniquePath(dir, incoming.fileName);
            Files.write(output, data);
            incomingFiles.remove(sender + "|" + id);
            append("[FILE] Đã nhận từ " + sender + ": " + incoming.fileName + " -> " + output.toAbsolutePath());
            send("FILE_RECEIVED|" + clean(sender) + "|" + id);
        } catch (Exception e) {
            append("[FILE] Không lưu được file: " + e.getMessage());
        }
    }

    private String decodeFileName(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private String safeFileName(String name) {
        String n = name == null ? "file.bin" : name.replace('\\', '_').replace('/', '_').trim();
        return n.isBlank() ? "file.bin" : n;
    }

    private Path uniquePath(Path dir, String fileName) {
        Path p = dir.resolve(fileName);
        if (!Files.exists(p)) return p;
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) { base = fileName.substring(0, dot); ext = fileName.substring(dot); }
        int i = 1;
        while (Files.exists(p = dir.resolve(base + " (" + i++ + ")" + ext))) { }
        return p;
    }

    private void showQuizCreator() {
        JTextField q = new JTextField(); JTextField a = new JTextField(); JTextField b = new JTextField(); JTextField c = new JTextField(); JTextField d = new JTextField();
        JComboBox<String> correct = new JComboBox<>(new String[]{"A", "B", "C", "D"});
        JPanel p = new JPanel(new GridLayout(0, 2, 7, 7));
        p.add(new JLabel("Câu hỏi")); p.add(q); p.add(new JLabel("A")); p.add(a); p.add(new JLabel("B")); p.add(b); p.add(new JLabel("C")); p.add(c); p.add(new JLabel("D")); p.add(d); p.add(new JLabel("Đáp án đúng")); p.add(correct);
        int result = JOptionPane.showConfirmDialog(this, p, "Tạo câu hỏi thời gian thực", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) send("QUIZ_START|" + clean(q.getText()) + "|" + clean(a.getText()) + "|" + clean(b.getText()) + "|" + clean(c.getText()) + "|" + clean(d.getText()) + "|" + correct.getSelectedIndex());
    }

    private void showQuiz(String[] p) {
        if (p.length < 6) return;
        String[] options = {"A. " + p[2], "B. " + p[3], "C. " + p[4], "D. " + p[5]};
        int answer = JOptionPane.showOptionDialog(this, p[1], "Câu hỏi thời gian thực", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer >= 0) send("QUIZ_ANSWER|" + answer);
    }

    private void startWebRTCCall() {
        if (currentRoom == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng vào một phòng học trước khi gọi video.");
            return;
        }
        List<String> choices = roomUsernames.stream()
                .filter(name -> !name.equals(username))
                .sorted()
                .toList();
        if (choices.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Trong phòng chưa có người dùng khác để gọi.");
            return;
        }
        JComboBox<String> cb = new JComboBox<>(choices.toArray(new String[0]));
        int result = JOptionPane.showConfirmDialog(this, cb,
                "Chọn người muốn gọi video", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;
        String target = String.valueOf(cb.getSelectedItem());
        if (target == null || target.isBlank()) return;

        webRtcTarget = target;
        webRtcMode = "caller";
        webRtcSignals.clear();
        send("WEBRTC_CALL|" + clean(target));
        openWebRTCPage();
    }

    private void startClassWebRTCCall() {
        if (!"TEACHER".equals(role)) {
            JOptionPane.showMessageDialog(this, "Chỉ giáo viên mới có thể mở lớp học trực tuyến.");
            return;
        }
        if (currentRoom == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng tạo hoặc vào một phòng học trước.");
            return;
        }
        int yes = JOptionPane.showConfirmDialog(this,
                "Mời toàn bộ học sinh trong phòng " + currentRoom + " tham gia học bằng Camera/Microphone?\n\n" +
                        "Mỗi học sinh sẽ nhận một lời mời tham gia.",
                "Mở lớp học trực tuyến", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (yes != JOptionPane.YES_OPTION) return;

        classCallActive = true;
        classTeacher = username;
        classWebRtcMode = "teacher";
        classParticipants.clear();
        classWebRtcSignals.clear();
        send("WEBRTC_CLASS_CALL");
        openClassWebRTCPage();
    }

    private void handleIncomingClassCall(String teacher, String room) {
        if (teacher == null || teacher.isBlank()) return;
        int yes = JOptionPane.showConfirmDialog(this,
                "Giáo viên " + teacher + " đang mở lớp học trực tuyến" +
                        (room == null || room.isBlank() ? "." : " cho phòng " + room + ".") +
                        "\nBạn có muốn tham gia không?",
                "Lời mời học trực tuyến", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (yes == JOptionPane.YES_OPTION) {
            classCallActive = true;
            classTeacher = teacher;
            classWebRtcMode = "student";
            classParticipants.clear();
            classWebRtcSignals.clear();
            send("WEBRTC_CLASS_ACCEPT|" + clean(teacher));
            openClassWebRTCPage();
        } else {
            send("WEBRTC_CLASS_REJECT|" + clean(teacher));
        }
    }

    private void handleWebRTCHangup(String who) {
        if (classCallActive) {
            classWebRtcSignals.offer("{\"from\":\"" + jsonEscape(who) + "\",\"signal\":{\"type\":\"hangup\"}}");
            if ("TEACHER".equals(role)) {
                classParticipants.remove(who);
                append("[LỚP TRỰC TUYẾN] " + who + " đã rời buổi học.");
            } else if (who.equals(classTeacher)) {
                classCallActive = false;
                append("[LỚP TRỰC TUYẾN] Giáo viên đã ngắt kết nối buổi học.");
            }
            return;
        }
        webRtcSignals.offer("{\"type\":\"hangup\"}");
        append("[WEBRTC] " + who + " đã kết thúc cuộc gọi.");
    }

    private int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private void handleIncomingWebRTCCall(String caller) {
        if (caller == null || caller.isBlank()) return;
        int yes = JOptionPane.showConfirmDialog(this,
                caller + " đang gọi video cho bạn.\nBạn có muốn nhận cuộc gọi không?",
                "Cuộc gọi WebRTC", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (yes == JOptionPane.YES_OPTION) {
            webRtcTarget = caller;
            webRtcMode = "callee";
            webRtcSignals.clear();
            send("WEBRTC_ACCEPT|" + clean(caller));
            openWebRTCPage();
        } else {
            send("WEBRTC_REJECT|" + clean(caller));
        }
    }

    private void handleWebRTCSignal(String[] p) {
        if (p.length < 3) return;
        String from = p[1];
        try {
            String decoded = decodeSignal(p[2]);
            if (classCallActive) {
                classWebRtcSignals.offer("{\"from\":\"" + jsonEscape(from) + "\",\"signal\":" + decoded + "}");
                return;
            }
            if (webRtcTarget == null || webRtcTarget.isBlank()) webRtcTarget = from;
            if (!from.equals(webRtcTarget)) return;
            webRtcSignals.offer(decoded);
        } catch (Exception e) {
            append("[WEBRTC] Không đọc được dữ liệu signaling: " + e.getMessage());
        }
    }

    private synchronized void ensureWebRtcHttpServer() throws IOException {
        if (webRtcHttpServer != null) return;
        webRtcHttpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        webRtcHttpPort = webRtcHttpServer.getAddress().getPort();
        webRtcHttpServer.createContext("/", this::serveWebRtcPage);
        webRtcHttpServer.createContext("/info", this::serveWebRtcInfo);
        webRtcHttpServer.createContext("/signal", this::handleWebRtcHttpSignal);
        webRtcHttpServer.createContext("/hangup", this::handleWebRtcHttpHangup);
        webRtcHttpServer.createContext("/classroom", this::serveClassWebRtcPage);
        webRtcHttpServer.createContext("/class-info", this::serveClassWebRtcInfo);
        webRtcHttpServer.createContext("/class-participants", this::serveClassWebRtcParticipants);
        webRtcHttpServer.createContext("/class-signal", this::handleClassWebRtcHttpSignal);
        webRtcHttpServer.createContext("/class-end", this::handleClassWebRtcHttpEnd);
        webRtcHttpServer.setExecutor(Executors.newCachedThreadPool());
        webRtcHttpServer.start();
    }

    private void openWebRTCPage() {
        try {
            ensureWebRtcHttpServer();
            if (!Desktop.isDesktopSupported()) throw new IOException("Máy không hỗ trợ mở trình duyệt tự động");
            URI uri = new URI("http://127.0.0.1:" + webRtcHttpPort + "/");
            Desktop.getDesktop().browse(uri);
            append("[WEBRTC] Đã mở cuộc gọi với " + webRtcTarget + " trên trình duyệt.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Không mở được mô-đun WebRTC.\n" + e.getMessage(),
                    "Lỗi WebRTC", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openClassWebRTCPage() {
        try {
            ensureWebRtcHttpServer();
            if (!Desktop.isDesktopSupported()) throw new IOException("Máy không hỗ trợ mở trình duyệt tự động");
            URI uri = new URI("http://127.0.0.1:" + webRtcHttpPort + "/classroom");
            Desktop.getDesktop().browse(uri);
            append("[LỚP TRỰC TUYẾN] Đã mở giao diện WebRTC cho " + ("teacher".equals(classWebRtcMode) ? "giáo viên" : "học sinh") + ".");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Không mở được giao diện lớp học WebRTC.\n" + e.getMessage(),
                    "Lỗi WebRTC", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void serveClassWebRtcPage(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { sendHttp(ex, 405, "text/plain; charset=utf-8", "Method not allowed"); return; }
        try (InputStream in = SmartClassroomClient.class.getResourceAsStream("/webrtc/classroom.html")) {
            if (in == null) { sendHttp(ex, 404, "text/plain; charset=utf-8", "Không tìm thấy giao diện lớp học WebRTC trong file JAR."); return; }
            sendHttp(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private void serveClassWebRtcInfo(HttpExchange ex) throws IOException {
        String json = "{\"username\":\"" + jsonEscape(username) + "\",\"room\":\"" +
                jsonEscape(currentRoom) + "\",\"mode\":\"" + jsonEscape(classWebRtcMode) +
                "\",\"teacher\":\"" + jsonEscape(classTeacher) + "\"}";
        sendHttp(ex, 200, "application/json; charset=utf-8", json);
    }

    private void serveClassWebRtcParticipants(HttpExchange ex) throws IOException {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (String name : classParticipants.stream().sorted().toList()) {
            if (!first) b.append(',');
            first = false;
            b.append('\"').append(jsonEscape(name)).append('\"');
        }
        b.append(']');
        sendHttp(ex, 200, "application/json; charset=utf-8", b.toString());
    }

    private void handleClassWebRtcHttpSignal(HttpExchange ex) throws IOException {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String target = getQueryParam(ex.getRequestURI(), "target");
            if ((target == null || target.isBlank()) && "student".equals(classWebRtcMode)) target = classTeacher;
            if (target == null || target.isBlank()) {
                sendHttp(ex, 409, "text/plain; charset=utf-8", "Thiếu người nhận signaling");
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                send("WEBRTC_SIGNAL|" + clean(target) + "|" + encodeSignal(body));
                sendHttp(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
            } catch (Exception e) {
                sendHttp(ex, 500, "text/plain; charset=utf-8", e.getMessage());
            }
            return;
        }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            String signal = classWebRtcSignals.poll();
            if (signal == null) { ex.sendResponseHeaders(204, -1); ex.close(); }
            else sendHttp(ex, 200, "application/json; charset=utf-8", signal);
            return;
        }
        sendHttp(ex, 405, "text/plain; charset=utf-8", "Method not allowed");
    }

    private void handleClassWebRtcHttpEnd(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendHttp(ex, 405, "text/plain; charset=utf-8", "Method not allowed"); return; }
        if (classCallActive) {
            if ("teacher".equals(classWebRtcMode)) send("WEBRTC_CLASS_END");
            else if (classTeacher != null && !classTeacher.isBlank()) send("WEBRTC_HANGUP|" + clean(classTeacher));
        }
        endClassCallLocal();
        sendHttp(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private void endClassCallLocal() {
        classCallActive = false;
        classParticipants.clear();
        classWebRtcSignals.clear();
        classTeacher = null;
        classWebRtcMode = "student";
    }

    private String getQueryParam(URI uri, String key) {
        String q = uri == null ? null : uri.getRawQuery();
        if (q == null || q.isBlank()) return null;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && key.equals(URLDecoder.decode(kv[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void serveWebRtcPage(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { sendHttp(ex, 405, "text/plain; charset=utf-8", "Method not allowed"); return; }
        try (InputStream in = SmartClassroomClient.class.getResourceAsStream("/webrtc/index.html")) {
            if (in == null) { sendHttp(ex, 404, "text/plain; charset=utf-8", "Không tìm thấy giao diện WebRTC trong file JAR."); return; }
            sendHttp(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private void serveWebRtcInfo(HttpExchange ex) throws IOException {
        String json = "{\"username\":\"" + jsonEscape(username) + "\",\"target\":\"" +
                jsonEscape(webRtcTarget) + "\",\"room\":\"" + jsonEscape(currentRoom) +
                "\",\"mode\":\"" + jsonEscape(webRtcMode) + "\"}";
        sendHttp(ex, 200, "application/json; charset=utf-8", json);
    }

    private void handleWebRtcHttpSignal(HttpExchange ex) throws IOException {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (webRtcTarget == null || webRtcTarget.isBlank()) {
                sendHttp(ex, 409, "text/plain; charset=utf-8", "Chưa chọn người nhận cuộc gọi");
                return;
            }
            try {
                send("WEBRTC_SIGNAL|" + clean(webRtcTarget) + "|" + encodeSignal(body));
                sendHttp(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
            } catch (Exception e) {
                sendHttp(ex, 500, "text/plain; charset=utf-8", e.getMessage());
            }
            return;
        }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            String signal = webRtcSignals.poll();
            if (signal == null) { ex.sendResponseHeaders(204, -1); ex.close(); }
            else sendHttp(ex, 200, "application/json; charset=utf-8", signal);
            return;
        }
        sendHttp(ex, 405, "text/plain; charset=utf-8", "Method not allowed");
    }

    private void handleWebRtcHttpHangup(HttpExchange ex) throws IOException {
        if (webRtcTarget != null && !webRtcTarget.isBlank()) send("WEBRTC_HANGUP|" + clean(webRtcTarget));
        webRtcSignals.clear();
        sendHttp(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private void sendHttp(HttpExchange ex, int status, String contentType, String text) throws IOException {
        sendHttp(ex, status, contentType, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendHttp(HttpExchange ex, int status, String contentType, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private String encodeSignal(String json) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    private String decodeSignal(String encoded) throws IOException {
        byte[] compressed = Base64.getUrlDecoder().decode(encoded);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private void send(String message) {
        if (!running || socket == null || serverAddress == null) return;
        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, serverAddress, serverPort));
        } catch (Exception e) { SwingUtilities.invokeLater(() -> append("[LỖI GỬI] " + e.getMessage())); }
    }

    private void append(String s) {
        areaChat.append(s + "\n"); areaChat.setCaretPosition(areaChat.getDocument().getLength());
    }

    private void disconnected(String reason) {
        running = false;
        if (socket != null && !socket.isClosed()) socket.close();
        currentRoom = null;
        lblConnection.setText("MẤT KẾT NỐI"); lblConnection.setForeground(Color.RED);
        append("[HỆ THỐNG] Đã ngắt kết nối: " + reason);
    }

    private void resetLogin() {
        running = false; if (socket != null && !socket.isClosed()) socket.close(); btnConnect.setEnabled(true); lblLoginStatus.setText("Kết nối thất bại. Hãy kiểm tra tên người dùng / IP / cổng.");
    }

    private void shutdown() {
        if (running) send("LOGOUT");
        running = false;
        heartbeat.shutdownNow();
        if (socket != null && !socket.isClosed()) socket.close();
        endClassCallLocal();
        if (webRtcHttpServer != null) { webRtcHttpServer.stop(0); webRtcHttpServer = null; }
    }

    private static class IncomingFile {
        final String sender;
        final String id;
        final String fileName;
        final long size;
        final int totalChunks;
        final ConcurrentHashMap<Integer, byte[]> chunks = new ConcurrentHashMap<>();

        IncomingFile(String sender, String id, String fileName, long size, int totalChunks) {
            this.sender = sender;
            this.id = id;
            this.fileName = fileName;
            this.size = size;
            this.totalChunks = totalChunks;
        }
    }

    private String clean(String s) { return s == null ? "" : s.trim().replace("|", "/").replace("\r", " ").replace("\n", " "); }
}
