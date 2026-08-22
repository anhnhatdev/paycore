# PROJECT CONTEXT: PayCore — Payment Platform Microservices

Bạn là một AI hỗ trợ phát triển phần mềm. Dưới đây là toàn bộ bối cảnh (context) của dự án. Hãy ghi nhớ và tuân thủ các quy ước này trong suốt quá trình hỗ trợ code, thiết kế, hoặc trả lời câu hỏi liên quan.

## 1. Tổng quan dự án

**Tên dự án:** PayCore
**Mô tả:** Nền tảng thanh toán (payment platform) xây dựng theo kiến trúc **Microservices**, mô phỏng các hệ thống thực tế như ví điện tử (MoMo, ZaloPay) hoặc payment gateway (Stripe, VNPay). Dự án dùng để thực hành/portfolio, đi sâu vào các bài toán đặc thù fintech: **strong consistency, idempotency, distributed transaction (Saga), ledger/sổ cái, bảo mật giao dịch, audit trail**.

**Bối cảnh:** Đây là dự án thứ 2 sau MiniShop (e-commerce microservices), với mục tiêu chủ động **đa dạng hóa domain kiến thức** — giữ nguyên nền tảng kỹ thuật microservices (Spring Boot, Spring Cloud, Docker) nhưng chuyển sang bài toán tài chính, nơi độ chính xác dữ liệu và bảo mật quan trọng hơn tốc độ/trải nghiệm.

**Mục tiêu:** Xây dựng Core hoàn chỉnh (account, ledger, transfer) chạy được end-to-end với đảm bảo consistency chặt chẽ trước, sau đó mở rộng payment gateway integration, fraud detection và các hạ tầng nâng cao.

**Nguyên tắc khác biệt cốt lõi so với MiniShop (e-commerce):**

| Khía cạnh | MiniShop (e-commerce) | PayCore (fintech) |
|---|---|---|
| Consistency | Eventual consistency chấp nhận được | Strong consistency bắt buộc ở Ledger — không được lệch dù 1 đồng |
| Retry | Retry đơn giản | Retry BẮT BUỘC kèm Idempotency-Key |
| Saga | Optional, dùng khi mở rộng | Bắt buộc cho mọi luồng transfer/refund |
| Audit | Log thông thường | Audit trail đầy đủ, immutable, không cho update/delete transaction record |
| Bảo mật | JWT + role cơ bản | JWT + mTLS nội bộ + mã hóa dữ liệu nhạy cảm + tuân thủ tư duy PCI-DSS |

## 2. Tech stack

| Thành phần | Công nghệ |
|---|---|
| Ngôn ngữ / Framework | Java 25, Spring Boot 4.1.0, Spring Cloud 2025.1.0 |
| API Gateway | Spring Cloud Gateway |
| Service Discovery | Netflix Eureka |
| Bảo mật | Spring Security + JWT (access + refresh token), mTLS giữa các service nội bộ |
| Database | PostgreSQL (mỗi service 1 database riêng) |
| ORM | Spring Data JPA (Hibernate) |
| Migration | Flyway |
| Build tool | Maven |
| API Docs | Springdoc OpenAPI / Swagger UI |
| Container | Docker + Docker Compose |
| Validation | Spring Validation (Bean Validation) |
| Message Queue | **Kafka** (bắt buộc từ đầu, không để giai đoạn sau — vì Saga/event-driven là xương sống của hệ thống) |
| Cache / Idempotency store | **Redis** (lưu idempotency key, rate limiting) |
| Distributed Tracing | **Zipkin** hoặc Jaeger (bắt buộc — cần trace chính xác lỗi giao dịch) |
| Monitoring | Prometheus + Grafana |
| Resilience | Resilience4j (circuit breaker, retry có kiểm soát) |

## 3. Nguyên tắc kiến trúc bắt buộc

