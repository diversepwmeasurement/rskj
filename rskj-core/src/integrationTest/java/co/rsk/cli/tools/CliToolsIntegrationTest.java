/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package co.rsk.cli.tools;

import co.rsk.RskContext;
import co.rsk.trie.Trie;
import co.rsk.util.HexUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.squareup.okhttp.*;
import org.ethereum.core.Block;
import org.ethereum.util.ByteUtil;
import org.ethereum.util.FileUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class CliToolsIntegrationTest {
    private String projectPath;
    private String buildLibsPath;
    private String jarName;
    private String databaseDir;
    private final JsonNodeFactory jsonNodeFactory = JsonNodeFactory.instance;
    private final int port = 9999;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String[] baseArgs;
    private LinkedList<String> lnkListBaseArgs;
    private String strBaseArgs;
    private String integrationTestResourcesPath;
    private String logbackXmlFile;
    private String baseJavaCmd;


    class CustomProcess {
        private final Process process;
        private final String input;
        private final String errors;

        public CustomProcess(Process process, String input, String errors) {
            this.process = process;
            this.input = input;
            this.errors = errors;
        }

        public Process getProcess() {
            return process;
        }

        public String getInput() {
            return input;
        }

        public String getErrors() {
            return errors;
        }
    }

    @TempDir
    private Path tempDir;

    private String getProcStreamAsString(InputStream in) throws IOException {
        byte bytesAvailable[] = new byte[in.available()];
        in.read(bytesAvailable, 0, bytesAvailable.length);
        return new String(bytesAvailable);
    }

    @BeforeEach
    public void setup() throws IOException {
        projectPath = System.getProperty("user.dir");
        buildLibsPath = String.format("%s/build/libs", projectPath);
        integrationTestResourcesPath = String.format("%s/src/integrationTest/resources", projectPath);
        logbackXmlFile = String.format("%s/logback.xml", integrationTestResourcesPath);
        Stream<Path> pathsStream = Files.list(Paths.get(buildLibsPath));
        jarName = pathsStream.filter(p -> !p.toFile().isDirectory())
                .map(p -> p.getFileName().toString())
                .filter(fn -> fn.endsWith("-all.jar"))
                .findFirst()
                .get();
        databaseDir = tempDir.resolve("database").toString();
        baseArgs = new String[]{
                String.format("-Xdatabase.dir=%s", databaseDir),
                "--regtest",
                "-Xkeyvalue.datasource=leveldb",
                String.format("-Xrpc.providers.web.http.port=%s", port)
        };
        lnkListBaseArgs = Stream.of(baseArgs).collect(Collectors.toCollection(LinkedList::new));
        strBaseArgs = String.join(" ", baseArgs);
        baseJavaCmd = String.format("java %s", String.format("-Dlogback.configurationFile=%s", logbackXmlFile));
    }

    private CustomProcess runCommand(String cmd, int timeout, TimeUnit timeUnit) throws InterruptedException, IOException {
        return runCommand(cmd, timeout, timeUnit, null);
    }

    private CustomProcess runCommand(String cmd, int timeout, TimeUnit timeUnit, Consumer<Process> beforeDestroyFn) throws InterruptedException, IOException {
        Process proc = Runtime.getRuntime().exec(cmd);

        proc.waitFor(timeout, timeUnit);
        String procInput = getProcStreamAsString(proc.getInputStream());
        String procErrors = getProcStreamAsString(proc.getErrorStream());

        if (beforeDestroyFn != null) {
            beforeDestroyFn.accept(proc);
        }

        proc.destroy();

        return new CustomProcess(proc, procInput, procErrors);
    }

    private String getHashFromLog(List<String> logLines, int logIndex) {
        List<String> hashes = logLines.stream().filter(l -> l.contains("[miner client]") && l.contains("blockHash"))
                .map(this::getHashFromLog)
                .collect(Collectors.toList());

        return logIndex < 0 ? hashes.get(hashes.size() - 1) : hashes.get(logIndex);
    }

    private String getHashFromLog(String log) {
        return log.split("\\[blockHash=")[1].split(", blockHeight")[0];
    }

    private Response getBestBlock() throws IOException {
        String content = "[{\n" +
                "    \"method\": \"eth_getBlockByNumber\",\n" +
                "    \"params\": [\n" +
                "        \"latest\",\n" +
                "        true\n" +
                "    ],\n" +
                "    \"id\": 1,\n" +
                "    \"jsonrpc\": \"2.0\"\n" +
                "}]";

        return sendJsonRpcMessage(content);
    }

    private Block getBestBlock(RskContext rskContext) {
        return rskContext.getBlockchain().getBestBlock();
    }

    private static OkHttpClient getUnsafeOkHttpClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                                                       String authType) throws CertificateException {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                                       String authType) throws CertificateException {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            return new OkHttpClient()
                    .setSslSocketFactory(sslSocketFactory)
                    .setHostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return true;
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Response sendJsonRpcMessage(String content) throws IOException {
        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json-rpc"), content);
        URL url = new URL("http", "localhost", port, "/");
        Request request = new Request.Builder().url(url)
                .addHeader("Host", "localhost")
                .addHeader("Accept-Encoding", "identity")
                .post(requestBody).build();
        return getUnsafeOkHttpClient().newCall(request).execute();
    }

    @Test
    void whenExportBlocksRuns_shouldExportSpecifiedBlocks() throws Exception {
        Map<String, Response> responseMap = new HashMap<>();
        String cmd = String.format("%s -cp %s/%s co.rsk.Start --reset %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        runCommand(
                cmd,
                1,
                TimeUnit.MINUTES,
                proc -> {
                    try {
                        Response response = getBestBlock();
                        responseMap.put("latestProcessedBlock", response);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
        );

        String responseBody = responseMap.get("latestProcessedBlock").body().string();
        JsonNode jsonRpcResponse = objectMapper.readTree(responseBody);
        JsonNode transactionsNode = jsonRpcResponse.get(0).get("result").get("transactions");

        Long blockNumber = HexUtils.jsonHexToLong(transactionsNode.get(0).get("blockNumber").asText());

        File blocksFile = tempDir.resolve("blocks.txt").toFile();
        Files.deleteIfExists(Paths.get(blocksFile.getAbsolutePath()));

        Assertions.assertTrue(blocksFile.createNewFile());

        cmd = String.format("%s -cp %s/%s co.rsk.cli.tools.ExportBlocks --fromBlock 0 --toBlock %s --file %s %s", baseJavaCmd, buildLibsPath, jarName, blockNumber, blocksFile.getAbsolutePath(), strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        List<String> exportedBlocksLines = Files.readAllLines(Paths.get(blocksFile.getAbsolutePath()));
        String exportedBlocksLine = exportedBlocksLines.stream()
                .filter(l -> l.split(",")[0].equals(blockNumber.toString()))
                .findFirst()
                .get();
        String[] exportedBlocksLineParts = exportedBlocksLine.split(",");

        Files.delete(Paths.get(blocksFile.getAbsolutePath()));

        Assertions.assertFalse(exportedBlocksLines.isEmpty());
        Assertions.assertTrue(transactionsNode.get(0).get("blockHash").asText().contains(exportedBlocksLineParts[1]));
    }

    @Test
    void whenExportStateRuns_shouldExportSpecifiedBlockState() throws Exception {
        String cmd = String.format("%s -cp %s/%s co.rsk.Start --reset %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        RskContext rskContext = new RskContext(baseArgs);

        Block block = getBestBlock(rskContext);
        Optional<Trie> optionalTrie = rskContext.getTrieStore().retrieve(block.getStateRoot());
        byte[] bMessage = optionalTrie.get().toMessage();
        String strMessage = ByteUtil.toHexString(bMessage);
        Long blockNumber = block.getNumber();

        rskContext.close();

        File statesFile = tempDir.resolve("states.txt").toFile();
        Files.deleteIfExists(Paths.get(statesFile.getAbsolutePath()));

        Assertions.assertTrue(statesFile.createNewFile());

        cmd = String.format("%s -cp %s/%s co.rsk.cli.tools.ExportState --block %s --file %s %s", baseJavaCmd, buildLibsPath, jarName, blockNumber, statesFile.getAbsolutePath(), strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        List<String> exportedStateLines = Files.readAllLines(Paths.get(statesFile.getAbsolutePath()));

        Files.delete(Paths.get(statesFile.getAbsolutePath()));

        Assertions.assertFalse(exportedStateLines.isEmpty());
        Assertions.assertTrue(exportedStateLines.stream().anyMatch(l -> l.equals(strMessage)));
    }

    @Test
    void whenShowStateInfoRuns_shouldShowSpecifiedState() throws Exception {
        Map<String, Response> responseMap = new HashMap<>();
        String cmd = String.format("%s -cp %s/%s co.rsk.Start --reset %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        runCommand(
                cmd,
                1,
                TimeUnit.MINUTES, proc -> {
                    try {
                        Response response = getBestBlock();
                        responseMap.put("latestProcessedBlock", response);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
        );

        String responseBody = responseMap.get("latestProcessedBlock").body().string();
        JsonNode jsonRpcResponse = objectMapper.readTree(responseBody);
        JsonNode result = jsonRpcResponse.get(0).get("result");
        JsonNode transactionsNode = result.get("transactions");

        Long blockNumber = HexUtils.jsonHexToLong(transactionsNode.get(0).get("blockNumber").asText());

        cmd = String.format("%s -cp %s/%s co.rsk.cli.tools.ShowStateInfo --block %s %s", baseJavaCmd, buildLibsPath, jarName, blockNumber, strBaseArgs);
        CustomProcess showStateInfoProc = runCommand(cmd, 1, TimeUnit.MINUTES);

        List<String> stateInfoLines = Arrays.asList(showStateInfoProc.getInput().split("\\n"));

        Assertions.assertFalse(stateInfoLines.isEmpty());
        Assertions.assertTrue(stateInfoLines.stream().anyMatch(l -> l.contains(HexUtils.removeHexPrefix(result.get("hash").asText()))));
    }

    @Test
    void whenExecuteBlocksRuns_shouldReturnExpectedBestBlock() throws Exception {
        Map<String, Response> responseMap = new HashMap<>();
        String cmd = String.format("%s -cp %s/%s co.rsk.Start --reset %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        runCommand(
                cmd,
                1,
                TimeUnit.MINUTES, proc -> {
                    try {
                        Response response = getBestBlock();
                        responseMap.put("latestProcessedBlock", response);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
        );

        String responseBody = responseMap.get("latestProcessedBlock").body().string();
        JsonNode jsonRpcResponse = objectMapper.readTree(responseBody);
        JsonNode result = jsonRpcResponse.get(0).get("result");
        JsonNode transactionsNode = result.get("transactions");

        Long blockNumber = HexUtils.jsonHexToLong(transactionsNode.get(0).get("blockNumber").asText());

        cmd = String.format("%s -cp %s/%s co.rsk.cli.tools.ExecuteBlocks --fromBlock 0 --toBlock %s %s", baseJavaCmd, buildLibsPath, jarName, blockNumber, strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        RskContext rskContext = new RskContext(baseArgs);

        Block block = getBestBlock(rskContext);

        rskContext.close();

        Assertions.assertEquals(block.getNumber(), blockNumber);
    }

    @Test
    void whenConnectBlocksRuns_shouldConnectSpecifiedBlocks() throws Exception {
        String cmd = String.format("%s -cp %s/%s co.rsk.Start --reset %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        RskContext rskContext = new RskContext(baseArgs);

        Block block1 = rskContext.getBlockchain().getBlockByNumber(1);
        Block block2 = rskContext.getBlockchain().getBlockByNumber(2);
        rskContext.close();


        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("1,");
        stringBuilder.append(ByteUtil.toHexString(block1.getHash().getBytes()));
        stringBuilder.append(",02,");
        stringBuilder.append(ByteUtil.toHexString(block1.getEncoded()));
        stringBuilder.append("\n");
        stringBuilder.append("1,");
        stringBuilder.append(ByteUtil.toHexString(block2.getHash().getBytes()));
        stringBuilder.append(",03,");
        stringBuilder.append(ByteUtil.toHexString(block2.getEncoded()));
        stringBuilder.append("\n");

        File blocksFile = tempDir.resolve("blocks.txt").toFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(blocksFile))) {
            writer.write(stringBuilder.toString());
        }

        cmd = String.format("%s -cp %s/%s co.rsk.cli.tools.ConnectBlocks --file %s %s", baseJavaCmd, buildLibsPath, jarName, blocksFile.getAbsolutePath(), strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        Files.delete(Paths.get(blocksFile.getAbsolutePath()));

        rskContext = new RskContext(baseArgs);

        Block block1AfterConnect = rskContext.getBlockchain().getBlockByNumber(1);
        Block block2AfterConnect = rskContext.getBlockchain().getBlockByNumber(2);

        rskContext.close();

        Assertions.assertEquals(block1.getHash(), block1AfterConnect.getHash());
        Assertions.assertEquals(block2.getHash(), block2AfterConnect.getHash());
    }

    @Test
    void whenImportBlocksRuns_shouldImportAllExportedBlocks() throws Exception {
        String cmd = String.format("%s -cp %s/%s co.rsk.Start --reset %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        File blocksFile = tempDir.resolve("blocks.txt").toFile();

        Assertions.assertTrue(blocksFile.createNewFile());

        cmd = String.format("%s -cp %s/%s co.rsk.cli.tools.ExportBlocks --fromBlock 0 --toBlock 20 --file %s %s", baseJavaCmd, buildLibsPath, jarName, blocksFile.getAbsolutePath(), strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        FileUtil.recursiveDelete(databaseDir);

        cmd = String.format("%s -cp %s/%s co.rsk.cli.tools.ImportBlocks --file %s %s", baseJavaCmd, buildLibsPath, jarName, blocksFile.getAbsolutePath(), strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        RskContext rskContext = new RskContext(baseArgs);

        Long maxNumber = rskContext.getBlockStore().getMaxNumber();

        rskContext.close();

        Assertions.assertEquals(20, maxNumber);
    }

    @Test
    void whenDbMigrateRuns_shouldMigrateLevelDbToRocksDbAndShouldNotStartNodeWithPrevDbKind() throws Exception {
        String cmd = String.format("%s -cp %s/%s co.rsk.Start --reset %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        cmd = String.format("%s -cp %s/%s co.rsk.cli.tools.DbMigrate --targetDb rocksdb %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        CustomProcess dbMigrateProc = runCommand(cmd, 1, TimeUnit.MINUTES);

        cmd = String.format("%s -cp %s/%s co.rsk.Start --regtest %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        CustomProcess proc = runCommand(cmd, 1, TimeUnit.MINUTES);

        List<String> logLines = Arrays.asList(proc.getInput().split("\\n"));

        Assertions.assertTrue(dbMigrateProc.getInput().contains("DbMigrate finished"));
        Assertions.assertTrue(logLines.stream().anyMatch(l -> l.equals("java.lang.IllegalStateException: DbKind mismatch. You have selected LEVEL_DB when the previous detected DbKind was ROCKS_DB.")));
    }

    @Test
    public void whenDbMigrateRuns_shouldMigrateLevelDbToRocksDbAndShouldStartNodeSuccessfully() throws Exception {
        String cmd = String.format("%s -cp %s/%s co.rsk.Start --reset %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        runCommand(cmd, 1, TimeUnit.MINUTES);

        cmd = String.format("%s -cp %s/%s co.rsk.cli.tools.DbMigrate --targetDb rocksdb %s", baseJavaCmd, buildLibsPath, jarName, strBaseArgs);
        CustomProcess dbMigrateProc = runCommand(cmd, 1, TimeUnit.MINUTES);

        LinkedList<String> args = Stream.of(baseArgs)
                .map(arg -> arg.equals("-Xkeyvalue.datasource=leveldb") ? "-Xkeyvalue.datasource=rocksdb" : arg)
                .collect(Collectors.toCollection(LinkedList::new));

        cmd = String.format("%s -cp %s/%s co.rsk.Start %s", baseJavaCmd, buildLibsPath, jarName, String.join(" ", args));
        CustomProcess proc = runCommand(cmd, 1, TimeUnit.MINUTES);

        List<String> logLines = Arrays.asList(proc.getInput().split("\\n"));

        Assertions.assertTrue(dbMigrateProc.getInput().contains("DbMigrate finished"));
        Assertions.assertTrue(logLines.stream().anyMatch(l -> l.contains("[minerserver] [miner client]  Mined block import result is IMPORTED_BEST")));
        Assertions.assertTrue(logLines.stream().noneMatch(l -> l.contains("Exception:")));
    }
}
