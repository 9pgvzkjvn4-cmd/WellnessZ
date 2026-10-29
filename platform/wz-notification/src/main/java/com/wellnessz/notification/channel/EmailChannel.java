package com.wellnessz.notification.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.wellnessz.notification.Channel;

/** SMTP e-mail (STARTTLS enforced via spring.mail properties in non-local profiles). */
@Component
@ConditionalOnBean(JavaMailSender.class)
public class EmailChannel implements NotificationChannel {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailChannel(JavaMailSender mailSender, @Value("${wellnessz.notification.email.from:no-reply@wellnessz.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public void send(String address, String body) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(address);
        msg.setSubject("Occupational Health – notification");
        msg.setText(body);
        mailSender.send(msg);
    }
}
