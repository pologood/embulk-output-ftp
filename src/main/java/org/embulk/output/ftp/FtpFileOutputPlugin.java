package org.embulk.output.ftp;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import it.sauronsoftware.ftp4j.FTPAbortedException;
import it.sauronsoftware.ftp4j.FTPClient;
import it.sauronsoftware.ftp4j.FTPCommunicationListener;
import it.sauronsoftware.ftp4j.FTPConnector;
import it.sauronsoftware.ftp4j.FTPDataTransferException;
import it.sauronsoftware.ftp4j.FTPDataTransferListener;
import it.sauronsoftware.ftp4j.FTPException;
import it.sauronsoftware.ftp4j.FTPIllegalReplyException;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.output.ftp.SSLPlugins.SSLPluginConfig;
import org.embulk.spi.Buffer;
import org.embulk.spi.Exec;
import org.embulk.spi.FileOutputPlugin;
import org.embulk.spi.TransactionalFileOutput;
import org.embulk.spi.util.RetryExecutor.RetryGiveupException;
import org.embulk.spi.util.RetryExecutor.Retryable;
import org.slf4j.Logger;
import static org.embulk.spi.util.RetryExecutor.retryExecutor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;

public class FtpFileOutputPlugin implements FileOutputPlugin
{
    public interface PluginTask extends Task, SSLPlugins.SSLPluginTask
    {
        @Config("host")
        String getHost();

        @Config("port")
        @ConfigDefault("null")
        Optional<Integer> getPort();
        void setPort(Optional<Integer> port);

        @Config("user")
        @ConfigDefault("null")
        Optional<String> getUser();

        @Config("password")
        @ConfigDefault("null")
        Optional<String> getPassword();

        @Config("passive_mode")
        @ConfigDefault("true")
        boolean getPassiveMode();

        @Config("ascii_mode")
        @ConfigDefault("false")
        boolean getAsciiMode();

        @Config("ssl")
        @ConfigDefault("false")
        boolean getSsl();

        SSLPluginConfig getSSLConfig();
        void setSSLConfig(SSLPluginConfig config);

        @Config("path_prefix")
        String getPathPrefix();

        @Config("file_ext")
        String getFileNameExtension();

        @Config("sequence_format")
        @ConfigDefault("\"%03d.%02d\"")
        String getSequenceFormat();

        @Config("max_connection_retry")
        @ConfigDefault("10") // 10 times retry to connect FTP server if failed.
        int getMaxConnectionRetry();
    }

    private static final Logger log = Exec.getLogger(FtpFileOutputPlugin.class);
    private static final long TRANSFER_NOTICE_BYTES = 100 * 1024 * 1024;

    @Override
    public ConfigDiff transaction(ConfigSource config, int taskCount, FileOutputPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);
        task.setSSLConfig(SSLPlugins.configure(task));

        // try to check if plugin could connect to FTP server
        FTPClient client = null;
        try {
            client = newFTPClient(log, task);
        }
        catch (Exception ex) {
            throw new ConfigException(ex);
        }
        finally {
            disconnectClient(client);
        }

