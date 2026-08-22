# SERVICE SPEC: reconciliation-service (PayCore)

Service đối soát — chạy độc lập, phần lớn là **batch job**, không nằm trên đường xử lý giao dịch real-time. Vai trò: phát hiện lệch số liệu ở bất kỳ đâu trong hệ thống, KHÔNG tự động sửa tiền. Đây là nguyên tắc quan trọng nhất của toàn bộ spec này — nói kỹ ở mục 1.

## 1. Nguyên tắc cốt lõi: chỉ phát hiện và cảnh báo, KHÔNG tự động "sửa" tiền

Khác với `payment-gateway-service` (được phép tự xử lý khi reconcile phát hiện 1 giao dịch PENDING đã thật sự thành công phía provider — vì đó là bù đắp 1 THIẾU SÓT đã biết rõ nguyên nhân, theo đúng luồng nghiệp vụ đã định nghĩa), `reconciliation-service` đối diện với **lệch số liệu không rõ nguyên nhân** — có thể do bug, do race condition hiếm gặp, do gian lận. Tự động "sửa" trong tình huống không rõ nguyên nhân là rủi ro lớn hơn bản thân cái lệch: có thể che giấu bug, hoặc tệ hơn là tự động chuyển tiền sai thêm 1 lần nữa.

**Vì vậy: mọi discrepancy được phát hiện đều dừng ở mức ghi nhận + cảnh báo cho con người xử lý, không có endpoint nào trong service này được phép ghi vào `ledger_entries` hay thay đổi `balances`.**

## 2. Trách nhiệm

- **Đối soát nội bộ (internal reconciliation):** kiểm tra tính nhất quán trong chính hệ thống PayCore
  - Per-account: tổng `ledger_entries` của 1 account có khớp với `balances.available_balance` không (gọi lại endpoint đã có sẵn ở Ledger Service, không tự tính lại logic)
  - Toàn hệ thống (global invariant): tổng tất cả bút toán DEBIT phải bằng tổng tất cả bút toán CREDIT trên toàn bộ `ledger_entries` — đây là bất biến toán học của double-entry, lệch dù 1 đồng cũng là dấu hiệu cực kỳ nghiêm trọng (bug ghi ledger, không phải chuyện nghiệp vụ thông thường)
  - Cross-service: mọi `transactions` ở `transaction-service` có `status=COMPLETED` phải có đúng cặp bút toán tương ứng tồn tại ở Ledger; ngược lại mọi cặp bút toán ở Ledger phải trace được về đúng 1 `transaction_id` hợp lệ
- **Đối soát ngoài (external reconciliation):** so khớp `gateway_transactions` (nội bộ) với file sao kê/settlement report chính thức từ provider (VNPay/Momo/Stripe) — đây là nguồn xác thực cao hơn cả webhook, vì webhook có thể bị giả mạo hoặc lỗi, còn settlement file là báo cáo chính thức cuối kỳ
- Phân loại mức độ nghiêm trọng (severity) của từng discrepancy, publish cảnh báo cho vận hành qua kênh phù hợp mức độ

**Không thuộc phạm vi:** sửa dữ liệu, quyết định nghiệp vụ, gửi thông báo cho end-user (đó là notification-service, và chỉ khi có quyết định rõ ràng từ con người).

## 3. Database schema chi tiết

```sql
CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_type VARCHAR(30) NOT NULL,            -- INTERNAL_PER_ACCOUNT | INTERNAL_GLOBAL_INVARIANT | CROSS_SERVICE | EXTERNAL_GATEWAY
    period_start TIMESTAMP NOT NULL,
    period_end TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING', -- RUNNING | COMPLETED | FAILED
    total_checked INT,
    total_discrepancies INT,
    started_at TIMESTAMP NOT NULL DEFAULT now(),
    completed_at TIMESTAMP
);

CREATE TABLE discrepancies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_run_id UUID NOT NULL REFERENCES reconciliation_runs(id),
    discrepancy_type VARCHAR(50) NOT NULL,    -- BALANCE_MISMATCH | GLOBAL_INVARIANT_VIOLATION |
                                                -- ORPHAN_LEDGER_ENTRY | MISSING_LEDGER_ENTRY |
                                                -- GATEWAY_AMOUNT_MISMATCH | GATEWAY_MISSING_INTERNAL_RECORD
    severity VARCHAR(10) NOT NULL,            -- LOW | MEDIUM | HIGH | CRITICAL
    entity_reference VARCHAR(255) NOT NULL,   -- accountId, transactionId, hoặc providerTransactionRef tùy loại
    expected_value JSONB,
    actual_value JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN', -- OPEN | ACKNOWLEDGED | RESOLVED | FALSE_POSITIVE
    resolved_by VARCHAR(255),                  -- admin xử lý, ghi tay qua API mục 5.3
    resolution_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    resolved_at TIMESTAMP
);
CREATE INDEX idx_discrepancies_status ON discrepancies(status, severity);
CREATE INDEX idx_discrepancies_run ON discrepancies(reconciliation_run_id);

-- Lưu file settlement report gốc từ provider để tra soát/audit, tương tự nguyên tắc
-- lưu raw_payload ở payment-gateway-service
CREATE TABLE settlement_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(20) NOT NULL,
    report_date DATE NOT NULL,
    raw_file_reference TEXT NOT NULL,          -- đường dẫn lưu trữ file gốc (object storage), KHÔNG lưu nguyên file vào DB
    row_count INT,
    downloaded_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (provider, report_date)
);
```

