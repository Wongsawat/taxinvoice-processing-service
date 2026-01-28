# Tax Invoice Processing Service

Microservice for processing and enriching Thai e-Tax **tax invoice** data in the Invoice Processing System.

## Overview

The Tax Invoice Processing Service is responsible for:

- **Receiving** validated tax invoices from the Document Intake Service via Kafka
- **Parsing** XML tax invoices using the teda library v1.0.0
- **Enriching** tax invoice data with business logic
- **Calculating** totals, taxes, and other derived values
- **Publishing** processed tax invoice events
- **Requesting** XML signing for downstream services

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
| Database | PostgreSQL |
| Messaging | Apache Kafka |
| Service Discovery | Netflix Eureka |
| Database Migration | Flyway |
| XML Parsing | teda Library v1.0.0 |

## Database Schema

### Tables

1. **processed_tax_invoices** - Main tax invoice data
2. **tax_invoice_parties** - Seller and buyer information
3. **tax_invoice_line_items** - Tax invoice line items with quantities and prices

## Kafka Integration

### Consumed Events

| Event | Topic | Description |
|-------|-------|-------------|
| `TaxInvoiceReceivedEvent` | `document.received.tax-invoice` | Tax invoice validated by Document Intake Service |

### Published Events

| Event | Topic | Description |
|-------|-------|-------------|
| `TaxInvoiceProcessedEvent` | `taxinvoice.processed` | Tax invoice processing completed |
| `XmlSigningRequestedEvent` | `xml.signing.requested` | Request XML signing (XAdES) |

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

### Build

```bash
# Build teda library first
cd ../../../teda && mvn clean install

# Build this service
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

### Health Check

```bash
GET http://localhost:8088/actuator/health
```

### Metrics

```bash
GET http://localhost:8088/actuator/metrics
GET http://localhost:8088/actuator/prometheus
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
    ├── messaging/          # Kafka consumers, publishers
    └── config/             # Configuration classes
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

- `kafka_consumer_fetch_manager_records_lag` - Consumer lag
- `jvm_memory_used_bytes` - JVM memory usage
- Custom business metrics

### Logging

Structured logging is configured for:

- Application events
- Kafka message processing
- Database operations
- Error tracking

## Testing

### Unit Tests

```bash
mvn test
```

### Integration Tests

```bash
mvn verify
```

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
│                             │
│  1. Parse XML (teda 1.0.0)  │
│  2. Validate                │
│  3. Calculate totals        │
│  4. Save to DB              │
└──────────┬──────────────────┘
           │
           ▼
    (taxinvoice.processed)
           │
           ├──▶ Notification Service
           │
           ▼
   (xml.signing.requested)
           │
           ▼
    XML Signing Service
```

## License

MIT License

## Contact

Maintained by wpanther (rabbit_roger@yahoo.com)
