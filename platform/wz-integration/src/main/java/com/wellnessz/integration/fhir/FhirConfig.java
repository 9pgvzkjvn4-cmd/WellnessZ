package com.wellnessz.integration.fhir;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;

/** A single shared R4 context (expensive to create, thread-safe). */
@Configuration
public class FhirConfig {

    @Bean
    FhirContext fhirContext() {
        FhirContext ctx = FhirContext.forR4();
        ctx.setParserErrorHandler(new StrictErrorHandler());
        return ctx;
    }

    static IParser jsonParser(FhirContext ctx) {
        return ctx.newJsonParser().setPrettyPrint(false);
    }
}
