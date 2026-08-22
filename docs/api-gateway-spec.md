# SERVICE SPEC: api-gateway (PayCore)

Cổng vào duy nhất của hệ thống. Client (web/mobile) KHÔNG BAO GIỜ gọi trực tiếp vào `account-service`, `wallet-ledger-service`... mà luôn đi qua Gateway. Ở fintech, Gateway còn gánh thêm vai trò lớp phòng thủ đầu tiên chống abuse/fraud ở tầng network.

## 1. Trách nhiệm

- Routing request tới đúng service dựa trên path, dùng service discovery qua Eureka (KHÔNG hardcode IP/port)
- Xác thực JWT ở tầng Gateway trước khi forward (fail-fast — chặn request không hợp lệ sớm nhất có thể, tránh tốn tài nguyên các service phía sau)
- **Rate limiting theo user và theo IP** — đây là yêu cầu quan trọng hơn hẳn so với e-commerce, vì fintech là mục tiêu hàng đầu của brute-force và credential stuffing attack
- Forward các header cần thiết (`X-User-Id`, `X-User-Role` được Gateway trích từ JWT) để service phía sau không cần tự parse JWT lại (tùy chọn kiến trúc — có thể để mỗi service tự verify JWT độc lập cho chắc, xem mục 5)
- Ẩn cấu trúc nội bộ hệ thống khỏi client (client không biết có bao nhiêu service, tên gì)

## 2. Dependency

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
```

Lưu ý: Spring Cloud Gateway (bản reactive, WebFlux) — Redis cần dùng bản reactive để tương thích non-blocking, khác với Redis blocking dùng ở các service thường (VD Ledger dùng Redis cho idempotency có thể dùng bản blocking bình thường).

## 3. Cấu hình routing

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: account-service
          uri: lb://account-service          # lb:// = load balanced qua Eureka
          predicates:
            - Path=/api/v1/auth/**, /api/v1/users/**, /api/v1/accounts/**
          filters:
            - name: JwtAuthFilter
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 10
                redis-rate-limiter.burstCapacity: 20
                key-resolver: "#{@userKeyResolver}"

        - id: wallet-ledger-service-internal
          uri: lb://wallet-ledger-service
          predicates:
            - Path=/internal/**
          filters:
            - name: DenyPublicAccess   # custom filter: CHẶN TUYỆT ĐỐI, endpoint /internal/** không bao giờ được lộ ra Gateway

        - id: transaction-service
          uri: lb://transaction-service
          predicates:
            - Path=/api/v1/transactions/**
          filters:
            - name: JwtAuthFilter
            - name: IdempotencyKeyRequiredFilter   # custom filter: từ chối request POST không có header Idempotency-Key
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 5
                redis-rate-limiter.burstCapacity: 10
                key-resolver: "#{@userKeyResolver}"
```

**Nguyên tắc quan trọng:** endpoint `/internal/**` (dành cho service-to-service, VD Ledger Service) phải có filter chặn tuyệt đối ngay ở Gateway — dù có bug ở service backend quên check auth thì Gateway vẫn là lớp chặn cuối cùng không cho public truy cập trực tiếp.

## 4. Custom Filters cần implement

### 4.1 JwtAuthFilter (GatewayFilterFactory)
- Đọc header `Authorization: Bearer <token>`
- Verify chữ ký bằng public key (RS256, key lấy từ `account-service` lúc khởi động hoặc config tĩnh)
- Nếu hợp lệ: decode payload, set thêm header `X-User-Id`, `X-User-Role`, `X-Kyc-Status` để forward xuống service phía sau
- Nếu không hợp lệ/hết hạn: trả 401 ngay tại Gateway, KHÔNG forward request

### 4.2 RateLimiter — 2 lớp
- **Theo IP** (áp dụng cho endpoint public như `/auth/login`, `/auth/register` — chưa có JWT để định danh user): key = IP address, giới hạn chặt hơn (VD 5 request/phút) để chống brute-force đăng nhập
- **Theo User** (áp dụng cho endpoint đã có JWT, VD `/transactions/**`): key = `userId` lấy từ JWT, giới hạn theo nghiệp vụ (VD 5 giao dịch/giây tối đa/user)

```java
@Bean
KeyResolver ipKeyResolver() {
    return exchange -> Mono.just(
        exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
    );
}

@Bean
KeyResolver userKeyResolver() {
    return exchange -> Mono.justOrEmpty(
        exchange.getRequest().getHeaders().getFirst("X-User-Id")
    ).switchIfEmpty(Mono.just("anonymous"));
}
```

### 4.3 IdempotencyKeyRequiredFilter
- Với mọi POST/PUT vào `/api/v1/transactions/**`, bắt buộc có header `Idempotency-Key` (UUID format), thiếu thì trả 400 ngay tại Gateway thay vì để service phía sau tự xử lý — fail fast.

### 4.4 GlobalLoggingFilter
- Log mỗi request: method, path, `X-User-Id` (nếu có), response status, latency, **traceId** (sinh tại Gateway nếu chưa có, forward xuống toàn bộ service phía sau qua header `X-Trace-Id` để phục vụ distributed tracing/Zipkin)

## 5. Quyết định kiến trúc: Gateway verify JWT có đủ, hay mỗi service tự verify?

**Khuyến nghị cho PayCore: mỗi service PHẢI tự verify JWT độc lập, KHÔNG chỉ tin header `X-User-Id` do Gateway forward.**

Lý do: nếu có cách nào đó request đi vòng qua Gateway (misconfiguration mạng, service bị lộ trực tiếp), việc chỉ tin header không có JWT gốc là lỗ hổng nghiêm trọng cho hệ thống tài chính. Gateway verify JWT là lớp phòng thủ đầu (fail fast, giảm tải), nhưng KHÔNG thay thế việc mỗi service tự verify lại — đây là nguyên tắc **defense in depth**, quan trọng hơn ở fintech so với e-commerce.

## 6. Docker Compose entry

```yaml
api-gateway:
  build: ./api-gateway
  ports:
    - "8080:8080"
  depends_on:
    eureka-server:
      condition: service_healthy
    redis:
      condition: service_started
  environment:
    - EUREKA_URI=http://eureka-server:8761/eureka/
    - REDIS_HOST=redis
```

## 7. Test case bắt buộc

- Request không có JWT tới `/api/v1/accounts/me` → 401 ngay tại Gateway
- Request tới `/internal/v1/ledger/balance/{id}` từ client bên ngoài → bị chặn tuyệt đối dù có JWT hợp lệ (endpoint chỉ dành service-to-service)
- Spam login sai 10 lần trong 1 phút từ cùng 1 IP → bị rate limit chặn từ lần thứ 6
- Spam POST `/transactions/transfer` không kèm `Idempotency-Key` → 400 ngay tại Gateway
- Test load balancing: chạy 2 instance `account-service`, gọi liên tiếp nhiều request → verify traffic được chia đều (round-robin mặc định)

---

Sẵn sàng để code — có thể bắt đầu từ `JwtAuthFilter` (phần phức tạp nhất) hoặc từ cấu hình routing cơ bản trước.
