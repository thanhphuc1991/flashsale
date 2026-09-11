# FlashSale Service

Backend service cho user authentication và flash sale — Java 21 + Spring Boot 3.3, H2 (file-based).

## 1. Kiến trúc tổng quan

**Modular monolith**, chia 3 module rõ ràng theo package:

- `auth` — register/login/logout, OTP verification, JWT + refresh token
- `flashsale` — quản lý window/item bằng DB, mua hàng với concurrency control, transactional outbox
- `inventory` — read model tồn kho, đồng bộ qua scheduled job trong cùng process (idempotent)

Toàn bộ chạy **không cần Docker, không cần cài DB server riêng**: DB là H2 file-based (`./data/flashsaledb`), đồng bộ tồn kho chạy bằng `@Scheduled` job trong cùng JVM thay vì message broker ngoài. Docker Compose vẫn có sẵn (đáp ứng yêu cầu đề bài), nhưng không bắt buộc cho dev hằng ngày.

```
Client → [Spring Boot app instance, stateless]
              └─ H2 file DB (users, flash sale, purchase, outbox, inventory)
                    └─ scheduled job đọc outbox mỗi 500ms, áp dụng vào inventory
```

## 2. Xử lý concurrency (phần quan trọng nhất)

Yêu cầu bắt buộc: (1) không bán vượt số lượng cấu hình, (2) mỗi user chỉ mua 1 sản phẩm/ngày, (3) đúng khi nhiều request đồng thời.

Cả 3 được enforce **ở tầng database**, không phải chỉ ở application code — đây là điểm mấu chốt để chạy đúng trên multi-instance (nhiều JVM không thể chia sẻ lock trong memory với nhau):

1. **Không bán vượt số lượng**: `FlashSaleItemRepository.tryReserveStock()` là một câu `UPDATE flash_sale_item SET quantity_sold = quantity_sold + 1 WHERE id = ? AND quantity_sold < quantity_limit` duy nhất, atomic. DB tự lock row trong lúc UPDATE, nên nhiều request đồng thời cho cùng 1 item bị serialize bởi chính DB — không cần lock ứng dụng. Có thêm `@Check(constraints = "quantity_sold <= quantity_limit")` ở entity làm lưới an toàn cuối.
2. **1 sản phẩm/ngày/user**: `@UniqueConstraint` trên `(user_id, purchase_date)` ở entity `Purchase`. Có pre-check ở service để trả lỗi thân thiện nhanh, nhưng constraint DB mới là guarantee thật khi 2 request của cùng user race nhau.
3. **Idempotency**: `unique = true` trên `idempotency_key` (client-generated) — client retry sau timeout không tạo double purchase / double trừ kho.

Có test chứng minh: `FlashSaleConcurrencyTest` bắn 100 thread mua đồng thời 1 item chỉ có 10 stock (H2 in-memory), assert đúng 10 giao dịch thành công.

> **Lưu ý kỹ thuật**: H2 dùng để đơn giản hóa việc chạy/test local, không cần Docker. Toàn bộ query đều là SQL chuẩn/JPA thuần (không có cú pháp riêng của H2), nên đổi sang Postgres cho production chỉ là đổi datasource + thêm driver dependency, không phải viết lại logic.

## 3. Đồng bộ tồn kho

**Transactional Outbox pattern**: khi mua hàng thành công, event `PURCHASE_COMPLETED` được ghi vào bảng `outbox_event` **trong cùng transaction** với việc trừ `quantity_sold` — nên không bao giờ mất event hay tạo event cho giao dịch chưa thực sự commit.

`InventorySyncProcessor` (`@Scheduled`, chạy mỗi 500ms, cùng JVM) đọc các row `PENDING` và áp dụng vào bảng `inventory` (read model), **idempotent** bằng bảng `processed_event` (khóa chính suy ra từ `outbox_event.id`) — nếu job này retry hoặc app restart giữa chừng, event đã xử lý sẽ không bị áp dụng lại lần 2, đảm bảo "không xử lý trùng lặp, dữ liệu nhất quán".

Thiết kế này là bản đơn giản hóa của outbox + Kafka: nếu cần scale ra nhiều instance thật với message broker, chỉ cần thay `InventorySyncProcessor` bằng 1 Kafka producer + 1 consumer riêng — bảng `outbox_event` đóng vai trò contract không đổi, phần còn lại của hệ thống không cần sửa.

