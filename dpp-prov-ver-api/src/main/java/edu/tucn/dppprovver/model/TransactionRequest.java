package edu.tucn.dppprovver.model;

/**
 * Gateway invocation descriptor for the synchronous path (no queueing).
 *
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public record TransactionRequest(TxType txType, String function, String... args) {
}
