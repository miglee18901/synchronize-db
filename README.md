# ToolSyncTonelist21mTo16m

Công cụ Java đồng bộ dữ liệu nhạc chờ từ CRBT21M (nguồn) sang CRBT16M (đích). Ứng dụng đọc `RBT_LOG` tăng dần theo `ID`, chỉ xử lý dữ liệu thành công từ các server nằm trong whitelist và lưu kết quả vào `TONELIST_SYNLOG`.

## Yêu cầu

- JDK 7
- Apache Maven 3.8 trở lên
- MySQL có hai schema CRBT21M và CRBT16M

Tài khoản CRBT21M cần quyền đọc `RBT_LOG`, `MAP_CP_RBT`, `TONELIST`. Tài khoản CRBT16M cần quyền đọc/ghi `MAP_CP_RBT`, `TONELIST`, `TONELIST_SYNLOG`.

## Cấu hình

Cấu hình kết nối nằm tại:

- `etc/hibernate_mysql_crbt21m.cfg.xml`: DB nguồn
- `etc/hibernate_mysql_crbt16m.cfg.xml`: DB đích

Không nên commit mật khẩu production vào Git.

Các tham số tại `etc/config.properties`:

```properties
BATCH_SIZE=1000
DELAY_TIME=1000
PERIOD_TIME=300000
SERVER_IP_WHITELIST=127.0.0.1,10.0.0.1
```

| Thuộc tính | Ý nghĩa |
| --- | --- |
| `BATCH_SIZE` | Số bản ghi tối đa trong một batch. |
| `DELAY_TIME` | Thời gian chờ trước lượt đầu tiên, đơn vị mili giây. |
| `PERIOD_TIME` | Chu kỳ chạy, đơn vị mili giây. |
| `SERVER_IP_WHITELIST` | Danh sách giá trị `RBT_LOG.SERVER`, phân cách bằng dấu phẩy. Giá trị rỗng không hợp lệ. |

`etc/offset.txt` lưu ID cuối cùng đã xử lý. Nếu file chưa tồn tại hoặc rỗng, offset bắt đầu từ `0`.

## Luồng xử lý

Ứng dụng truy vấn theo từng batch:

```sql
SELECT ID, TONE_ID, TONE_CODE, ACTION_TYPE, SERVER
FROM RBT_LOG
WHERE ID > :offset
  AND RESULT = 1
  AND ACTION_TYPE IN (1, 3)
  AND SERVER IN (:serverIpWhitelist)
ORDER BY ID ASC
```

Với từng bản ghi:

- `ACTION_TYPE = 3`: sao chép `MAP_CP_RBT`.
- `ACTION_TYPE = 1`: sao chép `MAP_CP_RBT`, sau đó sao chép `TONELIST`.
- Action khác `1` và `3` bị loại ngay khi đọc `RBT_LOG`; dữ liệu đã tồn tại ở đích được bỏ qua và vẫn được xem là xử lý thành công.
- Nếu thành công, insert `TONELIST_SYNLOG` với `DESCRIPTION = 'success'`, `STATE = 1`.
- Nếu lỗi, rollback dữ liệu của bản ghi rồi insert `TONELIST_SYNLOG` với chi tiết lỗi, `STATE = 0`.

`TONELIST_SYNLOG` nằm tại CRBT16M và cần các cột:

| Cột | Giá trị |
| --- | --- |
| `LOG_ID` | Khóa tự tăng, DB tự sinh. |
| `TONE_ID` | `RBT_LOG.TONE_ID`. |
| `TONE_CODE` | `RBT_LOG.TONE_CODE`. |
| `MOD_DATE` | Thời điểm ghi kết quả. |
| `DESCRIPTION` | `success` hoặc chỉ nội dung lỗi trả về từ quá trình đồng bộ. |
| `STATE` | `1` thành công, `0` lỗi. |

Ứng dụng không còn tạo file báo cáo lỗi. Log kỹ thuật vẫn được ghi theo `etc/log4j2.xml`.

Lưu ý: offset vẫn tiến qua bản ghi đồng bộ lỗi. Bản ghi lỗi sẽ không tự chạy lại; cần xử lý dựa trên `TONELIST_SYNLOG` và điều chỉnh offset nếu muốn đồng bộ lại.

## Build và chạy

```bash
mvn clean test
mvn clean package
```

Chạy class chính từ thư mục gốc dự án:

```text
org.example.sync.scheduler.SyncStart
```

File `03_sync_test_data.sql` cung cấp schema tối thiểu và dữ liệu kiểm thử thủ công.
