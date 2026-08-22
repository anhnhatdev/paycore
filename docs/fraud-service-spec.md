# SERVICE SPEC: fraud-service (PayCore)

Service kiểm tra rủi ro, được `transaction-service` gọi **đồng bộ** ở bước đầu tiên của Saga (xem STEP 1 ở `transaction-service-spec.md`), với timeout rất ngắn (2 giây). Vì vậy toàn bộ thiết kế ở đây bị chi phối bởi 1 ràng buộc cứng: **phải nhanh**, không được để bất kỳ rule nào phụ thuộc vào query DB nặng hay gọi service khác nữa.

## 1. Trách nhiệm

- Đánh giá 1 giao dịch dựa trên tập rule cấu hình được (không hardcode threshold trong code) → trả về quyết định `ALLOW` / `REJECT` / `REVIEW`
- Theo dõi tần suất giao dịch theo thời gian thực (velocity check) — dùng Redis, KHÔNG dùng DB, vì cần đọc/ghi counter ở độ trễ mili-giây
- Duy trì blacklist (account, device fingerprint, IP) có thể cập nhật từ tín hiệu bên ngoài (chargeback, khiếu nại, admin đánh dấu thủ công)
- Ghi log đầy đủ **lý do** của mỗi quyết định (reason codes) — không chỉ trả true/false, vì fintech cần giải trình được khi có tranh chấp hoặc khi cần audit quyết định
- Đưa giao dịch nghi ngờ (không rõ ràng đúng/sai) vào hàng đợi **review thủ công** thay vì tự ý reject cứng — tránh chặn oan giao dịch hợp lệ (false positive gây trải nghiệm tệ, cũng là rủi ro kinh doanh không kém gì bỏ lọt gian lận)

**Không thuộc phạm vi:** quyết định cuối cùng giao dịch có được thực hiện hay không (Transaction Service tôn trọng kết quả REJECT nhưng vẫn là bên orchestrate), ghi bút toán, gửi thông báo.

## 2. Ràng buộc thiết kế quan trọng nhất: ngân sách thời gian (latency budget)

`transaction-service` cho fraud-service tối đa **2 giây** (đã spec ở service gọi). Bên trong 2 giây đó, fraud-service tự chia ngân sách:

| Bước | Ngân sách | Nguồn dữ liệu |
|---|---|---|
| Rule tĩnh (amount limit, KYC status) | ~50ms | Cache trong memory (rule cấu hình, load lại mỗi 5 phút hoặc qua Kafka khi rule đổi) |
| Velocity check | ~100ms | Redis (INCR + TTL, không phải SQL) |
| Blacklist check | ~50ms | Redis Set / Bloom filter, không query DB trực tiếp trên đường hot path |
| Buffer dự phòng (network, GC pause...) | phần còn lại | — |

**Nguyên tắc:** nếu 1 bước nào đó tự nó vượt ngân sách riêng (VD Redis chậm bất thường) → cắt ngay bước đó, coi như "không xác định được" cho riêng bước đó, KHÔNG đợi hết 2 giây rồi mới timeout toàn bộ. Nếu tổng thời gian xử lý vượt ngưỡng an toàn nội bộ (VD 1.5 giây, để dư thời gian trả response về trước khi Transaction Service tự timeout ở mốc 2 giây) → trả về `REVIEW` thay vì cố xử lý tiếp, kèm reason code `INTERNAL_TIMEOUT_PARTIAL_CHECK`.

## 3. Database & Cache schema

### PostgreSQL (dữ liệu cấu hình, không nằm trên hot path)

