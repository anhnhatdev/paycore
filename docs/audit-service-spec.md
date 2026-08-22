# SERVICE SPEC: audit-service (PayCore)

Service lưu trữ **toàn bộ lịch sử sự kiện quan trọng** xảy ra trong hệ thống, dưới dạng bất biến và có khả năng **chứng minh không bị chỉnh sửa** (tamper-evident). Khác `wallet-ledger-service` (bất biến để đảm bảo đúng số dư) và `reconciliation-service` (phát hiện lệch số liệu), `audit-service` phục vụ mục đích **giải trình và tuân thủ** — trả lời được câu hỏi "chuyện gì đã xảy ra, khi nào, do ai/hệ thống nào quyết định" cho bất kỳ giao dịch hay hành động nào, kể cả nhiều năm sau.

## 1. Trách nhiệm

- Tiêu thụ event từ **mọi service khác** trong hệ thống (không chỉ giao dịch tiền — cả đăng nhập, đổi quyền, quyết định fraud, thay đổi rule, admin resolve discrepancy...) qua Kafka
- Lưu trữ append-only, **không có API nào trong service này được phép UPDATE hay DELETE** một audit record đã ghi
- Đảm bảo **tính toàn vẹn có thể kiểm chứng** (tamper-evidence) — nếu ai đó (kể cả admin có quyền truy cập DB trực tiếp) sửa 1 record, phải có cách phát hiện được
- Cung cấp API tra cứu phục vụ điều tra/tranh chấp/tuân thủ pháp lý, có kiểm soát quyền truy cập chặt và **tự audit luôn cả việc ai đã tra cứu audit log** (meta-audit)
- Quản lý retention theo yêu cầu tuân thủ (không được xóa sớm hơn thời hạn quy định, dù vậy vẫn cần chiến lược lưu trữ để không phình vô hạn)

**Không thuộc phạm vi:** phát hiện lệch số liệu tài chính (đó là reconciliation-service — 2 service phục vụ mục đích khác nhau dù cùng đọc event: reconciliation quan tâm "số có khớp không", audit quan tâm "ai/cái gì đã làm gì, có chứng minh được không"), gửi thông báo, ra quyết định nghiệp vụ.

## 2. Database schema chi tiết

```sql
-- Bảng chính — append-only tuyệt đối, KHÔNG có cột updated_at vì không bao giờ update
CREATE TABLE audit_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_number BIGSERIAL NOT NULL,       -- tăng dần tuyệt đối, dùng cho hash chain (mục 3)
    event_id UUID NOT NULL,                    -- = outbox_events.id của service gốc
    source_service VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,           -- USER | ADMIN | SYSTEM
    actor_id VARCHAR(255),                     -- userId hoặc adminId, NULL nếu actor_type=SYSTEM
    entity_type VARCHAR(50),                   -- TRANSACTION | ACCOUNT | LEDGER_ENTRY | FRAUD_RULE | ...
    entity_id VARCHAR(255),                    -- id của đối tượng bị tác động, để query nhanh theo entity
    payload JSONB NOT NULL,                    -- toàn bộ dữ liệu sự kiện, ĐÃ redact các trường nhạy cảm (mục 4)
    record_hash VARCHAR(64) NOT NULL,           -- SHA-256 của (record hiện tại + prev_hash) — xem mục 3
    prev_hash VARCHAR(64) NOT NULL,             -- hash của record ngay trước đó theo sequence_number
    occurred_at TIMESTAMP NOT NULL,             -- thời điểm sự kiện xảy ra ở service gốc (khác created_at)
    recorded_at TIMESTAMP NOT NULL DEFAULT now() -- thời điểm audit-service ghi nhận
) PARTITION BY RANGE (recorded_at);             -- partition theo tháng, xem mục 6 (Retention)

CREATE INDEX idx_audit_entity ON audit_records(entity_type, entity_id, occurred_at);
CREATE INDEX idx_audit_actor ON audit_records(actor_id, occurred_at);
CREATE INDEX idx_audit_event_type ON audit_records(event_type, occurred_at);
CREATE UNIQUE INDEX idx_audit_event_id ON audit_records(event_id);  -- chống xử lý trùng event

-- Chống trùng theo mẫu đã chuẩn hóa ở notification-service
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Meta-audit: tự ghi lại AI đã TRA CỨU audit log — bắt buộc với hệ thống tuân thủ nghiêm túc
CREATE TABLE audit_access_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    accessed_by VARCHAR(255) NOT NULL,          -- adminId thực hiện query
    query_params JSONB NOT NULL,                -- filter đã dùng (entityId, time range...)
    result_count INT,
    accessed_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Checkpoint hash định kỳ, publish ra kênh bên ngoài (mục 3) để làm bằng chứng độc lập
CREATE TABLE hash_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    up_to_sequence_number BIGINT NOT NULL,
    checkpoint_hash VARCHAR(64) NOT NULL,
    published_reference TEXT,                    -- VD nơi đã công bố hash này (xem mục 3)
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
```

