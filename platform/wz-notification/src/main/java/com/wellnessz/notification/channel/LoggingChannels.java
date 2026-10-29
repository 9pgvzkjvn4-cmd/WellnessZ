package com.wellnessz.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.wellnessz.notification.Channel;

/** Development stand-ins for SMS and push gateways. They log metadata only, never the address or body. */
@Configuration
@ConditionalOnProperty(name = "wellnessz.notification.dev-channels", havingValue = "true", matchIfMissing = true)
public class LoggingChannels {

    private static final Logger log = LoggerFactory.getLogger(LoggingChannels.class);

    @Bean
    NotificationChannel smsLoggingChannel() {
        return logging(Channel.SMS);
    }

    @Bean
    NotificationChannel pushLoggingChannel() {
        return logging(Channel.PUSH);
    }

    private static NotificationChannel logging(Channel channel) {
        return new NotificationChannel() {
            @Override
            public Channel channel() {
                return channel;
            }

            @Override
            public void send(String address, String body) {
                log.info("[dev] {} notification dispatched ({} chars)", channel, body.length());
            }
        };
    }
}
