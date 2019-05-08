package org.adridadou.ethereum.ethj;

import org.adridadou.ethereum.propeller.exception.EthereumApiException;
import org.adridadou.ethereum.propeller.values.*;
import org.apache.tuweni.crypto.SECP256K1;
import org.apache.tuweni.eth.Block;
import org.apache.tuweni.eth.Transaction;
import org.apache.tuweni.eth.repository.BlockchainRepository;

import java.math.BigInteger;

/**
 * Created by davidroon on 30.01.17.
 * This code is released under Apache 2 license
 */
public class LocalExecutionService {
    private static final long GAS_LIMIT_FOR_LOCAL_EXECUTION = 100_000_000_000L;
    private final BlockchainImpl blockchain;

    public LocalExecutionService(BlockchainImpl blockchain) {
        this.blockchain = blockchain;
    }


    public GasUsage estimateGas(final EthAccount account, final EthAddress address, final EthValue value, final EthData data) {
        TransactionExecutor execution = execute(account, address, value, data);
        return new GasUsage(BigInteger.valueOf(execution.getGasUsed()));
    }

    public EthData executeLocally(final EthAccount account, final EthAddress address, final EthValue value, final EthData data) {
        TransactionExecutor execution = execute(account, address, value, data);
        return EthData.of(execution.getResult().getHReturn());
    }

    private TransactionExecutor execute(final EthAccount account, final EthAddress address, final EthValue value, final EthData data) {
        Block callBlock = blockchain.getBestBlock();
        Repository repository = getRepository().getSnapshotTo(callBlock.getStateRoot()).startTracking();
        try {
            Transaction tx = createTransaction(account, BigInteger.ZERO, BigInteger.ZERO, address, value, data);
            TransactionExecutor executor = new TransactionExecutor(tx, callBlock.getCoinbase(), repository, blockchain.getBlockStore(), blockchain.getProgramInvokeFactory(), callBlock).setLocalCall(true);

            executor.init();
            executor.execute();
            executor.go();
            executor.finalization();

            if (!executor.getReceipt().isSuccessful()) {
                throw new EthereumApiException(executor.getReceipt().getError());
            }
            return executor;
        } finally {
            repository.rollback();
        }
    }

    private BlockchainRepository getRepository() {
        return blockchain.getRepository();
    }

    private Transaction createTransaction(EthAccount account, BigInteger nonce, BigInteger gasPrice, EthAddress address, EthValue value, EthData data) {
        Transaction tx = CallTransaction.createRawTransaction(nonce.longValue(), gasPrice.longValue(), GAS_LIMIT_FOR_LOCAL_EXECUTION, address.toString(), value.inWei().longValue(), data.data);
        tx.sign(getKey(account));
        return tx;
    }

    private SECP256K1.PublicKey getKey(EthAccount account) { return new SECP256K1.PublicKey.fromInteger(account.getBigIntPrivateKey()); }
}