## 4. Đáp ứng tải cao (≥500 TPS)

- **1 round-trip DB cho request nóng nhất**: `tryReserveStock()` là 1 câu `UPDATE` điều kiện duy nhất, không phải SELECT-rồi-UPDATE (2 round-trip + cần lock ứng dụng để tránh race giữa 2 bước đó).
- **Index trên mọi truy vấn hay chạy nhất** (khai báo qua `@Table(indexes = ...)` ở entity, không phải chỉ dựa vào PK):
  - `flash_sale_window(start_time, end_time)` — backing `GET /flash-sale/current`, endpoint bị gọi nhiều nhất hệ thống.
  - `flash_sale_item(window_id)` — backing cùng endpoint đó khi lấy danh sách item theo window.
  - `otp_verification(identifier, purpose, consumed)` — chạy mỗi lần verify OTP.
  - `outbox_event(status, created_at)` — `InventorySyncProcessor` poll `WHERE status = 'PENDING'` mỗi 500ms, bảng này lớn dần theo số đơn hàng nên cực kỳ cần index.
  - `purchase(user_id, purchase_date)` và `idempotency_key`, `refresh_tokens.token_hash`, `users.identifier` đã tự có index vì đều khai báo `unique = true`.
- **Stateless (JWT, không session server-side)** — scale ngang tự do bằng cách thêm instance sau load balancer, không cần sticky session hay cache session dùng chung.
- **`open-in-view: false`** — connection trả về pool ngay sau khi transaction service kết thúc, không giữ suốt vòng đời request/response (giữ lâu = nghẽn connection pool khi tải cao).
- **Outbox tách rời khỏi critical path**: API mua hàng trả response ngay sau khi ghi outbox row, không chờ xử lý đồng bộ tồn kho xong — latency của request mua hàng không phụ thuộc tốc độ xử lý background job.
- **`GET /flash-sale/current` không qua JWT filter** — bỏ được chi phí parse/verify token cho endpoint bị gọi nhiều nhất (client thường poll liên tục để cập nhật sản phẩm đang sale).
- **HikariCP pool** cấu hình sẵn (`maximum-pool-size: 30`), cần benchmark thực tế và tune theo tải + số CPU core của máy chạy production.

> Muốn đo thật: viết thêm load test (k6/Gatling/JMeter) bắn liên tục vào `POST /flash-sale/purchase`, theo dõi p99 latency + throughput. README này liệt kê các quyết định thiết kế nhắm tới mục tiêu 500 TPS, nhưng con số cụ thể phụ thuộc phần cứng chạy thật — nên trình bày rõ điều này khi present, tránh cam kết số liệu chưa benchmark.

## 5. Chạy project local

**Không cần Docker.** Chỉ cần Java 21 + Maven:

```bash
mvn spring-boot:run
```

App chạy ở `http://localhost:8080`. Schema tự tạo bởi Hibernate (`ddl-auto: update`) khi app khởi động lần đầu, kèm seed data demo (1 flash sale window đang active với 3 sản phẩm) để test ngay không cần insert tay — file DB nằm ở `./data/flashsaledb.mv.db`, tồn tại giữa các lần restart.

Xem trực tiếp dữ liệu qua H2 Console: `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:file:./data/flashsaledb`, user `sa`, password để trống).

Health check: `GET http://localhost:8080/actuator/health`

### Chạy bằng Docker (tùy chọn)

Nếu muốn đóng gói chạy container (không cần cài Java/Maven):

```bash
docker-compose up --build
```

### Chạy test

```bash
mvn test
```

Không cần Docker — toàn bộ test dùng H2 in-memory (mỗi test class 1 DB riêng, tự dọn khi JVM thoát).

**Test coverage:**

