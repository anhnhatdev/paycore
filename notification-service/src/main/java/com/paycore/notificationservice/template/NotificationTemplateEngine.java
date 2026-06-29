package com.paycore.notificationservice.template;

import com.paycore.notificationservice.domain.enums.NotificationChannel;
import com.paycore.notificationservice.dto.RenderedMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class NotificationTemplateEngine {

    /**
     * Renders subject and body template according to eventType and payload.
     */
    public RenderedMessage render(
            String eventType,
            NotificationChannel channel,
            String recipient,
            String recipientMasked,
            Map<String, Object> payload
    ) {
        String templateCode = mapTemplateCode(eventType, channel);
        String subject;
        String body;

        Object amount = payload.getOrDefault("amount", "");
        Object currency = payload.getOrDefault("currency", "VND");
        Object txId = payload.getOrDefault("transactionId", payload.getOrDefault("id", "N/A"));
        Object reason = payload.getOrDefault("reason", payload.getOrDefault("errorReason", ""));

        switch (eventType) {
            case "TransactionCompleted":
                subject = "[PayCore] Giao dịch thành công";
                body = String.format("Giao dịch %s số tiền %s %s của bạn đã được thực hiện thành công.",
                        txId, amount, currency);
                break;

            case "TransactionFailed":
                subject = "[PayCore] Giao dịch thất bại";
                body = String.format("Giao dịch %s số tiền %s %s không thành công. Lý do: %s.",
                        txId, amount, currency, reason);
                break;

            case "TransactionCompensated":
                subject = "[PayCore BẢO MẬT] Giao dịch được hoàn tiền tự động";
                body = String.format("Giao dịch rút tiền %s số tiền %s %s đã được hoàn trả về tài khoản ví do lỗi hệ thống cổng thanh toán.",
                        txId, amount, currency);
                break;

            case "AccountFrozen":
                subject = "[PayCore BẢO MẬT KHẨN CẤP] Tài khoản của bạn đã bị đóng băng";
                body = String.format("Tài khoản của bạn đã bị tạm khóa để đảm bảo an toàn bảo mật. Lý do: %s. Vui lòng liên hệ bộ phận hỗ trợ khách hàng.",
                        reason != null && !reason.toString().isBlank() ? reason : "Phát hiện hoạt động bất thường");
                break;

            case "GatewayPaymentSuccess":
                subject = "[PayCore] Nạp tiền qua cổng thanh toán thành công";
                body = String.format("Giao dịch nạp tiền %s số tiền %s %s qua cổng thanh toán đã hoàn tất.",
                        txId, amount, currency);
                break;

            case "GatewayPaymentFailed":
                subject = "[PayCore] Nạp tiền qua cổng thanh toán thất bại";
                body = String.format("Giao dịch nạp tiền %s số tiền %s %s qua cổng thanh toán thất bại. Lý do: %s.",
                        txId, amount, currency, reason);
                break;

            case "GatewayPaymentExpired":
                subject = "[PayCore] Phiên thanh toán hết hạn";
                body = String.format("Yêu cầu nạp tiền %s số tiền %s %s đã hết hạn do quá thời gian chờ thanh toán.",
                        txId, amount, currency);
                break;

            case "FraudReviewApproved":
                subject = "[PayCore] Giao dịch đã được duyệt";
                body = String.format("Giao dịch %s đang trong hàng đợi kiểm tra rủi ro đã được quản trị viên phê duyệt thành công.",
                        txId);
                break;

            case "FraudReviewRejected":
                subject = "[PayCore] Giao dịch bị từ chối sau kiểm tra";
                body = String.format("Giao dịch %s đã bị từ chối sau quá trình đánh giá rủi ro chuyên sâu. Lý do: %s.",
                        txId, reason);
                break;

            default:
                subject = "[PayCore] Thông báo hệ thống";
                body = String.format("Sự kiện %s: %s", eventType, payload);
                break;
        }

        return RenderedMessage.builder()
                .channel(channel)
                .recipient(recipient)
                .recipientMasked(recipientMasked)
                .templateCode(templateCode)
                .subject(subject)
                .body(body)
                .build();
    }

    public String mapTemplateCode(String eventType, NotificationChannel channel) {
        return eventType.toUpperCase() + "_" + channel.name();
    }
}
