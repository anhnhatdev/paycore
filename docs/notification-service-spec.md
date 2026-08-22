# SERVICE SPEC: notification-service (PayCore)

Service tiêu thụ event từ Kafka (do các service khác publish qua Outbox Pattern) và gửi thông báo cho user. Khác các service trước — đây là service đầu tiên trong hệ thống đóng vai trò **Kafka consumer** thuần túy thay vì bị gọi qua REST/mTLS, nên rủi ro chính không phải là consistency dữ liệu tài chính mà là **gửi trùng thông báo** hoặc **rò rỉ dữ liệu nhạy cảm qua kênh không an toàn** (email/SMS là kênh dễ bị lộ hơn API nội bộ).

## 1. Trách nhiệm

- Subscribe các Kafka topic liên quan: `transaction-events`, `gateway-payment-events`, `account-events` (AccountFrozen, v.v.)
- Map mỗi event type sang 1 template thông báo tương ứng, render nội dung, gửi qua kênh phù hợp (email/SMS/push)
- Đảm bảo **idempotent theo event** — Kafka là at-least-once delivery, cùng 1 event có thể được consumer nhận lại (rebalance, consumer restart, retry) → tuyệt đối không gửi email/SMS trùng cho user vì trải nghiệm tệ và với SMS còn tốn phí thật
- Tôn trọng **tùy chọn nhận thông báo của user** (opt-in/opt-out theo loại event, theo kênh)
- Retry có kiểm soát khi gửi thất bại (provider email/SMS down tạm thời), có dead-letter cho trường hợp gửi mãi không được
- Ghi lại lịch sử gửi (delivery log) phục vụ tra soát khi user khiếu nại "không nhận được thông báo"

**Không thuộc phạm vi:** quyết định giao dịch có hợp lệ hay không, quyết định nội dung nghiệp vụ (chỉ render lại dữ liệu đã có trong event, không tự suy luận thêm).

## 2. Database schema chi tiết

```sql
-- Bảng chống trùng — mỗi event Kafka có eventId duy nhất (sinh từ nơi publish, VD outbox_events.id bên service gốc)
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,               -- = outbox_events.id của service gốc, KHÔNG tự sinh mới
    event_type VARCHAR(50) NOT NULL,
    source_service VARCHAR(50) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT now()
);
-- Bảng này chỉ cần tồn tại lâu hơn thời gian Kafka có thể replay/rebalance thực tế (VD giữ 30 ngày rồi dọn),
-- không cần giữ vĩnh viễn — khác với ledger_entries là bất biến vĩnh viễn

CREATE TABLE notification_preferences (
    user_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,          -- TransactionCompleted, TransactionFailed, ...
    channel VARCHAR(10) NOT NULL,             -- EMAIL | SMS | PUSH
    enabled BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (user_id, event_type, channel)
);
-- Mặc định: nếu user chưa có row nào cho 1 (event_type, channel) → coi như enabled=true cho EMAIL
-- (kênh mặc định), nhưng RIÊNG các event bảo mật quan trọng (VD AccountFrozen, giao dịch bất thường
-- từ fraud-service REVIEW) KHÔNG cho phép user tắt — xem mục 4 "Non-optional notifications"

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,                   -- reference tới processed_events.event_id
    user_id UUID NOT NULL,
    channel VARCHAR(10) NOT NULL,
    template_code VARCHAR(50) NOT NULL,
    recipient_masked VARCHAR(255) NOT NULL,   -- VD "us**@gmail.com", "090***4567" — KHÔNG lưu recipient đầy đủ trong log thường
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | SENT | FAILED | DEAD_LETTER
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    sent_at TIMESTAMP
);
CREATE INDEX idx_notifications_user ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_retry ON notifications(status) WHERE status = 'FAILED';
```

