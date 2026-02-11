# Tax Invoice Processing Service

Microservice for processing and enriching Thai e-Tax **tax invoice** data in the Invoice Processing System.

## Overview

The Tax Invoice Processing Service is responsible for:

- **Receiving** validated tax invoices from the Document Intake Service via Kafka (using Apache Camel routes)
- **Parsing** XML tax invoices using the teda library v1.0.0
- **Enriching** tax invoice data with business logic
- **Calculating** totals, taxes, and other derived values
- **Publishing** processed tax invoice events to downstream services
- **Requesting** XML signing for processed invoices (PDF generation is triggered after XML signing)

**Recent Changes**: This service has been migrated from Spring Kafka to Apache Camel 4.14.4 for message routing and handling.

## Key Difference from Invoice Processing Service

This service processes **tax invoices** (`TaxInvoice_CrossIndustryInvoice` XML type) while the Invoice Processing Service processes regular invoices (`Invoice_CrossIndustryInvoice` XML type). Both use the teda library but with different JAXB packages:
- **Tax Invoice Processing**: Uses `taxinvoice.rsm.impl` and `taxinvoice.ram.impl` packages
- **Invoice Processing**: Uses `invoice.rsm.impl` and `invoice.ram.impl` packages

## Architecture

### Domain-Driven Design

This service follows DDD principles with:

- **Aggregates**: `ProcessedTaxInvoice` (root)
- **Value Objects**: `Money`, `Address`, `Party`, `LineItem`, `TaxIdentifier`
- **Domain Services**: `TaxInvoiceParserService`
- **Repositories**: `ProcessedTaxInvoiceRepository`

### Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Java 21 |
| Framework | Spring Boot 3.2.5 |
| Message Routing | Apache Camel 4.14.4 |
| Database | PostgreSQL |
| Messaging | Apache Kafka (via Camel) |
| Service Discovery | Netflix Eureka |
| Database Migration | Flyway |
| XML Parsing | teda Library v1.0.0 |
| Event Base Classes | saga-commons Library v1.0.0-SNAPSHOT |
| Event Delivery | Outbox Pattern with Debezium CDC |

### Outbox Pattern

This service uses the **Transactional Outbox Pattern** for reliable event delivery:

1. **Outbox Table**: `outbox_events` stores events atomically with domain changes
2. **Debezium CDC**: Monitors the outbox table and publishes to Kafka
3. **Exactly-Once Delivery**: Guaranteed by PostgreSQL logical replication

**Flow**: Service saves domain + outbox events in one transaction → Debezium captures changes → Events published to Kafka → Downstream services consume

This pattern ensures:
- No events lost if service crashes after DB commit
- Atomic consistency between domain state and events
- Decoupling from external systems (Kafka)

## Database Schema

### Tables

1. **processed_tax_invoices** - Main tax invoice data
2. **tax_invoice_parties** - Seller and buyer information
3. **tax_invoice_line_items** - Tax invoice line items with quantities and prices
4. **outbox_events** - Transactional outbox for reliable event delivery (CDC)

## Kafka Integration

All Kafka integration is handled via Apache Camel routes defined in `TaxInvoiceRouteConfig.java`.

### Consumed Events

| Event | Topic | Description |
|-------|-------|-------------|
| `TaxInvoiceReceivedEvent` | `document.received.tax-invoice` | Tax invoice validated by Document Intake Service |

### Published Events

| Event | Topic | Description |
|-------|-------|-------------|
| `TaxInvoiceProcessedEvent` | `taxinvoice.processed` | Tax invoice processing completed |
| `XmlSigningRequestedEvent` | `xml.signing.requested` | Request XML signing (XAdES) |

### Error Handling

Failed events are routed to a Dead Letter Queue after 3 retries with exponential backoff:
- **DLQ Topic**: `taxinvoice.processing.dlq`
- **Retry Policy**: 1s initial delay, 2x multiplier, max 10s delay

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `taxinvoiceprocess_db` |
| `DB_USERNAME` | Database username | `postgres` |
| `DB_PASSWORD` | Database password | `postgres` |
| `KAFKA_BROKERS` | Kafka bootstrap servers | `localhost:9092` |
| `EUREKA_URL` | Eureka server URL | `http://localhost:8761/eureka/` |

## Running the Service

### Prerequisites

1. PostgreSQL database running
2. Kafka broker running
3. Eureka server running (optional)
4. Thai e-Tax Invoice library (teda v1.0.0) installed locally
5. saga-commons library v1.0.0-SNAPSHOT installed locally

### Build

```bash
# Build dependencies first
cd ../../../teda && mvn clean install
cd ../../../saga-commons && mvn clean install

# Build this service
cd services/taxinvoice-processing-service
mvn clean package
```

### Run Locally

```bash
# Set environment variables
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=taxinvoiceprocess_db
export DB_USERNAME=postgres
export DB_PASSWORD=your_password
export KAFKA_BROKERS=localhost:9092

# Run application
mvn spring-boot:run
```

