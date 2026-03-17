package com.wpanther.taxinvoice.processing.domain.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Value Object representing a business party (seller or buyer) in a tax invoice.
 *
 * <p>Field nullability:
 * <ul>
 *   <li>{@code name} — required; enforced by the compact constructor.</li>
 *   <li>{@code taxIdentifier} — required by Thai e-Tax specification; the XML parser
 *       always provides a non-null value. May be {@code null} only when a party is
 *       reconstructed from a legacy database row that predates the not-null constraint.</li>
 *   <li>{@code address} — same semantics as {@code taxIdentifier}.</li>
 *   <li>{@code email} — genuinely optional; use {@link #hasEmail()} before accessing.</li>
 * </ul>
 */
public record Party(
    String name,
    TaxIdentifier taxIdentifier,
    Address address,
    String email
) implements Serializable {

    public Party {
        Objects.requireNonNull(name, "Party name is required");

        if (name.isBlank()) {
            throw new IllegalArgumentException("Party name cannot be blank");
        }
    }

    /**
     * Create party with minimal information
     */
    public static Party of(String name, TaxIdentifier taxIdentifier, Address address) {
        return new Party(name, taxIdentifier, address, null);
    }

    /**
     * Create party with all information
     */
    public static Party of(String name, TaxIdentifier taxIdentifier, Address address, String email) {
        return new Party(name, taxIdentifier, address, email);
    }

    /**
     * Check if party has email
     */
    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
