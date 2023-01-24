package com.webtide.jetty.httpclient.test;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.eclipse.jetty.toolchain.perf.HistogramSnapshot;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.mortbay.jetty.load.generator.HTTP1ClientTransportBuilder;
import org.mortbay.jetty.load.generator.LoadGenerator;
import org.mortbay.jetty.load.generator.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RunServlet extends HttpServlet {

    private static final boolean RUN_LOAD_ON_START = Boolean.getBoolean("runLoadOnStart");

    private static final Logger LOGGER = LoggerFactory.getLogger(RunServlet.class);

    private final ExecutorService executorService =
            Executors.newFixedThreadPool(1, r -> new Thread(r, "runServletThread"));

    @Override
    public void init() throws ServletException {
        LOGGER.info("RunServlet#init");
        // should we test if target host:port responding?
        // then run test
        if (RUN_LOAD_ON_START){
            try {
                executorService.submit(() -> {
                    try {
                        runLoad(null);
                    } catch (ExecutionException | InterruptedException | TimeoutException e) {
                        LOGGER.error("Ignore except running load:" + e.getMessage(), e);
                    }
                });
            } catch (Throwable e) {
                LOGGER.error("error running the load", e);
                throw new RuntimeException(e);
            }
        }
    }

    private String getParameterValue(HttpServletRequest request, String paramName, String sysProp, String defaultValue) {
        if(request != null) {
            String fromRequestParam = request.getParameter(paramName);
            if(fromRequestParam != null) {
                return fromRequestParam;
            }
        }
        return System.getProperty(sysProp, defaultValue);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if ("true".equals(req.getParameter("runLoad"))) {
            executorService.submit(() -> {
                try {
                    runLoad(req);
                } catch (ExecutionException | InterruptedException | TimeoutException e) {
                    LOGGER.error("Ignore except running load:" + e.getMessage(), e);
                }
            });
            PrintWriter writer = resp.getWriter();
            writer.println("Load Restarted");
            writer.flush();
        } else {
            PrintWriter writer = resp.getWriter();
            writer.println("Hello from RunServlet");
            writer.flush();
        }
    }

    private void runLoad(HttpServletRequest request) throws ExecutionException, InterruptedException, TimeoutException {
        long minutes = Integer.parseInt(getParameterValue(request, "time", "runLoad.time", "5"));
        String scheme = getParameterValue(request, "scheme", "runLoad.scheme", "https");
        String host = getParameterValue(request, "host", "runLoad.host", "localhost");
        int port = Integer.parseInt(getParameterValue(request, "port", "runLoad.port", "8443"));
        Resource resource = new Resource("/demo-simple/");

        ResponseTimeListener responseTimeListener = new ResponseTimeListener();

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setTrustAll(true);

        LoadGenerator generator = LoadGenerator.builder()
                .scheme(scheme)
                .host(host)
                .port(port)
                .resource(resource)
                .sslContextFactory(sslContextFactory)
                .resourceRate(Integer.parseInt(getParameterValue(request, "resourceRate", "runLoad.resourceRate", "0")))
                .httpClientTransportBuilder(new HTTP1ClientTransportBuilder())
                .threads(Integer.parseInt(getParameterValue(request, "threads", "runLoad.threads", "2")))
                .usersPerThread(Integer.parseInt(getParameterValue(request, "usersPerThread", "runLoad.usersPerThread", "10")))
                .channelsPerUser(Integer.parseInt(getParameterValue(request, "channelsPerUser", "runLoad.channelsPerUser", "6")))
                .warmupIterationsPerThread(Integer.parseInt(getParameterValue(request, "warmupIterationsPerThread", "runLoad.warmupIterationsPerThread", "10")))
                //.iterationsPerThread(100)
                .runFor(minutes, TimeUnit.MINUTES)
                .listener(responseTimeListener)
                .resourceListener(responseTimeListener)
                .build();

        LOGGER.info("start LoadGenerator: {}", generator);

        CompletableFuture<Void> complete = generator.begin();
        complete.get(minutes + 1, TimeUnit.MINUTES);
        Histogram histogram = responseTimeListener.histogram;
        LOGGER.info(new HistogramSnapshot(histogram).toString());
    }

    private long toMs(long nano) {
        return TimeUnit.NANOSECONDS.toMillis(nano);
    }

    static class ResponseTimeListener implements Resource.NodeListener, LoadGenerator.CompleteListener, LoadGenerator.BeginListener {
        private final Recorder recorder;
        private Histogram histogram;

        public ResponseTimeListener() {
            this.recorder = new Recorder(5);
        }

        @Override
        public void onResourceNode(Resource.Info info) {
            long responseTime = info.getResponseTime() - info.getRequestTime();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("responseTime: {}", responseTime);
            }
            recorder.recordValue(responseTime);
        }

        @Override
        public void onComplete(LoadGenerator loadGenerator) {
            this.histogram = recorder.getIntervalHistogram();
            LOGGER.info("LoadGenerator#onComplete");
        }

        @Override
        public void onBegin(LoadGenerator generator) {
            LOGGER.info("LoadGenerator#onBegin");
        }
    }

}