- Mỗi microservice có **database riêng biệt** (Database per Service) — KHÔNG dùng foreign key cứng giữa 2 service khác nhau, chỉ lưu ID tham chiếu logic.
- Giao tiếp đồng bộ giữa client và hệ thống đi qua **API Gateway** duy nhất.
- Giao tiếp giữa các service với nhau ưu tiên **bất đồng bộ qua Kafka** theo **Outbox Pattern** (ghi event vào bảng outbox cùng transaction với business data, có process riêng đọc outbox và publish lên Kafka) để đảm bảo không mất event khi service crash giữa chừng.
- **Idempotency bắt buộc**: mọi API ghi dữ liệu (POST/PUT giao dịch) phải nhận header `Idempotency-Key`, lưu vào Redis/DB với TTL hợp lý, request lặp lại với cùng key trả về kết quả cũ, không xử lý lại.
- **Saga Pattern (choreography-based qua Kafka event)** áp dụng cho mọi luồng xuyên nhiều service: transfer tiền, nạp/rút tiền, refund. Mỗi bước có compensating action rõ ràng.
- **Ledger là nguồn sự thật duy nhất (single source of truth)** cho balance — không cho phép service khác tự tính toán số dư, luôn phải hỏi Ledger Service.
- **Double-entry accounting**: mỗi giao dịch ghi tối thiểu 2 bút toán (debit — credit), tổng luôn phải bằng 0, không cho phép sửa/xóa transaction record đã ghi (chỉ cho phép ghi bút toán đảo/reversal).
- Xác thực dùng **JWT stateless** cho client-facing, **mTLS** cho giao tiếp service-to-service nội bộ (do tính nhạy cảm của domain).
- Các service tự đăng ký với **Eureka Server** để tự động discovery, không hardcode địa chỉ IP/port.
- Toàn bộ service được **Docker hóa**, chạy qua `docker-compose` ở môi trường local.
- Dữ liệu nhạy cảm (số thẻ, thông tin định danh) phải **mã hóa at-rest**, không bao giờ log ra plaintext.

## 4. Danh sách services

### 🔵 CORE (build trước — bắt buộc phải có)
1. **eureka-server** — Service Discovery
2. **api-gateway** — Cổng vào duy nhất, routing, rate limit
3. **account-service** — Đăng ký, đăng nhập, JWT, quản lý user/account, KYC cơ bản, phân quyền (ADMIN/MERCHANT/USER)
4. **ledger-service** — Sổ cái, double-entry bookkeeping, quản lý balance — **service quan trọng nhất hệ thống**
5. **transaction-service** — Orchestrate luồng transfer/nạp/rút tiền, điều phối Saga giữa Ledger, Gateway, Notification

### 🟢 MODULE MỞ RỘNG (build sau)
6. **payment-gateway-service** — Tích hợp cổng thanh toán bên ngoài (VNPay/Momo/Stripe sandbox), xử lý webhook có verify signature
7. **fraud-service** — Rule-based check: giới hạn hạn mức, tần suất giao dịch bất thường, blacklist
8. **notification-service** — Gửi email/SMS khi giao dịch thành công/thất bại
9. **reconciliation-service** — Đối soát batch job cuối ngày giữa Ledger nội bộ và payment gateway bên ngoài
10. **audit-service** — Lưu trữ audit log immutable (dùng event sourcing, đọc từ Kafka topic), phục vụ tra soát/khiếu nại

## 5. Thiết kế Database (Core)

**account_db (Account Service):**
```
users: id (UUID, PK), email (unique), password_hash, full_name, role (ENUM: ADMIN/MERCHANT/USER), kyc_status (ENUM: PENDING/VERIFIED/REJECTED), status (ACTIVE/LOCKED), created_at, updated_at
accounts: id (UUID, PK), user_id (FK), account_number (unique), currency (VD: VND), status (ACTIVE/FROZEN), created_at
refresh_tokens: id (UUID, PK), user_id (FK), token, expires_at, revoked (boolean)
```

