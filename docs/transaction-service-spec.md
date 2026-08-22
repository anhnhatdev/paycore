# SERVICE SPEC: transaction-service (PayCore)

Service orchestrate luồng nghiệp vụ xuyên nhiều service (Saga choreography/orchestration hybrid). Là nơi duy nhất quyết định "1 giao dịch coi như thành công hay thất bại", nhưng KHÔNG tự giữ balance — mọi thay đổi số dư đều đi qua `wallet-ledger-service`.

## 1. Trách nhiệm

- Nhận request giao dịch từ client (qua Gateway): transfer, deposit, withdraw
- Điều phối Saga: gọi `fraud-service` kiểm tra rủi ro → gọi `wallet-ledger-service` ghi bút toán → nếu thành công thì hoàn tất, nếu thất bại giữa chừng thì compensate (gọi reversal)
- Đảm bảo idempotency ở tầng client-facing (khác với idempotency nội bộ của Ledger Service — xem mục 4 để phân biệt rõ 2 tầng)
- Là nguồn sự thật cho **trạng thái** của giao dịch (PENDING/PROCESSING/COMPLETED/FAILED/COMPENSATED), phục vụ client tra cứu lịch sử
- Publish event qua Outbox để `notification-service` gửi thông báo, `audit-service` ghi log

**Không thuộc phạm vi:** tính toán/lưu số dư thật (đó là Ledger Service), quyết định rule chống gian lận chi tiết (đó là Fraud Service — Transaction Service chỉ gọi và tôn trọng kết quả).

## 2. Database schema chi tiết

```sql
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_idempotency_key VARCHAR(255) UNIQUE NOT NULL,  -- key do CLIENT gửi lên qua header, khác với idempotency key nội bộ gọi Ledger
    from_account_id UUID,                     -- NULL nếu type=DEPOSIT (tiền từ ngoài vào)
    to_account_id UUID,                       -- NULL nếu type=WITHDRAW (tiền ra ngoài)
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    type VARCHAR(20) NOT NULL,                -- TRANSFER | DEPOSIT | WITHDRAW | REFUND
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
        -- PENDING → PROCESSING → COMPLETED
        --                     → FAILED (business reject, chưa động gì tới Ledger)
        --                     → COMPENSATING → COMPENSATED (đã rollback qua Ledger)
    failure_reason VARCHAR(100),              -- INSUFFICIENT_BALANCE | FRAUD_REJECTED | LEDGER_TIMEOUT | ...
    ledger_debit_entry_id UUID,               -- lưu lại để trace, phục vụ reversal khi cần compensate
    ledger_credit_entry_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_transactions_from_account ON transactions(from_account_id, created_at DESC);
CREATE INDEX idx_transactions_to_account ON transactions(to_account_id, created_at DESC);
-- client_idempotency_key đã UNIQUE nên tự có index

-- Nhật ký từng bước Saga — phục vụ debug/audit khi giao dịch fail giữa chừng,
-- và để phát hiện transaction bị "kẹt" (chạy job quét định kỳ)
CREATE TABLE saga_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    step_name VARCHAR(50) NOT NULL,           -- FRAUD_CHECK | LEDGER_DEBIT_CREDIT | LEDGER_REVERSAL | NOTIFY
    status VARCHAR(20) NOT NULL,              -- STARTED | SUCCESS | FAILED
    request_payload JSONB,
    response_payload JSONB,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_saga_logs_transaction_id ON saga_logs(transaction_id, created_at);

-- Idempotency ở tầng client-facing — khác mục đích với idempotency_keys bên Ledger Service
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    transaction_id UUID,                      -- trỏ tới transactions.id đã tạo, NULL nếu đang xử lý
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    response_snapshot JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP NOT NULL
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,               -- = transaction_id
    event_type VARCHAR(50) NOT NULL,          -- TransactionCompleted | TransactionFailed | TransactionCompensated
    payload JSONB NOT NULL,
    published BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_unpublished ON outbox_events(published) WHERE published = false;
```

## 3. Phân biệt 2 tầng idempotency — điểm dễ nhầm nhất khi implement

