package com.wpanther.taxinvoice.processing.infrastructure.config;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Apache Camel ProducerTemplate bean.
 */
@Configuration
public class CamelProducerConfig {

    @Bean
    public ProducerTemplate producerTemplate(CamelContext camelContext) {
        return camelContext.createProducerTemplate();
    }
}