### Run with Docker

```bash
# Build image
docker build -t taxinvoice-processing-service:latest .

# Run container
docker run -p 8088:8088 \
  -e DB_HOST=postgres \
  -e DB_PORT=5432 \
  -e DB_NAME=taxinvoiceprocess_db \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e KAFKA_BROKERS=kafka:29092 \
  taxinvoice-processing-service:latest
```

## API Endpoints

This service is event-driven with no REST API endpoints. Only Spring Boot Actuator endpoints are available:

### Health Check

```bash
GET http://localhost:8088/actuator/health
```

### Metrics

```bash
GET http://localhost:8088/actuator/metrics
GET http://localhost:8088/actuator/prometheus
```

### Camel Routes Monitoring

```bash
GET http://localhost:8088/actuator/camelroutes
```

## Development

### Project Structure

```
src/main/java/com/wpanther/taxinvoice/processing/
├── TaxInvoiceProcessingServiceApplication.java
├── domain/
│   ├── model/              # Domain models (aggregates, value objects)
│   ├── repository/         # Repository interfaces
│   ├── service/            # Domain services
│   └── event/              # Integration events
├── application/
│   └── service/            # Application services
└── infrastructure/
    ├── persistence/        # JPA entities, repositories
    ├── messaging/          # EventPublisher (uses Camel ProducerTemplate)
    ├── service/            # TaxInvoiceParserServiceImpl (teda XML parsing)
    └── config/             # Spring configuration, Camel routes
```

### Database Migrations

Flyway migrations are located in `src/main/resources/db/migration/`.

```bash
# Run migrations
mvn flyway:migrate

# Check migration status
mvn flyway:info
```

## Integration with teda Library

This service uses the Thai e-Tax Invoice library (teda v1.0.0) for:

- XML parsing and validation
- JAXB class generation
- Database-backed code lists

**CRITICAL**: The service expects XML documents with the `TaxInvoice_CrossIndustryInvoice` root element and uses the `taxinvoice` JAXB packages:
- `com.wpanther.etax.generated.taxinvoice.rsm.impl`
- `com.wpanther.etax.generated.taxinvoice.ram.impl`

## Monitoring

### Metrics

The service exposes Prometheus metrics at `/actuator/prometheus`:

- `camel.*` - Apache Camel route metrics
- `jvm_memory_used_bytes` - JVM memory usage
- Custom business metrics

### Logging

Structured logging is configured for:

- Application events
- Apache Camel route processing
- Kafka message exchange
- Database operations
- Error tracking

## Testing

This service has **261 tests** in total:
- **248 unit tests** - Framework-independent tests with mocked dependencies
- **7 Kafka consumer integration tests** - End-to-end tests through Apache Camel routes
- **6 CDC (Debezium) integration tests** - Outbox pattern event delivery verification

### Unit Tests

```bash
mvn test
```

### Integration Tests

Integration tests require Docker containers for PostgreSQL, Kafka, and optionally Debezium.

#### Start Test Containers

```bash
cd /home/wpanther/projects/etax/invoice-microservices

# Start PostgreSQL + Kafka (for Kafka consumer tests)
./scripts/test-containers-start.sh

# Start PostgreSQL + Kafka + Debezium (for CDC tests)
./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors
```

#### Run Integration Tests

```bash
cd services/taxinvoice-processing-service

# Run Kafka consumer integration tests only
mvn test -Pintegration -Dtest=KafkaConsumerIntegrationTest

# Run CDC integration tests only
mvn test -Pintegration -Dtest=TaxInvoiceCdcIntegrationTest

# Run all integration tests
mvn test -Pintegration
```

**Note**: See [docs/INTEGRATION_TESTS.md](docs/INTEGRATION_TESTS.md) for detailed integration test documentation.

### Coverage

```bash
mvn verify
```

JaCoCo enforces 80% line coverage per package. The build will fail if coverage is below threshold.

## Event Flow

```
Document Intake Service
        │
        ▼
   (document.received.tax-invoice)
        │
        ▼
┌─────────────────────────────┐
│  Tax Invoice Processing     │
│  Service                    │
│  (Apache Camel Routes)      │
│                             │
│  1. Parse XML (teda 1.0.0)  │
│  2. Validate                │
│  3. Calculate totals        │
│  4. Save to DB              │
└──────────┬──────────────────┘
           │
           ├──▶ (taxinvoice.processed) ──▶ Notification Service
           │
           └──▶ (xml.signing.requested) ──▶ XML Signing Service
                                              │
                                              ▼
                                        (xml.signed.tax-invoice)
                                              │
                                              ▼
                                      Tax Invoice PDF Generation Service
```

**Message Routing**: All Kafka integration is handled via Apache Camel routes defined in `TaxInvoiceRouteConfig.java`. Failed messages are routed to a Dead Letter Queue (`taxinvoice.processing.dlq`) after 3 retries with exponential backoff.

## License

MIT License

## Contact

Maintained by wpanther (rabbit_roger@yahoo.com)