| | `transaction-service` | `wallet-ledger-service` |
|---|---|---|
| Key do ai tạo | **Client** gửi qua header `Idempotency-Key` | **Transaction Service** tự sinh, dẫn xuất từ `transaction.id` |
| Mục đích | Chống việc client bấm submit 2 lần / retry network ở tầng UI | Chống việc Transaction Service gọi lại Ledger do timeout/retry nội bộ |
| Phạm vi | 1 request nghiệp vụ hoàn chỉnh (transfer/deposit/withdraw) | 1 lệnh ghi bút toán cụ thể |

**Quy tắc sinh ledger idempotency key:** `{transactionId}:DEBIT_CREDIT` cho bước ghi chính, `{transactionId}:REVERSAL` cho bước compensate — deterministic, không phụ thuộc số lần retry, để dù Transaction Service tự crash và job quét phát hiện lại giao dịch PENDING thì vẫn gọi Ledger với đúng key cũ, không tạo bút toán trùng.

## 4. API chi tiết

### 4.1 POST /api/v1/transactions/transfer

Header bắt buộc: `Authorization: Bearer <JWT>`, `Idempotency-Key: <uuid>` (đã được `api-gateway` chặn nếu thiếu, nhưng service vẫn tự validate lại — defense in depth).

**Request:**
```json
{
  "toAccountNumber": "PC000000000123",
  "amount": 500000.00,
  "currency": "VND",
  "note": "Trả tiền ăn trưa"
}
```
Lưu ý: `fromAccountId` KHÔNG lấy từ body — luôn suy ra từ `X-User-Id` trong JWT (tránh trường hợp client giả mạo gửi tài khoản người khác làm nguồn).

**Response 202 Accepted:**
```json
{ "transactionId": "uuid", "status": "PROCESSING" }
```
Trả **202 + PROCESSING** ngay, không đợi Saga chạy xong hẳn mới trả response (transfer có thể mất vài trăm ms qua nhiều service) — client poll `GET /transactions/{id}` hoặc nhận thông báo qua Notification Service khi hoàn tất. Cân nhắc: nếu muốn UX đơn giản hơn (trả kết quả cuối luôn), có thể chạy đồng bộ với timeout ngắn (VD 3 giây) rồi mới fallback sang 202 — quyết định tùy độ phức tạp muốn implement ở giai đoạn Core, khuyến nghị bắt đầu với 202 async cho đúng bản chất Saga.

**Business logic:**
1. Validate DTO cơ bản (`amount > 0`, `toAccountNumber` đúng format)
2. Check `Idempotency-Key` ở bảng `idempotency_keys` nội bộ transaction-service (logic y hệt mục Pha 0 đã mô tả ở `wallet-ledger-service-spec.md`: COMPLETED/FAILED → trả lại snapshot; PROCESSING còn mới → 409; PROCESSING stale >30s → chiếm lại; hash khác → 422)
3. Resolve `toAccountNumber` → `toAccountId` (gọi `account-service` nội bộ)
4. Insert `transactions` (`status=PENDING`), insert `saga_logs` step khởi tạo
5. Trả 202 ngay, đẩy phần còn lại (bước 6 trở đi) vào **hàng đợi xử lý bất đồng bộ nội bộ** (VD Spring `@Async` + thread pool riêng, hoặc publish vào Kafka topic nội bộ `transaction.saga.start` rồi có consumer riêng xử lý — khuyến nghị dùng Kafka ngay từ Core để nhất quán với toàn hệ thống và để job quét/retry dễ dàng hơn nếu instance chết giữa chừng)
6. Saga bắt đầu chạy — xem mục 5

---

### 4.2 POST /api/v1/transactions/deposit, POST /api/v1/transactions/withdraw

Cấu trúc tương tự 4.1, khác ở validate:
- **Deposit** không cần `fromAccountId` (tiền từ payment gateway ngoài), sẽ gọi thêm `payment-gateway-service` (module mở rộng) trước khi tới bước ghi Ledger — ở giai đoạn Core, có thể giả lập bước này (mock luôn thành công) để tập trung học Saga/Ledger trước, đánh dấu rõ TODO khi build thật payment-gateway-service.
- **Withdraw** không cần `toAccountId`.

