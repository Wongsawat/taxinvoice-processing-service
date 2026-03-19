package com.wpanther.taxinvoice.processing.domain.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Value Object representing a business party (seller or buyer) in a tax invoice.
 *
 * <p>Field nullability:
 * <ul>
 *   <li>{@code name} — required; enforced by the compact constructor.</li>
 *   <li>{@code taxIdentifier} — required by Thai e-Tax specification; the XML parser
 *       always provides a non-empty value. May be {@link Optional#empty()} only when a
 *       party is reconstructed from a legacy database row that predates the not-null
 *       constraint. Use {@link Optional#orElseThrow()} for the normal (non-legacy) path.</li>
 *   <li>{@code address} — {@code null} when the party's {@code PostalTradeAddress}
 *       element is absent or has no {@code CountryID} in the source XML (both optional
 *       per Thai e-Tax XSD), or when the party is reconstructed from a legacy database
 *       row predating address enforcement. Check for {@code null} before accessing.</li>
 *   <li>{@code email} — genuinely optional; use {@link #hasEmail()} before accessing.</li>
 * </ul>
 */
public record Party(
    String name,
    Optional<TaxIdentifier> taxIdentifier,
    Address address,
    String email
) implements Serializable {

    public Party {
        Objects.requireNonNull(name, "Party name is required");

        if (name.isBlank()) {
            throw new IllegalArgumentException("Party name cannot be blank");
        }

        // Normalise null to Optional.empty() so callers never receive a null Optional.
        taxIdentifier = taxIdentifier != null ? taxIdentifier : Optional.empty();
    }

    /**
     * Create party with minimal information
     */
    public static Party of(String name, TaxIdentifier taxIdentifier, Address address) {
        return new Party(name, Optional.ofNullable(taxIdentifier), address, null);
    }

    /**
     * Create party with all information
     */
    public static Party of(String name, TaxIdentifier taxIdentifier, Address address, String email) {
        return new Party(name, Optional.ofNullable(taxIdentifier), address, email);
    }

    /**
     * Check if party has email
     */
    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
