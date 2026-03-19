# Tax Invoice Processing Service

Microservice for processing and enriching Thai e-Tax **tax invoice** data in a Saga-based orchestration pattern.

## Overview

The Tax Invoice Processing Service is responsible for:
- **Receiving** saga commands from the Saga Orchestrator via Kafka (using Apache Camel routes)
- **Parsing** XML tax invoices using the teda library v1.0.0
- **Enriching** tax invoice data with business logic
- **Calculating** totals, taxes, and other derived values
- **Publishing** saga replies and notification events to downstream services
- **Handling** compensation commands to rollback processing

## Key Difference from Invoice Processing Service

This service processes **tax invoices** (`TaxInvoice_CrossIndustryInvoice` XML type) while Invoice Processing Service processes regular invoices (`Invoice_CrossIndustryInvoice` XML type). Both use the teda library but with different JAXB packages:
- **Tax Invoice Processing**: Uses `taxinvoice.rsm.impl` and `taxinvoice.ram.impl` packages
- **Invoice Processing**: Uses `invoice.rsm.impl` and `invoice.ram.impl` packages

## Architecture

### Domain-Driven Design

This service follows DDD principles with hexagonal (ports and adapters) architecture:

- **Aggregates**: `ProcessedTaxInvoice` (root)
- **Value Objects**: `Money`, `Address`, `Party`, `LineItem`, `TaxIdentifier`
- **Ports (in)**: `ProcessTaxInvoiceUseCase`, `CompensateTaxInvoiceUseCase`
- **Ports (out)**: `ProcessedTaxInvoiceRepository`, `TaxInvoiceParserPort`, `SagaReplyPort`, `TaxInvoiceEventPublishingPort`
- **Adapters (in)**: `SagaCommandHandler`, `SagaRouteConfig` (Camel routes)
- **Adapters (out)**: `TaxInvoiceParserServiceImpl`, `ProcessedTaxInvoiceRepositoryImpl`, `SagaReplyPublisher`, `TaxInvoiceEventPublisher`

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
2. **Debezium CDC**: Monitors outbox table and publishes to Kafka
3. **Exactly-Once Delivery**: Guaranteed by PostgreSQL logical replication
4. **Saga Flow**: Service saves domain + outbox events in one transaction → Debezium captures changes → Saga reply published → Orchestrator continues

This pattern ensures:
- No events lost if service crashes after DB commit
- Atomic consistency between domain state and events
- Decoupling from external systems (Kafka)

### XML Parsing Security

The XML parser applies two safety guards before JAXB touches the payload:

1. **Size limit**: Rejects payloads larger than 500 KB (UTF-8) to prevent memory exhaustion
2. **Wall-clock timeout**: JAXB unmarshal runs in a virtual-thread executor; if parsing does not complete within `app.parsing.timeout-seconds` (default 10 s) the task is cancelled
3. **XXE / XML-bomb prevention**: Secure SAX parser with DOCTYPE disallowed, external entities disabled, and `FEATURE_SECURE_PROCESSING` applied at both factory and XMLReader level
4. **Concurrency cap**: A semaphore limits concurrent parses to `app.parsing.max-concurrent` (default 300) to prevent unbounded CPU contention under burst load

### Idempotency and Race Conditions

- **Pre-insert check**: `findBySourceInvoiceId` before processing detects duplicate commands
- **Partial-failure resume**: Invoices found in `PROCESSING` state are completed without re-parsing
- **Race-condition detection**: Concurrent inserts on `source_invoice_id` are detected via `SQLState 23505` + constraint name, then resolved by re-checking in a `REQUIRES_NEW` transaction
- **All timestamps stored in UTC**: `createdAt` and `completedAt` use `LocalDateTime.now(ZoneOffset.UTC)`

## Database Schema

### Tables