**Vì sao `recipient_masked` chứ không lưu email/SĐT đầy đủ trong bảng log chính:** giảm bề mặt lộ dữ liệu cá nhân nếu DB này bị truy cập trái phép — địa chỉ email/SĐT thật chỉ lấy real-time từ `account-service` lúc gửi, không cache lâu dài dạng plaintext trong service này.

## 3. Luồng xử lý 1 event — thứ tự bắt buộc để đảm bảo idempotent

```
[1] Consumer nhận message từ Kafka topic (VD "TransactionCompleted", eventId=X)
[2] KIỂM TRA TRƯỚC KHI LÀM GÌ KHÁC: SELECT ... WHERE event_id = X trong processed_events
    → Đã tồn tại → bỏ qua hoàn toàn, commit offset, DỪNG (đây là lần nhận lại do Kafka
      at-least-once, KHÔNG gửi lại thông báo)
    → Chưa tồn tại → tiếp tục [3]
[3] INSERT processed_events (event_id=X) — làm NGAY, TRƯỚC khi gửi thông báo thật,
    trong CÙNG transaction với việc tạo record notifications ở status=PENDING
    (nguyên tắc: đánh dấu "đã nhận diện event" phải atomic với việc queue công việc gửi,
    không để khoảng hở giữa 2 bước này)
[4] Check notification_preferences: user có tắt kênh này cho loại event này không
    → Tắt (và không phải loại non-optional) → notifications.status vẫn tạo nhưng
      đánh dấu riêng SKIPPED_BY_PREFERENCE, không gửi thật, KHÔNG coi là lỗi
[5] Resolve thông tin liên hệ thật (gọi account-service lấy email/SĐT hiện tại —
    KHÔNG dùng dữ liệu cache cũ, vì user có thể đã đổi email)
[6] Render nội dung theo template_code + dữ liệu từ event payload
[7] Gọi provider gửi (email: SES/SendGrid, SMS: Twilio/eSMS...) — xem mục 5 retry
[8] Update notifications.status = SENT/FAILED, commit offset Kafka SAU KHI toàn bộ
    bước trên hoàn tất (không commit offset sớm rồi mới xử lý, tránh mất event nếu
    consumer crash giữa chừng — thà nhận lại và bị chặn ở bước [2] còn hơn mất event)
```

**Điểm mấu chốt của thiết kế idempotent này:** bước [2]+[3] phải nằm trước bước gửi thật và phải atomic với nhau — đây chính là lớp bảo vệ để dù Kafka gửi lại event N lần, thông báo thật vẫn chỉ gửi tối đa 1 lần (trừ trường hợp gửi ở bước [7] thành công nhưng service crash ngay trước khi update status ở bước [8] — xem giới hạn ở mục 6).

## 4. Non-optional notifications (không cho user tắt)

Một số loại event liên quan trực tiếp đến bảo mật tài khoản BẮT BUỘC gửi dù user có tắt notification hay không — vì đây là nghĩa vụ bảo vệ user, không phải marketing:
- `AccountFrozen` (tài khoản bị đóng băng — user cần biết ngay)
- `TransactionCompensated` (tiền bị hoàn do lỗi hệ thống — user cần biết)
- Kết quả `REVIEW` từ fraud-service khi có quyết định cuối (approve/reject thủ công liên quan giao dịch của họ)

Danh sách `event_type` thuộc nhóm này nên cấu hình (không hardcode rải rác trong code), và bỏ qua hoàn toàn bước check `notification_preferences` ở [4] cho các loại này.

## 5. Retry & Dead Letter

- Gửi thất bại (provider timeout/lỗi tạm thời) → retry với backoff (VD 3 lần: 5s, 30s, 2 phút), tăng `attempt_count` mỗi lần
- Sau khi hết số lần retry cấu hình → `status=DEAD_LETTER`, đẩy vào topic Kafka riêng `notification.dead-letter` để có thể xử lý thủ công/alert vận hành, KHÔNG lặp lại vô hạn (tránh nghẽn consumer cho các event khác)
- Retry chạy **bất đồng bộ, tách khỏi consumer chính** (VD scheduled job quét `notifications WHERE status='FAILED'`) — không giữ consumer Kafka block chờ retry, vì sẽ làm chậm toàn bộ luồng xử lý event khác trong cùng partition

