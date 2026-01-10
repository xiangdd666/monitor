package org.example;

import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Int128;
import org.web3j.abi.datatypes.generated.Int24;
import org.web3j.abi.datatypes.generated.Int256;
import org.web3j.abi.datatypes.generated.Uint128;
import org.web3j.abi.datatypes.generated.Uint160;
import org.web3j.protocol.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.websocket.WebSocketService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class UniswapPoolMonitor {

    private static final String CONTRACT_ADDRESS = "0xef8cd93baf5d97d9d4da15263c56995038432db8";
    //    private static  String RPC_URL = "https://arb-one.api.pocket.network"; // Public Arbitrum RPC, consider using a private node for production
    private static String RPC_URL = "wss://arbitrum-one-rpc.publicnode.com"; // Public Arbitrum RPC, consider using a private node for production
    private static BigDecimal THRESHOLD = new BigDecimal("100"); // Define "large" as >1000 USD24, adjust as needed
    private static final int USDC_DECIMALS = 6; // USDC has 6 decimals
    private static final int USD24_DECIMALS = 2; // USD24 has 2 decimals


    // Uniswap V3 Swap event
    private static final Event SWAP_EVENT = new Event("Swap",
            Arrays.asList(
                    new TypeReference<Address>(true) {
                    }, // sender
                    new TypeReference<Address>(true) {
                    }, // recipient
                    new TypeReference<Int256>() {
                    }, // amount0 (USDC)
                    new TypeReference<Int256>() {
                    }, // amount1 (USD24)
                    new TypeReference<Uint160>() {
                    }, // sqrtPriceX96
                    new TypeReference<Uint128>() {
                    }, // liquidity
                    new TypeReference<Int24>() {
                    } // tick
            ));

    //https://arbiscan.io/tx/0x93b236dea5c0336e70362ea6037200c7dd07fe8efb1fd24c15acddea044a6643

    private static Web3j web3j;
    private static Web3jService service;


    public static void main(String[] args) {

        connectAndSubscribe();

        // 优雅关闭（生产中建议加上）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                web3j.shutdown();
                service.close();
            } catch (Exception e) {
                // ignore
            }
        }));
        // Keep the program running to listen for events
        System.out.println("Monitoring Uniswap Pool for large USD24 swaps...");
        LockSupport.park();

    }

    private static void connectAndSubscribe() {
        try {
            if (RPC_URL.startsWith("ws")) {
                service = new WebSocketService(RPC_URL, false); // Enable automatic reconnect in WebSocketService
                ((WebSocketService) service).connect();
            } else {
                service = new HttpService(RPC_URL);
            }


            web3j = Web3j.build(service);
            subscribeToEvents();
            System.out.println("Connected and subscribed to Uniswap Pool events.");
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    private static void subscribeToEvents() {


//        DefaultBlockParameter from = DefaultBlockParameter.valueOf(new BigInteger("419838898"));
        DefaultBlockParameter from = DefaultBlockParameterName.LATEST;

        EthFilter filter = new EthFilter(
                from,
                DefaultBlockParameterName.LATEST,
                CONTRACT_ADDRESS
        );
        filter.addSingleTopic(EventEncoder.encode(SWAP_EVENT));


        web3j.ethLogFlowable(filter).subscribe(log -> {
            try {
                List<org.web3j.abi.datatypes.Type> decoded = org.web3j.abi.FunctionReturnDecoder.decode(
                        log.getData(), SWAP_EVENT.getNonIndexedParameters());

                BigInteger amount0 = ((Int256) decoded.get(0)).getValue(); // USDC
                BigInteger amount1 = ((Int256) decoded.get(1)).getValue(); // USD24

                // Convert to human-readable amounts (absolute values for simplicity)
                BigDecimal usdcAmount = new BigDecimal(amount0.abs()).divide(BigDecimal.TEN.pow(USDC_DECIMALS));
                BigDecimal usd24Amount1 = new BigDecimal(amount1.abs()).divide(BigDecimal.TEN.pow(USD24_DECIMALS));
                double v = (usd24Amount1.doubleValue() - usdcAmount.doubleValue()) / usd24Amount1.doubleValue();

                System.out.println("USD24: " + amount1.doubleValue());
                // Check if USD24 swap is large (either in or out)
                if (usd24Amount1.compareTo(THRESHOLD) >= 0) {

                    System.out.println("Large USD24 Swap Detected!");
                    String content = "USDC Amount: " + usdcAmount + (amount0.signum() < 0 ? " (Out)" : " (In)") +
                            "\nUSD24 Amount: " + usd24Amount1 + (amount1.signum() < 0 ? " (Out)" : " (In)") +
//                            "\nSender: " + log.getTopics().get(1).substring(26) +
//                            "\nRecipient: " + log.getTopics().get(2).substring(26) +
                            "\nTx: " + log.getTransactionHash() +
                            "\nBlock Number: " + log.getBlockNumber() +
                            "\nExchange rate: " + v;
                    System.out.println(content);


                    //https://worker-notice.1132251350.workers.dev/?title=aa&content=bbb&jumpurl=www.baidu.com
                    // Send notification
                    if (v > 0.02) {
                        String title = "Large USD24 Swap Detected";
                        String jumpurl = "https://arbiscan.io/tx/" + log.getTransactionHash();
                        sendNotification(title, content, jumpurl);
                    }

                    System.out.println("-----------------------------------");
                }
            } catch (Exception e) {
                System.err.println("Error decoding log: " + e.getMessage());
            }
        }, throwable -> {
            System.err.println("Subscription error: " + throwable);
            // Reconnect and resubscribe
            reconnect();
        });
    }

    private static void reconnect() throws IOException {
        if (web3j != null) {
            web3j.shutdown();
        }
        if (service != null) {
            service.close();
        }
        System.err.println("Reconnecting in " + 30 + " seconds...");
        try {
            TimeUnit.MILLISECONDS.sleep(30 * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connectAndSubscribe();
    }


    private static void sendNotification(String title, String content, String jumpurl) {
        try {
            String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString());
            String encodedContent = URLEncoder.encode(content, StandardCharsets.UTF_8.toString());
            String encodedJumpurl = URLEncoder.encode(jumpurl, StandardCharsets.UTF_8.toString());

            String notificationUrl = "https://notice.dyna123.fun/?title=" + encodedTitle +
                    "&content=" + encodedContent + "&jumpurl=" + encodedJumpurl;

            URL url = new URL(notificationUrl);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");

            int responseCode = con.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("Notification sent successfully.");
            } else {
                System.out.println("Failed to send notification. Response code: " + responseCode);
            }
        } catch (Exception e) {
            System.err.println("Error sending notification: " + e.getMessage());
        }
    }

    private static final String CONFIG_FILE = "./config.properties";

    // 在类加载时读取配置（静态块）
    static {
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(CONFIG_FILE)) {
            if (input == null) {
                System.err.println("Sorry, unable to find " + CONFIG_FILE);
                THRESHOLD = new BigDecimal("1000"); // 默认值
            } else {
                props.load(input);
                String thresholdStr = props.getProperty("threshold", "1000");
                String wsurl = props.getProperty("rpc.url", "");
                try {
                    THRESHOLD = new BigDecimal(thresholdStr.trim());
                    RPC_URL = wsurl;
                } catch (Exception e) {
                    System.err.println(e);
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading config: " + e.getMessage());
        }

        System.out.println(THRESHOLD.intValue());
        System.out.println(RPC_URL);
    }
}