---

### 4.3 GET /api/v1/transactions/{id}

Trả chi tiết 1 giao dịch, chỉ owner (so `X-User-Id` với `from_account_id`/`to_account_id` tương ứng qua account-service) hoặc `ADMIN` mới xem được.

### 4.4 GET /api/v1/transactions?accountId=&status=&page=

Lịch sử giao dịch có phân trang, filter theo trạng thái. Chỉ trả giao dịch thuộc về user hiện tại (suy từ JWT), trừ khi role ADMIN.

## 5. Luồng Saga chi tiết — luồng Transfer (điều phối chính của service)

```
[STEP 0] Transaction status: PENDING
         saga_logs: step=INIT, status=SUCCESS

[STEP 1] Gọi Fraud Service (đồng bộ, timeout ngắn VD 2s)
         saga_logs: step=FRAUD_CHECK, status=STARTED
         → Fraud reject → status=FAILED, failure_reason=FRAUD_REJECTED
                          → publish outbox "TransactionFailed", DỪNG (chưa đụng Ledger, không cần compensate)
         → Fraud Service timeout/không phản hồi → coi là FAILED tạm thời,
           failure_reason=FRAUD_SERVICE_UNAVAILABLE, KHÔNG mặc định cho qua
           (fail-closed, không fail-open — nguyên tắc an toàn bắt buộc ở fintech:
           thà từ chối giao dịch hợp lệ còn hơn để lọt giao dịch gian lận vì service phụ bị down)
         → Fraud pass → tiếp STEP 2
         saga_logs: step=FRAUD_CHECK, status=SUCCESS

[STEP 2] Transaction status: PROCESSING
         Gọi POST /internal/v1/ledger/entries (mTLS), idempotencyKey = "{transactionId}:DEBIT_CREDIT"
         saga_logs: step=LEDGER_DEBIT_CREDIT, status=STARTED
         → Ledger trả 422 INSUFFICIENT_BALANCE → status=FAILED, failure_reason=INSUFFICIENT_BALANCE
                          → publish outbox "TransactionFailed", DỪNG (Ledger tự rollback nội bộ, không cần compensate)
         → Gọi Ledger timeout/network lỗi → RETRY tối đa 3 lần với backoff (idempotencyKey
           không đổi nên an toàn để gọi lại) → nếu vẫn lỗi sau 3 lần → status=FAILED,
           failure_reason=LEDGER_UNAVAILABLE, đẩy vào saga_logs để job quét xử lý thủ công/cảnh báo
           (đây là tình huống hiếm cần con người can thiệp — không tự ý coi là thành công hay compensate mù)
         → Ledger trả 200 COMPLETED → lưu ledger_debit_entry_id, ledger_credit_entry_id
         saga_logs: step=LEDGER_DEBIT_CREDIT, status=SUCCESS

[STEP 3] Transaction status: COMPLETED
         Publish outbox "TransactionCompleted" (payload gồm transactionId, amount, 2 account,
         timestamp) → notification-service lắng nghe gửi email, audit-service ghi log
         saga_logs: step=NOTIFY, status=SUCCESS (chỉ đánh dấu đã publish, không đợi notify thật xong)
```

**Vì sao KHÔNG có bước compensate ở luồng trên:** vì mọi lỗi (Fraud reject, Insufficient balance, Ledger unavailable) đều xảy ra TRƯỚC hoặc TRONG bước ghi Ledger, mà bước ghi Ledger tự đảm bảo atomicity 2 vế debit-credit (đã spec kỹ ở `wallet-ledger-service-spec.md`) — nên với thiết kế 1-lần-gọi-ghi-cả-cặp này, Transaction Service **hiếm khi cần tự compensate**. Compensate chỉ thật sự cần khi Saga có ≥ 2 bước ghi dữ liệu tách rời nhau (VD giai đoạn mở rộng: debit ví nguồn ở bước A, rồi mới gọi payment-gateway-service ở bước B để chuyển tiền ra ngân hàng ngoài — nếu B fail thì phải compensate A). Ở Core hiện tại, tình huống compensate thực tế chỉ xảy ra trong luồng mở rộng dưới đây.

