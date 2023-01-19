package com.webtide.jetty.httpclient.test;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.eclipse.jetty.toolchain.perf.HistogramSnapshot;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.mortbay.jetty.load.generator.HTTP1ClientTransportBuilder;
import org.mortbay.jetty.load.generator.LoadGenerator;
import org.mortbay.jetty.load.generator.Resource;
import org.mortbay.jetty.load.generator.listeners.ReportListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.EventListener;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RunServlet extends HttpServlet {

    private static final String TARGET_HOST = System.getProperty("target.host", "localhost");
    private static final int TARGET_PORT = Integer.getInteger("target.port", 8443);

    private static boolean RUN_LOAD_ON_START = Boolean.getBoolean("runLoadOnStart");

    private static final Logger LOGGER = LoggerFactory.getLogger(RunServlet.class);


    @Override
    public void init() throws ServletException {
        LOGGER.info("RunServlet#init");
        // should we test if target host:port responding?
        // then run test
        if (Boolean.getBoolean("runLoad.onStart")){
            try {
                runLoad(getLoadTimeMinutes(null));
            } catch (Throwable e) {
                LOGGER.error("error running the load", e);
                throw new RuntimeException(e);
            }
        }
    }

    private int getLoadTimeMinutes(HttpServletRequest request) {
        if (request != null) {
           String minutes = request.getParameter("time");
           if (minutes != null) {
               return Integer.valueOf(minutes);
           }
        }
        return Integer.getInteger("runLoad.time", 5);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if ("true".equals(req.getParameter("runLoad"))) {
            try {
                runLoad(getLoadTimeMinutes(req));
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                LOGGER.error("Ignore except running load:" + e.getMessage(), e);
            }
        } else {
            PrintWriter writer = resp.getWriter();
            writer.println("Hello from RunServlet");
            writer.flush();
        }
    }

    private void runLoad(int minutes) throws ExecutionException, InterruptedException, TimeoutException {
        LOGGER.info("start run for {} minutes", minutes);
        Resource resource = new Resource("/demo-simple/");

        ResponseTimeListener responseTimeListener = new ResponseTimeListener();

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setTrustAll(true);

        LoadGenerator generator = LoadGenerator.builder()
                .scheme("https")
                .host(TARGET_HOST)
                .port(TARGET_PORT)
                .resource(resource)
                .sslContextFactory(sslContextFactory)
                .httpClientTransportBuilder(new HTTP1ClientTransportBuilder())
                .threads(2)
                .usersPerThread(10)
                .channelsPerUser(6)
                .warmupIterationsPerThread(10)
                .iterationsPerThread(100)
                .runFor(minutes, TimeUnit.MINUTES) // Overrides iterationsPerThread()
                .listener(responseTimeListener)
                .resourceListener(responseTimeListener)
                .build();

        CompletableFuture<Void> complete = generator.begin();
        complete.get(minutes + 1, TimeUnit.MINUTES);
        Histogram histogram = responseTimeListener.histogram;
        LOGGER.info(new HistogramSnapshot(histogram).toString());
//        LOGGER.info("runLoad result : totalCount{}, maxValue: {}, mean: {}, 50percentile: {}, 90percentile: {}",
//                histogram.getTotalCount(), toMs(histogram.getMaxValue()),
//                toMs(histogram.getMean()), toMs(histogram.getValueAtPercentile(50)),
//                toMs(histogram.getValueAtPercentile(90)));
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