## 3. Cơ chế tamper-evidence (hash chaining) — phần đặc thù nhất của service này

Mỗi record khi ghi tính `record_hash = SHA256(prev_hash + event_id + payload + occurred_at + sequence_number)`, trong đó `prev_hash` là `record_hash` của record ngay trước nó (theo `sequence_number`). Kết quả là 1 chuỗi hash liên kết — giống nguyên lý blockchain đơn giản, không cần blockchain thật:

- Nếu ai đó sửa payload của 1 record cũ → `record_hash` của chính nó không còn khớp khi tính lại → phát hiện được
- Sửa xong tính lại hash cho đúng record đó thì **toàn bộ chuỗi phía sau cũng phải tính lại theo** (vì mỗi record sau đều phụ thuộc `prev_hash`) → càng sửa record càng cũ, càng phải tính lại nhiều, càng dễ bị phát hiện qua checkpoint

**Checkpoint định kỳ (VD cuối mỗi ngày):** tính 1 hash tổng hợp tới `sequence_number` hiện tại, **công bố ra ngoài hệ thống PayCore** (VD publish lên 1 kênh không thể sửa được từ trong hệ thống — ghi vào file ký số lưu ngoài, gửi email nội bộ tới nhóm compliance, hoặc ở mức nâng cao hơn là anchor lên 1 dịch vụ timestamp/blockchain công khai). Mục đích: nếu toàn bộ DB `audit_records` bị compromise và kẻ tấn công cố sửa lại + tính lại hash chain cho khớp, họ vẫn không thể sửa được checkpoint đã công bố ra ngoài trước đó — so sánh lại là phát hiện được có sự can thiệp.

**Lưu ý về giới hạn thực tế:** cơ chế này chống được việc sửa âm thầm không để lại dấu vết trong chính hệ thống — không chống được người có quyền root DB xóa sạch rồi dựng lại từ đầu một chuỗi hash "giả nhưng tự nhất quán". Vì vậy checkpoint công bố RA NGOÀI hệ thống là bắt buộc, không phải tùy chọn, để có bằng chứng độc lập nằm ngoài tầm kiểm soát của chính hệ thống PayCore.

## 4. Redaction — chống việc audit log vô tình trở thành nơi lộ dữ liệu nhạy cảm

Vì audit-service tiêu thụ event từ MỌI service, có rủi ro 1 service khác lỡ đưa dữ liệu nhạy cảm vào payload event (VD `payment-gateway-service` lỡ để lọt số thẻ vào `raw_payload` rồi 1 phần bị forward qua event). Trước khi lưu vào `payload`:
1. Áp dụng danh sách field bị cấm tuyệt đối (`cardNumber`, `cvv`, `password`, `passwordHash`, `otpCode`...) — nếu payload chứa field trùng tên (kể cả lồng nhau) → tự động mask thành `"[REDACTED]"` trước khi ghi, KHÔNG tin tưởng mù quáng rằng service gốc đã làm sạch đúng
2. Ghi log riêng (không phải audit_records) khi redaction thực sự xảy ra, kèm `source_service` + `event_type` → để báo động cho team phát triển service gốc đang làm rò rỉ, cần fix tại nguồn chứ không chỉ dựa vào lớp chặn cuối này

