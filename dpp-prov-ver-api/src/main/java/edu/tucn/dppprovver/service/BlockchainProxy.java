package edu.tucn.dppprovver.service;

import edu.tucn.dppprovver.exceptions.BlockchainRelatedException;
import edu.tucn.dppprovver.model.GatewayResult;
import edu.tucn.dppprovver.model.TransactionRequest;
import edu.tucn.dppprovver.model.TxType;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import jakarta.annotation.PreDestroy;
import org.hyperledger.fabric.client.*;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.cert.CertificateException;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Thread-shared synchronous Fabric Gateway connection; lazily opened on first request.
 *
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Component
public final class BlockchainProxy {
    @Value("${blockchain.msp-id}")
    private String mspId;
    @Value("${blockchain.channel-name}")
    private String channelName;
    @Value("${blockchain.chaincode-name}")
    private String chaincodeName;
    @Value("${blockchain.crypto-path}")
    private String cryptoPath;
    @Value("${blockchain.cert-path}")
    private String certPath;
    @Value("${blockchain.key-dir-path}")
    private String keyDirPath;
    @Value("${blockchain.tls-cert-path}")
    private String tlsCertPath;
    @Value("${blockchain.peer-endpoint}")
    private String peerEndpoint;
    @Value("${blockchain.override_authority}")
    private String overrideAuthority;

    private volatile Gateway gateway;
    private ManagedChannel channel;

    private Contract contract() {
        if (gateway == null) {
            synchronized (this) {
                if (gateway == null) connect();
            }
        }
        return gateway.getNetwork(channelName).getContract(chaincodeName);
    }

    private void connect() {
        try {
            Path root = Path.of(cryptoPath);
            var credentials = TlsChannelCredentials.newBuilder()
                    .trustManager(root.resolve(tlsCertPath).toFile()).build();
            channel = Grpc.newChannelBuilder(peerEndpoint, credentials)
                    .overrideAuthority(overrideAuthority).build();
            try (var certificateReader = Files.newBufferedReader(root.resolve(certPath));
                 var keyReader = Files.newBufferedReader(privateKey(root.resolve(keyDirPath)))) {
                var identity = new X509Identity(mspId,
                        Identities.readX509Certificate(certificateReader));
                Signer signer = Signers.newPrivateKeySigner(Identities.readPrivateKey(keyReader));
                gateway = Gateway.newInstance().identity(identity).signer(signer).connection(channel)
                        .evaluateOptions(opts -> opts.withDeadlineAfter(10, TimeUnit.SECONDS))
                        .endorseOptions(opts -> opts.withDeadlineAfter(30, TimeUnit.SECONDS))
                        .submitOptions(opts -> opts.withDeadlineAfter(15, TimeUnit.SECONDS))
                        .commitStatusOptions(opts -> opts.withDeadlineAfter(90, TimeUnit.SECONDS))
                        .connect();
            }
        } catch (IOException | CertificateException | InvalidKeyException | RuntimeException e) {
            if (channel != null) channel.shutdownNow();
            throw new BlockchainRelatedException("CONNECTION", "Unable to initialize Fabric Gateway: "
                    + e.getMessage(), null, null, e);
        }
    }

    private Path privateKey(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).sorted(Comparator.naturalOrder())
                    .findFirst().orElseThrow(() -> new IOException("No private key in " + dir));
        }
    }

    public GatewayResult execute(TransactionRequest request) {
        long started = System.nanoTime();
        String txId = null;
        try {
            Contract c = contract();
            if (request.txType() == TxType.READ) {
                byte[] result = c.evaluateTransaction(request.function(), request.args());
                return new GatewayResult(new String(result, StandardCharsets.UTF_8), null,
                        null, elapsedMs(started));
            }
            // One proposal/endorsement/submit per request. submit() BLOCKS until VALID commit.
            var proposal = c.newProposal(request.function()).addArguments(request.args()).build();
            txId = proposal.getTransactionId();
            var endorsed = proposal.endorse();
            byte[] result = endorsed.submit();
            return new GatewayResult(new String(result, StandardCharsets.UTF_8), txId,
                    "VALID", elapsedMs(started));
        } catch (CommitException e) {
            throw new BlockchainRelatedException("COMMIT", e.getMessage(), e.getTransactionId(),
                    e.getCode().name(), e);
        } catch (EndorseException e) {
            throw new BlockchainRelatedException("ENDORSE", e.getMessage(), txId, null, e);
        } catch (SubmitException e) {
            throw new BlockchainRelatedException("SUBMIT", e.getMessage(), txId, null, e);
        } catch (CommitStatusException e) {
            // Unknown commit outcome: do not automatically retry a submitted transaction.
            throw new BlockchainRelatedException("COMMIT_STATUS_UNKNOWN", e.getMessage(), txId, null, e);
        } catch (BlockchainRelatedException e) {
            throw e;
        } catch (Exception e) {
            throw new BlockchainRelatedException("GATEWAY", e.getMessage(), txId, null, e);
        }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    @PreDestroy
    public synchronized void close() {
        if (gateway != null) {
            gateway.close();
            gateway = null;
        }
        if (channel != null) {
            channel.shutdownNow();
            channel = null;
        }
    }
}
