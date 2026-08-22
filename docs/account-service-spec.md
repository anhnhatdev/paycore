# SERVICE SPEC: account-service (PayCore)

Tài liệu này là spec chi tiết để implement `account-service` — service đầu tiên trong hệ thống PayCore, vì tất cả service khác đều phụ thuộc vào auth/JWT do service này phát hành. Tuân thủ context tổng ở `payment-platform-context.md`.

## 1. Trách nhiệm của service

- Đăng ký / đăng nhập / cấp phát JWT (access + refresh token)
- Quản lý thông tin user (profile, role, KYC status)
- Quản lý account (tài khoản ví gắn với user, số dư KHÔNG lưu ở đây — chỉ Ledger Service mới có quyền giữ số dư thật)
- Là nguồn sự thật (source of truth) cho: user tồn tại hay không, role gì, account có bị khóa (FROZEN) hay không

**Không thuộc phạm vi service này:** balance, transaction history (thuộc Ledger/Transaction Service). Account Service chỉ trả lời "tài khoản này có hợp lệ để giao dịch không", không trả lời "còn bao nhiêu tiền".

## 2. Database schema chi tiết

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(20) UNIQUE,
    role VARCHAR(20) NOT NULL DEFAULT 'USER', -- ADMIN, MERCHANT, USER
    kyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, VERIFIED, REJECTED
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, LOCKED
    failed_login_attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    account_number VARCHAR(20) UNIQUE NOT NULL, -- sinh tự động, dạng PC + 12 số
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, FROZEN, CLOSED
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(255) NOT NULL, -- KHÔNG lưu raw token, chỉ lưu hash (SHA-256)
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    device_info VARCHAR(255) -- optional, để hỗ trợ "đăng xuất khỏi thiết bị khác"
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_accounts_user_id ON accounts(user_id);
```

**Lưu ý bảo mật:** `password_hash` dùng BCrypt (strength ≥ 12). `token_hash` của refresh token cũng phải hash trước khi lưu DB — nếu DB bị leak, kẻ tấn công không lấy được token dùng ngay được.

## 3. API chi tiết

### 3.1 POST /api/v1/auth/register

**Request:**
```json
{
  "email": "user@example.com",
  "password": "MinPass8Ky@",
  "fullName": "Nguyen Van A",
  "phoneNumber": "0901234567"
}
```

**Validation:**
- `email`: format hợp lệ, unique (check DB, trả lỗi 409 nếu trùng)
- `password`: tối thiểu 8 ký tự, có chữ hoa + chữ thường + số + ký tự đặc biệt (khác e-commerce, ở fintech nên siết password policy chặt hơn)
- `phoneNumber`: format VN (regex `^0\d{9}$`), unique
- `fullName`: không rỗng, tối đa 255 ký tự

**Response 201:**
```json
{
  "userId": "uuid",
  "email": "user@example.com",
  "kycStatus": "PENDING",
  "message": "Đăng ký thành công, vui lòng hoàn tất KYC để giao dịch"
}
```

**Business logic:**
1. Validate input
2. Check email/phone chưa tồn tại
3. Hash password (BCrypt)
4. Tạo user với `kyc_status=PENDING`, `role=USER`
5. **Tự động tạo 1 account mặc định** (currency=VND, status=ACTIVE) — publish event `AccountCreated` lên Kafka để Ledger Service khởi tạo balance record ban đầu = 0
6. KHÔNG tự động login sau register — bắt buộc user login riêng (chuẩn bảo mật fintech, khác với UX ecommerce thường auto-login)

**Error cases:**
| Code | Trường hợp |
|---|---|
| 400 | Validation fail (password yếu, email sai format) |
| 409 | Email hoặc phone đã tồn tại |

---

### 3.2 POST /api/v1/auth/login

**Request:**
```json
{ "email": "user@example.com", "password": "MinPass8Ky@" }
```

**Response 200:**
```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "expiresIn": 900,
  "tokenType": "Bearer"
}
```

**Business logic:**
1. Tìm user theo email → nếu không tồn tại, trả lỗi **generic** "Email hoặc mật khẩu không đúng" (KHÔNG được nói rõ "email không tồn tại" — tránh lộ thông tin cho kẻ dò email hợp lệ, nguyên tắc bảo mật quan trọng ở fintech)
2. Verify password bằng BCrypt
3. Nếu sai → tăng `failed_login_attempts`; nếu ≥ 5 lần trong 15 phút → tự động set `status=LOCKED`, trả 423 Locked
4. Nếu đúng → reset `failed_login_attempts=0`, generate:
   - Access Token (JWT, exp 15 phút): payload gồm `userId`, `role`, `kycStatus`
   - Refresh Token (JWT, exp 7 ngày): lưu hash vào DB
5. Publish event `UserLoggedIn` (phục vụ audit log — không chứa password)

**Error cases:**
| Code | Trường hợp |
|---|---|
| 401 | Sai email/password |
| 423 | Tài khoản bị khóa do đăng nhập sai quá nhiều |
| 403 | `status=LOCKED` do admin khóa thủ công |

---

### 3.3 POST /api/v1/auth/refresh

**Request:**
```json
{ "refreshToken": "eyJ..." }
```

**Business logic:**
1. Verify chữ ký JWT + hash để so khớp DB
2. Check `revoked=false` và chưa hết hạn
3. Nếu hợp lệ → **rotate**: revoke refresh token cũ, tạo access token + refresh token mới (refresh token rotation — chuẩn bảo mật để phát hiện token bị đánh cắp: nếu 1 refresh token đã revoked bị dùng lại → nghi ngờ bị lộ, có thể revoke toàn bộ token của user đó)

**Response 200:** giống response của login.

---

### 3.4 POST /api/v1/auth/logout

**Request:** header `Authorization: Bearer <accessToken>`, body chứa `refreshToken`

**Business logic:** set `revoked=true` cho refresh token tương ứng. Access token không thể thu hồi trước hạn (đặc điểm JWT stateless) — vì vậy access token nên có thời hạn ngắn (15 phút) để giới hạn rủi ro.

---

### 3.5 GET /api/v1/users/me

Yêu cầu JWT hợp lệ. Trả về thông tin user hiện tại (không trả `password_hash`).

---

### 3.6 GET /api/v1/accounts/me

Yêu cầu JWT hợp lệ. Trả về danh sách account của user (account_number, currency, status) — **không trả balance**, muốn balance phải gọi qua Transaction Service (Transaction Service mới có quyền tổng hợp gọi Ledger nội bộ).

---

### 3.7 POST /api/v1/accounts/{id}/freeze

Role `ADMIN` only. Đóng băng tài khoản (dùng khi nghi ngờ gian lận). Publish event `AccountFrozen` để các service khác (Transaction Service) biết và chặn giao dịch mới trên account này.

## 4. Endpoint nội bộ (internal, gọi qua mTLS, không qua Gateway public)

```
GET /internal/v1/accounts/{accountId}/status
```
Dùng cho Transaction Service kiểm tra account có `ACTIVE` không trước khi cho phép giao dịch — bắt buộc check ở mỗi lần transfer, không cache lâu vì trạng thái FROZEN cần có hiệu lực gần như ngay lập tức.

## 5. JWT payload chuẩn

```json
{
  "sub": "userId",
  "role": "USER",
  "kycStatus": "VERIFIED",
  "iat": 1234567890,
  "exp": 1234568790
}
```

Các service khác (Transaction Service, Ledger Service khi cần) verify JWT bằng public key (nếu dùng RS256 — khuyến nghị dùng RS256 thay vì HS256 để Account Service giữ private key, các service khác chỉ cần public key để verify, không thể tự tạo token giả).

## 6. Test case bắt buộc

- Register: email trùng, password yếu, tạo account mặc định thành công
- Login: sai password 5 lần liên tiếp → account bị khóa
- Refresh: dùng lại refresh token đã revoked → phải bị từ chối + có thể trigger cảnh báo
- Freeze account: sau khi freeze, JWT cũ vẫn còn hạn nhưng account đã FROZEN — Transaction Service phải chặn được giao dịch (test tích hợp end-to-end với Transaction Service)

---

Sẵn sàng để bắt đầu code `account-service` theo spec này. Khi bạn cần, mình có thể viết cụ thể từng phần: entity + migration Flyway, DTO, controller, service layer, JWT util, hoặc Docker Compose config.
