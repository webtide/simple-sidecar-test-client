package com.webtide.jetty.httpclient.test;

import org.HdrHistogram.Recorder;
import org.mortbay.jetty.load.generator.HTTP1ClientTransportBuilder;
import org.mortbay.jetty.load.generator.HTTPClientTransportBuilder;
import org.mortbay.jetty.load.generator.LoadGenerator;
import org.mortbay.jetty.load.generator.Resource;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RunServlet extends HttpServlet {

    private static final String TARGET_HOST = System.getProperty("target.host", "localhost");
    private static final int TARGET_PORT = Integer.getInteger("target.port", 9090);

    private static boolean RUN_LOAD_ON_START = Boolean.getBoolean("runLoadOnStart");

    @Override
    public void init() throws ServletException {
        // is target host:port responding?
        log("RunServlet#init");
        // run text

    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if ("true".equals(req.getParameter("runLoad"))) {
            Instant start = Instant.now();
            try {
                runLoad();
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                log("Ignore except running load:" + e.getMessage(), e);
            }
            Instant end = Instant.now();
            log("Time to runLoad:" + (end.minusNanos(start.getNano())).toString());
        } else {
            PrintWriter writer = resp.getWriter();
            writer.println("Hello from RunServlet");
            writer.flush();
        }
    }

    private void runLoad() throws ExecutionException, InterruptedException, TimeoutException {
        Resource resource = new Resource("/demo-simple/");

        ResponseTimeListener responseTimeListener = new ResponseTimeListener();

        LoadGenerator generator = LoadGenerator.builder()
                .scheme("http")
                .host(TARGET_HOST)
                .port(TARGET_PORT)
                .resource(resource)
                .httpClientTransportBuilder(new HTTP1ClientTransportBuilder())
                .threads(1)
                .usersPerThread(10)
                .channelsPerUser(6)
                .warmupIterationsPerThread(10)
                .iterationsPerThread(100)
                .runFor(5, TimeUnit.MINUTES) // Overrides iterationsPerThread()
                .resourceListener(responseTimeListener)
                .build();

        CompletableFuture<Void> complete = generator.begin();

        complete.get(10, TimeUnit.MINUTES);

        log("runLoad result:" + responseTimeListener.toString());
    }

    class ResponseTimeListener implements Resource.NodeListener, LoadGenerator.CompleteListener {
        private final org.HdrHistogram.Recorder recorder;
        private org.HdrHistogram.Histogram histogram;

        public ResponseTimeListener() {
            this.recorder = new Recorder(5);
        }

        @Override
        public void onResourceNode(Resource.Info info) {
            long responseTime = info.getResponseTime() - info.getRequestTime();
            recorder.recordValue(responseTime);
        }

        @Override
        public void onComplete(LoadGenerator loadGenerator) {
            // Retrieve the histogram, resetting the recorder.
            this.histogram = recorder.getIntervalHistogram();
        }
    }

}
