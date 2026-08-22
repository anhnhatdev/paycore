# SERVICE SPEC: payment-gateway-service (PayCore)

Module mở rộng — cầu nối giữa PayCore và thế giới bên ngoài (VNPay/Momo/Stripe sandbox). Là service **duy nhất** trong hệ thống được phép giao tiếp với cổng thanh toán bên thứ 3, và là ranh giới bảo mật quan trọng nhất vì xử lý dữ liệu thanh toán nhạy cảm.

## 1. Trách nhiệm

- Tạo yêu cầu thanh toán tới cổng ngoài (nạp tiền qua VNPay/Momo, hoặc rút tiền ra tài khoản ngân hàng)
- Nhận và xử lý **webhook** từ cổng ngoài báo kết quả (thành công/thất bại) — đây là phần khó và rủi ro nhất của service
- Verify chữ ký webhook để đảm bảo request thật sự đến từ cổng thanh toán, không phải giả mạo
- Publish kết quả về cho `transaction-service` qua event (Kafka), KHÔNG gọi thẳng `wallet-ledger-service` (giữ đúng ranh giới: Transaction Service vẫn là nơi orchestrate Saga duy nhất)
- Đối soát (reconcile) định kỳ với cổng ngoài để phát hiện giao dịch lệch (VD gateway báo thành công nhưng webhook bị mất, hoặc ngược lại)

**Không thuộc phạm vi:** quyết định giao dịch có hợp lệ về nghiệp vụ hay không (đó là Fraud Service), ghi bút toán/balance (đó là Ledger Service), lưu trữ thông tin thẻ của user (KHÔNG BAO GIỜ — xem mục 6 Bảo mật).

## 2. Vị trí trong luồng — quan hệ với transaction-service

```
Client → Transaction Service → Payment Gateway Service → [Cổng ngoài: VNPay/Momo/Stripe]
                                         ↑
                              [Cổng ngoài gọi webhook về]
                                         ↓
Payment Gateway Service → publish event Kafka → Transaction Service (tiếp tục Saga)
```

Payment Gateway Service **không tự quyết định** giao dịch deposit/withdraw có hoàn tất hay không đối với hệ thống nội bộ — nó chỉ báo cáo trung thực trạng thái phía cổng ngoài. Quyết định "vậy giờ có ghi Ledger hay không" vẫn thuộc về Transaction Service (giữ đúng nguyên tắc 1 nơi duy nhất orchestrate Saga, tránh 2 service cùng tranh quyền quyết định trạng thái giao dịch).

## 3. Database schema chi tiết

```sql
CREATE TABLE gateway_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    internal_transaction_id UUID NOT NULL,      -- reference logic tới transactions.id bên transaction-service
    provider VARCHAR(20) NOT NULL,              -- VNPAY | MOMO | STRIPE
    provider_transaction_ref VARCHAR(255),      -- mã giao dịch phía cổng ngoài trả về, NULL cho tới khi có phản hồi
    direction VARCHAR(10) NOT NULL,             -- INBOUND (deposit) | OUTBOUND (withdraw)
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
        -- INITIATED → PENDING_PROVIDER → SUCCEEDED | FAILED
        --                              → EXPIRED (user không hoàn tất thanh toán trong thời hạn)
    checkout_url TEXT,                          -- URL redirect cho INBOUND flow (VNPay/Momo kiểu redirect)
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP                        -- hạn checkout, VD 15 phút cho INBOUND
);
CREATE INDEX idx_gateway_tx_internal_id ON gateway_transactions(internal_transaction_id);
CREATE INDEX idx_gateway_tx_provider_ref ON gateway_transactions(provider, provider_transaction_ref);

-- Lưu MỌI webhook nhận được, kể cả trùng lặp — bằng chứng đối soát và chống replay
CREATE TABLE webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(20) NOT NULL,
    provider_event_id VARCHAR(255),             -- ID sự kiện phía provider (nếu có), dùng chống duplicate
    raw_payload JSONB NOT NULL,                  -- lưu NGUYÊN VẸN payload gốc — bắt buộc để tra soát/audit sau này
    signature_valid BOOLEAN NOT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED', -- RECEIVED | PROCESSED | IGNORED_DUPLICATE | REJECTED_INVALID_SIGNATURE
    gateway_transaction_id UUID,                 -- resolve được sau khi parse payload, NULL nếu không match được
    received_at TIMESTAMP NOT NULL DEFAULT now(),
    processed_at TIMESTAMP
);
CREATE UNIQUE INDEX idx_webhook_dedup ON webhook_events(provider, provider_event_id)
    WHERE provider_event_id IS NOT NULL;         -- chặn xử lý trùng ở tầng DB, không chỉ tầng code

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,                   -- = internal_transaction_id
    event_type VARCHAR(50) NOT NULL,              -- GatewayPaymentSucceeded | GatewayPaymentFailed | GatewayPaymentExpired
    payload JSONB NOT NULL,
    published BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_unpublished ON outbox_events(published) WHERE published = false;
```

