package org.adridadou.ethereum.blockchain;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.adridadou.ethereum.*;
import org.adridadou.ethereum.handler.EthereumEventHandler;
import org.adridadou.ethereum.smartcontract.SmartContractReal;
import org.adridadou.ethereum.smartcontract.SmartContract;
import org.adridadou.ethereum.swarm.SwarmService;
import org.adridadou.ethereum.values.*;
import org.adridadou.ethereum.values.smartcontract.SmartContractMetadata;
import org.adridadou.exception.EthereumApiException;
import org.ethereum.core.BlockchainImpl;
import org.ethereum.core.CallTransaction;
import org.ethereum.core.Transaction;
import org.ethereum.core.TransactionReceipt;
import org.ethereum.facade.Ethereum;
import org.ethereum.solidity.compiler.CompilationResult;
import org.ethereum.solidity.compiler.SolidityCompiler;
import org.ethereum.util.ByteUtil;
import org.spongycastle.util.encoders.Hex;

import static org.adridadou.ethereum.values.EthValue.wei;

/**
 * Created by davidroon on 20.04.16.
 * This code is released under Apache 2 license
 */
public class BlockchainProxyReal implements BlockchainProxy {

    private static final long BLOCK_WAIT_LIMIT = 16;
    private static final BigInteger DEFAULT_GAS_LIMIT = BigInteger.valueOf(4_000_000);
    private final Ethereum ethereum;
    private final EthereumEventHandler eventHandler;
    private final SwarmService swarmService;
    private final Map<EthAddress, BigInteger> pendingTransactions = new ConcurrentHashMap<>();

    public BlockchainProxyReal(Ethereum ethereum, EthereumEventHandler eventHandler, SwarmService swarmService) {
        this.ethereum = ethereum;
        this.eventHandler = eventHandler;
        this.swarmService = swarmService;
        eventHandler.onReady().thenAccept((b) -> ethereum.getBlockchain().flush());
    }

    @Override
    public SmartContract map(SoliditySource src, String contractName, EthAddress address, EthAccount sender) {
        try {
            CompilationResult.ContractMetadata metadata = compile(src, contractName);
            return mapFromAbi(new ContractAbi(metadata.abi), address, sender);

        } catch (IOException e) {
            throw new EthereumApiException("error while mapping a smart contract", e);
        }
    }

    @Override
    public SmartContract mapFromAbi(ContractAbi abi, EthAddress address, EthAccount sender) {
        return new SmartContractReal(abi.getAbi(), ethereum, sender, address, this);
    }

    @Override
    public CompletableFuture<EthAddress> publish(SoliditySource code, String contractName, EthAccount sender, Object... constructorArgs) {
        try {
            return createContract(code, contractName, sender, constructorArgs);
        } catch (IOException e) {
            throw new EthereumApiException("error while publishing " + contractName + ":", e);
        }
    }

    private CompletableFuture<EthAddress> createContract(SoliditySource soliditySrc, String contractName, EthAccount sender, Object... constructorArgs) throws IOException {
        CompilationResult.ContractMetadata metadata = compile(soliditySrc, contractName);
        CallTransaction.Contract contractAbi = new CallTransaction.Contract(metadata.abi);
        CallTransaction.Function constructor = contractAbi.getConstructor();
        if (constructor == null && constructorArgs.length > 0) {
            throw new EthereumApiException("No constructor with params found");
        }
        publishContractMetadaToSwarm(metadata.metadata);
        byte[] argsEncoded = constructor == null ? new byte[0] : constructor.encodeArguments(constructorArgs);
        return sendTx(wei(0), EthData.of(ByteUtil.merge(Hex.decode(metadata.bin), argsEncoded)), sender);
    }

    private void publishContractMetadaToSwarm(String metadata) throws IOException {
        swarmService.publish(metadata);
    }

    private CompilationResult.ContractMetadata compile(SoliditySource src, String contractName) throws IOException {
        SolidityCompiler.Result result = SolidityCompiler.getInstance().compileSrc(src.getSource().getBytes(EthereumFacade.CHARSET), true,true,
                SolidityCompiler.Options.ABI, SolidityCompiler.Options.BIN, SolidityCompiler.Options.METADATA);
        if (result.isFailed()) {
            throw new EthereumApiException("Contract compilation failed:\n" + result.errors);
        }
        CompilationResult res = CompilationResult.parse(result.output);
        if (res.contracts.isEmpty()) {
            throw new EthereumApiException("Compilation failed, no contracts returned:\n" + result.errors);
        }
        CompilationResult.ContractMetadata metadata = res.contracts.get(contractName);
        if (metadata != null && (metadata.bin == null || metadata.bin.isEmpty())) {
            throw new EthereumApiException("Compilation failed, no binary returned:\n" + result.errors);
        }
        return metadata;
    }

