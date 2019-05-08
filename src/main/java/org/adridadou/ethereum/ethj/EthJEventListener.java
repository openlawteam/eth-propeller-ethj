package org.adridadou.ethereum.ethj;

import org.adridadou.ethereum.propeller.event.BlockInfo;
import org.adridadou.ethereum.propeller.event.EthereumEventHandler;
import org.adridadou.ethereum.propeller.solidity.converters.decoders.EthValueDecoder;
import org.adridadou.ethereum.propeller.values.*;
import org.apache.tuweni.eth.Block;
import org.apache.tuweni.eth.Log;
import org.apache.tuweni.eth.Transaction;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by davidroon on 27.04.16.
 * This code is released under Apache 2 license
 */
public class EthJEventListener {
    private final EthereumEventHandler eventHandler;
    private static final EthValueDecoder ethValueDecoder = new EthValueDecoder();

    EthJEventListener(EthereumEventHandler eventHandler) {
        this.eventHandler = eventHandler;
    }

    static List<EventData> createEventInfoList(EthHash transactionHash, List<Log> logs) {
        return logs.stream().map(log -> {
            List<DataWord> topics = log.topics();
            EthData eventSignature = EthData.of(topics.get(0).getData());
            EthData eventArguments = EthData.of(log.getData());
            List<EthData> indexedArguments = topics.subList(1, topics.size()).stream()
                    .map(dw -> EthData.of(dw.getData()))
                    .collect(Collectors.toList());

            return new EventData(transactionHash, eventSignature, eventArguments, indexedArguments);
        }).collect(Collectors.toList());
    }

    static org.adridadou.ethereum.propeller.values.TransactionReceipt toReceipt(TransactionReceipt transactionReceipt, EthHash blockHash) {
        Transaction tx = transactionReceipt.getTransaction();
        BigInteger txValue = tx.value().length > 0 ? new BigInteger(tx.value()) : BigInteger.ZERO;
        if (txValue.signum() == -1) {
            txValue = BigInteger.ZERO;
        }
        return new org.adridadou.ethereum.propeller.values.TransactionReceipt(
                EthHash.of(tx.hash().toBytes().toArray()),
                blockHash,
                EthAddress.of(tx.sender().toBytes().toArray()),
                EthAddress.of(tx.getReceiveAddress()),
                EthAddress.of(tx.getContractAddress()),
                EthData.of(tx.getData()),
                transactionReceipt.getError(),
                EthData.of(transactionReceipt.getExecutionResult()),
                transactionReceipt.isSuccessful() && transactionReceipt.isValid(),
                createEventInfoList(EthHash.of(tx.hash().toBytes().toArray()), transactionReceipt.getLogInfoList()),
                ethValueDecoder.decode(0, EthData.of(txValue), EthValue.class));
    }

    public void onBlock(Block block, List<TransactionReceipt> receipts) {
        EthHash blockHash = EthHash.of(block.header().hash().toBytes().toArray());
        eventHandler.onBlock(new BlockInfo(block.getNumber(), receipts.stream().map(receipt -> EthJEventListener.toReceipt(receipt, blockHash)).collect(Collectors.toList())));
        receipts.forEach(receipt -> eventHandler.onTransactionExecuted(new TransactionInfo(EthHash.of(receipt.getTransaction().getHash()), toReceipt(receipt, blockHash), TransactionStatus.Executed, EthHash.empty())));
    }

    public void onPendingTransactionUpdate(TransactionReceipt txReceipt, PendingTransactionState state, Block block) {
        EthHash blockHash = EthHash.of(block.header().hash().toBytes().toArray());
        switch (state) {
            case DROPPED:
                eventHandler.onTransactionDropped(new TransactionInfo(EthHash.of(txReceipt.getTransaction().getHash()), toReceipt(txReceipt, blockHash), TransactionStatus.Dropped, EthHash.empty()));
                break;
            default:
                break;
        }
    }

    public void onSyncDone(final SyncState syncState) {
        eventHandler.onReady();
    }
}
