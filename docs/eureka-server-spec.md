# SERVICE SPEC: eureka-server (PayCore)

Service hạ tầng, không chứa business logic. Là Service Registry để các service khác tự đăng ký và discover lẫn nhau, tránh hardcode IP/port.

## 1. Trách nhiệm

- Cho phép các service (`api-gateway`, `user-service`, `wallet-ledger-service`, v.v.) đăng ký bản thân khi khởi động (self-registration)
- Cung cấp danh sách instance đang sống của một service khi được hỏi (dùng cho client-side load balancing)
- Loại bỏ instance khỏi registry khi không còn heartbeat (self-preservation mode cần cân nhắc tắt ở môi trường dev để tránh giữ instance chết quá lâu)

## 2. Setup

**Dependency (Maven):**
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>
```

**Main class:** thêm annotation `@EnableEurekaServer`

**application.yml:**
```yaml
server:
  port: 8761

spring:
  application:
    name: eureka-server

eureka:
  client:
    register-with-eureka: false   # bản thân server không tự đăng ký như 1 client
    fetch-registry: false
  server:
    enable-self-preservation: false   # tắt ở dev để instance chết bị gỡ nhanh, KHÔNG tắt ở production
    eviction-interval-timer-in-ms: 5000
```

## 3. Cấu hình phía client (áp dụng cho MỌI service khác trong hệ thống)

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka/
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10   # gửi heartbeat mỗi 10s
    lease-expiration-duration-in-seconds: 30 # nếu không heartbeat trong 30s → coi như chết
```

Mỗi service set `spring.application.name` đúng chuẩn convention (`account-service`, `wallet-ledger-service`, `transaction-service`...) — đây là tên dùng để các service khác gọi nhau qua Eureka/Feign, phải khớp tuyệt đối với tên khai báo route ở `api-gateway`.

## 4. Docker Compose entry

```yaml
eureka-server:
  build: ./eureka-server
  ports:
    - "8761:8761"
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8761/actuator/health"]
    interval: 10s
    timeout: 5s
    retries: 5
```

Tất cả service khác trong `docker-compose.yml` nên có `depends_on: eureka-server` với điều kiện `condition: service_healthy` để tránh race condition lúc khởi động (service khởi động trước khi Eureka sẵn sàng vẫn tự retry register được, nhưng khai báo depends_on giúp log sạch hơn khi debug).

## 5. Bảo mật

Ở giai đoạn Core, Eureka Dashboard (`http://localhost:8761`) không public ra ngoài — chỉ chạy trong internal Docker network, không map port ra ngoài ở môi trường production. Nếu cần bảo vệ dashboard ở dev, có thể thêm Spring Security Basic Auth đơn giản, nhưng không bắt buộc ở giai đoạn Core.

## 6. Test case bắt buộc

- Khởi động `eureka-server` trước, sau đó khởi động `account-service` → verify service xuất hiện trong danh sách registry (`GET /eureka/apps`)
- Tắt đột ngột 1 instance của `wallet-ledger-service` → verify sau `eviction-interval` cấu hình, instance đó biến mất khỏi registry và `api-gateway` không còn route request tới instance chết
- Chạy 2 instance của cùng 1 service (VD 2 instance `account-service`) → verify cả 2 cùng đăng ký, phục vụ test load balancing ở `api-gateway`

---

Service này không có business logic nên không cần bàn sâu hơn — sẵn sàng để bạn code trực tiếp theo cấu hình trên.
