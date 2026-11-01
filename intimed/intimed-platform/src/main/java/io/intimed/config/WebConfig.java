package io.intimed.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Static entry points: console SPA and the reference host product page (EP-1 demo). */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry r) {
        r.addViewController("/host-demo/").setViewName("forward:/host-demo/index.html");
        r.addViewController("/host-demo").setViewName("redirect:/host-demo/");
    }
}
