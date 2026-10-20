package edu.tucn.dppprovver.exceptions;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
public class BlockchainRelatedException extends RuntimeException {
    private final String stage;
    private final String transactionId;
    private final String validationCode;

    public BlockchainRelatedException(String stage, String message, String txId,
                                      String validationCode, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.transactionId = txId;
        this.validationCode = validationCode;
    }

    public String getStage() {
        return stage;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getValidationCode() {
        return validationCode;
    }
}