**Mức độ nghiêm trọng (severity) — quyết định kênh cảnh báo, không phải hành động tự động:**

| Severity | Ví dụ | Kênh cảnh báo |
|---|---|---|
| LOW | Lệch 1 giao dịch nhỏ do webhook trễ vài giờ, đã tự khớp lại sau lần chạy tiếp theo | Log nội bộ, xem qua dashboard khi cần |
| MEDIUM | 1 account có `BALANCE_MISMATCH` nhỏ, không lặp lại ở lần chạy sau | Ghi vào queue, admin xem theo ca trực thường |
| HIGH | `MISSING_LEDGER_ENTRY` — có transaction COMPLETED nhưng không tìm thấy bút toán tương ứng | Alert ngay (email/Slack ops), cần xử lý trong ngày |
| CRITICAL | `GLOBAL_INVARIANT_VIOLATION` — tổng DEBIT ≠ tổng CREDIT toàn hệ thống | Alert ngay lập tức, mọi kênh (page-on-call nếu có), đây là dấu hiệu bug nghiêm trọng ở Ledger Service |

## 4. Các loại đối soát chi tiết

### 4.1 INTERNAL_PER_ACCOUNT — chạy thường xuyên (VD mỗi giờ)

Với từng account có hoạt động trong kỳ: gọi `GET /internal/v1/ledger/reconcile/{accountId}` (endpoint đã có sẵn ở Ledger Service — reconciliation-service KHÔNG tự tính lại logic double-entry, chỉ gọi lại và so sánh kết quả trả về). Nếu Ledger Service báo lệch → ghi `discrepancy_type=BALANCE_MISMATCH`.

### 4.2 INTERNAL_GLOBAL_INVARIANT — chạy ít thường xuyên hơn nhưng đều đặn (VD mỗi 6 giờ), tốn tài nguyên hơn vì quét toàn bộ

```sql
SELECT
  SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) AS total_debit,
  SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END) AS total_credit
FROM ledger_entries
WHERE created_at BETWEEN :period_start AND :period_end;
```
(Đây là query chạy trên **read replica** của `ledger_db`, không bao giờ query trực tiếp vào primary DB đang phục vụ giao dịch real-time — tránh reconciliation-service làm chậm đường ghi bút toán chính). `total_debit` phải bằng `total_credit` tuyệt đối — lệch dù 1 đồng → `severity=CRITICAL`.

### 4.3 CROSS_SERVICE — đối chiếu Transaction Service ↔ Ledger Service

1. Lấy danh sách `transactions` có `status=COMPLETED` trong kỳ (từ read replica của `transaction_db`)
2. Với mỗi transaction, verify tồn tại đúng cặp `ledger_entries` có `transaction_id` tương ứng (qua endpoint 3.5 đã spec ở Ledger Service)
3. Không tìm thấy → `discrepancy_type=MISSING_LEDGER_ENTRY`, `severity=HIGH`
4. Ngược lại, quét `ledger_entries` không map được về `transaction_id` hợp lệ nào (query chéo qua `transaction_id`) → `discrepancy_type=ORPHAN_LEDGER_ENTRY`, `severity=HIGH`

### 4.4 EXTERNAL_GATEWAY — đối soát với provider, chạy cuối ngày (settlement file thường chỉ có sau T+1)

1. Tải settlement report từ provider (SFTP/API tùy provider), lưu vào `settlement_reports` + object storage
2. Với mỗi dòng trong report: tìm `gateway_transactions` tương ứng qua `provider_transaction_ref`
   - Không tìm thấy record nội bộ nào khớp → `discrepancy_type=GATEWAY_MISSING_INTERNAL_RECORD`, `severity=HIGH` (tình huống nguy hiểm: có tiền thật di chuyển ở phía provider mà hệ thống không hề biết)
   - Tìm thấy nhưng số tiền lệch → `discrepancy_type=GATEWAY_AMOUNT_MISMATCH`, `severity=CRITICAL`
   - Tìm thấy và khớp hoàn toàn → không ghi discrepancy (đường happy path không tạo record, tránh bảng phình to vô ích — chỉ `reconciliation_runs.total_checked` tăng lên)