**ledger_db (Ledger Service):**
```
ledger_entries: id (UUID, PK), transaction_id (reference ID, không FK cứng), account_id (reference ID), entry_type (ENUM: DEBIT/CREDIT), amount (DECIMAL, KHÔNG dùng float), currency, balance_after (DECIMAL), created_at
  -- Bất biến: KHÔNG có updated_at, KHÔNG cho UPDATE/DELETE, chỉ INSERT
balances: account_id (PK), available_balance (DECIMAL), pending_balance (DECIMAL), updated_at
  -- balance được tính lại/đối chiếu định kỳ từ tổng ledger_entries để đảm bảo không lệch
idempotency_keys: key (PK), request_hash, response_snapshot (JSON), created_at, expires_at
```

**transaction_db (Transaction Service):**
```
transactions: id (UUID, PK), from_account_id, to_account_id, amount (DECIMAL), currency, type (ENUM: TRANSFER/DEPOSIT/WITHDRAW/REFUND), status (ENUM: PENDING/PROCESSING/COMPLETED/FAILED/COMPENSATED), idempotency_key (unique), created_at, updated_at
saga_logs: id (UUID, PK), transaction_id (FK), step_name, status (ENUM: STARTED/SUCCESS/FAILED/COMPENSATED), payload (JSON), created_at
outbox_events: id (UUID, PK), aggregate_id, event_type, payload (JSON), published (boolean), created_at
```

## 6. API Endpoints (Core, version v1)

**Account Service:**
```
POST   /api/v1/auth/register
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout
GET    /api/v1/users/me                    (yêu cầu JWT)
GET    /api/v1/accounts/me                 (yêu cầu JWT)
POST   /api/v1/accounts/{id}/freeze        (JWT, role ADMIN)
```

**Ledger Service (nội bộ — chỉ gọi qua service-to-service, có mTLS, KHÔNG expose public qua Gateway):**
```
GET    /internal/v1/ledger/balance/{accountId}
POST   /internal/v1/ledger/entries          (ghi bút toán, cần Idempotency-Key)
GET    /internal/v1/ledger/entries/{transactionId}
```

**Transaction Service:**
```
POST   /api/v1/transactions/transfer        (JWT, header Idempotency-Key bắt buộc)
POST   /api/v1/transactions/deposit         (JWT, header Idempotency-Key bắt buộc)
POST   /api/v1/transactions/withdraw        (JWT, header Idempotency-Key bắt buộc)
GET    /api/v1/transactions/{id}
GET    /api/v1/transactions                 (lịch sử giao dịch, pagination)
```

## 7. Luồng xác thực (Authentication Flow)

```
1. Client gọi POST /auth/login → Account Service verify password (bcrypt)
2. Account Service tạo Access Token (JWT, hạn ngắn ~15 phút)
   + Refresh Token (hạn dài, lưu DB)
3. Client lưu token, đính kèm Access Token trong header Authorization: Bearer <token>
   cho các request tiếp theo
4. Khi gọi service khác qua Gateway:
   → Service tự verify JWT bằng shared secret/public key
   → Lấy userId, role từ payload token để xử lý business logic/phân quyền
5. Giao tiếp service-to-service nội bộ (VD: Transaction Service → Ledger Service):
   → Xác thực bằng mTLS certificate, KHÔNG dùng lại JWT của user
6. Khi Access Token hết hạn → Client gọi POST /auth/refresh → nhận Access Token mới
```

## 8. Luồng nghiệp vụ tổng quan — Saga cho Transfer (luồng quan trọng nhất)