```sql
CREATE TABLE fraud_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_code VARCHAR(50) UNIQUE NOT NULL,   -- MAX_AMOUNT_PER_TX | MAX_AMOUNT_PER_DAY | VELOCITY_PER_MINUTE | ...
    enabled BOOLEAN NOT NULL DEFAULT true,
    params JSONB NOT NULL,                   -- VD {"maxAmount": 50000000, "currency": "VND"}
    applies_to_kyc_status VARCHAR(20),        -- rule có thể khác nhau tùy KYC (VD user chưa verify bị giới hạn thấp hơn)
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE blacklist_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(20) NOT NULL,         -- ACCOUNT | DEVICE | IP
    entity_value VARCHAR(255) NOT NULL,
    reason VARCHAR(255),
    added_by VARCHAR(20) NOT NULL,            -- SYSTEM_AUTO | ADMIN_MANUAL
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP                       -- NULL = vĩnh viễn, có giá trị nếu là tạm khóa có thời hạn
);
CREATE UNIQUE INDEX idx_blacklist_lookup ON blacklist_entries(entity_type, entity_value) WHERE active = true;

-- Log MỌI quyết định, kể cả ALLOW — cần cho audit và để cải thiện rule sau này
CREATE TABLE fraud_check_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    decision VARCHAR(10) NOT NULL,            -- ALLOW | REJECT | REVIEW
    reason_codes TEXT[] NOT NULL,             -- có thể nhiều lý do cùng lúc, VD ['VELOCITY_EXCEEDED', 'NEW_ACCOUNT_HIGH_AMOUNT']
    rules_evaluated JSONB,                    -- snapshot input/output từng rule, phục vụ giải trình khi có khiếu nại
    latency_ms INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_fraud_logs_transaction_id ON fraud_check_logs(transaction_id);
```

**Đồng bộ blacklist/rule từ PostgreSQL vào bộ nhớ/Redis:** service load `fraud_rules` vào in-memory cache khi khởi động, refresh định kỳ (polling 5 phút) HOẶC lắng nghe Kafka topic `fraud.rules.updated` để refresh ngay khi admin thay đổi rule qua trang quản trị — khuyến nghị Kafka để tránh độ trễ 5 phút cho trường hợp khẩn (VD phát hiện gian lận hàng loạt, cần chặn ngay). `blacklist_entries` đồng bộ sang Redis Set tương tự.

### Redis (hot path, bắt buộc để đạt latency budget)

```
Key: velocity:{accountId}:{window}       -- VD velocity:uuid:1min, velocity:uuid:1hour
Value: counter (INCR mỗi lần có giao dịch), TTL = độ dài window

Key: blacklist:account / blacklist:device / blacklist:ip   -- Redis Set, đồng bộ từ blacklist_entries
Key: dedup:{transactionId}                -- đánh dấu đã xử lý transactionId này (chống double-count velocity khi bị gọi lại)
```

## 4. API chi tiết

### 4.1 POST /internal/v1/fraud/check

Gọi bởi `transaction-service` (mTLS), là API duy nhất trên hot path.

**Request:**
```json
{
  "transactionId": "uuid",
  "fromAccountId": "uuid",
  "toAccountId": "uuid",
  "amount": 500000.00,
  "currency": "VND",
  "kycStatus": "VERIFIED",
  "deviceFingerprint": "abc123",
  "ipAddress": "1.2.3.4"
}
```

**Business logic:**
1. **Chống double-count trước tiên:** check `dedup:{transactionId}` trong Redis
   - Đã tồn tại → đây là lần gọi lại (retry từ Transaction Service do timeout lần trước) → trả lại **quyết định đã lưu** từ `fraud_check_logs` (query nhanh theo `transaction_id`, có index), KHÔNG chạy lại velocity increment (tránh 1 giao dịch bị tính 2 lần vào counter, gây sai lệch dữ liệu velocity cho các giao dịch sau)
   - Chưa tồn tại → set `dedup:{transactionId}` với TTL ngắn (VD 5 phút, đủ dài hơn timeout+retry của caller), tiếp tục bước 2
2. Check blacklist (account/device/IP) — Redis Set lookup, O(1)
   - Match → `decision=REJECT`, `reason_codes=['BLACKLISTED_ACCOUNT']` (hoặc DEVICE/IP tương ứng), DỪNG NGAY, không cần chạy rule khác (fail fast cho case rõ ràng nhất)
3. Chạy rule tĩnh từ cache in-memory (amount limit theo KYC status, v.v.)
4. Chạy velocity check: `INCR velocity:{accountId}:1min`, so với threshold rule tương ứng; tương tự cho window 1hour, 1day
5. Tổng hợp toàn bộ rule đã chạy → tính `decision`:
   - Có rule "hard reject" nào match (blacklist, vượt hạn mức tuyệt đối) → `REJECT`
   - Có rule "soft warning" match nhưng không rule nào hard reject (VD velocity hơi cao nhưng chưa tới ngưỡng chặn cứng, hoặc giao dịch lớn bất thường so với lịch sử nhưng account KYC verified) → `REVIEW`
   - Không rule nào match → `ALLOW`
6. Insert `fraud_check_logs` (đầy đủ `reason_codes`, `rules_evaluated`, `latency_ms` đo thực tế)
7. Trả response