        return resume(task.dump(), taskCount, control);
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource, int taskCount, FileOutputPlugin.Control control)
    {
        control.run(taskSource);

        return Exec.newConfigDiff();
    }

    @Override
    public void cleanup(TaskSource taskSource, int taskCount, List<TaskReport> successTaskReports)
    {
    }

    @Override
    public TransactionalFileOutput open(TaskSource taskSource, final int taskIndex)
    {
        final PluginTask task = taskSource.loadTask(PluginTask.class);

        FTPClient client = newFTPClient(log, task);
        return new FtpFileOutput(client, task, taskIndex);
    }

    public static class FtpFileOutput implements TransactionalFileOutput
    {
        private final FTPClient client;
        private final String pathPrefix;
        private final String sequenceFormat;
        private final String pathSuffix;
        private final int maxConnectionRetry;
        private BufferedOutputStream output = null;
        private int fileIndex;
        private File file;
        private String filePath;
        private int taskIndex;

        public FtpFileOutput(FTPClient client, PluginTask task, int taskIndex)
        {
            this.client = client;
            this.taskIndex = taskIndex;
            this.pathPrefix = task.getPathPrefix();
            this.sequenceFormat = task.getSequenceFormat();
            this.pathSuffix = task.getFileNameExtension();
            this.maxConnectionRetry = task.getMaxConnectionRetry();
        }

        @Override
        public void nextFile()
        {
            closeFile();

            try {
                String suffix = pathSuffix;
                if (!suffix.startsWith(".")) {
                    suffix = "." + suffix;
                }
                filePath = pathPrefix + String.format(sequenceFormat, taskIndex, fileIndex) + suffix;
                file = File.createTempFile(filePath, ".tmp");
                log.info("Writing local file {}", file.getAbsolutePath());
                output = new BufferedOutputStream(new FileOutputStream(file));
            }
            catch (IOException ex) {
                throw Throwables.propagate(ex);
            }
        }

        private void closeFile()
        {
            if (output != null) {
                try {
                    output.close();
                    fileIndex++;
                }
                catch (IOException ex) {
                    throw Throwables.propagate(ex);
                }
            }
        }

        @Override
        public void add(Buffer buffer)
        {
            try {
                output.write(buffer.array(), buffer.offset(), buffer.limit());
            }
            catch (IOException ex) {
                throw Throwables.propagate(ex);
            }
            finally {
                buffer.release();
            }
        }

        @Override
        public void finish()
        {
            close();
            uploadFile();
            disconnectClient(client);
        }

        private Void uploadFile()
        {
            if (filePath != null) {
                try {
                    return retryExecutor()
                            .withRetryLimit(maxConnectionRetry)
                            .withInitialRetryWait(500)
                            .withMaxRetryWait(30 * 1000)
                            .runInterruptible(new Retryable<Void>() {
                                @Override
                                public Void call() throws FTPIllegalReplyException, FTPException, FTPDataTransferException,
                                                          FTPAbortedException, IOException, RetryGiveupException
                                {
                                    log.info("Upload start {} to {}", file.getAbsolutePath(), filePath);
                                    client.upload(filePath, new BufferedInputStream(new FileInputStream(file)), 0L, 0L, new LoggingTransferListener(log, TRANSFER_NOTICE_BYTES));
                                    log.info("Upload completed {} to {}", file.getAbsolutePath(), filePath);
                                    if (!file.delete()) {
                                        throw new ConfigException("Couldn't delete local file " + file.getAbsolutePath());
                                    }
                                    log.info("Delete local temporary file {}", file.getAbsolutePath());
                                    return null;
                                }

                                @Override
                                public boolean isRetryableException(Exception exception)
                                {
                                    return true;
                                }

                                @Override
                                public void onRetry(Exception exception, int retryCount, int retryLimit, int retryWait)
                                        throws RetryGiveupException
                                {
                                    if (exception instanceof FileNotFoundException || exception instanceof URISyntaxException || exception instanceof ConfigException) {
                                        throw new RetryGiveupException(exception);
                                    }
                                    String message = String.format("FTP put request failed. Retrying %d/%d after %d seconds. Message: %s",
                                            retryCount, retryLimit, retryWait / 1000, exception.getMessage());
                                    if (retryCount % 3 == 0) {
                                        log.warn(message, exception);
                                    }
                                    else {
                                        log.warn(message);
                                    }
                                }

                                @Override
                                public void onGiveup(Exception firstException, Exception lastException)
                                        throws RetryGiveupException
                                {
                                }
                            });
                }
                catch (RetryGiveupException ex) {
                    throw Throwables.propagate(ex.getCause());
                }
                catch (InterruptedException ex) {
                    throw Throwables.propagate(ex);
                }
            }
            return null;
        }

        @Override
        public void close()
        {
            closeFile();
        }

        @Override
        public void abort() {}

        @Override
        public TaskReport commit()
        {
            return Exec.newTaskReport();
        }
    }

    private static FTPClient newFTPClient(Logger log, PluginTask task)
    {
        FTPClient client = new FTPClient();
        try {
            if (task.getSsl()) {
                client.setSSLSocketFactory(SSLPlugins.newSSLSocketFactory(task.getSSLConfig(), task.getHost()));
                client.setSecurity(FTPClient.SECURITY_FTPS);
                if (!task.getPort().isPresent()) {
                    task.setPort(Optional.of(990));
                }
            }
            else {
                if (!task.getPort().isPresent()) {
                    task.setPort(Optional.of(21));
                }
            }

            client.addCommunicationListener(new LoggingCommunicationListner(log));

            // TODO configurable timeout parameters
            client.setAutoNoopTimeout(3000);

            FTPConnector con = client.getConnector();
            con.setConnectionTimeout(30);
            con.setReadTimeout(60);
            con.setCloseTimeout(60);

            // for commons-net client
            //client.setControlKeepAliveTimeout
            //client.setConnectTimeout
            //client.setSoTimeout
            //client.setDataTimeout
            //client.setAutodetectUTF8

            log.info("Connecting to {}", task.getHost());
            if (task.getPort().isPresent()) {
                client.connect(task.getHost(), task.getPort().get());
            }

            if (task.getUser().isPresent()) {
                log.info("Logging in with user {}", task.getUser().get());
                client.login(task.getUser().get(), task.getPassword().or(""));
            }

            log.info("Using passive mode");
            client.setPassive(task.getPassiveMode());

            if (task.getAsciiMode()) {
                log.info("Using ASCII mode");
                client.setType(FTPClient.TYPE_TEXTUAL);
            }
            else {
                log.info("Using binary mode");
                client.setType(FTPClient.TYPE_BINARY);
            }

            if (client.isCompressionSupported()) {
                log.info("Using MODE Z compression");
                client.setCompressionEnabled(true);
            }

            FTPClient connected = client;
            client = null;
            return connected;
        }
        catch (FTPException ex) {
            log.info("FTP command failed: {}, {}", ex.getCode(), ex.getMessage());
            throw Throwables.propagate(ex);
        }
        catch (FTPIllegalReplyException ex) {
            log.info("FTP protocol error");
            throw Throwables.propagate(ex);
        }
        catch (IOException ex) {
            log.info("FTP network error: {}", ex);
            throw Throwables.propagate(ex);
        }
        finally {
            disconnectClient(client);
        }
    }

    static void disconnectClient(FTPClient client)
    {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect(false);
            }
            catch (FTPException | FTPIllegalReplyException | IOException ex) {
                // do nothing
            }
        }
    }

    private static class LoggingCommunicationListner implements FTPCommunicationListener
    {
        private final Logger log;

        public LoggingCommunicationListner(Logger log)
        {
            this.log = log;
        }

        public void received(String statement)
        {
            log.info("< " + statement);
        }

        public void sent(String statement)
        {
            if (statement.startsWith("PASS")) {
                // don't show password
                return;
            }
            log.info("> {}", statement);
        }
    }

    private static class LoggingTransferListener implements FTPDataTransferListener
    {
        private final Logger log;
        private final long transferNoticeBytes;

        private long totalTransfer;
        private long nextTransferNotice;

        public LoggingTransferListener(Logger log, long transferNoticeBytes)
        {
            this.log = log;
            this.transferNoticeBytes = transferNoticeBytes;
            this.nextTransferNotice = transferNoticeBytes;
        }

        public void started()
        {
            log.info("Transfer started");
        }

        public void transferred(int length)
        {
            totalTransfer += length;
            if (totalTransfer > nextTransferNotice) {
                log.info("Transferred {} bytes", totalTransfer);
                nextTransferNotice = ((totalTransfer / transferNoticeBytes) + 1) * transferNoticeBytes;
            }
        }

        public void completed()
        {
            log.info("Transfer completed {} bytes", totalTransfer);
        }

        public void aborted()
        {
            log.info("Transfer aborted");
        }

        public void failed()
        {
            log.info("Transfer failed");
        }
    }
}