## 6. Luồng cần compensate thật — ví dụ minh họa cho giai đoạn mở rộng (Withdraw ra ngân hàng ngoài)

```
STEP A: Ledger ghi DEBIT ví user → CREDIT system suspense account → SUCCESS
STEP B: Gọi payment-gateway-service để đẩy tiền ra ngân hàng thật → FAIL (gateway từ chối/timeout)
STEP C (COMPENSATE): status=COMPENSATING
        Gọi POST /internal/v1/ledger/entries/reversal, idempotencyKey = "{transactionId}:REVERSAL"
        → hoàn lại đúng số tiền vào ví user (bút toán mới, không sửa bút toán gốc)
        → status=COMPENSATED, publish outbox "TransactionCompensated"
```
Nguyên tắc: **STEP C luôn phải thành công** — đây là bước không được phép fail âm thầm. Nếu gọi reversal cũng lỗi (Ledger down đúng lúc đó), phải có cơ chế retry bền bỉ hơn (background job retry vô hạn có giới hạn thời gian, kèm cảnh báo/alert cho vận hành) — không được để trạng thái COMPENSATING treo mà không ai biết, vì lúc đó tiền của user đang "kẹt" (đã bị trừ nhưng chưa hoàn).

## 7. Job quét giao dịch kẹt (Stuck Transaction Reaper)

Chạy định kỳ (VD mỗi 1 phút), quét `transactions` có `status IN (PENDING, PROCESSING, COMPENSATING)` mà `updated_at` quá cũ (VD > 2 phút — ngưỡng phải lớn hơn nhiều timeout/retry nội bộ để không đá nhầm giao dịch đang xử lý bình thường):
- `PENDING`/`PROCESSING` quá hạn → khả năng instance xử lý đã crash giữa Saga → tiếp tục chạy lại Saga từ bước tương ứng (dựa vào `saga_logs` để biết đã làm tới đâu, và idempotency key deterministic đảm bảo gọi lại Ledger an toàn)
- `COMPENSATING` quá hạn → ưu tiên cao nhất, cảnh báo ngay cho vận hành (kênh riêng, không chỉ log) vì đây là tiền đang treo giữa chừng

## 8. Test case bắt buộc

- Submit transfer 2 lần liên tiếp với cùng `Idempotency-Key` → verify chỉ tạo 1 `transactions` record, response thứ 2 giống hệt response thứ 1
- Fraud Service trả reject → verify `status=FAILED`, KHÔNG có lời gọi nào tới Ledger Service (dùng mock verify)
- Fraud Service timeout → verify giao dịch bị từ chối (fail-closed), KHÔNG mặc định cho qua
- Ledger trả insufficient balance → verify `status=FAILED`, không compensate (vì chưa có gì để compensate)
- Giả lập Ledger timeout 2 lần đầu, thành công lần 3 → verify retry đúng 3 lần với cùng idempotencyKey, cuối cùng `status=COMPLETED`, và Ledger chỉ ghi bút toán đúng 1 lần (verify qua idempotency của Ledger, không phải nhờ may mắn)
- Test luồng compensate (mục 6): step B fail → verify reversal được gọi, balance user trở về đúng giá trị ban đầu, `status=COMPENSATED`
- Test Stuck Reaper: seed 1 transaction `status=PROCESSING` với `updated_at` giả cũ 5 phút → chạy job quét → verify giao dịch được resume đúng, không tạo bút toán trùng (nhờ idempotency key deterministic)
- Test phân quyền: user A gọi `GET /transactions/{id}` của giao dịch thuộc user B → verify 403

---

Service này là nơi phức tạp thứ 2 sau `wallet-ledger-service` vì phải xử lý đúng các tình huống lỗi giữa chừng — khi code, nên viết `saga_logs` đầy đủ ngay từ đầu (đừng để cuối) vì đây là công cụ debug chính khi Saga fail ở môi trường thật.
