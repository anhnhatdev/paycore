# SERVICE SPEC: wallet-ledger-service (PayCore)

**Service quan trọng nhất hệ thống.** Là nguồn sự thật duy nhất (single source of truth) cho số dư của mọi tài khoản. Mọi service khác — kể cả `transaction-service` — không được tự tính toán hay lưu balance riêng, luôn phải hỏi service này.

## 1. Trách nhiệm

- Ghi bút toán (ledger entry) theo nguyên tắc **double-entry accounting**: mỗi giao dịch tạo tối thiểu 2 bút toán (1 DEBIT, 1 CREDIT), tổng luôn = 0
- Tính và trả về balance chính xác tuyệt đối cho một account
- Đảm bảo **idempotency**: cùng 1 request ghi bút toán gửi lặp lại (do network retry, timeout...) không được ghi 2 lần
- Đảm bảo **atomicity** trong nội bộ service: 1 giao dịch ghi 2 bút toán DEBIT+CREDIT phải cùng thành công hoặc cùng thất bại, không bao giờ chỉ ghi được 1 vế
- Cung cấp bút toán đảo (reversal entry) phục vụ compensating transaction của Saga — KHÔNG BAO GIỜ cho phép sửa/xóa bút toán đã ghi

**Không thuộc phạm vi:** orchestrate luồng nghiệp vụ nhiều bước (transfer gồm debit + credit + notify) — đó là việc của `transaction-service`. Ledger Service chỉ làm đúng 1 việc: ghi bút toán chính xác khi được yêu cầu, và trả lời balance chính xác khi được hỏi.

**Ghi chú quan trọng — 3 khoảng trống bị bỏ sót ở bản trước đã fix trong bản này:**
1. Chưa xử lý luồng Deposit/Withdraw (chỉ có transfer giữa 2 account thật) → thêm khái niệm **system/suspense account**.
2. Idempotency có bug: ghi `FAILED` nhưng lại nằm trong transaction bị rollback toàn bộ → mất trạng thái, phá tính idempotent. → tách 2 pha transaction.
3. Thiếu Outbox event publish theo đúng nguyên tắc kiến trúc tổng của PayCore → thêm bước ghi `outbox_events`.

## 2. Database schema chi tiết

```sql
-- Bảng ghi bút toán — BẤT BIẾN, chỉ INSERT, không bao giờ UPDATE/DELETE
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,          -- reference logic tới transaction_service, KHÔNG FK cứng
    account_id UUID NOT NULL,               -- reference logic tới account-service
    entry_type VARCHAR(10) NOT NULL,        -- DEBIT | CREDIT
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),  -- LUÔN dương, chiều thể hiện qua entry_type
    currency VARCHAR(3) NOT NULL,
    balance_after NUMERIC(18,2) NOT NULL,   -- snapshot balance ngay sau bút toán này, phục vụ audit nhanh không cần tính lại
    reversal_of_entry_id UUID,              -- NULL nếu là bút toán gốc, có giá trị nếu đây là bút toán đảo
    created_at TIMESTAMP NOT NULL DEFAULT now()
    -- CỐ Ý không có updated_at — bảng này không được update
);

CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(account_id, created_at);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);

-- Bảng balance — cache/projection, có thể tính lại từ ledger_entries bất cứ lúc nào để đối soát
CREATE TABLE balances (
    account_id UUID PRIMARY KEY,
    available_balance NUMERIC(18,2) NOT NULL DEFAULT 0,
    pending_balance NUMERIC(18,2) NOT NULL DEFAULT 0,  -- tiền đang giữ chỗ (hold), chưa settle hẳn
    version BIGINT NOT NULL DEFAULT 0,       -- optimistic locking — bắt buộc, tránh race condition khi 2 giao dịch cùng update 1 account
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Idempotency store cho các API ghi bút toán
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,       -- hash của request body, để phát hiện trường hợp key trùng nhưng payload khác (lỗi client) → từ chối
    response_snapshot JSONB,                 -- lưu response trả về lần đầu, trả lại y hệt nếu gọi lại
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING', -- PROCESSING | COMPLETED | FAILED
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP NOT NULL             -- TTL, VD 24h, sau đó dọn bằng scheduled job
);

-- Outbox — bắt buộc theo nguyên tắc kiến trúc chung của PayCore (Outbox Pattern),
-- ghi cùng transaction với ledger_entries để không bao giờ mất event khi publish Kafka lỗi
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,              -- = transaction_id
    event_type VARCHAR(50) NOT NULL,         -- LedgerEntryCreated | LedgerEntryReversed | LedgerEntryRejected
    payload JSONB NOT NULL,
    published BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_unpublished ON outbox_events(published) WHERE published = false;

-- System/suspense account — đại diện cho "bên ngoài" (payment gateway) trong double-entry
-- khi Deposit/Withdraw, vì double-entry LUÔN cần 2 vế dù tiền đến từ ngoài hệ thống
CREATE TABLE system_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,        -- VD: 'SUSPENSE_VND', 'SUSPENSE_USD'
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(255)
    -- balance của system_account nằm chung bảng `balances`, account_id trỏ vào đây,
    -- KHÔNG cần validate available_balance >= amount cho account loại này (có thể âm,
    -- vì về bản chất đây là "nợ" hệ thống ghi nhận tiền đã ra/vào từ bên ngoài)
);
```

