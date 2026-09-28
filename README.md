# SMART CLASSROOM GK V2 - UDP + WebRTC + Client-Server

Phiên bản giữa kỳ có GUI cho cả **Server** và **Client**, chạy được nhiều máy trong cùng mạng LAN.

## 1. Thành phần

- `server.ServerAdminGUI`: giao diện quản trị server.
  - Start/Stop UDP server.
  - Hiển thị LAN IP + port để các máy client kết nối.
  - Danh sách user online: username, role, IP, port, room, heartbeat.
  - Danh sách room.
  - Kick user.
  - Broadcast thông báo tới toàn bộ client.
  - Đóng room.
  - Log sự kiện.
- `client.SmartClassroomClient`: GUI chung cho Teacher / Student.
  - Login bằng Server LAN IP + port.
  - Teacher: tạo room, chat, quiz.
  - Student: join room, điểm danh, giơ tay, chat, làm quiz.
  - Heartbeat để server phát hiện máy mất kết nối.
- `webrtc/index.html`: giao diện camera / microphone WebRTC P2P.

## 2. Biên dịch

Tại thư mục project:

```powershell
mkdir out
javac -encoding UTF-8 -d out src/server/ServerAdminGUI.java src/client/SmartClassroomClient.java
```

## 3. Chạy server GUI

```powershell
java -cp out server.ServerAdminGUI
```

Bấm **Start Server**. Server hiển thị LAN IP, ví dụ `192.168.1.10`, port mặc định `5000`.

## 4. Chạy client GUI

Trên từng laptop/PC:

```powershell
java -cp out client.SmartClassroomClient
```

Nhập:

- Server LAN IP: IP hiển thị trên server, ví dụ `192.168.1.10`.
- UDP Port: `5000`.
- Username: mỗi máy một tên khác nhau.
- Role: `TEACHER` hoặc `STUDENT`.

**Không dùng `127.0.0.1` khi client nằm ở máy khác.**

## 5. Điều kiện để nhiều máy kết nối

1. Server và client phải ở cùng Wi-Fi/LAN cho demo đơn giản.
2. Dùng LAN IP của máy server.
3. Windows Firewall phải cho phép Java/OpenJDK hoặc UDP port 5000 trên máy server.
4. Không cần mở port trên router nếu tất cả máy ở cùng LAN.

Kiểm tra IP Windows:

```powershell
ipconfig
```

## 6. Demo admin server

- Mở server GUI và Start Server.
- Cho 1 Teacher + 2 Student từ các máy khác nhau đăng nhập.
- Server sẽ thấy từng IP và port UDP của client.
- Chọn một student -> **Kick Selected User**.
- Bấm **Broadcast Announcement** để gửi thông báo toàn hệ thống.
- Qua tab Rooms để theo dõi thành viên và attendance.

## 7. WebRTC

Mở `webrtc/index.html` trên hai máy bằng Chrome/Edge. Nhấn **Start Camera** rồi trao đổi Offer/Answer SDP. Đây là WebRTC P2P thật; phiên bản giữa kỳ dùng signaling thủ công để phần Java vẫn bám nội dung Socket/UDP đã học.

## WebRTC đã tích hợp signaling qua UDP (bản FIX)

Bản này không còn yêu cầu copy/paste SDP thủ công.

1. Giáo viên và học sinh phải ở cùng một phòng.
2. Người gọi bấm **Gọi Video WebRTC** và chọn người muốn gọi.
3. Trình duyệt mở trên `http://127.0.0.1:<cổng-ngẫu-nhiên>` để Camera/Microphone hoạt động ổn định trên localhost.
4. Người nhận sẽ thấy hộp thoại cuộc gọi đến, chọn **Yes/Có**; trình duyệt WebRTC của người nhận tự mở.
5. Hai bên bấm **Bật Camera / Microphone** và cấp quyền cho Chrome/Edge.
6. Bên gọi bấm **Bắt đầu cuộc gọi**. SDP Offer/Answer được chuyển tự động qua UDP Server; audio/video sau khi kết nối truyền P2P bằng WebRTC.

Lưu ý: bản giữa kỳ ưu tiên demo các máy trong cùng Wi-Fi/LAN. Signaling sử dụng UDP của project, còn luồng media sử dụng WebRTC P2P.

# BỔ SUNG: GIÁO VIÊN GỌI CẢ LỚP HỌC TRỰC TUYẾN

Bản này bổ sung nút **Mở lớp học trực tuyến** dành riêng cho GIÁO VIÊN.

## Cách hoạt động

1. Giáo viên tạo/vào phòng, ví dụ `LTM01`.
2. Học sinh vào cùng phòng.
3. Giáo viên bấm **Mở lớp học trực tuyến**.
4. UDP Server gửi lời mời tới toàn bộ HỌC SINH trong phòng.
5. Mỗi học sinh chọn **Có** để tham gia.
6. Trình duyệt WebRTC tự mở trên từng máy.
7. Giáo viên và học sinh bấm **Bật Camera / Microphone**.
8. Giáo viên bấm **Bắt đầu phát cho cả lớp**.
9. Giáo viên nhìn thấy camera của từng học sinh trong dạng lưới; mỗi học sinh nhìn thấy video của giáo viên.
10. Giáo viên bấm **Kết thúc / Rời buổi học** để đóng buổi học cho toàn bộ lớp.

## Kiến trúc

- UDP Client-Server: quản lý phòng, lời mời, accept/reject và WebRTC signaling.
- WebRTC: truyền camera/microphone trực tiếp giữa giáo viên và từng học sinh.
- Mô hình video dùng kiểu star/teacher-centered: Teacher <-> Student 1, Teacher <-> Student 2, ...
- Học sinh không truyền video trực tiếp cho các học sinh khác. Cách này phù hợp demo lớp học và giảm số kết nối so với full-mesh.

## Lưu ý demo

- Nên test 1 Server + 1 Teacher + 2-4 Student trước.
- Các máy nên cùng Wi-Fi/LAN cho demo giữa kỳ.
- Trình duyệt phải được cấp quyền Camera/Microphone.
- Nếu Windows Firewall hỏi quyền Java, chọn Allow access cho mạng Private.
- Bản build sử dụng Java 17 (`class major version 61`).

## 11. Gửi file trong phòng bằng UDP

- Trong Client có nút **Gửi file** ở cạnh nút gửi tin nhắn.
- Chỉ gửi khi người dùng đang ở trong một phòng.
- File được chia thành các chunk 8 KB, đánh số thứ tự và mã hóa Base64 URL-safe trước khi gửi qua UDP.
- Server nhận từng chunk, ACK lại cho người gửi và chuyển tiếp đến các thành viên khác trong cùng phòng.
- Client gửi tự động thử lại tối đa 4 lần nếu một chunk chưa được Server ACK.
- Nếu phía nhận thiếu chunk, Client gửi `FILE_MISSING` để yêu cầu Server phát lại các chunk bị thiếu.
- File nhận xong được lưu vào `Downloads/SmartClassroomReceived` (hoặc thư mục người dùng nếu không có Downloads).
- Giới hạn file của bản demo: **20 MB**.

Các command chính: `FILE_START`, `FILE_START_OK`, `FILE_CHUNK`, `FILE_ACK`, `FILE_END`, `FILE_MISSING`, `FILE_RECEIVED`.
