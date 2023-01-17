package com.webtide.jetty.httpclient.test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

public class RunServlet extends HttpServlet {

    @Override
    public void init() throws ServletException {
        // is localhost 9090 responding?
        log("RunServlet#init");
        // run text

    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        PrintWriter writer = resp.getWriter();
        writer.println("Hello from RunServlet");
        writer.flush();
    }
}
