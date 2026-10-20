package edu.tucn.dppprovver.model;

/**
 * Returned only after a successful Fabric commit for SUBMIT operations.
 *
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public record GatewayResult(String payload, String transactionId,
                            String fabricStatus, long durationMs) {
}