**Vì sao lưu `raw_payload` nguyên vẹn:** khi có tranh chấp với cổng thanh toán hoặc user khiếu nại, đây là bằng chứng gốc duy nhất — không được chỉ lưu dữ liệu đã parse/rút gọn.

## 4. API chi tiết

### 4.1 POST /internal/v1/gateway/deposit/initiate

Gọi bởi `transaction-service` (mTLS), KHÔNG public qua Gateway.

**Request:**
```json
{
  "internalTransactionId": "uuid",
  "idempotencyKey": "uuid",
  "amount": 500000.00,
  "currency": "VND",
  "provider": "VNPAY",
  "returnUrl": "https://paycore.app/deposit/callback"
}
```

**Business logic:**
1. Idempotency check y hệt mẫu đã chuẩn hóa ở `wallet-ledger-service-spec.md` (2 pha, phân biệt business failure vs technical failure, xử lý stale PROCESSING)
2. Insert `gateway_transactions` (`status=INITIATED`)
3. Gọi API tạo giao dịch của provider tương ứng (mỗi provider có adapter riêng — xem mục 5)
4. Nhận về `checkout_url` (VD VNPay trả link redirect) → update `status=PENDING_PROVIDER`, lưu `provider_transaction_ref`
5. Set `expires_at` (VD now + 15 phút)
6. Trả `checkout_url` về Transaction Service → forward cho client redirect user sang trang thanh toán

**Response 200:**
```json
{ "gatewayTransactionId": "uuid", "checkoutUrl": "https://sandbox.vnpayment.vn/...", "expiresAt": "..." }
```

---

### 4.2 POST /internal/v1/gateway/withdraw/initiate

Tương tự 4.1 nhưng `direction=OUTBOUND`, không có `checkout_url` (rút tiền thường là API-to-API, không cần redirect user) — kết quả trả về ngay hoặc qua webhook tùy provider.

---

### 4.3 POST /webhooks/{provider} — endpoint PUBLIC duy nhất của service này

Đây là endpoint duy nhất trong toàn bộ `payment-gateway-service` được expose công khai qua `api-gateway` (route riêng, KHÔNG qua `JwtAuthFilter` vì cổng ngoài không có JWT của hệ thống — thay vào đó bảo vệ bằng chữ ký riêng của từng provider).

**Business logic (thứ tự CỰC KỲ quan trọng — verify chữ ký PHẢI làm trước tiên):**
1. Đọc raw body **trước khi parse JSON** (một số provider ký trên raw bytes, parse rồi serialize lại có thể đổi thứ tự field làm sai chữ ký)
2. Verify chữ ký (HMAC-SHA256 với secret riêng từng provider, hoặc verify theo cách riêng của provider đó — VD VNPay dùng `vnp_SecureHash`)
   - Sai chữ ký → insert `webhook_events` với `signature_valid=false`, `processing_status=REJECTED_INVALID_SIGNATURE`, trả **200 OK** (KHÔNG trả 4xx/5xx — nhiều provider sẽ retry liên tục nếu nhận lỗi, trả 200 để họ ngừng gửi lại, nhưng nội bộ đã ghi nhận và có thể alert nếu tần suất bất thường)
3. Insert `webhook_events` (`raw_payload`, `signature_valid=true`) — nếu `provider_event_id` đã tồn tại (unique index) → `processing_status=IGNORED_DUPLICATE`, trả 200 ngay, DỪNG
4. Parse payload, resolve `gateway_transaction_id` qua `provider_transaction_ref`
5. Update `gateway_transactions.status` tương ứng (`SUCCEEDED`/`FAILED`)
6. Insert `outbox_events` (`GatewayPaymentSucceeded`/`GatewayPaymentFailed`)
7. Update `webhook_events.processing_status=PROCESSED`
8. Trả 200 OK

**Trả 200 OK trong MỌI trường hợp đã ghi nhận được webhook** (kể cả duplicate, kể cả không resolve được transaction) — chỉ trả lỗi 4xx/5xx nếu có lỗi kỹ thuật thật sự khiến không ghi được vào DB (để provider retry đúng nghĩa). Nguyên tắc: đừng bao giờ khiến provider retry vô ích vì logic nghiệp vụ nội bộ.

---

### 4.4 GET /internal/v1/gateway/transactions/{id}/status

Cho Transaction Service hoặc job reconcile query trạng thái hiện tại (đọc từ `gateway_transactions`, không gọi lại API provider mỗi lần — chỉ dùng khi nghi ngờ lệch, xem mục 7).

## 5. Provider Adapter Pattern