**Vì sao dùng `NUMERIC(18,2)` chứ không phải `float`/`double`:** sai số làm tròn của kiểu số thực nhị phân là điều tuyệt đối không chấp nhận được khi tính tiền — 0.1 + 0.2 không bằng 0.3 trong floating point. Toàn bộ entity Java tương ứng dùng `BigDecimal`.

**Vì sao có cột `version` (optimistic locking) ở bảng `balances`:** khi 2 giao dịch cùng lúc cố update balance của cùng 1 account (VD 2 lệnh chuyển tiền đến cùng 1 ví gần như đồng thời), nếu không khóa đúng cách sẽ xảy ra lost update (giao dịch sau ghi đè mất kết quả giao dịch trước). `@Version` của JPA sẽ tự động throw `OptimisticLockException` khi phát hiện conflict, buộc phải retry.

## 3. API chi tiết (TOÀN BỘ endpoint ở service này là INTERNAL — chỉ gọi qua mTLS service-to-service, KHÔNG BAO GIỜ expose qua API Gateway ra ngoài)

### 3.1 POST /internal/v1/ledger/entries

Ghi 1 cặp bút toán (double-entry) cho 1 giao dịch. Endpoint duy nhất và quan trọng nhất của service.

**Request:**
```json
{
  "transactionId": "uuid-của-transaction-service",
  "idempotencyKey": "uuid",
  "debitAccountId": "uuid-tài-khoản-nguồn",
  "creditAccountId": "uuid-tài-khoản-đích",
  "amount": 500000.00,
  "currency": "VND"
}
```

**Business logic — TÁCH 2 PHA TRANSACTION (điểm sửa quan trọng nhất so với bản trước):**

Lý do phải tách pha: nếu gộp chung 1 transaction, khi business validation fail (VD thiếu số dư) mà rollback toàn bộ, thì việc set `status=FAILED` cũng bị cuốn theo và biến mất — lần sau client retry với cùng idempotency key sẽ chạy lại từ đầu thay vì nhận lại kết quả FAILED đã có. Phải phân biệt rõ 2 loại lỗi:
- **Business failure** (insufficient balance, currency mismatch...) → là kết quả hợp lệ, PHẢI được ghi nhận và trả lại y hệt khi retry
- **Technical failure** (DB down, deadlock timeout...) → phải rollback sạch để client retry thực sự chạy lại được

**Pha 0 — transaction ngắn, riêng biệt (`Propagation.REQUIRES_NEW`):**
1. Check `idempotencyKey` trong bảng `idempotency_keys`:
   - Tồn tại, `status=COMPLETED` hoặc `status=FAILED` → trả lại nguyên `response_snapshot` đã lưu, dừng luôn, KHÔNG chạy Pha 1
   - Tồn tại, `status=PROCESSING`, và `updated_at` cách hiện tại < 30 giây → có request khác đang xử lý, trả 409 Conflict
   - Tồn tại, `status=PROCESSING`, nhưng `updated_at` đã quá 30 giây (process trước đó khả năng đã chết/crash giữa chừng) → coi là stale, cho phép chiếm lại và xử lý tiếp (update `updated_at`, tiếp tục Pha 1)
   - Tồn tại nhưng `request_hash` khác request hiện tại → trả 422 "Idempotency key reused with different payload"
   - Chưa tồn tại → insert row mới `status=PROCESSING`, commit ngay pha này, tiếp tục Pha 1

