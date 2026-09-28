package io.github.jungm.crema.internal.protocol;

import io.github.jungm.crema.internal.invoke.ContentEncoders;
import io.github.jungm.crema.internal.invoke.Mapping;
import io.github.jungm.crema.internal.security.AccessPolicy;

/**
 * What request handling needs besides the MCP Server itself.
 */
public record Services(Mapping mapping, ContentEncoders encoders, AccessPolicy access) {
}