Mỗi provider (VNPay/Momo/Stripe) có cách gọi API, ký request, format webhook khác nhau — cô lập bằng interface chung:

```java
public interface PaymentProviderAdapter {
    InitiateResult initiateDeposit(DepositRequest request);
    InitiateResult initiateWithdraw(WithdrawRequest request);
    boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers);
    WebhookResult parseWebhook(byte[] rawBody);
}
```

`VnpayAdapter`, `MomoAdapter`, `StripeAdapter` implement riêng — service chọn adapter theo field `provider`. Cách ly này giúp thêm provider mới không phải sửa logic core, và giúp việc test dễ hơn (mock adapter thay vì mock HTTP call thật).

## 6. Bảo mật — quan trọng bậc nhất ở service này

- **KHÔNG BAO GIỜ lưu số thẻ, CVV, hoặc thông tin định danh tài khoản ngân hàng đầy đủ** trong bất kỳ bảng nào của service này — kể cả trong `raw_payload`, cần rà soát/mask trước khi lưu nếu provider trả về các trường đó (tư duy tuân thủ PCI-DSS: hệ thống không tự xử lý card data, luôn để provider xử lý qua trang/API của họ — PayCore chỉ nhận kết quả).
- Secret ký webhook của từng provider lưu trong secret manager/biến môi trường, KHÔNG hardcode, KHÔNG commit vào repo.
- `returnUrl`/callback URL phải validate whitelist domain, tránh open redirect.
- Rate limit riêng cho endpoint `/webhooks/{provider}` ở Gateway (dù không cần JWT, vẫn cần chặn spam) — theo IP range của provider nếu biết trước (VD whitelist dải IP VNPay).
- Log KHÔNG BAO GIỜ in ra secret/signature key, chỉ log kết quả verify (true/false).

## 7. Đối soát (Reconciliation) — bắt buộc vì webhook có thể mất

Webhook là cơ chế **không đảm bảo 100%** (network lỗi, provider down đúng lúc gửi, endpoint của mình down đúng lúc nhận) — không được coi đây là nguồn sự thật duy nhất.

**Batch job chạy định kỳ (VD mỗi giờ, hoặc cuối ngày tùy provider hỗ trợ):**
1. Query danh sách `gateway_transactions` đang `PENDING_PROVIDER` quá X phút (chưa nhận webhook)
2. Gọi API "query status" của provider (hầu hết provider đều có API tra cứu chủ động, không chỉ dựa vào webhook)
3. Nếu provider báo đã thành công nhưng hệ thống chưa cập nhật → xử lý y hệt như nhận được webhook hợp lệ (bước 4-8 ở mục 4.3), đánh dấu nguồn là `RECONCILE` thay vì `WEBHOOK` để phân biệt khi audit
4. Nếu quá `expires_at` mà vẫn `PENDING_PROVIDER` và provider cũng xác nhận không có giao dịch → `status=EXPIRED`, publish outbox tương ứng để Transaction Service đóng giao dịch nội bộ

## 8. Test case bắt buộc

- Webhook với chữ ký sai → verify bị từ chối xử lý nghiệp vụ, nhưng vẫn trả 200, và có ghi lại `webhook_events` với `signature_valid=false`
- Webhook gửi trùng 2 lần (cùng `provider_event_id`) → verify chỉ xử lý 1 lần, lần 2 là `IGNORED_DUPLICATE`, không publish outbox event 2 lần
- Webhook đến nhưng không resolve được `gateway_transaction_id` (dữ liệu rác/nhầm) → verify không crash, ghi log để điều tra, trả 200
- Test reconcile: giả lập 1 giao dịch `PENDING_PROVIDER` quá hạn, mock provider trả "đã thành công" → verify job reconcile tự cập nhật đúng và publish outbox y hệt luồng webhook bình thường
- Test hết hạn: giao dịch quá `expires_at` mà không có webhook lẫn kết quả reconcile xác nhận → verify chuyển `EXPIRED` đúng, không treo vĩnh viễn
- Test idempotent initiate: gọi `deposit/initiate` 2 lần cùng `idempotencyKey` → verify không tạo 2 giao dịch phía provider (chỉ gọi API provider 1 lần)
- Kiểm tra thủ công: rà soát toàn bộ trường lưu trong `webhook_events.raw_payload` của từng provider thật, đảm bảo không có trường nào chứa số thẻ/CVV — nếu có, phải mask trước khi lưu

---

Lưu ý triển khai: ở giai đoạn học tập, có thể bắt đầu với provider sandbox dễ nhất để tích hợp trước (thường VNPay sandbox có tài liệu tiếng Việt đầy đủ, phù hợp làm provider đầu tiên), sau đó thêm Adapter cho Momo/Stripe khi đã chạy ổn luồng webhook + reconcile — đây là phần dễ debug sai nhất nếu làm nhiều provider cùng lúc ngay từ đầu.
