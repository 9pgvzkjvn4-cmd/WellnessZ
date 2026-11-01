package io.intimed.config;

import io.intimed.common.Paths2;
import io.intimed.security.crypto.MasterKey;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Base64;

/**
 * Data source: for the embedded H2 store the file-encryption password and the user password are derived from the master
 * key (SC-10 encryption at rest in the LOCAL setting); for PostgreSQL ({@code postgres} profile) credentials come from
 * the environment / vault and TLS from the JDBC URL.
 */
@Configuration
public class DataSourceConfig {
    @Bean
    @Primary
    DataSource dataSource(DataSourceProperties props, Environment env) {
        String url = props.getUrl();
        if (url != null && url.startsWith("jdbc:h2:")) {
            Paths2.dir("db");
            String file = Base64.getUrlEncoder().withoutPadding().encodeToString(MasterKey.derive("h2-file"));
            String user = Base64.getUrlEncoder().withoutPadding().encodeToString(MasterKey.derive("h2-user"));
            props.setPassword(file + " " + user);
        }
        return props.initializeDataSourceBuilder().build();
    }
}
