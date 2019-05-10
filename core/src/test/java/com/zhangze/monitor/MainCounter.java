package com.zhangze.monitor;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.reporting.ConsoleReporter;
import sun.applet.Main;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by zhangze on 2019/5/10
 */
public class MainCounter {
    private final Counter testCounter = Metrics.newCounter(MainCounter.class, "testCounter");
    private final List<String> list = new ArrayList<>();
    public void add(String str) {
        testCounter.inc();
        list.add(str);
    }
    public String take() {
        testCounter.dec();
        return list.remove(0);
    }

    public static void main(String[] args)throws Exception {
        MainCounter tc = new MainCounter();
        ConsoleReporter.enable(1, TimeUnit.SECONDS);
        while(true){
            tc.add("s");
            Thread.sleep(1000);
        }
    }
}