**Response 200:**
```json
{ "decision": "ALLOW", "reasonCodes": [], "checkId": "uuid" }
```
hoặc
```json
{ "decision": "REJECT", "reasonCodes": ["VELOCITY_EXCEEDED_1MIN", "AMOUNT_ABOVE_UNVERIFIED_LIMIT"], "checkId": "uuid" }
```

**Về `REVIEW`:** Transaction Service theo spec hiện tại coi `REVIEW` như thế nào cần thống nhất rõ — khuyến nghị: ở giai đoạn Core, xử lý `REVIEW` giống `REJECT` tạm thời (an toàn, fail-closed) nhưng gắn `failure_reason=PENDING_MANUAL_REVIEW` thay vì `FRAUD_REJECTED`, đồng thời giao dịch được đẩy vào hàng đợi review (mục 4.2) để admin duyệt tay — nếu admin approve, có thể tạo lại giao dịch mới (không tự động resume giao dịch cũ, vì đã ở trạng thái FAILED và có thể đã trôi qua thời gian).

---

### 4.2 GET /internal/v1/fraud/review-queue, POST /internal/v1/fraud/review-queue/{checkId}/decide

Dành cho `ADMIN` (qua trang quản trị nội bộ, không phải API public cho end-user):
- GET: danh sách giao dịch đang `REVIEW`, kèm toàn bộ `rules_evaluated` để admin xem lý do
- POST: admin quyết định `APPROVE`/`REJECT` thủ công → publish event để các service liên quan biết (VD nếu approve, có thể tự động tạo blacklist exception hoặc ghi nhận false-positive để sau này tinh chỉnh rule)

---

### 4.3 POST /internal/v1/fraud/blacklist

`ADMIN` only, thêm/gỡ 1 entry blacklist thủ công (VD sau khi có báo cáo lừa đảo từ user khác). Ghi vào `blacklist_entries` + đồng bộ ngay vào Redis Set (không đợi job polling).

## 5. Nguồn tự động thêm blacklist (ngoài admin thủ công)

Ở giai đoạn mở rộng, các service khác có thể publish event khiến fraud-service tự thêm blacklist, VD:
- `transaction-service` phát hiện 1 account có tỷ lệ giao dịch bị `COMPENSATED` bất thường cao
- `payment-gateway-service` nhận webhook chargeback từ provider

Ở Core, chưa cần implement tự động — chỉ cần thiết kế `added_by=SYSTEM_AUTO` sẵn trong schema để không phải migrate lại sau.

## 6. Test case bắt buộc

- Account nằm trong blacklist → verify `REJECT` ngay, `latency_ms` rất thấp (< 50ms, vì fail fast không cần chạy rule khác)
- Gửi 10 giao dịch liên tiếp trong 1 phút từ cùng account, vượt ngưỡng `VELOCITY_PER_MINUTE` → verify giao dịch thứ vượt ngưỡng bị REJECT/REVIEW đúng theo rule, các giao dịch trước đó không bị ảnh hưởng
- **Test chống double-count (quan trọng nhất):** gọi `POST /fraud/check` 2 lần với cùng `transactionId` (mô phỏng Transaction Service retry do timeout) → verify velocity counter chỉ tăng 1 lần, response lần 2 giống hệt lần 1
- User `kycStatus=PENDING` gửi giao dịch vượt hạn mức dành cho tài khoản chưa xác minh → verify REJECT với reason code đúng, dù account đó không nằm trong blacklist
- Test latency budget: giả lập Redis chậm bất thường (mock delay) → verify service tự cắt sớm, trả `REVIEW` với `INTERNAL_TIMEOUT_PARTIAL_CHECK` thay vì treo tới hết 2 giây của caller
- Test đồng bộ rule: thay đổi 1 `fraud_rules` qua DB/API quản trị → verify service áp dụng rule mới trong vòng thời gian refresh cấu hình (polling hoặc Kafka), không cần restart service
- Admin approve 1 giao dịch đang REVIEW → verify log ghi nhận đúng người quyết định và thời điểm, phục vụ audit trail

---

Lưu ý triển khai: nên bắt đầu với rule đơn giản nhất (amount limit tĩnh) chạy được end-to-end trước, sau đó mới thêm velocity (cần setup Redis đúng cách với TTL theo từng window), cuối cùng mới đến blacklist và review queue — đừng cố làm tất cả rule cùng lúc vì phần khó thật sự nằm ở việc giữ đúng ngân sách latency, không phải ở độ phức tạp của rule.