1. **processed_tax_invoices** — Main tax invoice data (optimistic locking via `version` column)
2. **tax_invoice_parties** — Seller and buyer information (`country` nullable per Thai e-Tax XSD)
3. **tax_invoice_line_items** — Tax invoice line items with quantities and prices
4. **outbox_events** — Transient Debezium CDC relay (not the compliance store; pruned after 7 days)

## Kafka Integration

All Kafka integration is handled via Apache Camel routes defined in `SagaRouteConfig.java`.

### Consumed Saga Commands

| Event | Topic | Description |
|-------|--------|-------------|
| `ProcessTaxInvoiceCommand` | `saga.command.tax-invoice` | Saga command to process tax invoice |
| `CompensateTaxInvoiceCommand` | `saga.compensation.tax-invoice` | Saga compensation to rollback processing |

### Published Events

| Event | Topic | Description |
|-------|--------|-------------|
| `TaxInvoiceReplyEvent` | `saga.reply.tax-invoice` | Saga reply (SUCCESS/FAILURE/COMPENSATED) |
| `TaxInvoiceProcessedEvent` | `taxinvoice.processed` | Tax invoice processing completed (notification) |

### Error Handling

Failed saga commands are routed to a Dead Letter Queue after 3 retries with exponential backoff:
- **DLQ Topic**: `taxinvoice.processing.dlq`
- **Retry Policy**: 1 s initial delay, 2× multiplier, max 10 s delay

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `taxinvoiceprocess_db` |
| `DB_USERNAME` | Database username | *(required — no default in production)* |
| `DB_PASSWORD` | Database password | *(required — no default in production)* |
| `KAFKA_BROKERS` | Kafka bootstrap servers | `localhost:9092` |
| `EUREKA_URL` | Eureka server URL | `http://localhost:8761/eureka/` |

### Application Properties

| Property | Description | Default |
|----------|-------------|---------|
| `app.parsing.timeout-seconds` | Maximum wall-clock time for XML unmarshal per message | `10` |
| `app.parsing.max-concurrent` | Semaphore cap on concurrent JAXB unmarshal tasks | `300` |
| `app.tax-invoice.default-due-date-days` | Days added to issue date when XML omits a due date | `30` |
| `app.outbox.cleanup.retention-days` | Days to retain published outbox events before deletion | `7` |
| `app.outbox.cleanup.cron` | Cron schedule for outbox cleanup job | `0 0 2 * * *` |

### Kafka Topics

Topic names are configured via `app.kafka.topics.*` properties:
- `app.kafka.topics.saga-command-tax-invoice` → `saga.command.tax-invoice`
- `app.kafka.topics.saga-compensation-tax-invoice` → `saga.compensation.tax-invoice`
- `app.kafka.topics.saga-reply-tax-invoice` → `saga.reply.tax-invoice`
- `app.kafka.topics.taxinvoice-processed` → `taxinvoice.processed`
- `app.kafka.topics.dlq` → `taxinvoice.processing.dlq`

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
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=taxinvoiceprocess_db
export DB_USERNAME=your_username
export DB_PASSWORD=your_password
export KAFKA_BROKERS=localhost:9092

mvn spring-boot:run
```

### Run with Docker

```bash
# Build image
docker build -t taxinvoice-processing-service:latest .

# Run container (TZ=Asia/Bangkok required for correct cron scheduling)
docker run -p 8088:8088 \
  -e TZ=Asia/Bangkok \
  -e DB_HOST=postgres \
  -e DB_PORT=5432 \
  -e DB_NAME=taxinvoiceprocess_db \
  -e DB_USERNAME=your_username \
  -e DB_PASSWORD=your_password \
  -e KAFKA_BROKERS=kafka:29092 \
  taxinvoice-processing-service:latest
