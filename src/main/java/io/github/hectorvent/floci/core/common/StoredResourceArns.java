package io.github.hectorvent.floci.core.common;

import java.util.Optional;

/**
 * Finds the ARN a service stores a resource under, for a type of the request-resources table whose service follows
 * the name a request gives with an id it mints ({@code connection/<name>/<id>}), which only the service knows.
 *
 * <p>The reader asks for every type of the namespace, not only for the ones whose ARN carries an id the service
 * mints, so a service may answer for a type whose ARN it does not mint: a policy store an alias names is the store
 * the alias points at.
 *
 * <p>Implemented by the service that owns such resources (EventBridge, for its connections and API destinations;
 * Verified Permissions, for the policy store an alias names and the alias's own ARN);
 * looked up lazily by {@code ResourceArnBuilder} through {@code Instance<StoredResourceArns>} for the namespace a
 * request is of, so IAM never depends on that service directly.</p>
 */
public interface StoredResourceArns {

    /** The namespace of the resource types it finds, as the table names it ({@code events}). */
    String namespace();

    /**
     * The ARN the caller's resource of the type, named {@code name} in the Region, is stored under; empty when there
     * is none, or the type is not one it finds.
     */
    Optional<String> find(String type, String name, String region);
}