    public BigInteger getNonce(final EthAddress address) {
        BigInteger nonce = ((BlockchainImpl) ethereum.getBlockchain()).getRepository().getNonce(address.address);
        return nonce.add(pendingTransactions.getOrDefault(address, BigInteger.ZERO));
    }

    @Override
    public SmartContractByteCode getCode(EthAddress address) {
        byte[] code = ((BlockchainImpl) ethereum.getBlockchain()).getRepository().getCode(address.address);
        if(code.length == 0) {
            throw new EthereumApiException("no code found at the address. please verify that a smart contract is deployed at " + address.withLeading0x());
        }
        return SmartContractByteCode.of(code);
    }

    @Override
    public SmartContractMetadata getMetadata(SwarmMetadaLink swarmMetadaLink) {
        try {
            return swarmService.getMetadata(swarmMetadaLink.getHash());
        } catch (IOException e) {
            throw new EthereumApiException("error while getting metadata", e);
        }
    }

    @Override
    public void shutdown() {
        ethereum.close();
    }

    public CompletableFuture<EthAddress> sendTx(EthValue ethValue, EthData data, EthAccount sender) {
        return sendTx(ethValue,data,sender,BigInteger.valueOf(ByteUtil.byteArrayToLong(ethereum.getBlockchain().getBestBlock().getGasLimit())));
    }

    @Override
    public CompletableFuture<EthAddress> sendTx(EthValue ethValue, EthData data, EthAccount sender, BigInteger gasLimit) {
        return this.sendTxInternal(ethValue, data, sender, EthAddress.empty(), gasLimit)
                .thenApply(receipt -> EthAddress.of(receipt.getTransaction().getContractAddress()));
    }

    public CompletableFuture<EthExecutionResult> sendTx(EthValue value, EthData data, EthAccount sender, EthAddress address, BigInteger gasLimit) {
        return this.sendTxInternal(value, data, sender, address, gasLimit)
                .thenApply(receipt -> new EthExecutionResult(receipt.getExecutionResult()));
    }

    @Override
    public CompletableFuture<EthExecutionResult> sendTx(EthValue value, EthData data, EthAccount sender, EthAddress address) {
        return sendTx(value,data,sender,address,BigInteger.valueOf(ByteUtil.byteArrayToLong(ethereum.getBlockchain().getBestBlock().getGasLimit())));
    }

    private CompletableFuture<TransactionReceipt> sendTxInternal(EthValue value, EthData data, EthAccount account, EthAddress toAddress, BigInteger gasLimit) {
        return eventHandler.onReady().thenCompose((b) -> {
            BigInteger nonce = getNonce(account.getAddress());

            Transaction tx = ethereum.createTransaction(nonce,BigInteger.valueOf(ethereum.getGasPrice()),gasLimit, toAddress.address,value.inWei(),data.data);
            tx.sign(account.key);
            ethereum.submitTransaction(tx);
            increasePendingTransactionCounter(account.getAddress());
            long currentBlock = eventHandler.getCurrentBlockNumber();

            Predicate<TransactionReceipt> findReceipt = (TransactionReceipt receipt) -> Arrays.equals(receipt.getTransaction().getHash(), tx.getHash());

            return CompletableFuture.supplyAsync(() -> eventHandler.observeBlocks()
                    .filter(params -> params.receipts.stream().anyMatch(findReceipt) || params.block.getNumber() > currentBlock + BLOCK_WAIT_LIMIT)
                    .map(params -> {
                        Optional<TransactionReceipt> receipt = params.receipts.stream().filter(findReceipt).findFirst();
                        decreasePendingTransactionCounter(account.getAddress());
                        return receipt.map(eventHandler::checkForErrors)
                                .<EthereumApiException>orElseThrow(() -> new EthereumApiException("the transaction has not been added to any block after waiting for " + BLOCK_WAIT_LIMIT));
                    }).toBlocking().first());
        });
    }

    @Override
    public EthereumEventHandler events() {
        return eventHandler;
    }

    @Override
    public boolean addressExists(EthAddress address) {
        return ethereum.getRepository().isExist(address.address);
    }

    @Override
    public EthValue getBalance(EthAddress address) {
        return wei(ethereum.getRepository().getBalance(address.address));
    }

    private void decreasePendingTransactionCounter(EthAddress address) {
        pendingTransactions.put(address, pendingTransactions.getOrDefault(address, BigInteger.ZERO).subtract(BigInteger.ONE));
    }

    private void increasePendingTransactionCounter(EthAddress address) {
        pendingTransactions.put(address, pendingTransactions.getOrDefault(address, BigInteger.ZERO).add(BigInteger.ONE));
    }

    protected void finalize() {
        ethereum.close();
    }
}
