package az.fitnest.order.grpc;

import az.fitnest.notifications.grpc.BroadcastLocalizedPushRequest;
import az.fitnest.notifications.grpc.BroadcastLocalizedPushResponse;
import az.fitnest.notifications.grpc.LocalizedPushContent;
import az.fitnest.notifications.grpc.NotificationsServiceGrpc;
import az.fitnest.notifications.grpc.SendPushNotificationRequest;
import az.fitnest.notifications.grpc.SendSimpleEmailRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationGrpcClient {

    @GrpcClient("notifications-backend")
    private NotificationsServiceGrpc.NotificationsServiceBlockingStub notificationsStub;

    public void sendPushNotification(Long userId, String title, String body) {
        try {
            SendPushNotificationRequest request = SendPushNotificationRequest.newBuilder()
                    .setUserId(userId)
                    .setTitle(title)
                    .setBody(body)
                    .build();
            notificationsStub.sendPushNotification(request);
            log.info("Push notification sent to user {}", userId);
        } catch (Exception e) {
            log.error("Failed to send push notification to user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Fan-out used by the campaign push. Field numbers match notifications-backend.
     *
     * @return users the notification service accepted, or -1 when the RPC failed
     */
    public int broadcastLocalizedPush(Map<String, TitleBody> contents, Map<String, String> data) {
        try {
            BroadcastLocalizedPushRequest.Builder request = BroadcastLocalizedPushRequest.newBuilder();
            if (contents != null) {
                contents.forEach((language, text) -> request.addContents(LocalizedPushContent.newBuilder()
                        .setLanguage(language)
                        .setTitle(text.title())
                        .setBody(text.body())
                        .build()));
            }
            if (data != null) {
                request.putAllData(data);
            }
            BroadcastLocalizedPushResponse response = notificationsStub
                    .withDeadlineAfter(60, TimeUnit.SECONDS)
                    .broadcastLocalizedPushNotification(request.build());
            if (!response.getSuccess()) {
                log.error("Localized broadcast rejected: {}", response.getErrorMessage());
                return -1;
            }
            return response.getTargetUsers();
        } catch (Exception e) {
            log.error("Localized broadcast failed: {}", e.getMessage());
            return -1;
        }
    }

    public record TitleBody(String title, String body) {
    }

    public void sendEmail(String to, String subject, String body) {
        try {
            SendSimpleEmailRequest request = SendSimpleEmailRequest.newBuilder()
                    .setTo(to)
                    .setSubject(subject)
                    .setBody(body)
                    .build();
            notificationsStub.sendSimpleEmail(request);
            log.info("Email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }
}
