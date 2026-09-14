package vn.system.app.modules.email.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EmailService {

    private final JavaMailSender javaMailSender;
    private final SpringTemplateEngine templateEngine;

    public EmailService(JavaMailSender javaMailSender,
            SpringTemplateEngine templateEngine) {
        this.javaMailSender = javaMailSender;
        this.templateEngine = templateEngine;
    }

    public void sendEmailSync(String to, String subject, String content,
            boolean isMultipart, boolean isHtml) {
        MimeMessage mimeMessage = this.javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper message = new MimeMessageHelper(mimeMessage,
                    isMultipart, StandardCharsets.UTF_8.name());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content, isHtml);
            this.javaMailSender.send(mimeMessage);
        } catch (MailException | MessagingException e) {
            log.error("Không thể gửi email tới {}: {}", to, e.getMessage());
        }
    }

    @Async
    public void sendEmailAsync(String to, String subject, String content, boolean isHtml) {
        this.sendEmailSync(to, subject, content, false, isHtml);
    }

    @Async
    public void sendEmailFromTemplateSync(
            String to,
            String subject,
            String templateName,
            String username,
            Object value) {

        Context context = new Context();
        context.setVariable("name", username);
        context.setVariable("jobs", value);

        String content = templateEngine.process(templateName, context);
        this.sendEmailSync(to, subject, content, false, true);
    }

    // ⭐ THÊM METHOD NÀY
    @Async
    public void sendTemplateEmail(String to, String subject, Map<String, Object> variables) {
        Context context = new Context();
        variables.forEach(context::setVariable);

        String content = templateEngine.process("otp", context);
        this.sendEmailSync(to, subject, content, false, true);
    }

    @Async
    public void sendShareTokenEmail(String to, String subject, Map<String, Object> variables) {
        try {
            MimeMessage mimeMessage = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());

            helper.setTo(to);
            helper.setSubject(subject);

            String qrCid = "qr-code-image";
            variables.put("qrCid", qrCid);



            Context context = new Context();
            variables.forEach(context::setVariable);
            String content = templateEngine.process("share-token", context);
            helper.setText(content, true);

            String qrBase64 = (String) variables.get("qrBase64");
            if (qrBase64 != null) {
                byte[] qrBytes = java.util.Base64.getDecoder().decode(qrBase64);
                helper.addInline(qrCid,
                        new org.springframework.core.io.ByteArrayResource(qrBytes),
                        "image/png");
            }

            javaMailSender.send(mimeMessage);
        } catch (Exception e) {
            log.error("Không thể gửi email chia sẻ tới {}: {}", to, e.getMessage());
        }
    }
}