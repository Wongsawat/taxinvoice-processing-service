# Design: Fix Race Condition Handler in TaxInvoiceProcessingService

**Date**: 2026-03-17
**Service**: `taxinvoice-processing-service`
**Approach**: Option B — Remove broken catch, propagate exception, add distinct log warning

---

## Problem

`TaxInvoiceProcessingService.saveWithIdempotencyHandling()` catches `DataIntegrityViolationException` and attempts to call `sagaReplyPort.publishSuccess()` afterward. This is broken because:

1. `DataIntegrityViolationException` is an unchecked exception that propagates through `ProcessedTaxInvoiceRepositoryImpl.save()`'s `@Transactional` proxy boundary.
2. Spring marks the **shared outer transaction** as `ROLLBACK_ONLY` when this exception exits the proxy.
3. The subsequent `publishSuccess()` call (which uses `MANDATORY` propagation) writes to the outbox inside an already-poisoned transaction.
4. The transaction rolls back — taking the outbox entry with it. The orchestrator never receives a reply. **The saga hangs indefinitely.**

This race condition window exists because the Camel consumer runs 3 parallel threads (`consumersCount=3`). In practice, it is near-impossible due to Kafka partition affinity (`correlationId` is the message key, routing same-saga commands to the same partition and consumer thread), but the code is still incorrect and must be fixed.

---

## Solution: Approach B

Remove `saveWithIdempotencyHandling()` entirely. Use a direct `invoiceRepository.save()` call. Add a distinct `WARN` log in the `CompletionException` catch block when the cause is `DataIntegrityViolationException`, to distinguish race-condition retries from other DB failures in logs. Let Camel's existing Dead Letter Channel handle the retry.

### Retry Flow (After Fix)

```
Thread B hits DataIntegrityViolationException
  → propagates as CompletionException out of processingTimer.record()
  → catch block logs WARN: "Race condition (duplicate key) for document {id}, Camel will retry"
  → publishFailure() called on clean transaction
  → TaxInvoiceProcessingException thrown
  → Camel DLC retries (up to 3x, 1s/2s/4s backoff)

On retry:
  → findBySourceInvoiceId(documentId) finds Thread A's committed record
  → publishSuccess() called cleanly in valid transaction
  → saga proceeds normally
```

### What Does NOT Change

- All other error paths (parse failure, unexpected runtime errors) are unchanged.
- Camel retry configuration is unchanged (`maximumRedeliveries=3`, `redeliveryDelay=1000ms`, `backOffMultiplier=2.0`, `maximumRedeliveryDelay=10000ms`).
- No changes to Camel routes, outbox, Flyway migrations, or integration tests.

---

## Files Changed

| File | Change |
|------|--------|
| `application/service/TaxInvoiceProcessingService.java` | Remove `saveWithIdempotencyHandling()` method. Replace call site with direct `invoiceRepository.save(invoice)`. Add `instanceof DataIntegrityViolationException` log in `CompletionException` catch block. Remove unused `import org.springframework.dao.DataIntegrityViolationException`. |
| `application/port/in/ProcessTaxInvoiceUseCase.java` | Remove sentence *"Race conditions (DataIntegrityViolationException) are treated as idempotent success."* from Javadoc. |
| `application/service/TaxInvoiceProcessingServiceTest.java` | Add `testProcessInvoiceForSagaDataIntegrityViolationPropagates` test. |

---

## New Test

**File**: `TaxInvoiceProcessingServiceTest.java`
**Method**: `testProcessInvoiceForSagaDataIntegrityViolationPropagates`

**Purpose**: Verify that `DataIntegrityViolationException` from `invoiceRepository.save()` propagates cleanly as `TaxInvoiceProcessingException` (so Camel can retry), that `publishFailure()` is called (saga doesn't hang), and that `eventPublisher.publish()` is never called (invoice not yet committed).

```java
@Test
void testProcessInvoiceForSagaDataIntegrityViolationPropagates() throws Exception {
    // Given - simulate race condition: idempotency check passes but insert conflicts
    when(invoiceRepository.findBySourceInvoiceId(anyString())).thenReturn(Optional.empty());
    when(parserService.parse(anyString(), anyString())).thenReturn(validInvoice);
    when(invoiceRepository.save(any(ProcessedTaxInvoice.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key: source_invoice_id"));

    // When / Then - exception propagates so Camel can retry
    assertThrows(ProcessTaxInvoiceUseCase.TaxInvoiceProcessingException.class,
        () -> service.process("intake-123", "<xml>test</xml>",
                              "saga-1", SagaStep.PROCESS_TAX_INVOICE, "correlation-123"));

    // Failure reply published so saga doesn't hang during retry window
    verify(sagaReplyPort).publishFailure(eq("saga-1"), eq(SagaStep.PROCESS_TAX_INVOICE),
        eq("correlation-123"), anyString());

    // Domain event never published (invoice not committed)
    verify(eventPublisher, never()).publish(any());
}
```

**Why this is sufficient**: The retry success path (finding an existing record on retry) is already covered by `testProcessInvoiceForSagaAlreadyProcessed`.

---

## Success Criteria

- [ ] `saveWithIdempotencyHandling()` method no longer exists
- [ ] `DataIntegrityViolationException` import removed from `TaxInvoiceProcessingService`
- [ ] Stale Javadoc sentence removed from `ProcessTaxInvoiceUseCase`
- [ ] New test passes
- [ ] All existing 288 tests continue to pass (`mvn test`)
- [ ] `mvn verify` passes (JaCoCo 85% coverage threshold met)
