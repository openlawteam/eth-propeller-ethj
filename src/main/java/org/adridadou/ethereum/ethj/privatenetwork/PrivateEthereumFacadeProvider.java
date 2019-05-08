package org.adridadou.ethereum.ethj.privatenetwork;

import com.typesafe.config.ConfigFactory;
import org.adridadou.ethereum.ethj.EthereumJConfigs;
import org.adridadou.ethereum.ethj.EthereumReal;
import org.adridadou.ethereum.propeller.CoreEthereumFacadeProvider;
import org.adridadou.ethereum.propeller.EthereumBackend;
import org.adridadou.ethereum.propeller.EthereumConfig;
import org.adridadou.ethereum.propeller.EthereumFacade;
import org.adridadou.ethereum.propeller.event.EthereumEventHandler;
import org.adridadou.ethereum.propeller.exception.EthereumApiException;
import org.adridadou.ethereum.propeller.keystore.AccountProvider;
import org.adridadou.ethereum.propeller.values.EthAccount;
import org.adridadou.ethereum.values.config.DatabaseDirectory;
import org.apache.commons.io.FileUtils;
import org.apache.tuweni.eth.Block;
import org.apache.tuweni.eth.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * Created by davidroon on 20.11.16.
 * This code is released under Apache 2 license
 */
public class PrivateEthereumFacadeProvider {
    private static final int MINER_PORT = 55555;
    private static final Logger logger = LoggerFactory.getLogger(PrivateEthereumFacadeProvider.class);
    private final EthAccount mainAccount = AccountProvider.fromSeed("cow");

    public EthereumFacade create(PrivateNetworkConfig config, EthereumConfig ethereumConfig) {
        final boolean dagCached = new File("cachedDag/mine-dag.dat").exists();
        if (config.isResetPrivateBlockchain()) {
            deleteFolder(new File(config.getDbName()), true);
        }

        if (dagCached) {
            new File(config.getDbName()).mkdirs();
            try {
                FileUtils.copyFile(new File("cachedDag/mine-dag.dat"), new File(config.getDbName() + "/mine-dag.dat"));
                FileUtils.copyFile(new File("cachedDag/mine-dag-light.dat"), new File(config.getDbName() + "/mine-dag-light.dat"));
            } catch (IOException e) {
                throw new EthereumApiException("error while copying dag files", e);
            }
        }

        MinerConfig.dbName = config.getDbName();
        Ethereum ethereum = EthereumFactory.createEthereum(MinerConfig.class);
        EthereumBackend ethereumBackend = new EthereumReal(ethereum);
        ethereum.initSyncing();

        if (!dagCached) {
            try {
                new File("cachedDag").mkdirs();
                FileUtils.copyFile(new File(config.getDbName() + "/mine-dag.dat"), new File("cachedDag/mine-dag.dat"));
                FileUtils.copyFile(new File(config.getDbName() + "/mine-dag-light.dat"), new File("cachedDag/mine-dag-light.dat"));
            } catch (IOException e) {
                logger.warn("couldn't copy files: " + e.getMessage());
            }
        }

        EthereumEventHandler ethereumListener = new EthereumEventHandler();

        final EthereumFacade facade = CoreEthereumFacadeProvider.create(ethereumBackend, ethereumConfig);

        //This event does not trigger when you are the miner
        ethereumListener.onReady();
        facade.events().ready().thenAccept((b) -> config.getInitialBalances().entrySet().stream()
                .map(entry -> facade.sendEther(mainAccount, entry.getKey().getAddress(), entry.getValue()))
                .forEach(result -> {
                    try {
                        result.get();
                    } catch (InterruptedException | ExecutionException e) {
                        throw new EthereumApiException("error while setting the initial balances");
                    }
                })
        );
        return facade;
    }

    private void deleteFolder(File folder, final boolean root) {
        File[] files = folder.listFiles();
        if (files != null) { //some JVMs return null for empty dirs
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteFolder(f, false);
                } else {
                    f.delete();
                }
            }
        }
        if (!root) {
            folder.delete();
        }
    }

    /**
     * Spring configuration class for the Miner peer
     */
    private static class MinerConfig {

        public static String dbName = "sampleDB";

        public static String config() {
            // no need for discovery in that small network
            return EthereumJConfigs.privateMiner()
                    .dbDirectory(DatabaseDirectory.db(dbName))
                    .listenPort(MINER_PORT)
                    .toString();
        }

        @Bean
        public MinerNode node() {
            return new MinerNode();
        }

        /**
         * Instead of supplying properties via extendConfig file for the peer
         * we are substituting the corresponding bean which returns required
         * extendConfig for this instance.
         */
        @Bean
        public SystemProperties systemProperties() {
            SystemProperties props = new SystemProperties(ConfigFactory.empty(), PrivateEthereumFacadeProvider.class.getClassLoader());
            props.overrideParams(ConfigFactory.parseString(config().replaceAll("'", "\"")));
            return props;
        }
    }

    /**
     * Miner bean, which just start a miner upon creation and prints miner events
     */
    static class MinerNode implements MinerListener {
        public MinerNode() {
            // peers need different loggers
            super("sampleMiner");
        }

        public void run() {
            if (config.isMineFullDataset()) {
                logger.info("Generating Full Dataset (may take up to 10 min if not cached)...");
                // calling this just for indication of the dataset generation
                // basically this is not required
                Hash ethash = Hash.getForBlock(config, ethereum.getBlockchain().getBestBlock().getNumber());
                ethash.getFullDataset();
                logger.info("Full dataset generated (loaded).");
            }
            ethereum.getBlockMiner().addListener(this);
            ethereum.getBlockMiner().startMining();
        }

        public void miningStarted() {
            logger.info("Miner started");
        }

        public void miningStopped() {
            logger.info("Miner stopped");
        }

        public void blockMiningStarted(Block block) {
            logger.info("Start mining block: " + block.toString());
        }

        public void blockMined(Block block) {
            logger.info("Block mined! : \n" + block);
        }

        public void blockMiningCanceled(Block block) {
            logger.info("Cancel mining block: " + block.toString());
        }
    }
}