3. Ngược lại: quét `gateway_transactions.status=SUCCEEDED` trong kỳ không xuất hiện trong settlement report → cũng là `GATEWAY_MISSING_INTERNAL_RECORD` chiều ngược lại, severity HIGH (hệ thống nghĩ đã thành công nhưng provider không xác nhận trong báo cáo chính thức)

## 5. API

### 5.1 GET /internal/v1/reconciliation/runs, GET /internal/v1/reconciliation/discrepancies

Nội bộ/admin dashboard — xem lịch sử chạy và danh sách discrepancy, filter theo `status`/`severity`.

### 5.2 POST /internal/v1/reconciliation/trigger

`ADMIN` only, chạy 1 loại đối soát ngay lập tức ngoài lịch (VD sau khi vừa fix 1 bug, muốn verify lại ngay) — nhận `run_type` + khoảng thời gian.

### 5.3 POST /internal/v1/reconciliation/discrepancies/{id}/resolve

`ADMIN` only — **đây KHÔNG phải API sửa tiền**, chỉ là API ghi nhận "con người đã xem và xử lý xong ở đâu đó bên ngoài hệ thống này" (VD đã tự tay gọi Ledger Service ghi bút toán điều chỉnh qua quy trình riêng có kiểm soát, hoặc xác nhận đây là `FALSE_POSITIVE` do lỗi timing giữa các lần chạy). Request bắt buộc có `resolutionNote` giải thích, lưu lại `resolvedBy` để có audit trail đầy đủ về việc ai đã đóng discrepancy này và dựa trên căn cứ gì.

## 6. Idempotency & tần suất chạy

Khác các service trước, `reconciliation-service` không cần idempotency key kiểu request-response vì đây là job theo lịch, không phải API giao dịch — nhưng vẫn cần đảm bảo **chạy 2 lần cho cùng 1 khoảng thời gian (period) không tạo discrepancy trùng lặp vô nghĩa**: trước khi tạo discrepancy mới, check đã có `discrepancy` cùng `discrepancy_type` + `entity_reference` đang `OPEN` trong lần chạy gần nhất chưa — nếu có, chỉ update `reconciliation_run_id` tham chiếu lần chạy mới nhất phát hiện lại (không tạo row mới), tránh 1 vấn đề tồn đọng nhiều ngày sinh ra hàng chục discrepancy trùng ý nghĩa.

## 7. Test case bắt buộc

- Seed 1 account có `balances` lệch so với tổng `ledger_entries` thật (giả lập bug) → chạy `INTERNAL_PER_ACCOUNT` → verify phát hiện đúng, `severity` phù hợp
- Seed toàn hệ thống cân bằng đúng (tổng DEBIT = tổng CREDIT) → chạy `INTERNAL_GLOBAL_INVARIANT` → verify KHÔNG có discrepancy nào bị tạo sai (tránh false positive)
- Cố tình seed lệch 1 đồng ở tổng global → verify phát hiện, `severity=CRITICAL`, có publish alert
- Seed 1 `transaction.status=COMPLETED` nhưng xóa/không có `ledger_entries` tương ứng (giả lập bug đồng bộ) → verify `CROSS_SERVICE` phát hiện `MISSING_LEDGER_ENTRY`
- Test settlement file: seed report có 1 dòng giao dịch không khớp bất kỳ `gateway_transactions` nào → verify `GATEWAY_MISSING_INTERNAL_RECORD`
- Chạy cùng 1 loại đối soát 2 lần liên tiếp cho cùng period với data không đổi → verify KHÔNG tạo discrepancy trùng lặp (test mục 6)
- Gọi `POST /discrepancies/{id}/resolve` → verify `status=RESOLVED`, `resolvedBy`/`resolutionNote` được lưu đầy đủ, và **verify service này không hề gọi bất kỳ API ghi nào của Ledger Service** (test kiểm tra kiến trúc — dùng mock verify không có lời gọi POST tới `wallet-ledger-service` từ toàn bộ codebase của `reconciliation-service`)

---

Lưu ý triển khai: nên làm `INTERNAL_PER_ACCOUNT` và `INTERNAL_GLOBAL_INVARIANT` trước (chỉ phụ thuộc dữ liệu nội bộ, dễ test), để sẵn "lưới an toàn" phát hiện bug ở chính các service Core bạn đang xây — sau đó mới làm `EXTERNAL_GATEWAY` khi `payment-gateway-service` đã có đủ dữ liệu thật để đối soát.