```
Bước 1: Client gọi POST /transactions/transfer kèm Idempotency-Key
Bước 2: Transaction Service kiểm tra Idempotency-Key trong Redis
        → Nếu đã tồn tại: trả về kết quả cũ, KHÔNG xử lý lại
        → Nếu chưa: tiếp tục, lưu key ngay lập tức (tránh race condition)
Bước 3: Transaction Service tạo record transactions (status=PENDING)
        → ghi outbox_event "TransactionInitiated"
Bước 4: Gọi Fraud Service kiểm tra rule cơ bản (đồng bộ, timeout ngắn)
        → Fail → status=FAILED, publish "TransactionRejected", dừng luồng
Bước 5: Gọi Ledger Service ghi bút toán DEBIT tài khoản nguồn
        → Fail (không đủ số dư) → status=FAILED, publish "TransactionRejected"
        → Success → publish "DebitCompleted"
Bước 6: Transaction Service nhận event "DebitCompleted" → gọi Ledger Service
        ghi bút toán CREDIT tài khoản đích
        → Fail → publish "CreditFailed" → BẮT BUỘC compensate:
          gọi Ledger Service ghi bút toán đảo (reversal) hoàn lại DEBIT bước 5,
          status=COMPENSATED
        → Success → publish "CreditCompleted"
Bước 7: Transaction Service cập nhật status=COMPLETED
        → publish "TransactionCompleted"
Bước 8: Notification Service lắng nghe "TransactionCompleted"/"TransactionRejected"
        → gửi email/thông báo cho cả 2 bên tài khoản
```

**Nguyên tắc compensating transaction:** Không bao giờ xóa hoặc sửa bút toán đã ghi ở Ledger — mọi rollback đều là một bút toán MỚI (reversal entry) theo chiều ngược lại, để giữ nguyên tính bất biến và audit trail đầy đủ.

## 9. Quy ước code

- Cấu trúc mỗi service theo layer: `controller` → `service` → `repository` → `entity` → `dto` → `exception`.
- Dùng DTO riêng cho request/response, không expose Entity trực tiếp ra API.
- Xử lý lỗi tập trung bằng `@ControllerAdvice` / `@ExceptionHandler`, trả về response lỗi có format chuẩn (statusCode, message, timestamp, traceId — traceId để join với distributed tracing).
- Đặt tên package theo domain: `com.paycore.accountservice.*`, `com.paycore.ledgerservice.*`.
- Mỗi service có file `application.yml` riêng, không hardcode config, dùng biến môi trường (`.env` / Docker Compose env). **Không bao giờ commit secret/API key thật vào repo.**
- Viết Swagger annotation đầy đủ cho mọi endpoint public. Endpoint nội bộ (Ledger Service) đánh dấu rõ `@Hidden` hoặc tách OpenAPI group riêng.
- **Tiền tệ luôn dùng `BigDecimal`, KHÔNG BAO GIỜ dùng `float`/`double`** để tránh sai số làm tròn.
- Mọi entity liên quan đến tiền/ledger: cân nhắc `@Version` (optimistic locking) để tránh race condition khi cập nhật balance đồng thời.
- Test bắt buộc có unit test cho toàn bộ luồng Saga (bao gồm cả case compensate/rollback), không chỉ test happy path.

## 10. Trạng thái hiện tại của dự án

Đang ở giai đoạn: **Bắt đầu xây dựng Core**, thứ tự đề xuất:
1. `eureka-server` + `api-gateway` (hạ tầng nền)
2. `account-service` (auth, các service khác phụ thuộc vào đây)
3. `ledger-service` (trái tim hệ thống — cần thiết kế double-entry và idempotency chuẩn trước khi làm gì khác)
4. `transaction-service` (orchestrate Saga, tích hợp Kafka outbox)
5. Sau khi luồng transfer nội bộ chạy ổn định end-to-end → mở rộng `payment-gateway-service`, `fraud-service`, `notification-service`

---

**Hướng dẫn cho AI khi hỗ trợ:** Khi được yêu cầu viết code, thiết kế thêm, hoặc trả lời câu hỏi về dự án PayCore, hãy luôn tuân thủ đúng stack, cấu trúc thư mục, quy ước đặt tên, và đặc biệt là các nguyên tắc bắt buộc về **idempotency, double-entry ledger, và Saga pattern** đã nêu ở trên — đây là các nguyên tắc không được bỏ qua dù chỉ ở giai đoạn prototype, vì chúng là trọng tâm học tập của dự án này. Nếu yêu cầu của người dùng có xung đột với các nguyên tắc này (VD: dùng float cho tiền, cho phép update trực tiếp ledger_entries), hãy chỉ ra rõ ràng và đề xuất cách điều chỉnh phù hợp.