## 5. API

### 5.1 GET /internal/v1/audit/records — điều tra/tra soát

Chỉ role `ADMIN` hoặc `COMPLIANCE` (role riêng, tách khỏi ADMIN thường vì không phải admin vận hành nào cũng cần xem toàn bộ audit trail nhạy cảm), qua Gateway với JWT + có thể yêu cầu thêm xác thực bậc 2 (bước ngoài phạm vi Core, ghi chú để làm sau).

**Query params:** `entityType`, `entityId`, `actorId`, `eventType`, `from`, `to`, `page`

**Bắt buộc:** mọi lệnh gọi endpoint này đều tự động insert 1 row `audit_access_logs` — không có ngoại lệ, kể cả query rỗng không ra kết quả.

### 5.2 GET /internal/v1/audit/verify-chain?fromSeq=&toSeq=

Chạy lại việc tính hash cho 1 khoảng `sequence_number`, so sánh với `record_hash` đã lưu và với `hash_checkpoints` gần nhất — trả về `valid: true/false` kèm vị trí đầu tiên phát hiện sai lệch (nếu có). Dùng định kỳ (tự động, VD hàng tuần) hoặc khi có nghi ngờ cụ thể.

### 5.3 GET /internal/v1/audit/access-logs

Cho compliance team xem chính lịch sử ai đã tra cứu audit log — meta-audit, cũng chỉ đọc.

## 6. Retention & Volume — audit log tăng vô hạn nếu không có chiến lược

- **Partition theo tháng** (`PARTITION BY RANGE (recorded_at)`) — giúp query nhanh hơn (chỉ quét partition liên quan) và cho phép archival từng phần thay vì thao tác trên 1 bảng khổng lồ
- **KHÔNG được xóa** trong thời hạn tuân thủ tối thiểu (tùy quy định ngành tài chính tại thị trường vận hành, thường nhiều năm) — thay vào đó, sau 1 khoảng thời gian (VD 12 tháng), partition cũ được **archival** sang cold storage (object storage rẻ hơn, VD S3 Glacier-tương-đương), vẫn giữ được `record_hash`/`prev_hash` để verify chain khi cần, nhưng tách khỏi DB chính đang phục vụ query thường xuyên để giữ hiệu năng
- Việc archival PHẢI tự nó cũng được ghi vào `audit_records` (event_type=`AuditPartitionArchived`) — kể cả hành động vận hành lên chính audit log cũng phải để lại dấu vết

## 7. Test case bắt buộc

- Publish 1 event 2 lần (Kafka redeliver) → verify chỉ tạo 1 `audit_records` (nhờ `idx_audit_event_id` unique)
- Ghi 5 record liên tiếp → verify `prev_hash` của record N khớp đúng `record_hash` của record N-1
- Giả lập sửa trực tiếp `payload` của 1 record cũ trong DB (mô phỏng tấn công) → chạy `GET /verify-chain` → verify phát hiện đúng vị trí bị sửa
- Payload event chứa field `cardNumber` (giả lập lỗi từ service gốc) → verify bị redact thành `[REDACTED]` trước khi lưu, và có log cảnh báo riêng ghi nhận việc redaction xảy ra
- Gọi `GET /audit/records` bất kỳ → verify luôn có đúng 1 row mới trong `audit_access_logs` tương ứng
- User không có role COMPLIANCE/ADMIN gọi `GET /audit/records` → 403
- Test archival: chạy job archival cho 1 partition cũ → verify dữ liệu chuyển sang cold storage thành công, và có 1 `audit_records` mới ghi nhận chính sự kiện archival đó

---

Lưu ý triển khai: đây là service nên làm SAU CÙNG trong toàn bộ hệ thống (kể cả sau `reconciliation-service`), vì giá trị thật của nó chỉ rõ ràng khi đã có đủ nhiều loại event từ các service khác để tiêu thụ — làm audit-service đầu tiên với hệ thống chưa có gì để audit sẽ khó thấy được ý nghĩa của phần hash-chain, vốn là phần khó và đáng học nhất ở service này.