**Validate trước khi vào Pha 1 (fail fast, không cần mở transaction chính nếu sai):**
- `debitAccountId != creditAccountId` — nếu trùng, trả 400 "Cannot transfer to the same account"
- `amount > 0` — validate ở tầng DTO trước khi chạm DB, không đợi CHECK constraint throw exception khó đọc
- Currency của `debitAccountId` và `creditAccountId` (tra từ `balances`/cache) phải khớp nhau — nếu khác, trả 422 "Currency mismatch" (PayCore giai đoạn Core CHƯA hỗ trợ FX conversion, để dành module mở rộng)

**Pha 1 — transaction chính (`SERIALIZABLE` hoặc `REPEATABLE READ` + `SELECT FOR UPDATE`):**
2. Lock 2 row `balances` liên quan bằng `SELECT ... FOR UPDATE`, theo thứ tự `account_id` tăng dần (xem mục 4) — đây là cơ chế khóa chính, dùng pessimistic lock thay vì optimistic `@Version` cho đường ghi bút toán (xem giải thích ở mục 4 vì sao không dùng cả 2)
3. Kiểm tra `available_balance >= amount` (bỏ qua check này nếu `debitAccountId` là `system_accounts` — xem mục 3.1b Deposit/Withdraw)
   - Nếu không đủ → **rollback Pha 1**, sau đó mở 1 transaction ngắn khác (`REQUIRES_NEW`) chỉ để update `idempotency_keys.status=FAILED` + lưu `response_snapshot`, trả 422 "Insufficient balance"
4. Insert `ledger_entries` bút toán DEBIT cho `debitAccountId`, `balance_after` = balance mới sau khi trừ
5. Insert `ledger_entries` bút toán CREDIT cho `creditAccountId`, `balance_after` = balance mới sau khi cộng
6. Update `balances` cho cả 2 account (giảm/tăng `available_balance`)
7. Insert 1 row `outbox_events` (`event_type=LedgerEntryCreated`, payload gồm transactionId, 2 entryId, balance mới của cả 2 bên) — cùng transaction, đảm bảo event không bao giờ mất dù Kafka đang down lúc ghi
8. Update `idempotency_keys.status=COMPLETED`, lưu response vào `response_snapshot`
9. Commit Pha 1 — nếu bất kỳ bước 4-8 lỗi kỹ thuật (không phải business failure) → rollback toàn bộ Pha 1, `idempotency_keys` vẫn còn ở `PROCESSING` (do Pha 0 đã commit riêng), client retry sau sẽ tự chiếm lại theo cơ chế stale ở Pha 0

**Sau khi commit (ngoài transaction):** một scheduled process riêng (`OutboxPublisher`) poll bảng `outbox_events` where `published=false`, publish lên Kafka, set `published=true` — tách khỏi transaction chính để không làm chậm đường ghi bút toán.

**Response 200:**
```json
{
  "debitEntryId": "uuid",
  "creditEntryId": "uuid",
  "debitBalanceAfter": 1500000.00,
  "creditBalanceAfter": 2300000.00,
  "status": "COMPLETED"
}
```

**Response 422 (không đủ số dư):**
```json
{ "status": "FAILED", "reason": "INSUFFICIENT_BALANCE", "availableBalance": 200000.00 }
```

---

### 3.1b Deposit / Withdraw — dùng chung endpoint 3.1, khác ở cách chọn account

Double-entry luôn cần 2 vế, kể cả khi tiền đến từ bên ngoài (nạp qua payment gateway) hoặc đi ra ngoài (rút về ngân hàng). Giải pháp: dùng `system_accounts` (mục 2) làm vế còn lại, KHÔNG tạo API riêng — tái dùng `POST /internal/v1/ledger/entries`:

- **Deposit** (nạp tiền vào ví user): `debitAccountId = system_accounts['SUSPENSE_VND'].id`, `creditAccountId = <ví user>`. Bỏ qua check `available_balance >= amount` ở bước 3 vì system account được phép có balance âm (về bản chất là "hệ thống đang nợ" số tiền đã nhận từ gateway nhưng chưa nạp hết vào ví user).
- **Withdraw** (rút tiền ra khỏi ví user): `debitAccountId = <ví user>`, `creditAccountId = system_accounts['SUSPENSE_VND'].id`. Vẫn check số dư ví user bình thường.
- **Refund**: xử lý như 1 giao dịch mới theo chiều ngược của giao dịch gốc (KHÔNG dùng endpoint reversal ở 3.2, vì reversal chỉ dành cho compensating transaction nội bộ do Saga fail — refund là 1 nghiệp vụ hoàn tiền hợp lệ, cần entry mới độc lập, có `transactionId` riêng để dễ tra soát).

`transaction-service` chịu trách nhiệm gọi đúng chiều debit/credit tùy loại giao dịch — Ledger Service không tự suy luận "đây là deposit hay transfer", chỉ biết ghi đúng cặp account được yêu cầu.

---

### 3.2 POST /internal/v1/ledger/entries/reversal

Ghi bút toán đảo cho 1 cặp bút toán đã tồn tại — dùng khi Saga cần compensate (VD debit thành công nhưng credit ở bước sau thất bại).

**Request:**
```json
{
  "originalTransactionId": "uuid",
  "idempotencyKey": "uuid",
  "reason": "CREDIT_STEP_FAILED"
}
```

**Business logic:**
1. Tìm cặp bút toán gốc theo `originalTransactionId`
2. Idempotency check tương tự mục 3.1
3. Insert bút toán MỚI ngược chiều (nếu gốc là DEBIT account A → giờ ghi CREDIT lại account A), set `reversal_of_entry_id` trỏ về entry gốc
4. Cập nhật `balances` tương ứng
5. Insert `outbox_events` (`event_type=LedgerEntryReversed`)
6. **Tuyệt đối không đụng vào row bút toán gốc** — chỉ thêm bút toán mới

---

### 3.3 GET /internal/v1/ledger/balance/{accountId}

Trả về balance hiện tại. Đọc từ bảng `balances` (đã được duy trì đồng bộ qua mục 3.1/3.2), không cần tính lại từ toàn bộ `ledger_entries` mỗi lần gọi (quá chậm) — nhưng có endpoint riêng để đối soát định kỳ.

---

### 3.4 GET /internal/v1/ledger/reconcile/{accountId}

Dùng cho `reconciliation-service` (module mở rộng) hoặc chạy tay khi nghi ngờ lệch số liệu: tính lại balance từ toàn bộ `ledger_entries` của account, so sánh với giá trị đang lưu ở `balances`, trả về có khớp hay không. Endpoint này chạy ở isolation level `READ COMMITTED` bình thường (không lock), chấp nhận đọc dữ liệu tại 1 thời điểm — nếu có ghi đang chạy song song thì kết quả reconcile mang tính advisory, không phải bằng chứng lỗi tức thời, nên chạy định kỳ (VD cuối ngày, lúc ít giao dịch) chứ không dùng để chặn giao dịch real-time.

### 3.5 GET /internal/v1/ledger/entries?accountId={id}&from=&to=&page=

Trả lịch sử bút toán của 1 account, có phân trang, phục vụ `audit-service` và tính năng sao kê giao dịch (statement) sau này. Chỉ đọc, không có rủi ro consistency vì bảng `ledger_entries` bất biến.

## 4. Concurrency & Consistency — phần khó nhất của service này

**Chọn 1 cơ chế lock chính, không dùng cả 2 chồng lên nhau:** bản trước có mâu thuẫn khi vừa nói dùng `SELECT FOR UPDATE` vừa dựa vào `@Version` optimistic lock cho cùng 1 luồng ghi — dư thừa và dễ gây nhầm khi implement thật. Quyết định rõ:

- **Pessimistic lock (`SELECT ... FOR UPDATE`) là cơ chế chính** cho đường ghi bút toán ở mục 3.1/3.2, vì tần suất tranh chấp trên cùng 1 account có thể cao (nhiều giao dịch đến/đi cùng lúc) — pessimistic lock tránh được việc phải retry nhiều lần như optimistic lock hay gặp khi contention cao.
- **Cột `version` vẫn giữ lại** nhưng chỉ đóng vai trò "belt and suspenders" — một lớp bảo vệ phụ để phát hiện lost update nếu có đường code nào đó (VD job nội bộ khác) lỡ update `balances` mà quên qua `SELECT FOR UPDATE`. Không thiết kế luồng retry chính dựa vào nó.
- **Isolation level:** `READ COMMITTED` (mặc định PostgreSQL) là đủ khi đã có `SELECT FOR UPDATE` đúng cách — không cần `SERIALIZABLE` (vốn có chi phí retry cao hơn ở PostgreSQL do dùng SSI). Tuy nhiên với các thao tác đọc-tính-tổng nhiều account cùng lúc (như reconcile toàn hệ thống ở test load), có thể cân nhắc `REPEATABLE READ` để có snapshot nhất quán.
- **Deadlock tránh bằng cách lock theo thứ tự cố định:** khi 1 giao dịch cần lock 2 account (debit + credit), LUÔN lock theo thứ tự `account_id` tăng dần (so sánh UUID dạng string), không lock theo thứ tự debit-trước-credit-sau, để tránh deadlock khi có 2 giao dịch ngược chiều nhau chạy đồng thời (A→B và B→A cùng lúc tranh nhau lock).
- **Lock timeout:** set `lock_timeout` ở mức DB session (VD 5 giây) cho các câu `SELECT FOR UPDATE` — tránh 1 transaction bị treo vô hạn nếu deadlock detection của Postgres chưa kịp xử lý, throw lỗi để tầng service quyết định retry có kiểm soát (tối đa 3 lần, backoff ngắn) thay vì để request treo.

## 5. Test case bắt buộc (đây là service cần test kỹ nhất trong toàn hệ thống)

- Ghi bút toán với idempotency key lặp lại → verify chỉ ghi 1 lần, response trả về giống hệt lần đầu
- Ghi bút toán khi không đủ số dư → verify KHÔNG có ledger_entries nào được ghi (rollback sạch, không ghi được DEBIT mà thiếu CREDIT)
- Chạy 2 request ghi bút toán đồng thời (concurrent) trên cùng 1 account với tổng amount vượt quá balance hiện có → verify chỉ 1 request thành công, request kia bị từ chối đúng (test race condition thật, không chỉ test tuần tự)
- Test reversal: ghi bút toán → reversal → verify balance trở về đúng giá trị ban đầu, và bút toán gốc vẫn còn nguyên trong `ledger_entries` (không bị xóa)
- Test reconcile: cố tình seed dữ liệu lệch giữa `balances` và tổng `ledger_entries` → verify endpoint reconcile phát hiện được sai lệch
- Load test: bắn đồng thời hàng trăm giao dịch ngẫu nhiên giữa 1 nhóm account → cuối cùng tổng balance toàn hệ thống phải bằng đúng tổng ban đầu (không sinh ra hoặc mất tiền — invariant quan trọng nhất)
- **Idempotent retry sau business failure:** ghi bút toán thiếu số dư (nhận 422) → gọi lại y hệt idempotency key → verify trả lại đúng response FAILED đã lưu, KHÔNG chạy lại logic validate (test riêng cho bug đã fix ở bản này)
- **Stale PROCESSING recovery:** giả lập 1 row `idempotency_keys` kẹt ở `PROCESSING` với `updated_at` > 30 giây (mô phỏng process chết giữa chừng) → gọi lại → verify hệ thống chiếm lại và xử lý tiếp được, không kẹt vĩnh viễn
- Transfer với `debitAccountId == creditAccountId` → verify bị từ chối ngay, trả 400
- Transfer giữa 2 account khác currency → verify bị từ chối, trả 422 "Currency mismatch"
- Test Deposit: debit từ `system_accounts` vào ví user → verify ví user tăng đúng, balance của system account được phép âm
- Test outbox: sau khi ghi bút toán thành công → verify có đúng 1 row `outbox_events` với `published=false`, và sau khi `OutboxPublisher` chạy → `published=true`

---

Đây là service nên dành nhiều thời gian nhất để làm đúng — sai ở đây thì toàn hệ thống mất ý nghĩa. Khi bạn sẵn sàng code, nên bắt đầu từ entity + logic ghi bút toán ở mục 3.1 trước, viết test concurrency ngay từ đầu chứ không để cuối.