| Test class | Bao phủ |
|---|---|
| `FlashSaleConcurrencyTest` | 100 buyer mua đồng thời 1 item chỉ có 10 stock (H2 in-memory) → đúng 10 giao dịch thành công, không bán vượt |
| `FlashSalePurchaseIntegrationTest` | Full HTTP + DB thật: mua thành công, giới hạn 1 lần/ngày (kể cả khác sản phẩm), retry cùng idempotency key không bị bán trùng, endpoint yêu cầu authentication |
| `FlashSaleServiceTest` | Unit test business rule: sold-out, window chưa bắt đầu/đã kết thúc, đã mua trong ngày, duplicate idempotency key |
| `InventoryServiceTest` | Idempotency của inventory sync — apply cùng event 2 lần không bị trừ kho 2 lần |
| `InventorySyncProcessorTest` | Outbox event PENDING được áp dụng và đánh dấu PUBLISHED; payload lỗi bị đánh dấu FAILED thay vì crash cả batch |
| `AuthServiceTest` | Phân biệt email/phone, register trùng bị từ chối generic, login sai mật khẩu / tài khoản chưa verify, không lộ thông tin (cùng error code cho "sai mật khẩu" và "không tồn tại") |
| `OtpServiceTest` | OTP hết hạn, sai mã, khóa sau nhiều lần thử sai, verify thành công |

## 6. API chính

### Auth

| Method | Path | Mô tả |
|---|---|---|
| POST | `/api/v1/auth/register` | `{identifier, password}` — identifier là email hoặc phone (tự detect qua regex). Trả OTP mock qua log. |
| POST | `/api/v1/auth/register/verify-otp` | `{identifier, otpCode}` — xác thực OTP, kích hoạt tài khoản |
| POST | `/api/v1/auth/login` | `{identifier, password}` → `{accessToken, refreshToken, expiresInSeconds}` |
| POST | `/api/v1/auth/refresh` | `{refreshToken}` → cặp token mới (refresh token cũ bị revoke — rotation) |
| POST | `/api/v1/auth/logout` | `{refreshToken}` → revoke refresh token |

### Flash Sale

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/v1/flash-sale/current` | Public — danh sách sản phẩm đang trong flash sale tại thời điểm hiện tại |
| POST | `/api/v1/flash-sale/purchase` | Cần `Authorization: Bearer <accessToken>`. `{itemId, idempotencyKey}` |

## 7. Giả định (assumptions)

- User đã có sẵn balance trong hệ thống (theo đề bài) — cột `balance` trên bảng `users` được seed sẵn 1,000,000, chưa trừ tiền thực tế khi mua (out of scope theo đề, có thể mở rộng thêm bước trừ balance atomic tương tự cách trừ stock).
- OTP mock gửi qua log + lưu DB, không tích hợp SMS/email provider thật.
- "1 sản phẩm/ngày" hiểu là 1 lượt mua flash sale/ngày (không phân biệt theo từng window/sản phẩm khác nhau trong cùng ngày), theo đúng câu chữ đề bài.
- **DB chọn H2 file-based thay vì Postgres để đơn giản hóa việc chạy/chấm bài (không cần cài/khởi động DB server riêng).** Toàn bộ SQL dùng cú pháp chuẩn/JPA thuần, không phụ thuộc tính năng riêng của H2, nên đổi sang Postgres cho production chỉ cần đổi 4 dòng datasource trong `application.yml` + thêm driver dependency.
- **Đồng bộ tồn kho chạy in-process (`@Scheduled`) thay vì qua Kafka**, cùng lý do trên (không cần broker/Docker). Outbox table vẫn giữ nguyên contract — swap sang Kafka producer/consumer thật khi cần scale multi-instance, phần business logic không đổi.
- Access token JWT 15 phút, refresh token 7 ngày (lưu hash trong DB để revoke được).

## 8. Hướng mở rộng

- Thêm sản phẩm mới / mở rộng điều kiện flash sale: chỉ cần insert row vào `flash_sale_window` / `flash_sale_item`, không cần deploy lại (toàn bộ config nằm trong DB).
- Thêm business rule mới (ví dụ giới hạn theo số lượng/user thay vì 1/ngày): sửa unique constraint + logic check trong `FlashSaleService`, các phần còn lại không đổi.
- Scale thật ra nhiều instance + DB server riêng: đổi datasource sang Postgres (xem mục 7), thay `InventorySyncProcessor` bằng Kafka producer/consumer nếu cần throughput cao hơn 1 process xử lý outbox.
- Rate limiting cho login/OTP endpoint (chưa có trong bản này, nên thêm bằng Bucket4j hoặc API Gateway ở production).
- Trừ balance atomic tương tự cách trừ stock nếu cần tích hợp thanh toán thật.
- Thêm cache (Redis/Caffeine) cho `GET /flash-sale/current` nếu benchmark cho thấy đây là bottleneck thật sự dưới tải cao.

