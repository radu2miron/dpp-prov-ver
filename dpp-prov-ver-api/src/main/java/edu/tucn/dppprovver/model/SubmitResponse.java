package edu.tucn.dppprovver.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Synchronous Fabric VALID commit; semanticOutcome is independent of the commit status.
 *
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public record SubmitResponse(String message, String id, String transactionId,
                             String fabricStatus, String semanticOutcome,
                             JsonNode result, long durationMs) {
}
