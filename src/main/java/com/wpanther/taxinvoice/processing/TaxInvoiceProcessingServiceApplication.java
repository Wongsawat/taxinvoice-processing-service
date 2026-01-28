package com.wpanther.taxinvoice.processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Tax Invoice Processing Service - Main Application
 *
 * This microservice processes validated tax invoices, enriches data,
 * calculates totals, and requests PDF generation.
 *
 * Key Features:
 * - Consumes TaxInvoiceReceivedEvent from Kafka
 * - Parses XML tax invoices using teda library (TaxInvoice_CrossIndustryInvoice)
 * - Applies business logic and calculations
 * - Publishes TaxInvoiceProcessedEvent
 * - Requests PDF generation via events
 *
 * @author wpanther
 * @version 1.0.0
 */
@SpringBootApplication
@EnableKafka
@EnableDiscoveryClient
@EnableTransactionManagement
public class TaxInvoiceProcessingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaxInvoiceProcessingServiceApplication.class, args);
    }
}
