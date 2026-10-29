package com.wellnessz.notification.channel;

import com.wellnessz.notification.Channel;

/**
 * Delivery SPI. Production adapters: Azure Communication Services / Twilio (SMS), Firebase Cloud Messaging and
 * APNs (push), SMTP relay / Microsoft Graph sendMail / ACS Email (e-mail).
 */
public interface NotificationChannel {

    Channel channel();

    void send(String address, String body) throws Exception;
}