```

## API Endpoints

This service is event-driven with no REST API endpoints. Only Spring Boot Actuator endpoints are available:

```bash
GET http://localhost:8088/actuator/health
GET http://localhost:8088/actuator/metrics
GET http://localhost:8088/actuator/prometheus
GET http://localhost:8088/actuator/camelroutes
```

## Development

### Project Structure

```
src/main/java/com/wpanther/taxinvoice/processing/
├── TaxInvoiceProcessingServiceApplication.java
├── domain/
│   ├── model/                  # Aggregate root (ProcessedTaxInvoice), value objects
│   ├── port/out/               # Repository and parser interfaces
│   └── event/                  # Domain events
├── application/
│   ├── port/in/                # Use case interfaces (process, compensate)
│   ├── port/out/               # Saga reply and event publishing interfaces
│   └── service/                # TaxInvoiceProcessingService
└── infrastructure/
    ├── adapter/in/messaging/   # SagaCommandHandler, SagaRouteConfig (Camel), DTOs
    ├── adapter/out/messaging/  # SagaReplyPublisher, TaxInvoiceEventPublisher, HeaderSerializer
    ├── adapter/out/parsing/    # TaxInvoiceParserServiceImpl (JAXB + security guards)
    ├── adapter/out/persistence/ # JPA entities, mapper, repositories, OutboxCleanupScheduler
    └── config/                 # KafkaTopicsProperties
```

### Database Migrations

Flyway migrations are located in `src/main/resources/db/migration/`.

```bash
mvn flyway:migrate
mvn flyway:info
```

## Integration with teda Library

This service uses the Thai e-Tax Invoice library (teda v1.0.0) for XML parsing and validation.

**CRITICAL**: The service expects XML documents with `TaxInvoice_CrossIndustryInvoice` root element and uses `taxinvoice` JAXB packages:
- `com.wpanther.etax.generated.taxinvoice.rsm.impl`
- `com.wpanther.etax.generated.taxinvoice.ram.impl`
- `com.wpanther.etax.generated.common.qdt.impl`
- `com.wpanther.etax.generated.common.udt.impl`

## Monitoring

### Custom Business Metrics (Prometheus)

| Metric | Description |
|--------|-------------|
| `taxinvoice.processing.success` | Successfully processed invoices |
| `taxinvoice.processing.failure` | Failed processing attempts |
| `taxinvoice.processing.idempotent` | Duplicate commands handled idempotently |
| `taxinvoice.processing.race_condition_resolved` | Concurrent inserts resolved via REQUIRES_NEW re-check |
| `taxinvoice.processing.duration` | End-to-end processing time (timer) |
| `taxinvoice.compensation.success` | Successful compensations |
| `taxinvoice.compensation.idempotent` | Duplicate compensation commands (already deleted) |
| `taxinvoice.compensation.failure` | Failed compensation attempts |
| `outbox.cleanup.failure` | Outbox cleanup job failures (sustained non-zero = table growing unbounded) |

All metrics are exposed at `/actuator/prometheus` and tagged with `application=taxinvoice-processing-service`.

### Logging

Structured logging is configured for application events, Camel route processing, Kafka message exchange, database operations, and error tracking. Enable SQL logging with the `dev` profile:

```bash
mvn spring-boot:run -Dspring.profiles.active=dev
```

## Testing

This service has **321 unit tests** (integration tests excluded by default).

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

# Stop containers
./scripts/test-containers-stop.sh
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

See [docs/INTEGRATION_TESTS.md](docs/INTEGRATION_TESTS.md) for detailed integration test documentation.

### Coverage

```bash
mvn verify
```

JaCoCo enforces **85% line coverage** per package. The build fails if coverage falls below this threshold.

## Event Flow

```
Saga Orchestrator
        │
        ▼
saga.command.tax-invoice
        │
        ▼
┌──────────────────────────────────┐
│  Tax Invoice Processing Service  │
│  (Apache Camel Routes)           │
│                                  │
│  Parse XML → Validate → Save DB  │
└───────────┬──────────────────────┘
            │
            ▼
┌──────────────────────────────────┐
│  outbox_events (Transactional)   │
└───────────┬──────────────────────┘
            │  Debezium CDC
            ▼
saga.reply.tax-invoice ──────────► Orchestrator
taxinvoice.processed ────────────► Notification Service
```

## License

MIT License
