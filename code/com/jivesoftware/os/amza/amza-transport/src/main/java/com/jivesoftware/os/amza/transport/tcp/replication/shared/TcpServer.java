package com.jivesoftware.os.amza.transport.tcp.replication.shared;

import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import java.io.IOException;

/**
 *
 */
public class TcpServer {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    private final ConnectionWorker[] connectionWorkers;
    private final ConnectionAcceptor connectionAcceptor;
    private final ServerContext serverContext;

    public TcpServer(ConnectionAcceptor connectionAcceptor, ConnectionWorker[] connectionWorkers,
        ServerContext serverContext) throws IOException {
        this.connectionWorkers = connectionWorkers;
        this.connectionAcceptor = connectionAcceptor;
        this.serverContext = serverContext;
    }

    public void start() throws InterruptedException {
        if (serverContext.start()) {
            for (ConnectionWorker worker : connectionWorkers) {
                worker.start();
            }

            connectionAcceptor.start();
        }
    }

    public void stop() throws InterruptedException {
        if (serverContext.stop()) {
            connectionAcceptor.wakeup();

            connectionAcceptor.join();

            for (ConnectionWorker worker : connectionWorkers) {
                worker.wakeup();
                worker.join();
            }
        }
    }
}