## 6. Giới hạn thực tế cần biết trước (không giả vờ hệ thống hoàn hảo)

Có 1 khoảng hở lý thuyết ở bước [7]→[8]: nếu provider gửi thành công nhưng service crash trước khi ghi `status=SENT` và commit offset, khi restart consumer sẽ nhận lại event, bước [2] thấy `processed_events` đã có (do đã insert ở bước [3] trước đó) nên **sẽ KHÔNG gửi lại** — điều này đúng ý muốn (an toàn hơn gửi trùng), nhưng cái giá là: nếu bước [3] đã chạy mà bước [7] chưa kịp gửi thật (crash giữa [3] và [7]) thì thông báo đó **vĩnh viễn không được gửi** trừ khi có job quét bù. → cần thêm 1 job định kỳ quét `notifications WHERE status='PENDING' AND created_at < now() - interval '5 minutes'` để phát hiện và xử lý tiếp các bản ghi bị kẹt kiểu này — chấp nhận đánh đổi "có thể trễ vài phút" để đổi lấy "không bao giờ gửi trùng", vì với notification, trễ chấp nhận được nhưng gửi trùng (đặc biệt SMS) thì không.

## 7. API (giới hạn, phần lớn service này hoạt động qua consumer chứ không qua API)

### 7.1 GET /api/v1/notifications/preferences, PUT /api/v1/notifications/preferences

Client-facing (qua Gateway, JWT), cho user tự cấu hình muốn nhận loại thông báo nào qua kênh nào. PUT chặn không cho tắt các `event_type` thuộc nhóm non-optional (mục 4) — trả 400 nếu cố tắt.

### 7.2 GET /internal/v1/notifications/history/{userId}

Nội bộ, cho `audit-service` hoặc admin tra soát khi user khiếu nại không nhận được thông báo — trả danh sách `notifications` kèm `status`, `attempt_count`, `last_error`.

## 8. Test case bắt buộc

- Publish cùng 1 event 2 lần (mô phỏng Kafka redeliver) → verify chỉ gửi email/SMS thật 1 lần
- User tắt kênh EMAIL cho `TransactionCompleted` → verify không gửi, nhưng record `notifications` vẫn có với trạng thái SKIPPED_BY_PREFERENCE (không phải lỗi)
- Event `AccountFrozen` dù user đã tắt tất cả notification → verify vẫn gửi (non-optional)
- Provider gửi email lỗi 2 lần đầu, thành công lần 3 → verify đúng số lần retry, backoff đúng khoảng thời gian cấu hình
- Provider gửi lỗi liên tục vượt quá số lần retry → verify chuyển `DEAD_LETTER`, có message vào topic dead-letter tương ứng
- Test job quét PENDING kẹt: giả lập record `notifications.status=PENDING` quá 5 phút (mô phỏng crash giữa bước 3 và 7) → verify job quét phát hiện và xử lý gửi bù đúng 1 lần, không gửi trùng nếu vô tình chạy job 2 lần cùng lúc (cần lock/`SELECT FOR UPDATE SKIP LOCKED` khi lấy batch xử lý)
- Kiểm tra log: đảm bảo không có chỗ nào log ra email/SĐT đầy đủ dạng plaintext ngoài lúc gọi provider gửi thật (chỉ `recipient_masked` xuất hiện trong log/DB)

---

Lưu ý triển khai: nên implement trước cho 1 kênh (EMAIL, dễ test hơn SMS vì không tốn phí thật khi dev) và 1 loại event (`TransactionCompleted`) chạy đúng luồng idempotent end-to-end, sau đó mới nhân rộng ra các event/kênh khác — phần khó nằm ở đúng thứ tự [2]→[3]→...→[8], không nằm ở việc tích hợp thêm provider.
